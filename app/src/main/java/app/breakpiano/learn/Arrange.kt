package app.breakpiano.learn

import kotlin.math.max
import kotlin.math.min

/** A note to learn, with the hand that should play it (0 = right, 1 = left). */
data class LearnNote(val start: Float, val end: Float, val pitch: Int, val velocity: Float, val hand: Int)

enum class Arrangement(val title: String) { Melody("Melody"), MelodyBass("Melody + bass"), Full("Full") }

/**
 * Turns raw notes into something you can actually learn. Audio transcriptions include every
 * instrument in the mix, so "Melody" keeps only the top line, and "Melody + bass" adds a
 * simple left-hand bass. MIDI files usually sound great in "Full".
 */
object Arrange {
    private const val CHORD_WINDOW = 0.06f

    /** Overtone intervals (2nd–8th harmonics) in semitones. */
    private val HARMONICS = intArrayOf(12, 19, 24, 28, 31, 34, 36)

    /**
     * Cleans an audio transcription:
     *  • drops "ghost" notes that are really overtones of a louder note starting at the same time;
     *  • re-joins a sustained note that the detector split in two as it decayed.
     */
    fun clean(raw: List<SongNote>, voice: Boolean = false): List<SongNote> {
        val sorted = (if (voice) voiceLine(raw) else raw).sortedBy { it.start }
        val keep = BooleanArray(sorted.size) { true }
        for (i in sorted.indices) {
            val b = sorted[i]
            var j = i - 1
            while (j >= 0 && b.start - sorted[j].start <= 8f) j--   // anything that could still be ringing
            for (k in (j + 1) until sorted.size) {
                val a = sorted[k]
                if (a.start - b.start > 0.08f) break
                if (k == i || a.pitch >= b.pitch || (b.pitch - a.pitch) !in HARMONICS) continue
                val together = kotlin.math.abs(a.start - b.start) <= 0.08f
                val ringing = a.start < b.start && a.end >= b.start + 0.02f
                // a ghost is clearly quieter than its fundamental; be stricter for a held note
                if ((together && b.velocity < a.velocity * 0.7f) || (ringing && b.velocity < a.velocity * 0.55f)) {
                    keep[i] = false; break
                }
            }
        }
        val out = ArrayList<SongNote>()
        val lastByPitch = HashMap<Int, Int>()
        for (i in sorted.indices) {
            if (!keep[i]) continue
            val n = sorted[i]
            val prevIdx = lastByPitch[n.pitch]
            val prev = prevIdx?.let { out[it] }
            if (prev != null && n.start - prev.end in -0.02f..0.05f && n.velocity <= prev.velocity * 1.05f) {
                out[prevIdx] = prev.copy(end = maxOf(prev.end, n.end))
            } else {
                lastByPitch[n.pitch] = out.size
                out += n
            }
        }
        return out
    }

    /**
     * Singing / humming: one note at a time, short pitch wobbles absorbed into the note around
     * them, and same-pitch fragments joined across small breaths.
     */
    private fun voiceLine(raw: List<SongNote>): List<SongNote> {
        val sorted = raw.sortedBy { it.start }
        // keep the strongest note at each moment (a voice only sings one)
        val mono = ArrayList<SongNote>()
        for (n in sorted) {
            val last = mono.lastOrNull()
            if (last != null && n.start < last.end - 0.03f) {
                if (n.velocity > last.velocity * 1.15f && n.end - n.start > 0.12f) {
                    mono[mono.lastIndex] = last.copy(end = n.start)
                    mono += n
                }
                continue
            }
            mono += n
        }
        // absorb blips (< 110 ms) into the previous note, join same-pitch fragments
        val out = ArrayList<SongNote>()
        for (n in mono.filter { it.end - it.start > 0.03f }) {
            val last = out.lastOrNull()
            when {
                last != null && n.end - n.start < 0.11f && n.start - last.end < 0.08f -> out[out.lastIndex] = last.copy(end = n.end)
                // same pitch: vibrato splits a sung note into fragments that continue more softly — join them.
                // A deliberately repeated note comes back with a fresh, stronger attack — keep it separate.
                last != null && n.pitch == last.pitch && n.start - last.end < 0.15f &&
                    !(n.velocity > last.velocity * 1.02f && n.end - n.start >= 0.15f) ->
                    out[out.lastIndex] = last.copy(end = n.end)
                n.end - n.start >= 0.11f -> out += n
            }
        }
        return out
    }

    fun build(raw: List<SongNote>, mode: Arrangement): List<LearnNote> = when (mode) {
        Arrangement.Full -> raw.map { LearnNote(it.start, it.end, it.pitch, it.velocity, if (it.pitch < 60) 1 else 0) }
        Arrangement.Melody -> melody(raw).map { LearnNote(it.start, it.end, it.pitch, it.velocity, 0) }
        Arrangement.MelodyBass -> {
            val m = melody(raw)
            val b = bass(raw, m)
            (m.map { LearnNote(it.start, it.end, it.pitch, it.velocity, 0) } +
                b.map { LearnNote(it.start, it.end, it.pitch, it.velocity, 1) }).sortedBy { it.start }
        }
    }

    /** Skyline melody: the highest confident note at each moment, made monophonic. */
    private fun melody(raw: List<SongNote>): List<SongNote> {
        val src = raw.filter { it.pitch >= 52 && it.end - it.start >= 0.08f }.ifEmpty { raw }.sortedBy { it.start }
        val out = ArrayList<SongNote>()
        var i = 0
        while (i < src.size) {
            // gather a cluster of near-simultaneous onsets and keep its top note
            var j = i
            var top = src[i]
            while (j < src.size && src[j].start - src[i].start <= CHORD_WINDOW) {
                if (src[j].pitch > top.pitch && src[j].velocity >= top.velocity * 0.55f) top = src[j]
                j++
            }
            // skip if a louder, higher note is still sounding over this onset (it's harmony, not melody)
            val last = out.lastOrNull()
            val masked = last != null && last.end > top.start + 0.05f && last.pitch > top.pitch + 2 && last.velocity >= top.velocity
            if (!masked) {
                if (last != null && last.end > top.start) out[out.lastIndex] = last.copy(end = max(last.start + 0.05f, top.start))
                out += top
            }
            i = j
        }
        return out.filter { it.end - it.start >= 0.06f }
    }

    /** A sparse left-hand line: the lowest sustained note under the melody, at most ~4 per second. */
    private fun bass(raw: List<SongNote>, melody: List<SongNote>): List<SongNote> {
        val src = raw.filter { it.pitch in 28..59 && it.end - it.start >= 0.15f }.sortedBy { it.start }
        val out = ArrayList<SongNote>()
        for (n in src) {
            val last = out.lastOrNull()
            if (last != null && n.start - last.start < 0.25f) {
                if (n.pitch < last.pitch && n.start - last.start < CHORD_WINDOW) out[out.lastIndex] = n
                continue
            }
            if (melody.any { it.pitch == n.pitch && kotlin.math.abs(it.start - n.start) < 0.1f }) continue
            if (last != null && last.end > n.start) out[out.lastIndex] = last.copy(end = max(last.start + 0.05f, n.start))
            out += n
        }
        return out
    }

    /** Lowest and highest pitch, for fitting the keyboard. */
    fun range(notes: List<LearnNote>): IntRange =
        if (notes.isEmpty()) 60..72 else notes.minOf { it.pitch }..notes.maxOf { it.pitch }

}
