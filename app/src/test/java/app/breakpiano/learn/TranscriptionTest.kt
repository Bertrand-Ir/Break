package app.breakpiano.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TranscriptionTest {
    private fun load(name: String): Pair<Array<FloatArray>, Array<FloatArray>> {
        val bytes = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.int
        val note = Array(n) { FloatArray(88) { bb.float } }
        val onset = Array(n) { FloatArray(88) { bb.float } }
        return note to onset
    }

    /** Real Basic Pitch activations for C4 E4 G4 C5 (0.5 s each) followed by a C-E-G chord. */
    @Test
    fun buildsTheRightNotesFromModelOutput() {
        val (note, onset) = load("basic_pitch_cegc.bin")
        val notes = Arrange.clean(NoteBuilder.outputToNotes(note, onset))
        notes.forEach { println("%.2f-%.2f  %d  %.2f".format(it.start, it.end, it.pitch, it.velocity)) }

        val melody = notes.filter { it.start < 2.4f }.sortedBy { it.start }.map { it.pitch }
        assertEquals(listOf(60, 64, 67, 72), melody)
        val chord = notes.filter { it.start in 2.3f..2.8f }.map { it.pitch }.toSet()
        assertEquals(setOf(60, 64, 67), chord)
        // onsets land within ~60 ms of the truth
        val starts = notes.filter { it.start < 2.4f }.sortedBy { it.start }.map { it.start }
        listOf(0.25f, 0.75f, 1.25f, 1.75f).zip(starts).forEach { (t, s) -> assertTrue("onset $s vs $t", kotlin.math.abs(s - t) < 0.06f) }
    }

    @Test
    fun melodyArrangementKeepsTheTopLine() {
        val (note, onset) = load("basic_pitch_cegc.bin")
        val m = Arrange.build(Arrange.clean(NoteBuilder.outputToNotes(note, onset)), Arrangement.Melody).map { it.pitch }
        assertEquals(listOf(60, 64, 67, 72, 67), m)   // chord collapses to its top note
    }

    @Test
    fun parsesMidiTempoRunningStatusAndSkipsDrums() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("two_notes.mid")!!.readBytes()
        val notes = MidiFile.parse(bytes)
        assertEquals(2, notes.size)
        assertEquals(60, notes[0].pitch); assertEquals(0f, notes[0].start, 1e-4f); assertEquals(0.5f, notes[0].end, 1e-4f)
        assertEquals(64, notes[1].pitch); assertEquals(0.5f, notes[1].start, 1e-4f); assertEquals(1.0f, notes[1].end, 1e-4f)
    }

    @Test
    fun resamplerKeepsPitchAndLevel() {
        for (rate in intArrayOf(44100, 48000)) {
            val f = 440.0
            val x = FloatArray(rate) { (0.5 * kotlin.math.sin(2 * Math.PI * f * it / rate)).toFloat() }
            val y = resample(x, rate, 22050)
            assertEquals(22050f, y.size.toFloat(), 2f)
            var crossings = 0
            for (i in 1000 until 21050) if (y[i - 1] < 0f && y[i] >= 0f) crossings++
            val est = crossings * 22050.0 / 20050
            assertTrue("rate $rate → $est Hz", kotlin.math.abs(est - f) < 3.0)
            val peak = (1000 until 21050).maxOf { kotlin.math.abs(y[it]) }
            assertTrue("rate $rate peak $peak", peak in 0.47f..0.53f)
        }
    }
}
