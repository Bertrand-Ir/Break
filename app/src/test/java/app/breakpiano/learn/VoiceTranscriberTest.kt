package app.breakpiano.learn

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
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

    private class Syl(val midi: Int, val dur: Float, val gapAfter: Float = 0.07f, val glideFrom: Int? = null)

    private fun sing(
        syllables: List<Syl>,
        detuneCents: Double = 0.0,
        level: Double = 0.3,
        noise: Double = 0.003,
        consonant: Boolean = true,
        seed: Int = 7,
    ): FloatArray {
        val rnd = Random(seed)
        val total = 0.3 + syllables.sumOf { (it.dur + it.gapAfter).toDouble() } + 0.3
        val out = FloatArray((total * sr).toInt())
        var t0 = 0.3
        var phase = 0.0
        for (s in syllables) {
            val start = (t0 * sr).toInt()
            val n = (s.dur * sr).toInt()
            val amp = level * 10.0.pow(rnd.nextDouble(-3.0, 3.0) / 20)
            if (consonant) for (i in 0 until (0.012 * sr).toInt()) {           // "t" burst
                val idx = start - (0.012 * sr).toInt() + i
                if (idx in out.indices) out[idx] += (rnd.nextDouble(-1.0, 1.0) * amp * 0.5).toFloat()
            }
            for (i in 0 until n) {
                val tt = i.toDouble() / sr
                var cents = detuneCents - 60 * exp(-tt / 0.04)                   // scoop into the note
                if (tt > 0.15) cents += 25 * sin(2 * PI * 5.5 * (tt - 0.15))     // vibrato
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
                val env = minOf(1.0, tt / 0.02) * minOf(1.0, (s.dur - tt) / 0.04)
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
}
