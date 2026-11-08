package app.breakpiano.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays a short, idiomatic phrase for any instrument on a dedicated preview channel,
 * so you can audition sounds without changing the one under your fingers.
 */
class Previewer(private val scope: CoroutineScope) {
    var playingId by mutableStateOf<String?>(null); private set
    private var job: Job? = null

    private data class N(val at: Long, val note: Int, val len: Long, val vel: Float = 0.8f)

    private fun phrase(f: InstrumentFamily): List<N> = when (f) {
        InstrumentFamily.Piano, InstrumentFamily.Electric -> listOf(
            N(0, 48, 1500, .6f), N(0, 60, 260), N(250, 64, 260), N(500, 67, 260), N(750, 72, 900),
            N(1250, 60, 1100, .7f), N(1250, 64, 1100, .7f), N(1250, 67, 1100, .7f), N(1250, 71, 1100, .75f),
        )
        InstrumentFamily.Organ -> listOf(
            N(0, 48, 1900, .7f), N(0, 60, 900), N(0, 64, 900), N(0, 67, 900),
            N(950, 59, 950), N(950, 65, 950), N(950, 67, 950), N(950, 74, 950),
        )
        InstrumentFamily.Guitar, InstrumentFamily.World -> listOf(
            N(0, 48, 1200), N(30, 52, 1170), N(60, 55, 1140), N(90, 60, 1110), N(120, 64, 1080),
            N(750, 67, 380, .75f), N(1050, 64, 380, .7f), N(1350, 72, 800, .8f),
        )
        InstrumentFamily.Strings, InstrumentFamily.Winds, InstrumentFamily.Choir -> listOf(
            N(0, 67, 420), N(420, 69, 420), N(840, 71, 420), N(1260, 72, 1100, .85f),
        )
        InstrumentFamily.Bass -> listOf(
            N(0, 36, 300, .9f), N(330, 36, 140), N(500, 43, 300), N(850, 46, 300), N(1200, 48, 600, .9f),
        )
        InstrumentFamily.Synth -> listOf(
            N(0, 60, 1500), N(0, 64, 1500), N(0, 67, 1500), N(0, 71, 1500), N(600, 74, 900, .7f),
        )
        InstrumentFamily.Mallets -> listOf(
            N(0, 72, 400), N(180, 76, 400), N(360, 79, 400), N(540, 84, 900), N(900, 79, 700), N(1080, 76, 900),
        )
    }

    fun toggle(inst: Instrument) = if (playingId == inst.id) stop() else play(inst)

    fun play(inst: Instrument) {
        job?.cancel()
        NativeEngine.previewStop()
        NativeEngine.previewProgram(inst.bank, inst.program)
        playingId = inst.id
        job = scope.launch {
            val notes = phrase(inst.family)
            // flatten into on/off events sorted by time
            val events = notes.flatMap { listOf(it.at to (it.note to it.vel), (it.at + it.len) to (it.note to 0f)) }
                .sortedBy { it.first }
            var t = 0L
            try {
                for ((at, ev) in events) {
                    if (at > t) { delay(at - t); t = at }
                    NativeEngine.previewNote(ev.first, ev.second)
                }
                delay(700)
            } finally {
                NativeEngine.previewStop()
                if (playingId == inst.id) playingId = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        NativeEngine.previewStop()
        playingId = null
    }
}
