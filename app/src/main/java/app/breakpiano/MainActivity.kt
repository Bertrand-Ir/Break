package app.breakpiano

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.breakpiano.audio.BeatLibrary
import app.breakpiano.audio.NativeEngine
import app.breakpiano.audio.Previewer
import app.breakpiano.learn.Learner
import app.breakpiano.learn.MicListener
import app.breakpiano.ui.learn.ListenControls
import app.breakpiano.learn.SongLibrary
import app.breakpiano.audio.Recorder
import app.breakpiano.audio.Take
import app.breakpiano.audio.UserBeat
import app.breakpiano.data.BreakState
import app.breakpiano.ui.MainScreen
import app.breakpiano.ui.keyboard.KeyboardModel
import app.breakpiano.ui.theme.BreakTheme
import app.breakpiano.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var state: BreakState
    private lateinit var beats: BeatLibrary
    private lateinit var recorder: Recorder
    private lateinit var updater: Updater
    private lateinit var previewer: Previewer
    private lateinit var songLibrary: SongLibrary
    private lateinit var learner: Learner
    private var importStage by mutableStateOf<Pair<String, Float>?>(null)
    private lateinit var mic: MicListener
    private var listenOpen by mutableStateOf(false)
    private var listenVoice by mutableStateOf(false)
    private var listenCancelled = false
    private var redetecting by mutableStateOf<String?>(null)
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listenOpen = true else state.toast = "Break needs the microphone to listen"
    }
    private val model = KeyboardModel()

    private var ready by mutableStateOf(false)
    private var importProgress by mutableStateOf<Float?>(null)
    private var loaded = false

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBeat(uri)
    }
    private val pickSongAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSong(uri, midi = false)
    }
    private val pickSongMidi = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSong(uri, midi = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()

        state = BreakState(this)
        beats = BeatLibrary(this)
        recorder = Recorder(this, lifecycleScope)
        updater = Updater(applicationContext, lifecycleScope)
        previewer = Previewer(lifecycleScope)
        songLibrary = SongLibrary(this)
        learner = Learner(songLibrary, state, lifecycleScope)
        mic = MicListener(this)
        model.listener = { note, down -> learner.onKey(note, down) }

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) { NativeEngine.load(assets, NativeEngine.SOUNDFONT) }
            if (!ok) { state.toast = "Couldn't load sounds — please reinstall Break"; return@launch }
            loaded = true
            NativeEngine.start()
            state.onEngineReady()
            ready = true
            launch { songLibrary.upgradeVoiceSongs() }     // older sung songs → the new voice tracker
            delay(400)
            state.latencyMs = NativeEngine.getLatencyMs()
        }

        setContent {
            BreakTheme {
                MainScreen(
                    state = state,
                    model = model,
                    userBeats = beats.beats,
                    recorder = recorder,
                    updater = updater,
                    previewer = previewer,
                    learner = learner,
                    songs = songLibrary.songs,
                    importStage = importStage,
                    onImportSongAudio = { pickSongAudio.launch(arrayOf("audio/*", "video/mp4")) },
                    onImportSongMidi = { pickSongMidi.launch(arrayOf("audio/midi", "audio/x-midi", "audio/mid", "application/x-midi", "audio/sp-midi")) },
                    onLearnSong = { s -> state.panel = app.breakpiano.data.Panel.None; learner.open(s) },
                    onDeleteSong = { s -> if (learner.song?.id == s.id) learner.close(); songLibrary.delete(s) },
                    listen = ListenControls(
                        open = listenOpen,
                        voice = listenVoice,
                        mic = mic,
                        onOpen = ::openListen,
                        onClose = { if (mic.recording) { listenCancelled = true; mic.stop() }; listenOpen = false },
                        onMode = { listenVoice = it },
                        onRecord = ::startListening,
                        onDone = { mic.stop() },
                    ),
                    onRenameSong = { s, name -> songLibrary.rename(s, name) },
                    onRedetectSong = ::redetectSong,
                    redetecting = redetecting,
                    ready = ready,
                    importProgress = importProgress,
                    onImport = { pickAudio.launch(arrayOf("audio/*")) },
                    onPlayUserBeat = ::playUserBeat,
                    onDeleteUserBeat = ::deleteUserBeat,
                    onShare = ::shareTake,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (loaded) {
            NativeEngine.start()
            state.onEngineReady()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        updater.onResume()
        if (state.autoUpdate) updater.startWatching()
    }

    override fun onPause() {
        super.onPause()
        updater.stopWatching()
    }

    override fun onStop() {
        super.onStop()
        recorder.stop()
        recorder.stopPlayback()
        previewer.stop()
        learner.pause()
        if (mic.recording) mic.stop()   // whatever was heard so far still gets turned into keys
        model.releaseAll()
        if (loaded) NativeEngine.stop()
        state.onEngineStopped()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ── imported beats ──

    private fun importBeat(uri: Uri) = lifecycleScope.launch {
        importProgress = 0f
        state.beatsTab = 1   // show it in My beats
        try {
            val beat = beats.import(uri) { p -> runOnUiThread { importProgress = p } }
            state.toast = "“${beat.name}” is ready"
            importProgress = null
            playUserBeat(beat)
        } catch (t: Throwable) {
            state.toast = t.message?.takeIf { it.length < 80 } ?: "Couldn't read that audio file"
        } finally {
            importProgress = null
        }
    }

    private fun playUserBeat(beat: UserBeat) {
        if (!ready) return
        if (state.userTrackId == beat.id && state.userTrackLoading == null) {
            state.playUserTrack(!state.userTrackPlaying)
            return
        }
        lifecycleScope.launch {
            state.playUserTrack(false)
            state.userTrackId = beat.id
            state.userTrackLoading = 0f
            NativeEngine.collectGarbage()
            runCatching { beats.loadIntoEngine(beat) { p -> runOnUiThread { state.userTrackLoading = p } } }
                .onFailure { state.toast = "Couldn't load that beat"; state.userTrackId = null }
            delay(60) // let the audio thread pick up the new track
            state.userTrackLoading = null
            if (state.userTrackId == beat.id) {
                NativeEngine.seekUserTrack(0f)
                state.playUserTrack(true)
            }
        }
    }

    private fun deleteUserBeat(beat: UserBeat) {
        if (state.userTrackId == beat.id) {
            state.playUserTrack(false)
            NativeEngine.clearUserTrack()
            state.userTrackId = null
        }
        beats.delete(beat)
    }

    private fun redetectSong(song: app.breakpiano.learn.Song) = lifecycleScope.launch {
        if (redetecting != null) return@launch
        redetecting = song.id
        try {
            val updated = songLibrary.redetect(song)
            if (learner.song?.id == song.id) learner.open(updated)
            state.toast = "Notes detected again — ${updated.noteCount} notes"
        } catch (t: Throwable) {
            state.toast = t.message?.takeIf { it.length < 90 } ?: "Couldn't detect notes"
        } finally {
            redetecting = null
        }
    }

    private fun openListen() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) listenOpen = true
        else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    /** Records until the user taps stop, then transcribes the take into a learnable song. */
    private fun startListening() = lifecycleScope.launch {
        if (mic.recording) return@launch
        listenCancelled = false
        // keep Break's own beat / clicks out of the recording
        if (state.beatPlaying) state.stopBeat()
        if (state.metronomeOn) state.toggleMetronome()
        if (state.userTrackPlaying) state.playUserTrack(false)
        learner.pause()
        val voice = listenVoice
        val (id, file) = songLibrary.newRecording()
        try {
            val take = mic.record(file, forMusic = !voice)
            if (listenCancelled) { file.delete(); return@launch }
            listenOpen = false
            importStage = "Listening for notes…" to 0f
            val song = songLibrary.importRecording(id, take, voice) { label, p -> runOnUiThread { importStage = label to p } }
            state.toast = "Got it — ${song.noteCount} notes. Tap “Learn this song”."
        } catch (t: Throwable) {
            file.delete()
            state.toast = t.message?.takeIf { it.length < 100 } ?: "Couldn't listen — please try again"
        } finally {
            importStage = null
        }
    }

    private fun importSong(uri: Uri, midi: Boolean) = lifecycleScope.launch {
        importStage = (if (midi) "Reading MIDI…" else "Reading the song…") to 0f
        try {
            val song = if (midi) songLibrary.importMidi(uri)
            else songLibrary.importAudio(uri) { label, p -> runOnUiThread { importStage = label to p } }
            state.toast = "“${song.title}” is ready to learn"
        } catch (t: Throwable) {
            state.toast = t.message?.takeIf { it.length < 90 } ?: "Couldn't read that file"
        } finally {
            importStage = null
        }
    }

    private fun shareTake(take: Take) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", take.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, take.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share recording"))
    }
}
