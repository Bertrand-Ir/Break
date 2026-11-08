package app.breakpiano.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.breakpiano.BuildConfig
import app.breakpiano.audio.NativeEngine
import app.breakpiano.data.BreakState
import app.breakpiano.data.LabelMode
import app.breakpiano.data.Notation
import app.breakpiano.data.Panel
import app.breakpiano.ui.components.Card
import app.breakpiano.ui.components.GhostButton
import app.breakpiano.ui.components.IconChip
import app.breakpiano.ui.components.PrimaryButton
import app.breakpiano.ui.components.SectionLabel
import app.breakpiano.ui.components.Segmented
import app.breakpiano.ui.components.SettingRow
import app.breakpiano.ui.components.SliderRow
import app.breakpiano.ui.components.ToggleRow
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type
import app.breakpiano.update.UpdateState
import app.breakpiano.update.Updater
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Section(val title: String, val icon: ImageVector) {
    Keyboard("Keyboard", Icons.Rounded.Piano),
    Touch("Touch", Icons.Rounded.TouchApp),
    Sound("Sound", Icons.Rounded.GraphicEq),
    Updates("Updates", Icons.Rounded.SystemUpdate),
    About("About", Icons.Rounded.Info),
}

@Composable
fun SettingsScreen(state: BreakState, updater: Updater) {
    var section by remember { mutableIntStateOf(0) }
    Row(
        Modifier
            .fillMaxSize()
            .background(Brand.Obsidian)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // nav rail
        Column(
            Modifier.fillMaxHeight().width(220.dp).background(Brand.Night).padding(16.dp),
        ) {
            BreakWordmark(markSize = 26.dp)
            Text("Settings", style = Type.Body, color = Brand.Dim, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
            Section.entries.forEachIndexed { i, s ->
                val sel = i == section
                val bg by animateColorAsState(if (sel) Brand.Slate else Color.Transparent, label = "nav")
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                        .clickable { section = i }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(s.icon, null, tint = if (sel) Brand.Ember else Brand.Mist, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(s.title, style = Type.Heading, color = if (sel) Brand.Ivory else Brand.Mist)
                    if (s == Section.Updates && updater.state is UpdateState.Available) {
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Brand.Ember))
                    }
                }
            }
        }
        // content
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Section.entries[section].title, style = Type.Title, color = Brand.Ivory, modifier = Modifier.weight(1f))
                IconChip(Icons.Rounded.Close, "Close") { state.panel = Panel.None }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)
                    .widthIn(max = 640.dp),
            ) {
                when (Section.entries[section]) {
                    Section.Keyboard -> KeyboardSettings(state)
                    Section.Touch -> TouchSettings(state)
                    Section.Sound -> SoundSettings(state)
                    Section.Updates -> UpdateSettings(state, updater)
                    Section.About -> AboutSection()
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun KeyboardSettings(state: BreakState) {
    SectionLabel("Size")
    val keys = state.keysOnScreen
    SliderRow(
        "Keys on screen", keys.toFloat(), { state.keysOnScreen = it.roundToInt() },
        "$keys white · %.1f oct".format(keys / 7f), range = 7f..36f, steps = 28,
    )
    ToggleRow("Dual keyboard", "Two stacked rows — left hand below, right hand above", state.dualKeyboard) { state.dualKeyboard = it }

    SectionLabel("Key labels")
    Segmented(LabelMode.entries.map { it.title }, state.labelMode.ordinal, { state.labelMode = LabelMode.entries[it] }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    SettingRow("Note names") {
        Segmented(Notation.entries.map { it.title }, state.notation.ordinal, { state.notation = Notation.entries[it] }, Modifier.width(220.dp))
    }
    SettingRow("Accidentals", "How black keys are named") {
        Segmented(listOf("♯ Sharps", "♭ Flats"), if (state.useFlats) 1 else 0, { state.useFlats = it == 1 }, Modifier.width(220.dp))
    }
    ToggleRow("Octave numbers", "C4, Do4 … middle C is marked with an ember dot", state.showOctave) { state.showOctave = it }
    ToggleRow("Key glow", "Ember light under every key you play", state.keyGlow) { state.keyGlow = it }
}

@Composable
private fun TouchSettings(state: BreakState) {
    SectionLabel("Dynamics")
    ToggleRow("Touch dynamics", "Play near the front edge of a key for louder notes, near the back for softer — like a real action", state.dynamicVelocity) { state.dynamicVelocity = it }
    if (!state.dynamicVelocity) {
        SliderRow("Fixed velocity", state.fixedVelocity, { state.fixedVelocity = it }, "${(state.fixedVelocity * 127).roundToInt()}", range = 0.2f..1f)
    }
    SectionLabel("Feel")
    ToggleRow("Glissando", "Slide your finger across keys to sweep notes", state.glissando) { state.glissando = it }
    ToggleRow("Haptic tap", "A light tick on every key press", state.haptics) { state.haptics = it }
}

@Composable
private fun SoundSettings(state: BreakState) {
    var latency by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) { latency = NativeEngine.getLatencyMs().roundToInt() }
    SectionLabel("Output")
    SliderRow("Master volume", state.masterVol, { state.masterVol = it }, "${(state.masterVol * 100).roundToInt()}", range = 0f..1.2f)
    SliderRow("Reverb", state.reverb, { state.reverb = it }, "${(state.reverb * 100).roundToInt()}", range = 0f..0.8f)
    ToggleRow("Smart mix", "Auto-level imported beats, gentle ducking under your playing, and a limiter so nothing ever clips", state.smartMix) { state.smartMix = it }

    SectionLabel("Pitch")
    SliderRow("Tuning (A4)", state.tuningA4.toFloat(), { state.tuningA4 = it.roundToInt() }, "${state.tuningA4} Hz", range = 415f..466f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(432, 440, 442).forEach { hz ->
            GhostButton("$hz Hz", Modifier.weight(1f)) { state.tuningA4 = hz }
        }
    }
    Spacer(Modifier.height(6.dp))
    SettingRow("Transpose", "Shift everything you play by semitones") {
        Segmented(listOf("−12", "−1", "0", "+1", "+12"), -1, { i ->
            state.transpose = when (i) { 0 -> state.transpose - 12; 1 -> state.transpose - 1; 2 -> 0; 3 -> state.transpose + 1; else -> state.transpose + 12 }.coerceIn(-24, 24)
        }, Modifier.width(260.dp))
    }
    Text("Current: ${if (state.transpose == 0) "none" else "%+d semitones".format(state.transpose)}", style = Type.Body, color = Brand.Mist)

    SectionLabel("Engine")
    Card {
        Text(
            if (latency > 0) "≈ $latency ms output latency · ${NativeEngine.getSampleRate() / 1000.0} kHz" else "Measuring latency…",
            style = Type.Heading, color = Brand.Ivory,
        )
        Text("Low-latency Oboe/AAudio stream · sampled instruments by GeneralUser GS", style = Type.Body, color = Brand.Mist)
        Spacer(Modifier.height(10.dp))
        GhostButton("Silence all sounds") { NativeEngine.panic(); state.toast = "All sounds stopped" }
    }
}

@Composable
private fun UpdateSettings(state: BreakState, updater: Updater) {
    val scope = rememberCoroutineScope()
    SectionLabel("This version")
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BreakMark(40.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Break ${BuildConfig.VERSION_NAME}", style = Type.Title, color = Brand.Ivory)
                Text("Build ${BuildConfig.VERSION_CODE} · ${if (updater.configured) "github.com/${updater.repo}" else "local build"}", style = Type.Body, color = Brand.Mist)
            }
        }
    }
    SectionLabel("Updates")
    ToggleRow("Check automatically", "Looks for new builds on GitHub whenever you're online", state.autoUpdate) { state.autoUpdate = it }
    Spacer(Modifier.height(8.dp))
    when (val s = updater.state) {
        is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = Brand.Ember, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp)); Text("Checking GitHub…", style = Type.Heading, color = Brand.Ivory)
        }
        is UpdateState.Available -> Card {
            Text("${s.release.versionName} is ready", style = Type.Title, color = Brand.Ivory)
            if (s.release.notes.isNotBlank()) Text(s.release.notes, style = Type.Body, color = Brand.Mist, maxLines = 8, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Update now", Modifier.fillMaxWidth()) { updater.download(s.release) }
        }
        is UpdateState.Downloading -> Card {
            Text("Downloading ${s.release.versionName}… ${(s.progress * 100).roundToInt()}%", style = Type.Heading, color = Brand.Ivory)
            ProgressLine(s.progress)
        }
        is UpdateState.UpToDate -> Text("You're on the latest build.", style = Type.Heading, color = Brand.Success)
        is UpdateState.Failed -> Text(s.message, style = Type.Heading, color = Color(0xFFFF6B6B))
        else -> {}
    }
    Spacer(Modifier.height(12.dp))
    GhostButton("Check for updates") { scope.launch { updater.check() } }
}

@Composable
fun ProgressLine(progress: Float) {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(CircleShape).background(Brand.Line)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Brand.EmberGradient))
    }
}

@Composable
private fun AboutSection() {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.Start) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1C1C24), Color(0xFF09090C))))
                .border(1.dp, Brand.Line, RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center,
        ) { BreakMark(56.dp) }
        Spacer(Modifier.height(16.dp))
        Text("break", style = Type.Display, color = Brand.Ivory)
        Text("Play anywhere.", style = Type.Title, color = Brand.EmberHi)
        Spacer(Modifier.height(12.dp))
        Text(
            "A real piano feel in your pocket — sampled instruments, live grooves and a studio mix engine tuned for the lowest latency your phone can do.",
            style = Type.Body, color = Brand.Mist,
        )
        SectionLabel("Credits")
        Text("Sounds — GeneralUser GS by S. Christian Collins", style = Type.Body, color = Brand.Mist)
        Text("Synth engine — TinySoundFont by Bernhard Schelling (MIT)", style = Type.Body, color = Brand.Mist)
        Text("Audio I/O — Oboe by Google (Apache 2.0)", style = Type.Body, color = Brand.Mist)
        Text("Typeface — Manrope (OFL)", style = Type.Body, color = Brand.Mist)
    }
}
