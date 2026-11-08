package app.breakpiano.learn

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Records the microphone into Break's PCM format (16-bit LE stereo) so a song playing nearby —
 * or you singing / humming — can be turned into piano notes.
 */
class MicListener(context: Context) {
    private val app = context.applicationContext
    var recording by mutableStateOf(false); private set
    var elapsedMs by mutableLongStateOf(0L); private set
    /** Recent input levels 0..1 for the live meter (newest last). */
    val levels = mutableStateListOf<Float>()
    @Volatile private var stopRequested = false

    class Take(val file: File, val sampleRate: Int, val frames: Int)

    fun stop() { stopRequested = true }

    /**
     * Records until [stop] (or [maxSeconds]). [forMusic] asks for the rawest input the phone
     * offers so instruments aren't mangled by voice processing.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(out: File, forMusic: Boolean, maxSeconds: Int = 600): Take = withContext(Dispatchers.IO) {
        val rate = 44100
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val am = app.getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = when {
            forMusic && unprocessed -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION   // minimal processing, no AGC on most phones
        }
        val rec = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate / 5 * 2))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "The microphone is busy. Close other apps using it and try again." }

        val chunk = ShortArray(rate / 25)               // 40 ms
        val bytes = ByteArray(chunk.size * 4)
        var frames = 0L
        stopRequested = false
        withContext(Dispatchers.Main) { recording = true; elapsedMs = 0; levels.clear() }
        try {
            rec.startRecording()
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
                while (!stopRequested && frames < maxSeconds.toLong() * rate) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    var sum = 0.0
                    for (i in 0 until n) {
                        val s = chunk[i]
                        bb.putShort(s); bb.putShort(s)        // mono → stereo
                        sum += s.toDouble() * s
                    }
                    stream.write(bytes, 0, n * 4)
                    frames += n
                    val rms = sqrt(sum / n) / 32768.0
                    val level = ((20 * log10(rms + 1e-6) + 60) / 60).toFloat().coerceIn(0f, 1f)
                    val ms = frames * 1000 / rate
                    withContext(Dispatchers.Main) {
                        elapsedMs = ms
                        levels.add(level)
                        while (levels.size > 64) levels.removeAt(0)
                    }
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            withContext(Dispatchers.Main) { recording = false }
        }
        Take(out, rate, frames.toInt())
    }
}
