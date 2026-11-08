package app.breakpiano.ui.keyboard

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.breakpiano.audio.NativeEngine

/** Piano geometry helpers for the 88-key range A0 (21) … C8 (108). */
object PianoLayout {
    const val FIRST = 21
    const val LAST = 108
    private val BLACK = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)
    fun isBlack(note: Int) = BLACK[note % 12]

    /** MIDI notes of the 52 white keys. */
    val whites: IntArray = (FIRST..LAST).filter { !isBlack(it) }.toIntArray()
    val whiteCount = whites.size

    /** Index of the white key at or just below [note]. */
    fun whiteIndexOf(note: Int): Int {
        var n = note
        while (isBlack(n)) n--
        return whites.indexOf(n).coerceAtLeast(0)
    }

    /** Horizontal nudge of each black key relative to the gap it sits in (in white-key widths). */
    fun blackOffset(note: Int): Float = when (note % 12) {
        1 -> -0.07f; 3 -> 0.07f; 6 -> -0.09f; 8 -> 0f; 10 -> 0.09f; else -> 0f
    }

    private val letterSharp = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    private val letterFlat = arrayOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")
    private val solSharp = arrayOf("Do", "Do♯", "Ré", "Ré♯", "Mi", "Fa", "Fa♯", "Sol", "Sol♯", "La", "La♯", "Si")
    private val solFlat = arrayOf("Do", "Ré♭", "Ré", "Mi♭", "Mi", "Fa", "Sol♭", "Sol", "La♭", "La", "Si♭", "Si")

    fun name(note: Int, solfege: Boolean, flats: Boolean): String {
        val pc = note % 12
        return when {
            solfege && flats -> solFlat[pc]
            solfege -> solSharp[pc]
            flats -> letterFlat[pc]
            else -> letterSharp[pc]
        }
    }

    fun octave(note: Int) = note / 12 - 1
}

/**
 * Shared "which keys are down" model. Touch handlers call [press]/[release], which talk to the
 * engine synchronously (the JNI call only enqueues), then bump [version] so the canvases redraw.
 */
class KeyboardModel {
    val down = IntArray(128)
    val releasedAt = LongArray(128)
    var version by mutableIntStateOf(0)
    var fadeUntil = 0L

    fun press(note: Int, velocity: Float) {
        if (note !in 0..127) return
        NativeEngine.noteOn(note, velocity)
        down[note]++
        version++
    }

    fun release(note: Int) {
        if (note !in 0..127 || down[note] == 0) return
        down[note]--
        if (down[note] == 0) {
            NativeEngine.noteOff(note)
            val now = SystemClock.uptimeMillis()
            releasedAt[note] = now
            fadeUntil = now + GLOW_FADE_MS
        }
        version++
    }

    fun releaseAll() {
        for (n in 0..127) if (down[n] > 0) { down[n] = 1; release(n) }
    }

    /** 1 while held, then fades to 0 over [GLOW_FADE_MS]. */
    fun glow(note: Int, now: Long): Float {
        if (down[note] > 0) return 1f
        val dt = now - releasedAt[note]
        return if (dt in 0 until GLOW_FADE_MS) 1f - dt / GLOW_FADE_MS.toFloat() else 0f
    }

    companion object { const val GLOW_FADE_MS = 320L }
}
