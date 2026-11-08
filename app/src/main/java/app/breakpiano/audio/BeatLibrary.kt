package app.breakpiano.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

data class UserBeat(
    val id: String,
    val name: String,
    val sampleRate: Int,
    val frames: Int,
    /** Loudness-match gain computed at import (applied when Smart Mix is on). */
    val gain: Float,
) {
    val durationMs: Long get() = frames * 1000L / max(1, sampleRate)
}

/**
 * Imports any audio file Android can decode (mp3, m4a/aac, wav, ogg, flac…), decodes it once to
 * 16-bit stereo PCM on disk, and measures its loudness so it can be auto-levelled against the keys.
 */
class BeatLibrary(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "beats").apply { mkdirs() }
    private val index = File(dir, "index.json")

    val beats = mutableStateListOf<UserBeat>()

    init {
        runCatching {
            if (index.exists()) {
                val arr = JSONArray(index.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val b = UserBeat(o.getString("id"), o.getString("name"), o.getInt("rate"), o.getInt("frames"), o.getDouble("gain").toFloat())
                    if (pcmFile(b.id).exists()) beats += b
                }
            }
        }
    }

    private fun pcmFile(id: String) = File(dir, "$id.pcm")

    private fun save() {
        val arr = JSONArray()
        beats.forEach { b ->
            arr.put(JSONObject().put("id", b.id).put("name", b.name).put("rate", b.sampleRate).put("frames", b.frames).put("gain", b.gain.toDouble()))
        }
        index.writeText(arr.toString())
    }

    fun rename(beat: UserBeat, name: String) {
        val i = beats.indexOfFirst { it.id == beat.id }
        if (i >= 0 && name.isNotBlank()) { beats[i] = beat.copy(name = name.trim()); save() }
    }

    fun delete(beat: UserBeat) {
        pcmFile(beat.id).delete()
        beats.removeAll { it.id == beat.id }
        save()
    }

    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): UserBeat = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(12)
        val r = AudioDecoder.decode(app, uri, pcmFile(id), onProgress = onProgress)
        // Loudness match: mastered beats are usually far louder than a live instrument.
        // Bring the beat's RMS to ≈ -18 dBFS so keys and beat sit together.
        val gain = if (r.rms > 1e-5) (0.125 / r.rms).toFloat().coerceIn(0.2f, 2.5f) else 1f
        val beat = UserBeat(id, AudioDecoder.displayName(app, uri, "My beat"), r.sampleRate, r.frames, gain)
        withContext(Dispatchers.Main) { beats.add(0, beat); save() }
        beat
    }

    /** Streams the decoded PCM into the native engine. */
    suspend fun loadIntoEngine(beat: UserBeat, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val file = pcmFile(beat.id)
        NativeEngine.beginUserTrack(beat.frames, beat.sampleRate)
        val chunkFrames = 16384
        val bytes = ByteArray(chunkFrames * 4)
        val shorts = ShortArray(chunkFrames * 2)
        var done = 0L
        FileInputStream(file).use { input ->
            while (true) {
                var read = 0
                while (read < bytes.size) {
                    val r = input.read(bytes, read, bytes.size - read)
                    if (r < 0) break
                    read += r
                }
                if (read <= 0) break
                val frames = read / 4
                ByteBuffer.wrap(bytes, 0, frames * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, frames * 2)
                NativeEngine.appendUserTrack(shorts, frames)
                done += frames
                onProgress((done.toFloat() / max(1, beat.frames)).coerceIn(0f, 1f))
                if (read < bytes.size) break
            }
        }
        NativeEngine.commitUserTrack(beat.gain)
    }
}
