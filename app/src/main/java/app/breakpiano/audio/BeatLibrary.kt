package app.breakpiano.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

data class UserBeat(
    val id: String,
    val name: String,
    val sampleRate: Int,
    val frames: Int,
    /** Loudness-match gain computed at import (applied when Smart Mix is on). */
    val gain: Float,
) {
    val durationMs: Long get() = frames * 1000L / max(1, sampleRate)
}

/**
 * Imports any audio file Android can decode (mp3, m4a/aac, wav, ogg, flac…), decodes it once to
 * 16-bit stereo PCM on disk, and measures its loudness so it can be auto-levelled against the keys.
 */
class BeatLibrary(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "beats").apply { mkdirs() }
    private val index = File(dir, "index.json")

    val beats = mutableStateListOf<UserBeat>()

    init {
        runCatching {
            if (index.exists()) {
                val arr = JSONArray(index.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val b = UserBeat(o.getString("id"), o.getString("name"), o.getInt("rate"), o.getInt("frames"), o.getDouble("gain").toFloat())
                    if (pcmFile(b.id).exists()) beats += b
                }
            }
        }
    }

    private fun pcmFile(id: String) = File(dir, "$id.pcm")

    private fun save() {
        val arr = JSONArray()
        beats.forEach { b ->
            arr.put(JSONObject().put("id", b.id).put("name", b.name).put("rate", b.sampleRate).put("frames", b.frames).put("gain", b.gain.toDouble()))
        }
        index.writeText(arr.toString())
    }

    fun rename(beat: UserBeat, name: String) {
        val i = beats.indexOfFirst { it.id == beat.id }
        if (i >= 0 && name.isNotBlank()) { beats[i] = beat.copy(name = name.trim()); save() }
    }

    fun delete(beat: UserBeat) {
        pcmFile(beat.id).delete()
        beats.removeAll { it.id == beat.id }
        save()
    }

    private fun displayName(uri: Uri): String {
        var name = "My beat"
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        }
        return name.substringBeforeLast('.').replace('_', ' ').trim().ifBlank { "My beat" }
    }

    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): UserBeat = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(12)
        val out = pcmFile(id)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(app, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("That file has no audio track.")
            val inFormat = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false

            codec = MediaCodec.createDecoderByType(mime).apply { configure(inFormat, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frames = 0L
            var sumSq = 0.0
            var peak = 0
            val stream = BufferedOutputStream(FileOutputStream(out), 1 shl 16)
            var scratch = ByteArray(0)

            stream.use {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(5_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val ch = max(1, channels)
                            val sampleCount = if (isFloat) info.size / 4 else info.size / 2
                            val n = sampleCount / ch
                            if (scratch.size < n * 4) scratch = ByteArray(n * 4)
                            val bb = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN)
                            val fb = if (isFloat) buf.asFloatBuffer() else null
                            val sb = if (!isFloat) buf.asShortBuffer() else null
                            for (i in 0 until n) {
                                val l: Int; val r: Int
                                if (fb != null) {
                                    l = (fb.get(i * ch).coerceIn(-1f, 1f) * 32767f).toInt()
                                    r = if (ch > 1) (fb.get(i * ch + 1).coerceIn(-1f, 1f) * 32767f).toInt() else l
                                } else {
                                    l = sb!!.get(i * ch).toInt()
                                    r = if (ch > 1) sb.get(i * ch + 1).toInt() else l
                                }
                                bb.putShort(l.toShort()); bb.putShort(r.toShort())
                                val m = (l + r) * 0.5
                                sumSq += m * m
                                val a = max(kotlin.math.abs(l), kotlin.math.abs(r))
                                if (a > peak) peak = a
                            }
                            it.write(scratch, 0, n * 4)
                            frames += n
                            codec.releaseOutputBuffer(outIdx, false)
                            if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
            if (frames < rate / 4) error("That file is too short to use as a beat.")

            // Loudness match: mastered beats are usually far louder than a live instrument.
            // Bring the beat's RMS to ≈ -18 dBFS so keys and beat sit together.
            val rms = sqrt(sumSq / frames) / 32768.0
            val target = 0.125
            val gain = if (rms > 1e-5) (target / rms).toFloat().coerceIn(0.2f, 2.5f) else 1f

            val beat = UserBeat(id, displayName(uri), rate, frames.toInt(), gain)
            withContext(Dispatchers.Main) { beats.add(0, beat); save() }
            beat
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Streams the decoded PCM into the native engine. */
    suspend fun loadIntoEngine(beat: UserBeat, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val file = pcmFile(beat.id)
        NativeEngine.beginUserTrack(beat.frames, beat.sampleRate)
        val chunkFrames = 16384
        val bytes = ByteArray(chunkFrames * 4)
        val shorts = ShortArray(chunkFrames * 2)
        var done = 0L
        FileInputStream(file).use { input ->
            while (true) {
                var read = 0
                while (read < bytes.size) {
                    val r = input.read(bytes, read, bytes.size - read)
                    if (r < 0) break
                    read += r
                }
                if (read <= 0) break
                val frames = read / 4
                ByteBuffer.wrap(bytes, 0, frames * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, frames * 2)
                NativeEngine.appendUserTrack(shorts, frames)
                done += frames
                onProgress((done.toFloat() / max(1, beat.frames)).coerceIn(0f, 1f))
                if (read < bytes.size) break
            }
        }
        NativeEngine.commitUserTrack(beat.gain)
    }
}
