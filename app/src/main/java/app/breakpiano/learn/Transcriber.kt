package app.breakpiano.learn

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One transcribed / imported note. Times in seconds. */
data class SongNote(val start: Float, val end: Float, val pitch: Int, val velocity: Float)

/**
 * Audio → piano notes, fully on-device, using Spotify's Basic Pitch model (Apache-2.0).
 * This is a faithful port of basic_pitch.inference + note_creation.output_to_notes_polyphonic.
 */
class Transcriber(private val context: Context) {
    companion object {
        internal const val SR = 22050
        internal const val HOP = 256
        internal const val WIN = SR * 2 - HOP              // 43844 samples per model window
        internal const val N_OVERLAP_FRAMES = 30
        internal const val OVERLAP = N_OVERLAP_FRAMES * HOP // 7680
        internal const val STEP = WIN - OVERLAP              // 36164
        internal const val FRAMES_PER_WIN = 172
        internal const val KEYS = 88
        internal const val MIDI_OFFSET = 21
        internal const val ANNOT_FPS = 86                    // int(22050 / 256)

        // note-creation defaults from basic-pitch
        internal const val ONSET_THRESH = 0.5f
        internal const val FRAME_THRESH = 0.3f
        internal const val MIN_NOTE_FRAMES = 11              // ≈ 127.7 ms
        internal const val ENERGY_TOL = 11
    }

    /**
     * @param pcm 16-bit LE stereo file at [rate].
     * Progress: 0..0.15 resampling, 0.15..0.9 model, 0.9..1 note building.
     */
    fun transcribe(pcm: File, rate: Int, onProgress: (Float) -> Unit): List<SongNote> {
        val mono = readMono(pcm)
        onProgress(0.05f)
        val audio = resample(mono, rate, SR)
        onProgress(0.15f)
        val (notes, onsets) = runModel(audio) { onProgress(0.15f + 0.75f * it) }
        onProgress(0.9f)
        val events = NoteBuilder.outputToNotes(notes, onsets)
        onProgress(1f)
        return events
    }

    // ── audio prep ───────────────────────────────────────────────────────────

