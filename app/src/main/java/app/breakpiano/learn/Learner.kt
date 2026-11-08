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

enum class Grade(val label: String) { Perfect("Perfect!"), Great("Great"), Good("Good"), Miss("Miss"), Wrong("Oops") }

/** A moment of feedback to draw: a hit sparkle, a miss, a wrong key. */
class LearnFx(val pitch: Int, val at: Long, val grade: Grade, val hand: Int)

enum class Hands(val title: String) { Both("Both"), Right("Right"), Left("Left") }
enum class Listen(val title: String) { Piano("Piano"), Original("Original"), Off("Silent") }

/**
 * Learning mode: plays a song as falling notes, lights up the keys to press, plays a guide
 * (or the original recording) and — in Wait mode — holds on each note until you play it.
 */
private const val LEAD = 2.5f   // seconds of count-in before the first note

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
    /** Keys the song is playing by itself right now — drawn pressed, like a self-playing piano. */
    val autoDown = BooleanArray(128)
    /** When each key was last hit correctly / wrongly (uptime ms), for the green / red flashes. */
    val hitAt = LongArray(128)
    val hitHand = IntArray(128)
    val wrongAt = LongArray(128)
    /** Recent feedback events (newest last). */
    val fx = ArrayDeque<LearnFx>()
    var streak by mutableIntStateOf(0); private set
    var bestStreak by mutableIntStateOf(0); private set
    var lastGrade by mutableStateOf<Grade?>(null); private set
    var lastGradeAt = 0L; private set
    val stars: Int get() = when { accuracy >= 95 -> 3; accuracy >= 80 -> 2; accuracy >= 50 -> 1; else -> 0 }
    /** Notes that are next in line (the ones to look at). */
    val nextPitches = HashSet<Int>()
    private var audioStarted = false

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

    /** A sung tune is one line for one hand, whatever its range. */
    private fun build(r: List<SongNote>, a: Arrangement): List<LearnNote> {
        val b = Arrange.build(r, a)
        return if (song?.isVoice == true) b.map { it.copy(hand = 0) } else b
    }

    private fun isMine(n: LearnNote) = when (hands) { Hands.Both -> true; Hands.Right -> n.hand == 0; Hands.Left -> n.hand == 1 }

    // ── lifecycle ────────────────────────────────────────────────────────────

    fun open(s: Song) {
        close()
        scope.launch {
            loading = true
            raw = library.loadNotes(s).let {
                when {
                    s.fromMidi -> it
                    s.isVoice && s.notesVersion >= 2 -> it          // voice tracker output is already clean
                    else -> Arrange.clean(it, voice = s.isVoice)
                }
            }
            song = s
            arrangement = if (s.fromMidi || s.isVoice) Arrangement.Full else Arrangement.Melody
            notes = build(raw, arrangement)
            hitFlags = BooleanArray(notes.size)
            listen = Listen.Piano
            audioLoaded = false
            if (state.beatPlaying) state.stopBeat()
            if (state.userTrackPlaying) state.playUserTrack(false)
            NativeEngine.previewProgram(state.instrument.bank, state.instrument.program)
            hits = 0; misses = 0; finished = false
            seek(-LEAD)
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
        notes = build(raw, a)
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
        if (listen == Listen.Original && audioLoaded && !waiting && position >= 0f) {
            audioStarted = true
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
        streak = 0; bestStreak = 0; lastGrade = null
        fx.clear()
        hitFlags = BooleanArray(notes.size)
        seek(-LEAD)
    }

    fun seek(sec: Float) {
        position = sec.coerceIn(-LEAD, duration)
        audioStarted = false
        releaseSounding()
        waiting = false
        satisfied.clear()
        soundIdx = notes.indexOfFirst { it.start >= position }.let { if (it < 0) notes.size else it }
        chordIdx = soundIdx
        scoreIdx = soundIdx
        if (audioLoaded) NativeEngine.seekUserTrack((max(0f, position) / max(0.1f, duration)).coerceIn(0f, 1f))
        if (audioLoaded && !playing) NativeEngine.setUserTrackPlaying(false)
        updateFocus(force = true)
        version++
    }

    // ── input ────────────────────────────────────────────────────────────────

    private fun now() = android.os.SystemClock.uptimeMillis()

    private fun emit(pitch: Int, grade: Grade, hand: Int) {
        val t = now()
        fx.addLast(LearnFx(pitch, t, grade, hand))
        while (fx.size > 32) fx.removeFirst()
        lastGrade = grade; lastGradeAt = t
    }

    private fun good(pitch: Int, hand: Int, grade: Grade) {
        hitAt[pitch] = now(); hitHand[pitch] = hand
        streak++
        if (streak > bestStreak) bestStreak = streak
        emit(pitch, grade, hand)
    }

    private fun wrong(pitch: Int) {
        wrongAt[pitch] = now()
        misses++
        streak = 0
        emit(pitch, Grade.Wrong, 0)
    }

    fun onKey(note: Int, down: Boolean) {
        if (song == null) return
        if (!down) { held.remove(note); return }
        held.add(note)
        if (waiting) {
            val target = currentChord().firstOrNull { it.pitch == note }
            if (target != null) { satisfied.add(note); hitAt[note] = now(); hitHand[note] = target.hand }
            else wrong(note)
            return
        }
        if (!playing || position < 0f) return          // free play while paused / counting in
        if (waitMode) {
            // pressing ahead of the next note in Wait mode
            val chord = currentChord()
            val target = chord.firstOrNull { it.pitch == note }
            if (target != null) { satisfied.add(note); hitAt[note] = now(); hitHand[note] = target.hand }
            else wrong(note)
            return
        }
        // Watch mode: credit the nearest unplayed note of this pitch
        var best = -1; var bestD = 0.3f
        for (i in max(0, scoreIdx - 20) until min(notes.size, scoreIdx + 40)) {
            val n = notes[i]
            if (n.pitch != note || hitFlags[i] || !isMine(n)) continue
            val d = abs(n.start - position)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best >= 0) {
            hitFlags[best] = true; hits++
            good(note, notes[best].hand, if (bestD < 0.07f) Grade.Perfect else if (bestD < 0.16f) Grade.Great else Grade.Good)
        } else wrong(note)
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
        var first = true
        for (n in chord) {
            val i = notes.indexOf(n)
            if (i >= 0 && !hitFlags[i]) { hitFlags[i] = true; hits++ }
            if (first) { good(n.pitch, n.hand, Grade.Perfect); first = false } else { hitAt[n.pitch] = now(); hitHand[n.pitch] = n.hand }
        }
    }

    fun tick(dt: Float) {
        if (song == null) return
        if (playing && !waiting) {
            val original = listen == Listen.Original && audioLoaded
            if (original && audioStarted) position = NativeEngine.getUserTrackPosition() * duration
            else {
                position += dt * speed
                if (original && position >= 0f) {
                    audioStarted = true
                    NativeEngine.seekUserTrack((position / max(0.1f, duration)).coerceIn(0f, 1f))
                    NativeEngine.setUserTrackPlaying(true)
                }
            }
        }

        if (playing && waitMode) {
            val chord = currentChord()
            if (chord.isNotEmpty() && position >= chord.first().start) {
                if (!waiting) {
                    waiting = true
                    position = chord.first().start
                    if (audioLoaded) NativeEngine.setUserTrackPlaying(false)
                    val wanted = chord.map { it.pitch }.toSet()
                    satisfied.retainAll(wanted)                   // keep keys pressed a moment early
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
                if (isMine(notes[scoreIdx]) && !hitFlags[scoreIdx]) { misses++; streak = 0; emit(notes[scoreIdx].pitch, Grade.Miss, notes[scoreIdx].hand) }
                scoreIdx++
            }
        }

        // keys the song plays by itself: everything in Watch mode, the other hand in Wait mode
        autoDown.fill(false)
        for (n in sounding) if (!waitMode || !isMine(n)) autoDown[n.pitch] = true
        if (listen != Listen.Piano && playing) for (n in notes) {
            if (n.start > position) break
            if (n.end > position && (!waitMode || !isMine(n))) autoDown[n.pitch] = true
        }
        // what to look at next
        nextPitches.clear()
        if (waitMode) currentChord().forEach { nextPitches += it.pitch }
        else for (n in notes) {
            if (n.start > position + 0.7f) break
            if (n.start >= position - 0.05f && isMine(n)) nextPitches += n.pitch
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
