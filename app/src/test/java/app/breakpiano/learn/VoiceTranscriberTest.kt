package app.breakpiano.learn

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesises realistic sung takes — consonant attack, scoop into the note, vibrato, vowel
 * formants, uneven loudness, room noise, an off-pitch singer — and checks the notes come back.
 */
class VoiceTranscriberTest {
    private val sr = 44100

    private class Syl(val midi: Int, val dur: Float, val gapAfter: Float = 0.07f, val glideFrom: Int? = null, val cents: Double = 0.0)

    private fun sing(
        syllables: List<Syl>,
        detuneCents: Double = 0.0,
        level: Double = 0.3,
        noise: Double = 0.003,
        consonant: Boolean = true,
        seed: Int = 7,
        driftCents: Double = 0.0,          // how far the singer has drifted by the end of the take
        attack: Double = 0.02,
        release: Double = 0.04,
        consonantSec: Double = 0.012,
        fallCents: Double = 0.0,           // each note's pitch falls away at the very end (a sung "tail")
    ): FloatArray {
        val rnd = Random(seed)
        val total = 0.3 + syllables.sumOf { (it.dur + it.gapAfter).toDouble() } + 0.3
        val out = FloatArray((total * sr).toInt())
        var t0 = 0.3
        var phase = 0.0
        for ((idx, s) in syllables.withIndex()) {
            val drift = driftCents * idx / maxOf(1, syllables.size - 1)
            val start = (t0 * sr).toInt()
            val n = (s.dur * sr).toInt()
            val amp = level * 10.0.pow(rnd.nextDouble(-3.0, 3.0) / 20)
            if (consonant) for (i in 0 until (consonantSec * sr).toInt()) {   // "t" burst
                val at = start - (consonantSec * sr).toInt() + i
                if (at in out.indices) out[at] += (rnd.nextDouble(-1.0, 1.0) * amp * 0.5).toFloat()
            }
            for (i in 0 until n) {
                val tt = i.toDouble() / sr
                var cents = detuneCents + drift + s.cents - 60 * exp(-tt / 0.04)   // scoop into the note
                if (tt > 0.15) cents += 25 * sin(2 * PI * 5.5 * (tt - 0.15))     // vibrato
                val tail = s.dur - tt
                if (fallCents != 0.0 && tail < 0.09) cents += fallCents * (1 - tail / 0.09)
                val target = s.midi.toDouble()
                val pitch = if (s.glideFrom != null && tt < 0.06) s.glideFrom + (target - s.glideFrom) * tt / 0.06 else target
                val f0 = 440.0 * 2.0.pow((pitch - 69 + cents / 100) / 12)
                phase += 2 * PI * f0 / sr
                var v = 0.0
                for (h in 1..12) {
                    val fh = f0 * h
                    if (fh > 5000) break
                    val formant = 1.0 + 2.2 * exp(-((fh - 750) / 250).pow(2)) + 1.4 * exp(-((fh - 1250) / 300).pow(2))
                    v += sin(h * phase) * formant / h.toDouble().pow(1.2)
                }
                val env = minOf(1.0, tt / attack) * minOf(1.0, (s.dur - tt) / release) * (if (fallCents != 0.0 && tail < 0.09) 0.6 + 0.4 * tail / 0.09 else 1.0)
                out[start + i] += (v * env * amp * 0.25).toFloat()
            }
            t0 += s.dur + s.gapAfter
        }
        for (i in out.indices) out[i] += (rnd.nextDouble(-1.0, 1.0) * noise).toFloat()
        return out
    }

    private fun pitches(x: FloatArray) = VoiceTranscriber.transcribe(x, sr).also { notes ->
        notes.forEach { println("%.2f-%.2f %d %.2f".format(it.start, it.end, it.pitch, it.velocity)) }
    }.map { it.pitch }

    @Test fun tanTanTan_twinkleSungSharp() {
        val tune = listOf(60, 60, 67, 67, 69, 69, 67)
        val x = sing(tune.map { Syl(it, 0.38f) }, detuneCents = 35.0)
        assertEquals(tune, pitches(x))
    }

    @Test fun lowMaleHum() {
        val tune = listOf(43, 45, 47, 48, 50, 48)
        val x = sing(tune.map { Syl(it, 0.42f, 0.09f) }, detuneCents = -15.0, seed = 3)
        assertEquals(tune, pitches(x))
    }

    @Test fun highVoiceSungFlat() {
        val tune = listOf(72, 74, 76, 79, 76)
        val x = sing(tune.map { Syl(it, 0.33f, 0.06f) }, detuneCents = -38.0, seed = 11)
        assertEquals(tune, pitches(x))
    }

