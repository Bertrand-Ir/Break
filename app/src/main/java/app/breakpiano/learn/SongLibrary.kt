package app.breakpiano.learn

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import app.breakpiano.audio.AudioDecoder
import app.breakpiano.audio.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max

data class Song(
    val id: String,
    val title: String,
    val durationMs: Long,
    val noteCount: Int,
    val fromMidi: Boolean,
    val sampleRate: Int = 0,
    val frames: Int = 0,
    /** "file", "midi", "listen" (heard through the mic) or "voice" (sung / hummed). */
    val source: String = if (fromMidi) "midi" else "file",
    /** Note-detection version; sung songs at 2+ use the dedicated voice tracker. */
    val notesVersion: Int = 1,
) {
    val hasAudio get() = frames > 0
    val isVoice get() = source == "voice"
    val sourceLabel get() = when (source) { "midi" -> "MIDI"; "listen" -> "Listened"; "voice" -> "Sung"; else -> "Transcribed" }
}

/** Songs you learn: transcribed from audio (keeps the audio for play-along) or imported MIDI. */
class SongLibrary(context: Context) {
    companion object { const val CURRENT_NV = 4 }
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "songs").apply { mkdirs() }
    private val index = File(dir, "index.json")
    val songs = mutableStateListOf<Song>()

    init {
        runCatching {
            if (index.exists()) {
                val arr = JSONArray(index.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val s = Song(o.getString("id"), o.getString("title"), o.getLong("dur"), o.getInt("notes"),
                        o.optBoolean("midi"), o.optInt("rate"), o.optInt("frames"),
                        o.optString("source", if (o.optBoolean("midi")) "midi" else "file"), o.optInt("nv", 1))
                    if (notesFile(s.id).exists()) songs += s
                }
            }
        }
    }

    private fun notesFile(id: String) = File(dir, "$id.notes.json")
    fun pcmFile(id: String) = File(dir, "$id.pcm")

    private fun save() {
        val arr = JSONArray()
        songs.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("title", s.title).put("dur", s.durationMs).put("notes", s.noteCount)
                .put("midi", s.fromMidi).put("rate", s.sampleRate).put("frames", s.frames).put("source", s.source).put("nv", s.notesVersion))
        }
        index.writeText(arr.toString())
    }

    private fun writeNotes(id: String, notes: List<SongNote>) {
        val arr = JSONArray()
        notes.forEach { n -> arr.put(JSONArray().put(n.start.toDouble()).put(n.end.toDouble()).put(n.pitch).put(n.velocity.toDouble())) }
        notesFile(id).writeText(arr.toString())
    }

    suspend fun loadNotes(song: Song): List<SongNote> = withContext(Dispatchers.IO) {
        val arr = JSONArray(notesFile(song.id).readText())
        List(arr.length()) { i ->
            val a = arr.getJSONArray(i)
            SongNote(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getInt(2), a.getDouble(3).toFloat())
        }
    }

    /** stage callback: (label, progress 0..1) */
    suspend fun importAudio(uri: Uri, onStage: (String, Float) -> Unit): Song = withContext(Dispatchers.Default) {
        val id = UUID.randomUUID().toString().take(12)
        val pcm = pcmFile(id)
        try {
            onStage("Reading the song…", 0f)
            val r = AudioDecoder.decode(app, uri, pcm, maxSeconds = 600) { onStage("Reading the song…", it * 0.2f) }
            val notes = Transcriber(app).transcribe(pcm, r.sampleRate) { p ->
                onStage(if (p < 0.15f) "Preparing audio…" else if (p < 0.9f) "Listening for notes…" else "Writing the keys…", 0.2f + p * 0.8f)
            }
            require(notes.isNotEmpty()) { "Couldn't hear any clear notes in that recording." }
            writeNotes(id, notes)
            val song = Song(id, AudioDecoder.displayName(app, uri, "My song"), r.frames * 1000L / r.sampleRate,
                notes.size, fromMidi = false, sampleRate = r.sampleRate, frames = r.frames)
            withContext(Dispatchers.Main) { songs.add(0, song); save() }
            song
        } catch (t: Throwable) {
            pcm.delete(); notesFile(id).delete()
            throw t
        }
    }

    /** A fresh id + PCM file for a microphone take. */
    fun newRecording(): Pair<String, File> = UUID.randomUUID().toString().take(12).let { it to pcmFile(it) }

    /** Transcribes a microphone take (a song playing nearby, or your voice). */
    suspend fun importRecording(id: String, take: MicListener.Take, voice: Boolean, onStage: (String, Float) -> Unit): Song =
        withContext(Dispatchers.Default) {
            try {
                require(take.frames > take.sampleRate) { "That was too short — record at least a couple of seconds." }
                val notes = detect(take.file, take.sampleRate, if (voice) "voice" else "listen", onStage)
                require(notes.isNotEmpty()) { if (voice) "Couldn't hear a clear tune — try humming a bit louder, closer to the phone." else "Couldn't hear clear notes — turn the music up or move closer." }
                writeNotes(id, notes)
                val stamp = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                val song = Song(id, (if (voice) "Sung melody · " else "Listened · ") + stamp, take.frames * 1000L / take.sampleRate,
                    notes.size, fromMidi = false, sampleRate = take.sampleRate, frames = take.frames, source = if (voice) "voice" else "listen",
                    notesVersion = CURRENT_NV)
                withContext(Dispatchers.Main) { songs.add(0, song); save() }
                song
            } catch (t: Throwable) {
                take.file.delete(); notesFile(id).delete()
                throw t
            }
        }

    /** Picks the right detector for where the audio came from. */
    private fun detect(pcm: File, rate: Int, source: String, onStage: (String, Float) -> Unit): List<SongNote> =
        if (source == "voice") {
            onStage("Following your voice…", 0.3f)
            VoiceTranscriber.transcribe(pcm, rate).also { onStage("Writing the keys…", 1f) }
        } else {
            Transcriber(app).transcribe(pcm, rate, micTake = source == "listen") { p ->
                onStage(if (p < 0.15f) "Preparing audio…" else if (p < 0.9f) "Listening for notes…" else "Writing the keys…", p)
            }
        }

    /** Runs note detection again on a song's saved recording (e.g. after detection improves). */
    suspend fun redetect(song: Song, onStage: (String, Float) -> Unit = { _, _ -> }): Song = withContext(Dispatchers.Default) {
        require(song.hasAudio) { "This song has no recording to listen to again." }
        val notes = detect(pcmFile(song.id), song.sampleRate, song.source, onStage)
        require(notes.isNotEmpty()) { "Couldn't hear clear notes in that recording." }
        writeNotes(song.id, notes)
        val updated = song.copy(noteCount = notes.size, notesVersion = CURRENT_NV)
        withContext(Dispatchers.Main) {
            val i = songs.indexOfFirst { it.id == song.id }
            if (i >= 0) { songs[i] = updated; save() }
        }
        updated
    }

    /** Older sung songs were detected with the general model — redo them with the voice tracker. */
    suspend fun upgradeVoiceSongs() {
        for (s in songs.toList()) if (s.isVoice && s.hasAudio && s.notesVersion < CURRENT_NV) runCatching { redetect(s) }
    }

    fun rename(song: Song, title: String) {
        val i = songs.indexOfFirst { it.id == song.id }
        if (i >= 0 && title.isNotBlank()) { songs[i] = song.copy(title = title.trim()); save() }
    }

    suspend fun importMidi(uri: Uri): Song = withContext(Dispatchers.IO) {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open that file.")
        val notes = MidiFile.parse(bytes)
        val id = UUID.randomUUID().toString().take(12)
        writeNotes(id, notes)
        val song = Song(id, AudioDecoder.displayName(app, uri, "MIDI song"), (notes.maxOf { it.end } * 1000).toLong(),
            notes.size, fromMidi = true)
        withContext(Dispatchers.Main) { songs.add(0, song); save() }
        song
    }

    fun delete(song: Song) {
        pcmFile(song.id).delete(); notesFile(song.id).delete()
        songs.removeAll { it.id == song.id }
        save()
    }

    /** Streams the song's original audio into the engine's track player. */
    suspend fun loadAudioIntoEngine(song: Song) = withContext(Dispatchers.IO) {
        NativeEngine.beginUserTrack(song.frames, song.sampleRate)
        val chunk = 16384
        val bytes = ByteArray(chunk * 4)
        val shorts = ShortArray(chunk * 2)
        FileInputStream(pcmFile(song.id)).use { input ->
            while (true) {
                var read = 0
                while (read < bytes.size) { val r = input.read(bytes, read, bytes.size - read); if (r < 0) break; read += r }
                if (read <= 0) break
                val frames = read / 4
                ByteBuffer.wrap(bytes, 0, frames * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, frames * 2)
                NativeEngine.appendUserTrack(shorts, frames)
                if (read < bytes.size) break
            }
        }
        NativeEngine.commitUserTrack(0.6f)
    }

}
