package app.breakpiano.audio

import android.content.res.AssetManager

/**
 * Thin JNI binding to the C++ engine (Oboe + TinySoundFont). Every call is cheap and
 * non-blocking: note events go through a lock-free queue straight into the audio callback.
 */
object NativeEngine {
    init {
        System.loadLibrary("break")
    }

    const val SOUNDFONT = "GeneralUser-GS.sf2"

    external fun load(assets: AssetManager, asset: String): Boolean
    external fun start(): Boolean
    external fun stop()

    // keys
    external fun noteOn(key: Int, velocity: Float)
    external fun noteOff(key: Int)
    external fun allNotesOff()
    external fun panic()
    external fun setSustain(on: Boolean)
    external fun setInstrument(bank: Int, program: Int)
    /** bank = -1 disables the layer. */
    external fun setLayer(bank: Int, program: Int)
    external fun setLayerMix(mix: Float)
    external fun setTuning(semitones: Float)
    external fun setTranspose(semitones: Int)

    // mix
    external fun setVolumes(keys: Float, beat: Float, user: Float, metronome: Float, master: Float)
    external fun setReverb(amount: Float)
    external fun setSmartMix(on: Boolean)

    // transport
    external fun setTempo(bpm: Float)
    external fun setTimeSignature(num: Int, den: Int)
    external fun setMetronome(on: Boolean, sound: Int)
    external fun setPattern(
        lengthTicks: Int, kit: Int, bassMode: Int, bassPreset: Int,
        ticks: IntArray, kinds: ByteArray, notes: ByteArray, vels: ByteArray, glides: ByteArray, lens: IntArray,
    )
    external fun playBeat(countIn: Boolean)
    external fun stopBeat()
    external fun setBeatKey(key: Int)
    external fun setBassEnabled(on: Boolean)

    // imported beat
    external fun beginUserTrack(framesHint: Int, sampleRate: Int)
    external fun appendUserTrack(stereoPcm: ShortArray, frames: Int)
    external fun commitUserTrack(gain: Float)
    external fun clearUserTrack()
    external fun setUserTrackPlaying(on: Boolean)
    external fun setUserTrackLoop(on: Boolean)
    external fun seekUserTrack(fraction: Float)

    // recording
    external fun setRecording(on: Boolean)
    external fun readRecording(buffer: ShortArray): Int

    // status
    /** -1 when stopped, else bits: [16] counting-in, [8..15] beats per bar, [0..7] beat in bar. */
    external fun getBeatStatus(): Int
    external fun isBeatPlaying(): Boolean
    external fun isUserTrackPlaying(): Boolean
    external fun getUserTrackPosition(): Float
    external fun getLevel(): Float
    external fun getSampleRate(): Int
    external fun getLatencyMs(): Float
    external fun collectGarbage()
}