    @Test fun legatoAaahWithoutGaps() {
        val x = sing(
            listOf(Syl(60, 0.5f, 0f), Syl(64, 0.5f, 0f, glideFrom = 60), Syl(67, 0.6f, 0f, glideFrom = 64)),
            consonant = false, seed = 5,
        )
        assertEquals(listOf(60, 64, 67), pitches(x))
    }

    @Test fun quietNoisyPhoneRecording() {
        val tune = listOf(62, 64, 65, 67, 69)
        val x = sing(tune.map { Syl(it, 0.35f, 0.08f) }, level = 0.02, noise = 0.0012, seed = 21)
        assertEquals(tune, pitches(x))
    }

    /** Quick "tang-tang-tang" with no silence between syllables — only a very short "t". */
    @Test fun fastTangTangWithoutGaps() {
        val tune = listOf(60, 62, 64, 64, 62, 60, 67, 67)
        val x = sing(tune.map { Syl(it, 0.28f, 0f) }, attack = 0.008, release = 0.012, consonantSec = 0.007, seed = 13)
        assertEquals(tune, pitches(x))
    }

    /** A singer who starts in tune and slowly goes 70 cents flat over the take. */
    @Test fun singerDriftingFlat() {
        val tune = listOf(64, 62, 60, 62, 64, 64, 64, 62, 62, 62, 64, 67, 67)
        val x = sing(tune.map { Syl(it, 0.36f) }, detuneCents = 10.0, driftCents = -70.0, seed = 17)
        assertEquals(tune, pitches(x))
    }

    /** Some notes missed by more than half a semitone: the key of the tune says which note was meant. */
    @Test fun notesSungHalfwayLandOnTheIntendedKey() {
        val sung = listOf(60 to 0.0, 62 to 0.0, 64 to -55.0, 65 to 0.0, 67 to 55.0, 69 to -55.0, 71 to 0.0,
            72 to 0.0, 67 to 0.0, 64 to 0.0, 62 to 55.0, 60 to 0.0)
        val x = sing(sung.map { Syl(it.first, 0.34f, cents = it.second) }, seed = 19)
        assertEquals(sung.map { it.first }, pitches(x))
    }

    // ── junk that is not part of the melody ──
    private fun sniff(x: FloatArray, at: Double, level: Double, seed: Int) {
        val rnd = Random(seed); val n = (0.16 * sr).toInt(); val s0 = (at * sr).toInt()
        var y1 = 0.0; var y2 = 0.0
        val w = 2 * PI * 1600.0 / sr; val r = 0.97                          // nasal resonance ~1.6 kHz
        for (i in 0 until n) {
            val env = sin(PI * i / n)
            val v = rnd.nextDouble(-1.0, 1.0) + 2 * r * cos(w) * y1 - r * r * y2
            y2 = y1; y1 = v
            if (s0 + i in x.indices) x[s0 + i] += (v * 0.05 * level * env).toFloat()
        }
    }
    private fun beep(x: FloatArray, at: Double, hz: Double, dur: Double, level: Double) {
        val s0 = (at * sr).toInt(); val n = (dur * sr).toInt()
        for (i in 0 until n) if (s0 + i in x.indices) x[s0 + i] += (sin(2 * PI * hz * i / sr) * level * minOf(1.0, minOf(i, n - i) / 200.0)).toFloat()
    }
    private fun click(x: FloatArray, at: Double, level: Double) {
        val s0 = (at * sr).toInt()
        for (i in 0 until 60) if (s0 + i in x.indices) x[s0 + i] += (level * exp(-i / 8.0) * (if (i % 2 == 0) 1 else -1)).toFloat()
    }
    private fun breath(x: FloatArray, at: Double, dur: Double, level: Double, seed: Int) {
        val rnd = Random(seed); val s0 = (at * sr).toInt(); val n = (dur * sr).toInt()
        for (i in 0 until n) if (s0 + i in x.indices) x[s0 + i] += (rnd.nextDouble(-1.0, 1.0) * level * sin(PI * i / n)).toFloat()
    }

