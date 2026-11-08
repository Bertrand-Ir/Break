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
        return segment(frames, syllableOnsets(y))
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

    /**
     * Local tuning: how far (-0.5..0.5 semitones) the singer sits from concert pitch around each
     * frame, as a confidence-weighted circular mean over +/-3 s. Follows a singer who slowly drifts.
     */
    private fun localTuning(fr: List<FrameM>): FloatArray {
        val n = fr.size
        val cx = DoubleArray(n + 1); val cy = DoubleArray(n + 1)
        for (i in 0 until n) {
            var x = 0.0; var y = 0.0
            if (fr[i].voiced) {
                val a = 2 * PI * (fr[i].midi - fr[i].midi.roundToInt())
                x = cos(a) * fr[i].conf; y = sin(a) * fr[i].conf
            }
            cx[i + 1] = cx[i] + x; cy[i + 1] = cy[i] + y
        }
        val half = 300
        return FloatArray(n) { i ->
            val a = max(0, i - half); val b = min(n, i + half + 1)
            val sx = cx[b] - cx[a]; val sy = cy[b] - cy[a]
            if (sx == 0.0 && sy == 0.0) 0f else (atan2(sy, sx) / (2 * PI)).toFloat()
        }
    }

    // -- 4. note segmentation ------------------------------------------------

    private class Cand(val start: Float, val end: Float, val raw: Float, val vel: Float, val conf: Float = 1f, val peakDb: Float = 0f) {
        val dur get() = end - start
        fun withSpan(s: Float, e: Float) = Cand(s, e, raw, vel, conf, peakDb)
    }

    /**
     * Fine-grained (5 ms) syllable starts: a short dip in loudness, or the hiss of a consonant
     * ("t", "d", "k") between two vowels. Catches fast "tang-tang" on the same note that the
     * 46 ms pitch window smears together.
     */
    private fun syllableOnsets(y: FloatArray): List<Float> {
        val w = 128; val hop = 55
        val n = (y.size - w) / hop
        if (n < 30) return emptyList()
        val env = FloatArray(n); val hf = FloatArray(n)
        for (f in 0 until n) {
            val o = f * hop
            var e = 0.0; var h = 0.0
            for (j in 1 until w) {
                val v = y[o + j].toDouble(); e += v * v
                val d = (y[o + j] - y[o + j - 1]).toDouble(); h += d * d
            }
            env[f] = (10 * log10(e / w + 1e-12)).toFloat()
            hf[f] = (10 * log10(h / w + 1e-12)).toFloat()
        }
        val sorted = env.sorted()
        val loud = sorted[(n * 0.9).toInt().coerceIn(0, n - 1)]
        val out = ArrayList<Float>()
        var last = -100
        for (m in 12 until n - 12) {
            if (env[m] > env[m - 1] || env[m] > env[m + 1]) continue          // local minimum of loudness
            var before = -200f; var after = -200f
            for (q in m - 12 until m) before = max(before, env[q])
            for (q in m + 1..m + 12) after = max(after, env[q])
            if (after < loud - 25f) continue                                // not followed by a sung vowel
            val dip = min(before, after) - env[m]
            // consonant hiss: high-frequency share jumps above its local norm
            val tilt = ArrayList<Float>()
            for (q in max(0, m - 30)..min(n - 1, m + 30)) tilt += hf[q] - env[q]
            tilt.sort()
            val norm = tilt[tilt.size / 2]
            var hiss = false
            for (q in m - 3..m + 3) if (hf[q] - env[q] - norm >= 6f) { hiss = true; break }
            if ((dip >= 5f || (dip >= 2.5f && hiss)) && m - last > 16) {     // ≥ 80 ms apart
                out += (m * hop + w / 2).toFloat() / SR
                last = m
            }
        }
        return out
    }

    private fun segment(fr: List<FrameM>, onsets: List<Float>): List<SongNote> {
        val isOnset = BooleanArray(fr.size)
        for (t in onsets) {
            val f = ((t * SR - WIN / 2f) / HOP).roundToInt()
            if (f in fr.indices) isOnset[f] = true
        }
        val tune = localTuning(fr)
        val pc = FloatArray(fr.size) { if (fr[it].voiced) fr[it].midi - tune[it] else 0f }
        val cands = ArrayList<Cand>()
        val dt = HOP.toFloat() / SR
        var i = 0
        while (i < fr.size) {
            if (!fr[i].voiced) { i++; continue }
            var j = i
            var gap = 0
            while (j + 1 < fr.size && (fr[j + 1].voiced || gap < 2)) {
                if (fr[j + 1].voiced) gap = 0 else gap++
                j++
            }
            while (j > i && !fr[j].voiced) j--
            splitRun(fr, pc, isOnset, i, j, dt, cands)
            i = j + 1
        }
        val clean = snapToKey(purify(cands.filter { it.end - it.start >= 0.08f }.sortedBy { it.start })).toMutableList()
        // a glide between two connected notes passes briefly through the pitches in between: drop those
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

    private fun splitRun(fr: List<FrameM>, pc: FloatArray, isOnset: BooleanArray, a: Int, b: Int, dt: Float, out: MutableList<Cand>) {
        val cuts = ArrayList<Int>()
        cuts += a
        fun medPitch(k: Int): Float {
            val v = ArrayList<Float>()
            for (q in max(a, k - 2)..min(b, k + 2)) if (fr[q].voiced) v += pc[q]
            return if (v.isEmpty()) 0f else v.sorted()[v.size / 2]
        }
        var segStart = a
        var k = a + 1
        while (k <= b) {
            var minDb = fr[k].db
            var minConf = 1f
            for (q in max(segStart, k - 8) until k) minDb = min(minDb, fr[q].db)
            for (q in max(segStart, k - 4) until k) minConf = min(minConf, fr[q].conf)
            val rising = k + 1 <= b && fr[k + 1].db >= fr[k].db - 1f && k - 1 >= 0 && fr[k - 1].db < fr[k].db
            // syllable attack: a clear level dip ("tan | tan"), or a softer dip where the voice briefly
            // stopped being a clean tone (a quick "t" between syllables)
            val attack = k - segStart > 7 && rising &&
                (fr[k].db - minDb >= 4.5f || (fr[k].db - minDb >= 2.5f && minConf < 0.55f))
            var pitchChange = false
            if (k - segStart > 8 && k + 6 <= b) {
                val cur = steadyPitch(fr, pc, segStart, k - 1)
                var all = true
                for (q in k until k + 6) { if (!fr[q].voiced || abs(medPitch(q) - cur) < 0.75f) { all = false; break } }
                pitchChange = all
            }
            val syllable = isOnset[k] && k - segStart > 7 && b - k > 6
            if (syllable && !attack && !pitchChange) {
                cuts += k; segStart = k
                k = segStart + 5
                continue
            }
            if (attack || pitchChange) {
                var c = k
                if (attack) { var m = k; for (q in max(segStart + 1, k - 8) until k) if (fr[q].db < fr[m].db) m = q; c = m }
                if (c > segStart + 4) {
                    cuts += c; segStart = c
                    k = segStart + 5
                } else {
                    k++
                }
                continue
            }
            k++
        }
        cuts += b + 1
        for (s in 0 until cuts.size - 1) {
            val s0 = cuts[s]; val s1 = cuts[s + 1] - 1
            if (s1 - s0 < 6) continue
            val p = steadyPitch(fr, pc, s0, s1)
            if (p <= 0f) continue
            var peak = -120f
            var confSum = 0f; var confN = 0
            for (q in s0..s1) {
                peak = max(peak, fr[q].db)
                if (fr[q].voiced) { confSum += fr[q].conf; confN++ }
            }
            val vel = ((peak + 40f) / 34f).coerceIn(0.35f, 1f)
            out += Cand(fr[s0].t - (WIN / 2f) / SR + 0.01f, fr[s1].t + dt, p, vel, if (confN > 0) confSum / confN else 0f, peak)
        }
    }

    /** The pitch aimed for: median of the steady, confident frames in the body of the note. */
    private fun steadyPitch(fr: List<FrameM>, pc: FloatArray, a: Int, b: Int): Float {
        val len = b - a + 1
        val from = a + (len * 0.22f).toInt()
        val to = b - (len * 0.1f).toInt()
        val v = ArrayList<Float>()
        for (q in max(from, a + 1)..max(from, to)) {
            if (q > b || !fr[q].voiced || !fr[q - 1].voiced) continue
            if (abs(pc[q] - pc[q - 1]) < 0.2f && fr[q].conf > 0.6f) v += pc[q]
        }
        if (v.size < 3) for (q in a..b) if (fr[q].voiced) v += pc[q]
        if (v.isEmpty()) return 0f
        v.sort()
        return v[v.size / 2]
    }

    // -- 5. purity: keep only what was really sung -----------------------------

    /**
     * Removes everything that isn't part of the melody you sang:
     *  - breathy / noisy sounds (sniffs, breaths): a sung vowel is a clean, steady tone;
     *  - faint background blips, much quieter than your singing;
     *  - crumbs far shorter than your notes (clicks, glitches);
     *  - short isolated jumps far from the tune (beeps, squeaks);
     *  - short tails and fall-offs stuck to a real note (merged back into it).
     */
    private fun purify(c: List<Cand>): List<Cand> {
        if (c.size < 3) return c.filter { it.conf >= 0.7f }
        val medDur = c.map { it.dur }.sorted()[c.size / 2]
        val loud = c.map { it.peakDb }.sorted()[(c.size * 0.75).toInt().coerceAtMost(c.lastIndex)]
        val out = c.filter { it.conf >= 0.72f && it.peakDb >= loud - 15f && it.dur >= max(0.09f, medDur * 0.3f) }.toMutableList()

        // isolated jumps: short and far from both neighbours
        var i = 0
        while (i < out.size) {
            val n = out[i]
            val prev = out.getOrNull(i - 1); val next = out.getOrNull(i + 1)
            val farPrev = prev == null || abs(n.raw - prev.raw) > 7f
            val farNext = next == null || abs(n.raw - next.raw) > 7f
            if (n.dur < medDur * 0.6f && farPrev && farNext && (prev != null || next != null)) out.removeAt(i) else i++
        }

        // short fragments glued to a neighbour (note tails, scoops, transitions) fold into it
        val shortLen = max(0.13f, medDur * 0.42f)
        i = 0
        while (i < out.size) {
            val n = out[i]
            if (n.dur >= shortLen) { i++; continue }
            val prev = out.getOrNull(i - 1); val next = out.getOrNull(i + 1)
            val gluedPrev = prev != null && n.start - prev.end < 0.05f && abs(n.raw - prev.raw) < 5f
            val gluedNext = next != null && next.start - n.end < 0.05f && abs(n.raw - next.raw) < 5f
            when {
                gluedPrev && (!gluedNext || prev!!.dur >= next!!.dur) -> { out[i - 1] = prev!!.withSpan(prev.start, n.end); out.removeAt(i) }
                gluedNext -> { out[i + 1] = next!!.withSpan(n.start, next.end); out.removeAt(i) }
                else -> i++
            }
        }
        return out
    }

    // -- 5. key awareness ----------------------------------------------------

    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    private val MAJOR_SCALE = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MINOR_SCALE = intArrayOf(0, 2, 3, 5, 7, 8, 10, 11)

    /**
     * Works out the key of what was sung (Krumhansl-Kessler profiles). For notes sung almost
     * halfway between two keys, it picks the one that belongs to that key: the note that was meant.
     */
    private fun snapToKey(c: List<Cand>): List<SongNote> {
        val plain = c.map { SongNote(it.start, it.end, it.raw.roundToInt().coerceIn(21, 108), it.vel) }
        if (c.size < 5) return plain
        val hist = DoubleArray(12)
        for (n in c) {
            val r = n.raw.roundToInt()
            val certain = abs(n.raw - r) < 0.3f
            hist[((r % 12) + 12) % 12] += (n.end - n.start) * if (certain) 1.0 else 0.3
        }
        var best = -2.0; var bestTonic = 0; var bestMajor = true
        for (tonic in 0 until 12) for (major in listOf(true, false)) {
            val prof = if (major) MAJOR else MINOR
            val r = pearson(hist) { prof[((it - tonic) % 12 + 12) % 12] }
            if (r > best) { best = r; bestTonic = tonic; bestMajor = major }
        }
        if (best < 0.5) return plain
        val scale = (if (bestMajor) MAJOR_SCALE else MINOR_SCALE).map { (it + bestTonic) % 12 }.toSet()
        fun inKey(m: Int) = ((m % 12) + 12) % 12 in scale
        return c.mapIndexed { i, n ->
            val r = n.raw.roundToInt()
            val frac = n.raw - r
            if (!inKey(r) && abs(frac) > 0.22f) {
                val alt = if (frac > 0) r + 1 else r - 1
                if (inKey(alt)) return@mapIndexed plain[i].copy(pitch = alt.coerceIn(21, 108))
            }
            plain[i]
        }
    }

    private fun pearson(x: DoubleArray, y: (Int) -> Double): Double {
        val ys = DoubleArray(12) { y(it) }
        val mx = x.average(); val my = ys.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until 12) { val a = x[i] - mx; val b = ys[i] - my; sxy += a * b; sxx += a * a; syy += b * b }
        return if (sxx == 0.0 || syy == 0.0) 0.0 else sxy / sqrt(sxx * syy)
    }
}