    private fun readMono(file: File): FloatArray {
        val frames = (file.length() / 4).toInt()
        val out = FloatArray(frames)
        val buf = ByteArray(1 shl 16)
        var idx = 0
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val bb = ByteBuffer.wrap(buf, 0, n - n % 4).order(ByteOrder.LITTLE_ENDIAN)
                while (bb.remaining() >= 4 && idx < frames) {
                    val l = bb.short.toInt(); val r = bb.short.toInt()
                    out[idx++] = (l + r) / 65536f
                }
            }
        }
        return out
    }

    // ── model ────────────────────────────────────────────────────────────────

    private fun loadModel(): ByteBuffer {
        val fd = context.assets.openFd("models/basic_pitch.tflite")
        FileInputStream(fd.fileDescriptor).use { s ->
            return s.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    /** Returns (note, onset) activations, each [frames][88]. */
    private fun runModel(audio: FloatArray, onProgress: (Float) -> Unit): Pair<Array<FloatArray>, Array<FloatArray>> {
        val origLen = audio.size
        // pad the front with half the overlap so the first frames are centred like the reference
        val padded = FloatArray(OVERLAP / 2 + origLen)
        System.arraycopy(audio, 0, padded, OVERLAP / 2, origLen)
        val windows = max(1, (padded.size + STEP - 1) / STEP)

        val keep = FRAMES_PER_WIN - N_OVERLAP_FRAMES       // 142 frames kept per window
        val nOut = (origLen.toLong() * ANNOT_FPS / SR).toInt()
        val noteAll = Array(windows * keep) { FloatArray(KEYS) }
        val onsetAll = Array(windows * keep) { FloatArray(KEYS) }

        val opts = Interpreter.Options().apply { setNumThreads(4) }
        Interpreter(loadModel(), opts).use { interp ->
            val input = ByteBuffer.allocateDirect(WIN * 4).order(ByteOrder.nativeOrder())
            val onsetBuf = ByteBuffer.allocateDirect(FRAMES_PER_WIN * KEYS * 4).order(ByteOrder.nativeOrder())
            val noteBuf = ByteBuffer.allocateDirect(FRAMES_PER_WIN * KEYS * 4).order(ByteOrder.nativeOrder())
            val contourBuf = ByteBuffer.allocateDirect(FRAMES_PER_WIN * 264 * 4).order(ByteOrder.nativeOrder())
            for (w in 0 until windows) {
                input.clear()
                val start = w * STEP
                for (i in 0 until WIN) {
                    val s = start + i
                    input.putFloat(if (s < padded.size) padded[s] else 0f)
                }
                input.rewind()
                onsetBuf.clear(); noteBuf.clear(); contourBuf.clear()
                // output order verified from the model file: 0 = onset, 1 = note, 2 = contour
                interp.runForMultipleInputsOutputs(arrayOf<Any>(input), mapOf<Int, Any>(0 to onsetBuf, 1 to noteBuf, 2 to contourBuf))
                onsetBuf.rewind(); noteBuf.rewind()
                val ob = onsetBuf.asFloatBuffer(); val nb = noteBuf.asFloatBuffer()
                for (f in 0 until FRAMES_PER_WIN) {
                    val keepIdx = f - N_OVERLAP_FRAMES / 2
                    val inKeep = keepIdx in 0 until keep
                    for (k in 0 until KEYS) {
                        val o = ob.get(f * KEYS + k); val n = nb.get(f * KEYS + k)
                        if (inKeep) { onsetAll[w * keep + keepIdx][k] = o; noteAll[w * keep + keepIdx][k] = n }
                    }
                }
                onProgress((w + 1f) / windows)
            }
        }
        val n = min(nOut, noteAll.size)
        return Pair(noteAll.copyOf(n).requireNoNulls(), onsetAll.copyOf(n).requireNoNulls())
    }
}

/** Band-limited (windowed-sinc, Blackman, 16 taps per side) resampler. */
internal fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
    if (from == to) return x
    val ratio = from.toDouble() / to
    val n = floor(x.size / ratio).toInt()
    val y = FloatArray(n)
    val fc = min(1.0, 1.0 / ratio) * 0.94
    val half = 16
    for (j in 0 until n) {
        val c = j * ratio
        val i0 = floor(c).toInt()
        var acc = 0.0
        var wsum = 0.0
        for (k in i0 - half + 1..i0 + half) {
            if (k < 0 || k >= x.size) continue
            val t = c - k
            val arg = PI * fc * t
            val sinc = if (kotlin.math.abs(arg) < 1e-9) 1.0 else sin(arg) / arg
            val wpos = (t + half) / (2.0 * half)
            val w = if (wpos < 0 || wpos > 1) 0.0 else 0.42 - 0.5 * cos(2 * PI * wpos) + 0.08 * cos(4 * PI * wpos)
            val h = sinc * w
            acc += x[k] * h
            wsum += h
        }
        y[j] = if (wsum > 1e-9) (acc / wsum).toFloat() else 0f
    }
    return y
}


/** Pure note-creation step (unit-tested on the JVM against real model output). */
internal object NoteBuilder {
    private const val SR = Transcriber.SR
    private const val HOP = Transcriber.HOP
    private const val WIN = Transcriber.WIN
    private const val FRAMES_PER_WIN = Transcriber.FRAMES_PER_WIN
    private const val KEYS = Transcriber.KEYS
    private const val MIDI_OFFSET = Transcriber.MIDI_OFFSET
    private const val ONSET_THRESH = Transcriber.ONSET_THRESH
    private const val FRAME_THRESH = Transcriber.FRAME_THRESH
    private const val MIN_NOTE_FRAMES = Transcriber.MIN_NOTE_FRAMES
    private const val ENERGY_TOL = Transcriber.ENERGY_TOL

    // ── note creation (port of basic_pitch.note_creation) ────────────────────

    internal fun frameToTime(i: Int): Float {
        val t = i * HOP.toDouble() / SR
        val windowOffset = (HOP.toDouble() / SR) * (FRAMES_PER_WIN - WIN.toDouble() / HOP) + 0.0018
        return (t - windowOffset * floor(i.toDouble() / FRAMES_PER_WIN)).toFloat().coerceAtLeast(0f)
    }

