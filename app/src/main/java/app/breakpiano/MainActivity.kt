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
    private val model = KeyboardModel()

    private var ready by mutableStateOf(false)
    private var importProgress by mutableStateOf<Float?>(null)
    private var loaded = false

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBeat(uri)
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

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) { NativeEngine.load(assets, NativeEngine.SOUNDFONT) }
            if (!ok) { state.toast = "Couldn't load sounds — please reinstall Break"; return@launch }
            loaded = true
            NativeEngine.start()
            state.onEngineReady()
            ready = true
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
