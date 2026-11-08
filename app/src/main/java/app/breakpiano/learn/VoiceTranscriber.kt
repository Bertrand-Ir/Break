package app.breakpiano.learn

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Reads Break's 16-bit LE stereo PCM as mono floats. */
internal fun readPcmMono(file: File): FloatArray {
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

/** Second-order high-pass (RBJ biquad), in place. */
internal fun highPass(x: FloatArray, sr: Int, hz: Double) {
    val w = 2 * PI * hz / sr
    val q = 0.707
    val alpha = sin(w) / (2 * q)
    val cw = cos(w)
    val a0 = 1 + alpha
    val b0 = (1 + cw) / 2 / a0; val b1 = -(1 + cw) / a0; val b2 = b0
    val a1 = -2 * cw / a0; val a2 = (1 - alpha) / a0
    var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
    for (i in x.indices) {
        val xi = x[i].toDouble()
        val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = xi; y2 = y1; y1 = y
        x[i] = y.toFloat()
    }
}

/**
 * Single-voice note detection for singing / humming ("tan tan tan", "da da da").
 *
 * 1. Clean the signal: high-pass away rumble, band-limit to the voice range.
 * 2. YIN pitch tracking every 10 ms, with confidence and a noise gate from the level floor.
 * 3. Fix octave slips, then measure how sharp/flat the singer is overall and correct for it.
 * 4. Segment into notes: a new note at every syllable attack (energy dip → rise) or a held
 *    pitch change; each note's pitch is the median of its steady part (ignoring the scoop in).
 */
object VoiceTranscriber {
    private const val SR = 11025
    private const val WIN = 512                 // 46 ms analysis window
    private const val HOP = 110                 // ~10 ms
    private const val MIN_HZ = 65.0             // C2
    private const val MAX_HZ = 1050.0           // C6
    private const val YIN_THRESH = 0.15

    fun transcribe(file: File, rate: Int): List<SongNote> = transcribe(readPcmMono(file), rate)

    fun transcribe(input: FloatArray, rate: Int): List<SongNote> {
        val x = input.copyOf()
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        if (peak > 1e-4f) { val g = 0.9f / peak; for (i in x.indices) x[i] *= g }   // level-independent
        highPass(x, rate, 70.0)
        val y = resample(x, rate, SR)                 // band-limits to ~5 kHz on the way down
        val frames = analyse(y)
        if (frames.isEmpty()) return emptyList()
        fixOctaves(frames)
        val offset = tuningOffset(frames)
        return segment(frames, offset)
    }

    // ── 2. frame analysis ────────────────────────────────────────────────────

    private class Raw(val t: Float, val db: Float, val hz: Double, val ap: Double)

    private fun analyse(y: FloatArray): MutableList<FrameM> {
        val n = (y.size - WIN) / HOP
        if (n <= 0) return mutableListOf()
        val tauMin = floor(SR / MAX_HZ).toInt()
        val tauMax = min(WIN - 2, (SR / MIN_HZ).toInt() + 1)
        val d = DoubleArray(tauMax + 1)
        val raws = ArrayList<Raw>(n)
        for (f in 0 until n) {
            val o = f * HOP
            var e = 0.0
            for (j in 0 until WIN) { val v = y[o + j].toDouble(); e += v * v }
            val rms = sqrt(e / WIN)
            val db = (20 * log10(rms + 1e-9)).toFloat()
            // YIN difference + cumulative-mean normalisation
            val w = WIN - tauMax
            d[0] = 1.0
            var run = 0.0
            for (tau in 1..tauMax) {
                var s = 0.0
                for (j in 0 until w) { val dv = (y[o + j] - y[o + j + tau]).toDouble(); s += dv * dv }
                run += s
                d[tau] = if (run > 0) s * tau / run else 1.0
            }
            var tau = -1
            var t = tauMin
            while (t <= tauMax) {
                if (d[t] < YIN_THRESH) {
                    while (t + 1 <= tauMax && d[t + 1] < d[t]) t++
                    tau = t; break
                }
                t++
            }
            if (tau < 0) {             // no dip under threshold: take the global minimum, mark low confidence
                var best = tauMin
                for (k in tauMin..tauMax) if (d[k] < d[best]) best = k
                tau = best
            }
            val ap = d[tau]
            // parabolic interpolation for sub-sample precision
            var tf = tau.toDouble()
            if (tau in 1 until tauMax) {
                val a = d[tau - 1]; val b = d[tau]; val c = d[tau + 1]
                val den = a + c - 2 * b
                if (abs(den) > 1e-12) tf += 0.5 * (a - c) / den
            }
            raws += Raw(((o + WIN / 2).toDouble() / SR).toFloat(), db, SR / tf, ap)
        }
        // level gate from the noise floor (quietest 15 %) and the loudest parts
        val sorted = raws.map { it.db }.sorted()
        val floorDb = sorted[(sorted.size * 0.15).toInt().coerceIn(0, sorted.lastIndex)]
        val peakDb = sorted[(sorted.size * 0.98).toInt().coerceIn(0, sorted.lastIndex)]
        val gate = max(floorDb + 10f, peakDb - 38f)
        return raws.map {
            val voiced = it.db > gate && it.ap < 0.3 && it.hz in MIN_HZ..MAX_HZ
            FrameM(it.t, it.db, if (voiced) (69 + 12 * ln(it.hz / 440.0) / ln(2.0)).toFloat() else 0f, voiced, (1 - it.ap).toFloat().coerceIn(0f, 1f))
        }.toMutableList()
    }

    internal class FrameM(val t: Float, val db: Float, var midi: Float, var voiced: Boolean, val conf: Float)

    // ── 3. octave slips + tuning ─────────────────────────────────────────────

    private fun fixOctaves(fr: MutableList<FrameM>) {
        for (i in fr.indices) {
            if (!fr[i].voiced) continue
            val nb = ArrayList<Float>()
            for (k in max(0, i - 6)..min(fr.lastIndex, i + 6)) if (k != i && fr[k].voiced) nb += fr[k].midi
            if (nb.size < 4) continue
            val med = nb.sorted()[nb.size / 2]
            val diff = fr[i].midi - med
            if (abs(diff - 12) < 1.2f) fr[i].midi -= 12 else if (abs(diff + 12) < 1.2f) fr[i].midi += 12
        }
        // isolated voiced blips are noise
        for (i in fr.indices) {
            if (!fr[i].voiced) continue
            val prev = i > 0 && fr[i - 1].voiced
            val next = i < fr.lastIndex && fr[i + 1].voiced
            if (!prev && !next) fr[i].voiced = false
        }
    }

    /** How far (in semitones, −0.5..0.5) the singer sits from concert pitch, as a circular mean. */
    private fun tuningOffset(fr: List<FrameM>): Float {
        var sx = 0.0; var sy = 0.0
        for (f in fr) if (f.voiced) {
            val a = 2 * PI * (f.midi - f.midi.roundToInt())
            sx += cos(a) * f.conf; sy += sin(a) * f.conf
        }
        if (sx == 0.0 && sy == 0.0) return 0f
        return (atan2(sy, sx) / (2 * PI)).toFloat()
    }

    // ── 4. note segmentation ─────────────────────────────────────────────────

    private fun segment(fr: List<FrameM>, offset: Float): List<SongNote> {
        val notes = ArrayList<SongNote>()
        val dt = HOP.toFloat() / SR
        var i = 0
        while (i < fr.size) {
            if (!fr[i].voiced) { i++; continue }
            // a voiced run (bridge gaps of ≤ 2 frames)
            var j = i
            var gap = 0
            while (j + 1 < fr.size && (fr[j + 1].voiced || gap < 2)) {
                if (fr[j + 1].voiced) gap = 0 else gap++
                j++
            }
            while (j > i && !fr[j].voiced) j--
            splitRun(fr, i, j, offset, dt, notes)
            i = j + 1
        }
        val clean = notes.filter { it.end - it.start >= 0.08f }.sortedBy { it.start }.toMutableList()
        // a glide between two connected notes passes briefly through the pitches in between — drop those
        var k = 1
        while (k < clean.size - 1) {
            val p = clean[k - 1]; val c = clean[k]; val nx = clean[k + 1]
            val connected = c.start - p.end < 0.04f && nx.start - c.end < 0.04f
            val passing = c.pitch > minOf(p.pitch, nx.pitch) && c.pitch < maxOf(p.pitch, nx.pitch)
            if (connected && passing && c.end - c.start < 0.16f) {
                clean[k + 1] = nx.copy(start = c.start)
                clean.removeAt(k)
            } else k++
        }
        return clean
    }

    private fun splitRun(fr: List<FrameM>, a: Int, b: Int, offset: Float, dt: Float, out: MutableList<SongNote>) {
        val cuts = ArrayList<Int>()
        cuts += a
        // smoothed pitch for change detection
        fun medPitch(k: Int): Float {
            val v = ArrayList<Float>()
            for (q in max(a, k - 2)..min(b, k + 2)) if (fr[q].voiced) v += fr[q].midi - offset
            return if (v.isEmpty()) 0f else v.sorted()[v.size / 2]
        }
        var segStart = a
        var k = a + 1
        while (k <= b) {
            // syllable attack: level drops then rises ≥ 6 dB within ~80 ms ("tan | tan")
            var minDb = fr[k].db
            for (q in max(segStart, k - 8) until k) minDb = min(minDb, fr[q].db)
            val attack = k - segStart > 8 && fr[k].db - minDb >= 6f && k + 1 <= b && fr[k + 1].db >= fr[k].db - 1f &&
                (k - 1 >= 0 && fr[k - 1].db < fr[k].db)
            // held pitch change (legato): the next 6 frames sit ≥ 0.75 st away from the note so far
            var pitchChange = false
            if (k - segStart > 8 && k + 6 <= b) {
                val cur = steadyPitch(fr, segStart, k - 1, offset)
                var all = true
                for (q in k until k + 6) { if (!fr[q].voiced || abs(medPitch(q) - cur) < 0.75f) { all = false; break } }
                pitchChange = all
            }
            if (attack || pitchChange) {
                // place the cut at the lowest level point just before an attack
                var c = k
                if (attack) { var m = k; for (q in max(segStart + 1, k - 8) until k) if (fr[q].db < fr[m].db) m = q; c = m }
                if (c > segStart + 4) {
                    cuts += c; segStart = c
                    k = segStart + 5
                } else {
                    k++                       // too close to the note's start to be a new note
                }
                continue
            }
            k++
        }
        cuts += b + 1
        for (s in 0 until cuts.size - 1) {
            val s0 = cuts[s]; val s1 = cuts[s + 1] - 1
            if (s1 - s0 < 6) continue
            val p = steadyPitch(fr, s0, s1, offset)
            if (p <= 0f) continue
            var peak = -120f
            for (q in s0..s1) peak = max(peak, fr[q].db)
            val vel = ((peak + 40f) / 34f).coerceIn(0.35f, 1f)
            out += SongNote(fr[s0].t - (WIN / 2f) / SR + 0.01f, fr[s1].t + dt, p.roundToInt().coerceIn(21, 108), vel)
        }
    }

    /** Median pitch over the steady middle of a segment (skips the scoop into the note and the tail). */
    private fun steadyPitch(fr: List<FrameM>, a: Int, b: Int, offset: Float): Float {
        val len = b - a + 1
        val from = a + (len * 0.25f).toInt()
        val to = b - (len * 0.1f).toInt()
        val v = ArrayList<Float>()
        for (q in from..max(from, to)) if (q <= b && fr[q].voiced) v += fr[q].midi - offset
        if (v.size < 3) for (q in a..b) if (fr[q].voiced) v += fr[q].midi - offset
        if (v.isEmpty()) return 0f
        v.sort()
        return v[v.size / 2]
    }
}