    private fun inferredOnsets(onsets: Array<FloatArray>, frames: Array<FloatArray>): Array<FloatArray> {
        val n = frames.size
        val diff = Array(n) { FloatArray(KEYS) }
        var maxDiff = 0f
        var maxOnset = 0f
        for (t in 0 until n) for (k in 0 until KEYS) {
            val d1 = frames[t][k] - (if (t >= 1) frames[t - 1][k] else 0f)
            val d2 = frames[t][k] - (if (t >= 2) frames[t - 2][k] else 0f)
            var d = min(d1, d2)
            if (d < 0f || t < 2) d = 0f
            diff[t][k] = d
            if (d > maxDiff) maxDiff = d
            if (onsets[t][k] > maxOnset) maxOnset = onsets[t][k]
        }
        val scale = if (maxDiff > 0f) maxOnset / maxDiff else 0f
        return Array(n) { t -> FloatArray(KEYS) { k -> max(onsets[t][k], diff[t][k] * scale) } }
    }

    fun outputToNotes(frames: Array<FloatArray>, rawOnsets: Array<FloatArray>): List<SongNote> {
        val n = frames.size
        if (n < 3) return emptyList()
        val onsets = inferredOnsets(rawOnsets, frames)
        val energy = Array(n) { frames[it].copyOf() }
        data class Ev(val s: Int, val e: Int, val k: Int, val amp: Float)
        val events = ArrayList<Ev>()

        // onset peaks (strict local maxima in time) above threshold, processed backwards in time
        val peaks = ArrayList<Long>()
        for (t in 1 until n - 1) for (k in 0 until KEYS) {
            val v = onsets[t][k]
            if (v >= ONSET_THRESH && v > onsets[t - 1][k] && v > onsets[t + 1][k]) peaks.add(t.toLong() * KEYS + k)
        }
        peaks.sortDescending()

        fun mean(s: Int, e: Int, k: Int): Float { var a = 0f; for (t in s until e) a += frames[t][k]; return a / max(1, e - s) }
        fun clear(s: Int, e: Int, k: Int) {
            for (t in s until e) {
                energy[t][k] = 0f
                if (k < KEYS - 1) energy[t][k + 1] = 0f
                if (k > 0) energy[t][k - 1] = 0f
            }
        }

        for (p in peaks) {
            val start = (p / KEYS).toInt(); val k = (p % KEYS).toInt()
            if (start >= n - 1) continue
            var i = start + 1; var miss = 0
            while (i < n - 1 && miss < ENERGY_TOL) {
                if (energy[i][k] < FRAME_THRESH) miss++ else miss = 0
                i++
            }
            i -= miss
            if (i - start <= MIN_NOTE_FRAMES) continue
            clear(start, i, k)
            events += Ev(start, i, k, mean(start, i, k))
        }

        // "melodia trick": pick up sustained notes that never had a clear onset
        // Energy only ever gets zeroed, so a single descending sort of the remaining cells
        // visits them in exactly the order repeated argmax would — without the O(n²) rescans.
        val cand = ArrayList<Long>()
        for (t in 0 until n) for (k in 0 until KEYS) if (energy[t][k] > FRAME_THRESH) cand.add(t.toLong() * KEYS + k)
        cand.sortByDescending { energy[(it / KEYS).toInt()][(it % KEYS).toInt()] }
        for (c in cand) {
            val bt = (c / KEYS).toInt(); val bk = (c % KEYS).toInt()
            if (energy[bt][bk] <= FRAME_THRESH) continue
            energy[bt][bk] = 0f
            var i = bt + 1; var miss = 0
            while (i < n - 1 && miss < ENERGY_TOL) {
                if (energy[i][bk] < FRAME_THRESH) miss++ else miss = 0
                energy[i][bk] = 0f
                if (bk < KEYS - 1) energy[i][bk + 1] = 0f
                if (bk > 0) energy[i][bk - 1] = 0f
                i++
            }
            val end = i - 1 - miss
            i = bt - 1; miss = 0
            while (i > 0 && miss < ENERGY_TOL) {
                if (energy[i][bk] < FRAME_THRESH) miss++ else miss = 0
                energy[i][bk] = 0f
                if (bk < KEYS - 1) energy[i][bk + 1] = 0f
                if (bk > 0) energy[i][bk - 1] = 0f
                i--
            }
            val start = i + 1 + miss
            if (end - start <= MIN_NOTE_FRAMES) continue
            events += Ev(start, end, bk, mean(start, end, bk))
        }

        return events.map { SongNote(frameToTime(it.s), frameToTime(it.e), it.k + MIDI_OFFSET, it.amp.coerceIn(0.2f, 1f)) }
            .sortedWith(compareBy({ it.start }, { it.pitch }))
    }
}
