package app.breakpiano.learn

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.breakpiano.audio.NativeEngine
import app.breakpiano.data.BreakState
import app.breakpiano.ui.keyboard.PianoLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Hands(val title: String) { Both("Both"), Right("Right"), Left("Left") }
enum class Listen(val title: String) { Piano("Piano"), Original("Original"), Off("Silent") }

/**
 * Learning mode: plays a song as falling notes, lights up the keys to press, plays a guide
 * (or the original recording) and — in Wait mode — holds on each note until you play it.
 */
class Learner(private val library: SongLibrary, private val state: BreakState, private val scope: CoroutineScope) {
    var song by mutableStateOf<Song?>(null); private set
    var notes by mutableStateOf<List<LearnNote>>(emptyList()); private set
    var arrangement by mutableStateOf(Arrangement.Melody); private set
    var position by mutableFloatStateOf(0f); private set
    var playing by mutableStateOf(false); private set
    var waitMode by mutableStateOf(true)
    var speed by mutableFloatStateOf(1f)
    var hands by mutableStateOf(Hands.Both)
    var listen by mutableStateOf(Listen.Piano); private set
    var waiting by mutableStateOf(false); private set
    var finished by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var hits by mutableIntStateOf(0); private set
    var misses by mutableIntStateOf(0); private set
    /** First white key the keyboard should show (−1 = leave it). */
    var focusStart by mutableIntStateOf(-1); private set
    /** Bumped every frame while active so canvases redraw. */
    var version by mutableIntStateOf(0); private set

    /** Per-key guide intensity (0 none … 1 play now) and hand, for drawing the keys. */
    val guide = FloatArray(128)
    val guideHand = IntArray(128)

    val active: Boolean get() = song != null
    val duration: Float get() = (song?.durationMs ?: 0L) / 1000f
    val accuracy: Int get() = if (hits + misses == 0) 100 else (100f * hits / (hits + misses)).toInt()

    private var raw: List<SongNote> = emptyList()
    private var audioLoaded = false
    private var soundIdx = 0
    private val sounding = ArrayList<LearnNote>()
    private var chordIdx = 0
    private var scoreIdx = 0
    private val held = HashSet<Int>()
    private val satisfied = HashSet<Int>()
    private var hitFlags = BooleanArray(0)

    private fun isMine(n: LearnNote) = when (hands) { Hands.Both -> true; Hands.Right -> n.hand == 0; Hands.Left -> n.hand == 1 }

    // ── lifecycle ────────────────────────────────────────────────────────────

    fun open(s: Song) {
        close()
        scope.launch {
            loading = true
            raw = library.loadNotes(s).let { if (s.fromMidi) it else Arrange.clean(it) }
            song = s
            arrangement = if (s.fromMidi) Arrangement.Full else Arrangement.Melody
            notes = Arrange.build(raw, arrangement)
            hitFlags = BooleanArray(notes.size)
            listen = Listen.Piano
            audioLoaded = false
            if (state.beatPlaying) state.stopBeat()
            if (state.userTrackPlaying) state.playUserTrack(false)
            NativeEngine.previewProgram(state.instrument.bank, state.instrument.program)
            seek(0f)
            hits = 0; misses = 0; finished = false
            loading = false
        }
    }

    fun close() {
        if (song == null) return
        pause()
        NativeEngine.previewStop()
        if (audioLoaded) { NativeEngine.clearUserTrack(); audioLoaded = false }
        NativeEngine.setUserTrackLoop(state.userLoop)
        song = null
        notes = emptyList()
        focusStart = -1
        guide.fill(0f)
        version++
    }

    fun changeArrangement(a: Arrangement) {
        if (a == arrangement) return
        arrangement = a
        notes = Arrange.build(raw, a)
        hitFlags = BooleanArray(notes.size)
        seek(position)
    }

    fun changeListen(l: Listen) {
        val s = song ?: return
        if (l == Listen.Original && !s.hasAudio) return
        val wasPlaying = playing
        pause()
        listen = l
        if (l == Listen.Original) {
            speed = 1f
            if (!audioLoaded) {
                scope.launch {
                    loading = true
                    NativeEngine.collectGarbage()
                    library.loadAudioIntoEngine(s)
                    kotlinx.coroutines.delay(80)
                    NativeEngine.setUserTrackLoop(false)
                    audioLoaded = true
                    state.userTrackId = null
                    loading = false
                    if (wasPlaying) play()
                }
                return
            }
        }
        if (wasPlaying) play()
    }

    fun play() {
        if (song == null || loading) return
        if (finished) restart()
        playing = true
        if (listen == Listen.Original && audioLoaded && !waiting) {
            NativeEngine.seekUserTrack((position / max(0.1f, duration)).coerceIn(0f, 1f))
            NativeEngine.setUserTrackPlaying(true)
        }
    }

    fun pause() {
        playing = false
        if (audioLoaded) NativeEngine.setUserTrackPlaying(false)
        releaseSounding()
    }

    fun toggle() = if (playing) pause() else play()

    fun restart() {
        hits = 0; misses = 0; finished = false
        hitFlags = BooleanArray(notes.size)
        seek(0f)
    }

    fun seek(sec: Float) {
        position = sec.coerceIn(0f, duration)
        releaseSounding()
        waiting = false
        satisfied.clear()
        soundIdx = notes.indexOfFirst { it.start >= position }.let { if (it < 0) notes.size else it }
        chordIdx = soundIdx
        scoreIdx = soundIdx
        if (audioLoaded) NativeEngine.seekUserTrack((position / max(0.1f, duration)).coerceIn(0f, 1f))
        updateFocus(force = true)
        version++
    }

