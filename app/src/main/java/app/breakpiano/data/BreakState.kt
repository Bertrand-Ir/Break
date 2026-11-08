package app.breakpiano.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.breakpiano.audio.BeatCompiler
import app.breakpiano.audio.Beats
import app.breakpiano.audio.Instrument
import app.breakpiano.audio.Instruments
import app.breakpiano.audio.NativeEngine
import kotlin.math.log2
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class LabelMode(val title: String) { Off("Off"), C("C only"), White("White keys"), All("All keys") }
enum class Notation(val title: String) { Letters("C D E"), Solfege("Do Ré Mi") }
enum class Panel { None, Instruments, Beats, Metronome, Recordings, Settings }

/**
 * Single source of truth for the whole app. Every setting is Compose-observable, persisted to
 * SharedPreferences, and pushed to the native engine the moment it changes.
 */
class BreakState(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("break", Context.MODE_PRIVATE)

    private inner class Pref<T>(
        private val key: String,
        default: T,
        private val read: SharedPreferences.(String, T) -> T,
        private val write: SharedPreferences.Editor.(String, T) -> Unit,
        private val onChange: (T) -> Unit,
    ) : ReadWriteProperty<Any?, T> {
        private val state: MutableState<T> = mutableStateOf(sp.read(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            if (state.value == value) return
            state.value = value
            sp.edit().apply { write(key, value) }.apply()
            if (engineReady) onChange(value)
        }
    }

    private fun bool(k: String, d: Boolean, on: (Boolean) -> Unit = {}) =
        Pref(k, d, { a, b -> getBoolean(a, b) }, { a, b -> putBoolean(a, b) }, on)
    private fun int(k: String, d: Int, on: (Int) -> Unit = {}) =
        Pref(k, d, { a, b -> getInt(a, b) }, { a, b -> putInt(a, b) }, on)
    private fun float(k: String, d: Float, on: (Float) -> Unit = {}) =
        Pref(k, d, { a, b -> getFloat(a, b) }, { a, b -> putFloat(a, b) }, on)
    private fun str(k: String, d: String, on: (String) -> Unit = {}) =
        Pref(k, d, { a, b -> getString(a, b) ?: b }, { a, b -> putString(a, b) }, on)

    var engineReady = false; private set

    // ── Keyboard ──
    var keysOnScreen: Int by int("keysOnScreen", 15)
    var dualKeyboard: Boolean by bool("dualKeyboard", false)
    var labelModeName: String by str("labelMode", LabelMode.C.name)
    var notationName: String by str("notation", Notation.Letters.name)
    var showOctave: Boolean by bool("showOctave", true)
    var useFlats: Boolean by bool("useFlats", false)
    var upperStart: Int by int("upperStart", 23)   // first visible white key (index from A0) — C4 region
    var lowerStart: Int by int("lowerStart", 16)   // C3 region

    var labelMode: LabelMode
        get() = runCatching { LabelMode.valueOf(labelModeName) }.getOrDefault(LabelMode.C)
        set(v) { labelModeName = v.name }
    var notation: Notation
        get() = runCatching { Notation.valueOf(notationName) }.getOrDefault(Notation.Letters)
        set(v) { notationName = v.name }

    // ── Touch ──
    var dynamicVelocity: Boolean by bool("dynamicVelocity", true)
    var fixedVelocity: Float by float("fixedVelocity", 0.82f)
    var haptics: Boolean by bool("haptics", false)
    var glissando: Boolean by bool("glissando", true)
    var keyGlow: Boolean by bool("keyGlow", true)

    // ── Sound ──
    var instrumentId: String by str("instrument", Instruments.default.id) { applyInstrument() }
    var layerId: String by str("layer", "") { applyLayer() }
    var layerMix: Float by float("layerMix", 0.55f) { NativeEngine.setLayerMix(it) }
    var reverb: Float by float("reverb", 0.24f) { NativeEngine.setReverb(it) }
    var masterVol: Float by float("masterVol", 1f) { applyVolumes() }
    var keysVol: Float by float("keysVol", 0.9f) { applyVolumes() }
    var beatVol: Float by float("beatVol", 0.8f) { applyVolumes() }
    var userVol: Float by float("userVol", 0.85f) { applyVolumes() }
    var metroVol: Float by float("metroVol", 0.7f) { applyVolumes() }
    var smartMix: Boolean by bool("smartMix", true) { NativeEngine.setSmartMix(it) }
    var tuningA4: Int by int("tuningA4", 440) { applyTuning() }
    var transpose: Int by int("transpose", 0) { NativeEngine.setTranspose(it) }

    // ── Transport ──
    var bpm: Int by int("bpm", 90) { NativeEngine.setTempo(it.toFloat()) }
    var tsNum: Int by int("tsNum", 4) { NativeEngine.setTimeSignature(it, tsDen) }
    var tsDen: Int by int("tsDen", 4) { NativeEngine.setTimeSignature(tsNum, it) }
    var metroSound: Int by int("metroSound", 0) { NativeEngine.setMetronome(metronomeOn, it) }
    var countIn: Boolean by bool("countIn", true)
    var beatStyleId: String by str("beatStyle", "afrobeats")
    var beatVariation: Int by int("beatVariation", 0)
    var beatKey: Int by int("beatKey", 0) { NativeEngine.setBeatKey(it) }
    var bassOn: Boolean by bool("bassOn", true) { NativeEngine.setBassEnabled(it) }
    var userLoop: Boolean by bool("userLoop", true) { NativeEngine.setUserTrackLoop(it) }
    var loopVol: Float by float("loopVol", 0.9f) { NativeEngine.setLoopVolume(it) }

    // ── Updates ──
    var autoUpdate: Boolean by bool("autoUpdate", true)

    // ── Runtime (not persisted) ──
    var sustain by mutableStateOf(false)
    var metronomeOn by mutableStateOf(false)
    var beatPlaying by mutableStateOf(false)
    var beatStatus by mutableIntStateOf(-1)
    var userTrackId by mutableStateOf<String?>(null)
    var userTrackPlaying by mutableStateOf(false)
    var userTrackPos by mutableFloatStateOf(0f)
    var userTrackLoading by mutableStateOf<Float?>(null)
    var panel by mutableStateOf(Panel.None)
    var latencyMs by mutableFloatStateOf(-1f)
    var toast by mutableStateOf<String?>(null)
    var beatsTab by mutableIntStateOf(0)            // 0 styles · 1 my beats · 2 mix
    var loopState by mutableIntStateOf(0)
    var loopPos by mutableFloatStateOf(0f)
    var loopLenSec by mutableFloatStateOf(0f)
    var loopRecSec by mutableFloatStateOf(0f)

    val instrument: Instrument get() = Instruments.byId(instrumentId) ?: Instruments.default
    val layer: Instrument? get() = Instruments.byId(layerId)

    // ─────────────────────────────────────────────────────────────────────────

    fun onEngineReady() {
        engineReady = true
        applyInstrument(); applyLayer(); applyVolumes(); applyTuning()
        NativeEngine.setLayerMix(layerMix)
        NativeEngine.setReverb(reverb)
        NativeEngine.setSmartMix(smartMix)
        NativeEngine.setTranspose(transpose)
        NativeEngine.setTempo(bpm.toFloat())
        NativeEngine.setTimeSignature(tsNum, tsDen)
        NativeEngine.setBeatKey(beatKey)
        NativeEngine.setBassEnabled(bassOn)
        NativeEngine.setUserTrackLoop(userLoop)
        NativeEngine.setSustain(sustain)
        NativeEngine.setLoopVolume(loopVol)
        loadPattern()
    }

    /** Called after the audio stream was torn down (app backgrounded). */
    fun onEngineStopped() {
        sustain = false
        metronomeOn = false
        beatPlaying = false
        userTrackPlaying = false
    }

    private fun applyInstrument() = instrument.let { NativeEngine.setInstrument(it.bank, it.program) }
    private fun applyLayer() = layer.let { if (it == null) NativeEngine.setLayer(-1, 0) else NativeEngine.setLayer(it.bank, it.program) }
    private fun applyVolumes() = NativeEngine.setVolumes(keysVol, beatVol, userVol, metroVol, masterVol)
    private fun applyTuning() = NativeEngine.setTuning((12.0 * log2(tuningA4 / 440.0)).toFloat())

    fun toggleSustain() {
        sustain = !sustain
        NativeEngine.setSustain(sustain)
    }

    fun toggleMetronome() {
        metronomeOn = !metronomeOn
        NativeEngine.setMetronome(metronomeOn, metroSound)
    }

    fun setTempo(v: Int) { bpm = v.coerceIn(30, 260) }

    private fun loadPattern() {
        val style = Beats.byId(beatStyleId) ?: Beats.styles.first()
        val p = BeatCompiler.compile(style, beatVariation)
        NativeEngine.setPattern(p.lengthTicks, style.kit, style.bassMode.coerceAtLeast(0), style.bassPreset,
            p.ticks, p.kinds, p.notes, p.vels, p.glides, p.lens)
    }

    fun selectBeat(id: String, adoptTempo: Boolean = true) {
        val style = Beats.byId(id) ?: return
        val changed = id != beatStyleId
        beatStyleId = id
        if (changed) beatVariation = 0
        if (adoptTempo && changed) setTempo(style.bpm)
        loadPattern()
        if (!beatPlaying) playBeat()
    }

    fun selectVariation(v: Int) {
        beatVariation = v
        loadPattern()
    }

    fun playBeat() {
        if (userTrackPlaying) playUserTrack(false)
        loadPattern()
        NativeEngine.playBeat(countIn)
        beatPlaying = true
    }

    fun stopBeat() {
        NativeEngine.stopBeat()
        beatPlaying = false
    }

    fun toggleBeat() = if (beatPlaying) stopBeat() else playBeat()

    fun playUserTrack(on: Boolean) {
        if (on && beatPlaying) stopBeat()
        NativeEngine.setUserTrackPlaying(on)
        userTrackPlaying = on
    }

    fun looper(action: Int) = NativeEngine.looperAction(action)

    // Tap tempo
    private val taps = ArrayDeque<Long>()
    fun tapTempo() {
        val now = System.nanoTime()
        if (taps.isNotEmpty() && now - taps.last() > 2_000_000_000L) taps.clear()
        taps.addLast(now)
        while (taps.size > 6) taps.removeFirst()
        if (taps.size >= 2) {
            val avg = (taps.last() - taps.first()) / (taps.size - 1).toDouble()
            setTempo((60_000_000_000.0 / avg).toInt())
        }
    }

    /** Polled every frame by the UI. */
    fun pollEngine() {
        beatStatus = NativeEngine.getBeatStatus()
        loopState = NativeEngine.getLooperState()
        if (loopState != 0) {
            loopPos = NativeEngine.getLooperPosition()
            loopLenSec = NativeEngine.getLooperLength()
            loopRecSec = NativeEngine.getLooperRecorded()
        }
        if (userTrackId != null) {
            userTrackPos = NativeEngine.getUserTrackPosition()
            val up = NativeEngine.isUserTrackPlaying()
            if (up != userTrackPlaying) userTrackPlaying = up
        }
    }
}
