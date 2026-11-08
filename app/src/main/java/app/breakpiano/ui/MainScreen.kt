package app.breakpiano.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.breakpiano.audio.NativeEngine
import app.breakpiano.audio.Recorder
import app.breakpiano.audio.Take
import app.breakpiano.audio.UserBeat
import app.breakpiano.data.BreakState
import app.breakpiano.data.Panel
import app.breakpiano.ui.keyboard.KeyboardModel
import app.breakpiano.ui.keyboard.KeyboardRow
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type
import app.breakpiano.update.UpdateState
import app.breakpiano.update.Updater
import kotlinx.coroutines.delay

@Composable
fun MainScreen(
    state: BreakState,
    model: KeyboardModel,
    userBeats: List<UserBeat>,
    recorder: Recorder,
    updater: Updater,
    ready: Boolean,
    importProgress: Float?,
    onImport: () -> Unit,
    onPlayUserBeat: (UserBeat) -> Unit,
    onDeleteUserBeat: (UserBeat) -> Unit,
    onShare: (Take) -> Unit,
) {
    // One frame loop drives the beat indicators and the key-glow fade-outs.
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        var gc = 0
        while (true) {
            withFrameMillis {
                state.pollEngine()
                if (SystemClock.uptimeMillis() < model.fadeUntil + 34) model.version++
                if (++gc % 60 == 0) NativeEngine.collectGarbage()
            }
        }
    }

    val userBeatName = userBeats.firstOrNull { it.id == state.userTrackId }?.name

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brand.Obsidian)
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        val panelWidth = (maxWidth * 0.56f).coerceIn(340.dp, 420.dp)

        Column(Modifier.fillMaxSize()) {
            TopBar(state, recorder, userBeatName, updateBadge = updater.state is UpdateState.Available)
            // Piano body: lacquered case around the action
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF1C1C23), Color(0xFF0E0E12), Color(0xFF09090B))))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp)),
            ) {
                // glossy lacquer reflection along the top of the case
                Box(
                    Modifier.fillMaxWidth().height(18.dp)
                        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent))),
                )
                Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    if (state.dualKeyboard) {
                        KeyboardRow(model, state, state.upperStart, { state.upperStart = it }, Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        KeyboardRow(model, state, state.lowerStart, { state.lowerStart = it }, Modifier.weight(1f).fillMaxWidth().padding(bottom = 4.dp))
                    } else {
                        KeyboardRow(model, state, state.upperStart, { state.upperStart = it }, Modifier.fillMaxSize().padding(bottom = 4.dp))
                    }
                }
            }
        }

        // ── side panels (non-modal: visible keys stay playable) ──
        val sidePanel = state.panel.takeIf { it in setOf(Panel.Instruments, Panel.Beats, Panel.Metronome, Panel.Recordings) }
        AnimatedVisibility(
            visible = sidePanel != null,
            enter = slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 520f)) { it } + fadeIn(),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 58.dp, bottom = 6.dp).width(panelWidth),
        ) {
            when (sidePanel ?: lastSide) {
                Panel.Instruments -> InstrumentPanel(state)
                Panel.Beats -> BeatsPanel(state, userBeats, importProgress, onImport, onPlayUserBeat, onDeleteUserBeat)
                Panel.Metronome -> MetronomePanel(state)
                Panel.Recordings -> RecordingsPanel(state, recorder, onShare)
                else -> {}
            }
            if (sidePanel != null) lastSide = sidePanel
        }

        AnimatedVisibility(
            visible = state.panel == Panel.Settings,
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.98f),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.98f),
        ) { SettingsScreen(state, updater) }

        UpdateDialog(updater)

        // ── toast ──
        val toast = state.toast
        LaunchedEffect(toast) { if (toast != null) { delay(2400); state.toast = null } }
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
        ) {
            Text(
                toast ?: lastToast,
                style = Type.Heading, color = Brand.Ivory,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Brand.Slate)
                    .border(1.dp, Brand.Line, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
            )
            if (toast != null) lastToast = toast
        }

        // ── first-launch loading veil ──
        AnimatedVisibility(visible = !ready, enter = fadeIn(), exit = fadeOut(tween(450))) {
            LoadingVeil()
        }
    }
}

// Remember the last panel / toast so exit animations keep their content.
private var lastSide: Panel = Panel.Instruments
private var lastToast: String = ""

@Composable
private fun LoadingVeil() {
    val t = rememberInfiniteTransition(label = "veil")
    val breathe by t.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "b")
    val dots by t.animateFloat(0f, 3.99f, infiniteRepeatable(tween(1400)), label = "d")
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1B1418), Brand.Obsidian), radius = 1400f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BreakMark(84.dp, Modifier.scale(breathe))
            Spacer(Modifier.height(18.dp))
            Text("break", style = Type.Display, color = Brand.Ivory)
            Text("Tuning the piano" + ".".repeat(dots.toInt()), style = Type.Body, color = Brand.Mist)
        }
    }
}
