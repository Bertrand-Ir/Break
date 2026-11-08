package app.breakpiano.audio

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Take(val file: File) {
    val name: String get() = file.nameWithoutExtension.replace('_', ' ')
    val durationMs: Long by lazy {
        runCatching {
            RandomAccessFile(file, "r").use { r ->
                val b = ByteArray(4); r.seek(24); r.readFully(b)
                val rate = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.coerceAtLeast(8000)
                ((file.length() - 44).coerceAtLeast(0) / 4) * 1000L / rate
            }
        }.getOrDefault(0L)
    }
}

/** Records the final master mix (keys + beats + metronome) straight to a 16-bit WAV. */
class Recorder(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }
    val takes = mutableStateListOf<Take>()
    var isRecording by mutableStateOf(false); private set
    var elapsedMs by mutableLongStateOf(0L); private set
    var playingTake by mutableStateOf<File?>(null); private set
    var playProgress by mutableStateOf(0f); private set

    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var progressJob: Job? = null

    init { refresh() }

    fun refresh() {
        takes.clear()
        takes += dir.listFiles { f -> f.extension == "wav" }.orEmpty().sortedByDescending { it.lastModified() }.map { Take(it) }
    }

    fun toggle() = if (isRecording) stop() else start()

    fun start() {
        if (isRecording) return
        val buf = ShortArray(16384)
        while (NativeEngine.readRecording(buf) > 0) { /* drain stale audio */ }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US).format(Date())
        val file = File(dir, "Break_$stamp.wav")
        val rate = NativeEngine.getSampleRate()
        isRecording = true
        elapsedMs = 0
        NativeEngine.setRecording(true)
        job = scope.launch(Dispatchers.IO) {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                val bytes = ByteArray(buf.size * 2)
                var dataBytes = 0L
                val started = System.currentTimeMillis()
                fun drain(): Int {
                    val n = NativeEngine.readRecording(buf)
                    if (n > 0) {
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, 0, n)
                        raf.write(bytes, 0, n * 2)
                        dataBytes += n * 2
                    }
                    return n
                }
                while (isActive && isRecording) {
                    while (drain() > 0) { }
                    elapsedMs = System.currentTimeMillis() - started
                    delay(30)
                }
                while (drain() > 0) { }
                writeWavHeader(raf, rate, dataBytes)
            }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun stop() {
        if (!isRecording) return
        NativeEngine.setRecording(false)
        isRecording = false
    }

    private fun writeWavHeader(raf: RandomAccessFile, rate: Int, dataBytes: Long) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2)
        h.putInt(rate); h.putInt(rate * 4); h.putShort(4); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
        raf.seek(0); raf.write(h.array())
    }

    fun play(take: Take) {
        stopPlayback()
        player = MediaPlayer().apply {
            setDataSource(take.file.absolutePath)
            setOnCompletionListener { stopPlayback() }
            prepare(); start()
        }
        playingTake = take.file
        progressJob = scope.launch {
            while (isActive) {
                player?.let { if (it.duration > 0) playProgress = it.currentPosition / it.duration.toFloat() }
                delay(50)
            }
        }
    }

    fun stopPlayback() {
        progressJob?.cancel()
        runCatching { player?.stop() }
        player?.release()
        player = null
        playingTake = null
        playProgress = 0f
    }

    fun delete(take: Take) {
        if (playingTake == take.file) stopPlayback()
        take.file.delete()
        refresh()
    }

    fun rename(take: Take, newName: String) {
        val clean = newName.trim().replace(Regex("[^A-Za-z0-9 _.-]"), "").replace(' ', '_')
        if (clean.isBlank()) return
        take.file.renameTo(File(dir, "$clean.wav"))
        refresh()
    }
}