    /** "tang tang" with sniffs, a notification beep, clicks and breaths around it: only the tune remains. */
    @Test fun sniffsBeepsAndClicksAreIgnored() {
        val tune = listOf(60, 62, 64, 60, 60, 62, 64, 60, 64, 65, 67)
        val x = sing(tune.map { Syl(it, 0.34f, 0.16f) }, seed = 23)
        sniff(x, 0.05, 0.3, 1)                 // before singing
        sniff(x, 0.3 + 4 * 0.5 + 0.35, 0.3, 2) // in a gap
        beep(x, 0.3 + 6 * 0.5 + 0.36, 1975.0, 0.07, 0.05)  // short high beep in a gap (B6)
        click(x, 0.3 + 2 * 0.5 + 0.38, 0.4)
        click(x, 0.3 + 8 * 0.5 + 0.37, 0.4)
        breath(x, 0.3 + 3 * 0.5 + 0.35, 0.14, 0.03, 3)
        assertEquals(tune, pitches(x))
    }

    /** Every note ends by sliding down (a natural sung tail): no extra little notes. */
    @Test fun noteTailsDoNotBecomeNotes() {
        val tune = listOf(67, 65, 64, 62, 60, 62, 64)
        val x = sing(tune.map { Syl(it, 0.4f, 0.05f) }, fallCents = -350.0, seed = 29)
        assertEquals(tune, pitches(x))
    }

    // ── noisy rooms ──
    private fun fan(x: FloatArray, level: Double, seed: Int) {
        val rnd = Random(seed); var lp = 0.0
        for (i in x.indices) { lp = 0.92 * lp + 0.08 * rnd.nextDouble(-1.0, 1.0); x[i] += ((lp * 3 + rnd.nextDouble(-1.0, 1.0) * 0.3) * level).toFloat() }
    }
    private fun hum(x: FloatArray, level: Double) {
        for (i in x.indices) { val t = i.toDouble() / sr; x[i] += (level * (sin(2 * PI * 100 * t) + 0.5 * sin(2 * PI * 200 * t) + 0.3 * sin(2 * PI * 300 * t))).toFloat() }
    }
    /** Someone talking in the background: a wandering, syllabic voice through the whole take. */
    private fun talker(x: FloatArray, level: Double, seed: Int) {
        val rnd = Random(seed); var f = 150.0; var ph = 0.0; var target = 150.0
        for (i in x.indices) {
            if (i % 2205 == 0) target = rnd.nextDouble(100.0, 220.0)
            f += (target - f) * 0.0006
            ph += 2 * PI * f / sr
            val t = i.toDouble() / sr
            val syl = maxOf(0.0, sin(2 * PI * 4.3 * t)).pow(1.5)
            var v = 0.0
            for (h in 1..10) v += sin(h * ph) / h
            x[i] += (v * syl * level).toFloat()
        }
    }
    private fun reverb(x: FloatArray): FloatArray {
        val y = x.copyOf()
        val d1 = (0.047 * sr).toInt(); val d2 = (0.071 * sr).toInt(); val d3 = (0.113 * sr).toInt()
        for (i in y.indices) {
            var v = y[i].toDouble()
            if (i >= d1) v += 0.45 * y[i - d1]
            if (i >= d2) v += 0.35 * y[i - d2]
            if (i >= d3) v += 0.25 * y[i - d3]
            y[i] = (v * 0.7).toFloat()
        }
        return y
    }

    private val ode = listOf(64, 64, 65, 67, 67, 65, 64, 62, 60, 60, 62, 64, 64, 62, 62)   // "Ode to Joy"

    @Test fun loudFanInTheRoom() {
        val x = sing(ode.map { Syl(it, 0.32f, 0.08f) }, seed = 31)
        fan(x, 0.035, 4)
        assertEquals(ode, pitches(x))
    }

    @Test fun electricalHum() {
        val x = sing(ode.map { Syl(it, 0.32f, 0.08f) }, seed = 37)
        hum(x, 0.02)
        assertEquals(ode, pitches(x))
    }

    @Test fun someoneTalkingInTheBackground() {
        val x = sing(ode.map { Syl(it, 0.32f, 0.12f) }, seed = 41)
        talker(x, 0.025, 6)
        assertEquals(ode, pitches(x))
    }

    @Test fun echoeyRoom() {
        val x = reverb(sing(ode.map { Syl(it, 0.32f, 0.1f) }, seed = 43))
        assertEquals(ode, pitches(x))
    }

    @Test fun everythingAtOnce() {
        val x = reverb(sing(ode.map { Syl(it, 0.32f, 0.12f) }, detuneCents = 25.0, seed = 47))
        fan(x, 0.02, 8); hum(x, 0.01); talker(x, 0.015, 9)
        sniff(x, 0.3 + 3 * 0.44 + 0.33, 0.3, 5); click(x, 0.3 + 9 * 0.44 + 0.34, 0.3)
        assertEquals(ode, pitches(x))
    }
}