    // ── input ────────────────────────────────────────────────────────────────

    fun onKey(note: Int, down: Boolean) {
        if (song == null) return
        if (!down) { held.remove(note); return }
        held.add(note)
        if (waiting) {
            if (currentChord().any { it.pitch == note }) satisfied.add(note) else misses++
            return
        }
        if (!waitMode && playing) {
            // Watch mode: credit the nearest unplayed note of this pitch
            var best = -1; var bestD = 0.3f
            for (i in max(0, scoreIdx - 20) until min(notes.size, scoreIdx + 40)) {
                val n = notes[i]
                if (n.pitch != note || hitFlags[i] || !isMine(n)) continue
                val d = abs(n.start - position)
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0) { hitFlags[best] = true; hits++ }
        }
    }

    // ── per frame ────────────────────────────────────────────────────────────

    private fun currentChord(): List<LearnNote> {
        var i = chordIdx
        while (i < notes.size && !isMine(notes[i])) i++
        if (i >= notes.size) return emptyList()
        val t0 = notes[i].start
        val out = ArrayList<LearnNote>()
        var j = i
        while (j < notes.size && notes[j].start - t0 <= 0.06f) { if (isMine(notes[j])) out += notes[j]; j++ }
        return out
    }

    private fun advanceChord() {
        val chord = currentChord()
        if (chord.isEmpty()) { chordIdx = notes.size; return }
        val last = chord.last()
        chordIdx = notes.indexOf(last) + 1
        for (n in chord) { val i = notes.indexOf(n); if (i >= 0 && !hitFlags[i]) { hitFlags[i] = true; hits++ } }
    }

    fun tick(dt: Float) {
        if (song == null) return
        if (playing && !waiting) {
            position = if (listen == Listen.Original && audioLoaded) NativeEngine.getUserTrackPosition() * duration
            else position + dt * speed
        }

        if (playing && waitMode) {
            val chord = currentChord()
            if (chord.isNotEmpty() && position >= chord.first().start) {
                if (!waiting) {
                    waiting = true
                    position = chord.first().start
                    if (audioLoaded) NativeEngine.setUserTrackPlaying(false)
                    satisfied.clear()
                    chord.forEach { if (it.pitch in held) satisfied.add(it.pitch) }
                }
                if (chord.all { it.pitch in satisfied }) {
                    advanceChord()
                    waiting = false
                    satisfied.clear()
                    if (listen == Listen.Original && audioLoaded) NativeEngine.setUserTrackPlaying(true)
                }
            }
        }

        // guide playback (Wait mode plays only the hand you're not practising)
        if (playing && listen == Listen.Piano) {
            while (soundIdx < notes.size && notes[soundIdx].start <= position) {
                val n = notes[soundIdx]
                val mine = isMine(n)
                if (!waitMode || !mine) {
                    NativeEngine.previewNote(n.pitch, (n.velocity * if (mine) 0.75f else 0.6f).coerceIn(0.15f, 1f))
                    sounding += n
                }
                soundIdx++
            }
        } else if (playing) {
            while (soundIdx < notes.size && notes[soundIdx].start <= position) soundIdx++
        }
        val it = sounding.iterator()
        while (it.hasNext()) { val n = it.next(); if (n.end <= position) { NativeEngine.previewNote(n.pitch, 0f); it.remove() } }

        // Watch-mode misses: notes that slid past without being played
        if (playing && !waitMode) {
            while (scoreIdx < notes.size && notes[scoreIdx].start < position - 0.35f) {
                if (isMine(notes[scoreIdx]) && !hitFlags[scoreIdx]) misses++
                scoreIdx++
            }
        }

        // key guides
        guide.fill(0f)
        val look = 0.9f
        for (n in notes) {
            if (n.start > position + look) break
            if (n.end < position || !isMine(n)) continue
            val g = if (position >= n.start) 1f else 1f - (n.start - position) / look
            if (g > guide[n.pitch]) { guide[n.pitch] = g; guideHand[n.pitch] = n.hand }
        }

        updateFocus(force = false)

        if (position >= duration + 0.3f || (notes.isNotEmpty() && position > notes.last().end + 1.5f && !(listen == Listen.Original && audioLoaded))) {
            if (playing) { pause(); finished = true }
        }
        version++
    }

    private fun releaseSounding() {
        sounding.forEach { NativeEngine.previewNote(it.pitch, 0f) }
        sounding.clear()
    }

    /** Keep the next few seconds of notes on screen. */
    private fun updateFocus(force: Boolean) {
        val visible = state.keysOnScreen.coerceIn(7, PianoLayout.whiteCount)
        var lo = 127; var hi = 0
        for (n in notes) {
            if (n.start > position + 4f) break
            if (n.end < position || !isMine(n)) continue
            lo = min(lo, n.pitch); hi = max(hi, n.pitch)
        }
        if (lo > hi) {
            if (!force || notes.isEmpty()) return
            val r = Arrange.range(notes); lo = r.first; hi = r.last
        }
        val lw = PianoLayout.whiteIndexOf(lo)
        val hw = PianoLayout.whiteIndexOf(hi)
        val cur = focusStart
        if (!force && cur >= 0 && lw >= cur && hw <= cur + visible - 1) return
        val target = if (hw - lw + 3 <= visible) (lw + hw) / 2 - visible / 2 else lw - 1
        focusStart = target.coerceIn(0, PianoLayout.whiteCount - visible)
    }
}
