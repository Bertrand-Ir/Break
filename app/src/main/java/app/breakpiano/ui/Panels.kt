package app.breakpiano.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.breakpiano.audio.BeatStyle
import app.breakpiano.audio.Beats
import app.breakpiano.audio.InstrumentFamily
import app.breakpiano.audio.Instruments
import app.breakpiano.audio.Recorder
import app.breakpiano.audio.Take
import app.breakpiano.audio.UserBeat
import app.breakpiano.data.BreakState
import app.breakpiano.ui.components.BreakSwitch
import app.breakpiano.ui.components.Card
import app.breakpiano.ui.components.GhostButton
import app.breakpiano.ui.components.PrimaryButton
import app.breakpiano.ui.components.SectionLabel
import app.breakpiano.ui.components.Segmented
import app.breakpiano.ui.components.SidePanel
import app.breakpiano.ui.components.SliderRow
import app.breakpiano.ui.components.Stepper
import app.breakpiano.ui.components.ToggleRow
import app.breakpiano.ui.components.pressScale
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type
import kotlin.math.roundToInt

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ─────────────────────────────────────────────────────────────────────────────
// Instruments
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun InstrumentPanel(state: BreakState, modifier: Modifier = Modifier) {
    var target by remember { mutableIntStateOf(0) } // 0 = main, 1 = layer
    var family by remember { mutableStateOf(state.instrument.family) }
    val listState = rememberLazyListState()
    LaunchedEffect(family) { listState.scrollToItem(0) }

    SidePanel(
        title = "Instruments",
        subtitle = "${Instruments.all.size} sounds · tap one and play",
        onClose = { state.panel = app.breakpiano.data.Panel.None },
        modifier = modifier,
        scrollable = false,
    ) {
        Segmented(listOf("Main sound", if (state.layer != null) "Layer · on" else "Layer"), target, { target = it }, Modifier.fillMaxWidth().padding(top = 6.dp))
        if (target == 1) {
            Card(Modifier.padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.layer?.let { "${state.instrument.name} + ${it.name}" } ?: "Stack a second sound", style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (state.layer == null) "Pick any sound below to layer it under every note." else "Both play from every key.", style = Type.Body, color = Brand.Mist)
                    }
                    if (state.layer != null) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Brand.Slate).pressScale({ state.layerId = "" }), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Close, "Remove layer", tint = Brand.Ivory, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (state.layer != null) {
                    SliderRow("Layer level", state.layerMix, { state.layerMix = it }, "${(state.layerMix * 100).roundToInt()}%")
                }
            }
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(InstrumentFamily.entries.toList()) { f ->
                val sel = f == family
                val bg by animateColorAsState(if (sel) Brand.Ivory else Brand.Slate, label = "fam")
                Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(bg).pressScale({ family = f }).padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(f.tint))
                    Spacer(Modifier.width(6.dp))
                    Text(f.title, style = Type.Label, color = if (sel) Brand.Obsidian else Brand.Ivory)
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(Instruments.of(family), key = { it.id }) { inst ->
                val selected = if (target == 0) inst.id == state.instrumentId else inst.id == state.layerId
                val bg by animateColorAsState(if (selected) Brand.Ember.copy(alpha = 0.14f) else Color.Transparent, label = "row")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(bg)
                        .clickable { if (target == 0) state.instrumentId = inst.id else state.layerId = inst.id }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InstrumentTile(inst, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(inst.name, style = Type.Heading, color = Brand.Ivory)
                        Text(inst.blurb, style = Type.Body, color = Brand.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(Brand.EmberGradient), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Beats
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun BeatsPanel(
    state: BreakState,
    userBeats: List<UserBeat>,
    importProgress: Float?,
    onImport: () -> Unit,
    onPlayUserBeat: (UserBeat) -> Unit,
    onDeleteUserBeat: (UserBeat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = Beats.byId(state.beatStyleId) ?: Beats.styles.first()
    SidePanel(
        title = "Beats",
        subtitle = "${Beats.styles.size} grooves · or bring your own",
        onClose = { state.panel = app.breakpiano.data.Panel.None },
        modifier = modifier,
    ) {
        // ── Now playing ──
        Card(Modifier.padding(top = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val playing = state.beatPlaying
                Box(
                    Modifier.size(58.dp).clip(CircleShape)
                        .background(if (playing) Brand.EmberGradient else Brush.linearGradient(listOf(Brand.Slate, Brand.Slate)))
                        .pressScale({ state.toggleBeat() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (playing) "Stop" else "Play", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(style.name, style = Type.Title, color = Brand.Ivory)
                    Text(style.vibe, style = Type.Body, color = Brand.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    BigBeatDots(state.beatStatus, state.beatPlaying)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper("${state.bpm}", { state.setTempo(state.bpm - 1) }, { state.setTempo(state.bpm + 1) }, minWidth = 40.dp)
                Spacer(Modifier.width(6.dp))
                Text("BPM", style = Type.Caption, color = Brand.Dim)
                Spacer(Modifier.weight(1f))
                TapButton { state.tapTempo() }
            }
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("A · Main", "B · Lift"), state.beatVariation, { state.selectVariation(it) }, Modifier.fillMaxWidth())
            if (style.hasBass) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (style.bassMode <= 2) "808 key" else "Bass key", style = Type.Label, color = Brand.Mist, modifier = Modifier.weight(1f))
                    Text("Bass", style = Type.Label, color = Brand.Mist)
                    Spacer(Modifier.width(8.dp))
                    BreakSwitch(state.bassOn, { state.bassOn = it })
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(12) { k ->
                        val sel = k == state.beatKey
                        Box(
                            Modifier.size(width = 36.dp, height = 30.dp).clip(RoundedCornerShape(9.dp))
                                .background(if (sel) Brand.Ember else Brand.Slate)
                                .pressScale({ state.beatKey = k }),
                            contentAlignment = Alignment.Center,
                        ) { Text(Beats.keyNames[k], style = Type.Label, color = if (sel) Color.White else Brand.Ivory) }
                    }
                }
            }
            ToggleRow("Count-in", "One bar of clicks before the beat drops", state.countIn) { state.countIn = it }
        }

        // ── Styles ──
        SectionLabel("Styles")
        Beats.styles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s -> StyleCard(s, selected = s.id == state.beatStyleId, playing = state.beatPlaying && s.id == state.beatStyleId, modifier = Modifier.weight(1f)) { state.selectBeat(s.id) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // ── My beats ──
        SectionLabel("My beats")
        Row(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp))
                .border(1.5.dp, Brand.Ember.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .background(Brand.Ember.copy(alpha = 0.06f))
                .pressScale(onImport, enabled = importProgress == null)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (importProgress != null) {
                CircularProgressIndicator(progress = { importProgress }, color = Brand.Ember, trackColor = Brand.Line, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Importing… ${(importProgress * 100).roundToInt()}%", style = Type.Heading, color = Brand.Ivory)
            } else {
                Icon(Icons.Rounded.Add, null, tint = Brand.EmberHi, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Upload a beat", style = Type.Heading, color = Brand.Ivory)
                    Text("MP3, WAV, M4A, OGG, FLAC — auto-levelled to sit with your keys", style = Type.Body, color = Brand.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        userBeats.forEach { b ->
            UserBeatRow(state, b, onPlay = { onPlayUserBeat(b) }, onDelete = { onDeleteUserBeat(b) })
        }
        if (userBeats.isNotEmpty()) {
            ToggleRow("Loop my beat", "Repeat the track when it ends", state.userLoop) { state.userLoop = it }
        }

        // ── Mixer ──
        SectionLabel("Mix")
        Card {
            SliderRow("Keys", state.keysVol, { state.keysVol = it }, "${(state.keysVol * 100).roundToInt()}")
            SliderRow("Beats", state.beatVol, { state.beatVol = it }, "${(state.beatVol * 100).roundToInt()}")
            SliderRow("My beats", state.userVol, { state.userVol = it }, "${(state.userVol * 100).roundToInt()}")
            SliderRow("Metronome", state.metroVol, { state.metroVol = it }, "${(state.metroVol * 100).roundToInt()}")
            ToggleRow("Smart mix", "Levels imported beats and dips the beat ~3 dB while you play, so your keys always cut through", state.smartMix) { state.smartMix = it }
        }
    }
}

@Composable
private fun TapButton(onTap: () -> Unit) {
    var flash by remember { mutableIntStateOf(0) }
    val bg by animateColorAsState(if (flash % 2 == 1) Brand.Ember else Brand.Slate, label = "tap")
    LaunchedEffect(flash) { if (flash % 2 == 1) { kotlinx.coroutines.delay(90); flash++ } }
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .pointerInput(Unit) { detectTapGestures(onPress = { onTap(); flash++ }) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.TouchApp, null, tint = Brand.Ivory, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Tap tempo", style = Type.Label, color = Brand.Ivory)
    }
}

@Composable
private fun StyleCard(style: BeatStyle, selected: Boolean, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) Brand.Ember else Brand.Line, label = "sb")
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(style.color.copy(alpha = 0.16f), Brand.Night)))
            .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(16.dp))
            .pressScale(onClick, pressedScale = 0.96f)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(style.color))
            Spacer(Modifier.weight(1f))
            if (playing) Text("PLAYING", style = Type.Caption, color = Brand.EmberHi)
            else Text("${style.bpm}", style = Type.Caption, color = Brand.Dim)
        }
        Spacer(Modifier.height(10.dp))
        Text(style.name, style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(style.vibe, style = Type.Body, color = Brand.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun UserBeatRow(state: BreakState, beat: UserBeat, onPlay: () -> Unit, onDelete: () -> Unit) {
    val isCurrent = state.userTrackId == beat.id
    val playing = isCurrent && state.userTrackPlaying
    val loading = isCurrent && state.userTrackLoading != null
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) { if (confirmDelete) { kotlinx.coroutines.delay(2500); confirmDelete = false } }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) Brand.Slate else Brand.Night).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (playing) Brand.EmberGradient else Brush.linearGradient(listOf(Brand.Line, Brand.Line)))
                    .pressScale(onPlay, enabled = !loading),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) CircularProgressIndicator(progress = { state.userTrackLoading ?: 0f }, color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(beat.name, style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${fmtTime(beat.durationMs)} · ${if (beat.gain < 0.95f) "levelled −%.0f dB".format(20 * kotlin.math.log10(1 / beat.gain)) else "levelled"}",
                    style = Type.Body, color = Brand.Mist,
                )
            }
            if (isCurrent && state.userLoop) Icon(Icons.Rounded.Repeat, null, tint = Brand.Dim, modifier = Modifier.size(16.dp).padding(end = 2.dp))
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (confirmDelete) Color(0xFFE5303F) else Color.Transparent)
                    .pressScale({ if (confirmDelete) onDelete() else confirmDelete = true }),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = if (confirmDelete) Color.White else Brand.Mist, modifier = Modifier.size(19.dp)) }
        }
        if (isCurrent && !loading) {
            SeekBar(state.userTrackPos) { app.breakpiano.audio.NativeEngine.seekUserTrack(it) }
        }
    }
}

@Composable
private fun SeekBar(progress: Float, onSeek: (Float) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(22.dp).padding(top = 10.dp)
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Brand.Line))
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(Brand.EmberGradient))
    }
}

@Composable
private fun BigBeatDots(status: Int, running: Boolean) {
    val beats = if (status >= 0) ((status shr 8) and 0xff).coerceIn(1, 12) else 4
    val cur = if (running && status >= 0) status and 0xff else -1
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(beats) { i ->
            Box(
                Modifier.size(width = if (i == 0) 22.dp else 14.dp, height = 6.dp).clip(CircleShape)
                    .background(if (i == cur) (if (i == 0) Brand.EmberHi else Brand.Ember) else Brand.Line),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Metronome
// ─────────────────────────────────────────────────────────────────────────────
private fun tempoName(bpm: Int) = when {
    bpm < 60 -> "Largo"; bpm < 76 -> "Adagio"; bpm < 108 -> "Andante"
    bpm < 120 -> "Moderato"; bpm < 168 -> "Allegro"; else -> "Presto"
}

@Composable
fun MetronomePanel(state: BreakState, modifier: Modifier = Modifier) {
    val signatures = listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8)
    SidePanel("Metronome", "Stays locked to the beats", onClose = { state.panel = app.breakpiano.data.Panel.None }, modifier = modifier) {
        Card(Modifier.padding(top = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${state.bpm}", style = Type.Display, color = Brand.Ivory)
                        Spacer(Modifier.width(6.dp))
                        Text("BPM", style = Type.Label, color = Brand.Dim, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Text(tempoName(state.bpm), style = Type.Body, color = Brand.EmberHi)
                }
                Box(
                    Modifier.size(64.dp).clip(CircleShape)
                        .background(if (state.metronomeOn) Brand.EmberGradient else Brush.linearGradient(listOf(Brand.Slate, Brand.Slate)))
                        .pressScale({ state.toggleMetronome() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (state.metronomeOn) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            BigBeatDots(state.beatStatus, state.metronomeOn || state.beatPlaying)
            SliderRow("Tempo", state.bpm.toFloat(), { state.setTempo(it.roundToInt()) }, "${state.bpm}", range = 30f..260f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper("±1", { state.setTempo(state.bpm - 1) }, { state.setTempo(state.bpm + 1) }, minWidth = 36.dp)
                Spacer(Modifier.width(8.dp))
                Stepper("±5", { state.setTempo(state.bpm - 5) }, { state.setTempo(state.bpm + 5) }, minWidth = 36.dp)
                Spacer(Modifier.weight(1f))
                TapButton { state.tapTempo() }
            }
        }
        SectionLabel("Time signature")
        Segmented(
            signatures.map { "${it.first}/${it.second}" },
            signatures.indexOf(state.tsNum to state.tsDen),
            { i -> state.tsNum = signatures[i].first; state.tsDen = signatures[i].second },
            Modifier.fillMaxWidth(),
        )
        SectionLabel("Sound")
        Segmented(listOf("Click", "Wood", "Soft"), state.metroSound, { state.metroSound = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SliderRow("Volume", state.metroVol, { state.metroVol = it }, "${(state.metroVol * 100).roundToInt()}")
        ToggleRow("Count-in before beats", "One bar of clicks so you come in on the one", state.countIn) { state.countIn = it }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recordings
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RecordingsPanel(state: BreakState, recorder: Recorder, onShare: (Take) -> Unit, modifier: Modifier = Modifier) {
    SidePanel("Recordings", "Everything you hear — keys, beats & all", onClose = { state.panel = app.breakpiano.data.Panel.None }, modifier = modifier) {
        Card(Modifier.padding(top = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(58.dp).clip(CircleShape)
                        .background(if (recorder.isRecording) Color(0xFFE5303F) else Brand.Slate)
                        .pressScale({ recorder.toggle() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (recorder.isRecording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord, null,
                        tint = if (recorder.isRecording) Color.White else Color(0xFFFF4D5E), modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (recorder.isRecording) "Recording · ${fmtTime(recorder.elapsedMs)}" else "Ready to record", style = Type.Title, color = Brand.Ivory)
                    Text(if (recorder.isRecording) "Tap to stop and save" else "Studio-quality WAV of your full mix", style = Type.Body, color = Brand.Mist)
                }
            }
        }
        SectionLabel("Takes")
        if (recorder.takes.isEmpty()) {
            Text("No takes yet. Hit record, play something beautiful.", style = Type.Body, color = Brand.Dim, modifier = Modifier.padding(vertical = 12.dp))
        }
        recorder.takes.forEach { take ->
            val playing = recorder.playingTake == take.file
            var confirm by remember(take.file) { mutableStateOf(false) }
            LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(2500); confirm = false } }
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(if (playing) Brand.Slate else Brand.Night).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, active = playing) {
                        if (playing) recorder.stopPlayback() else recorder.play(take)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(take.name.removePrefix("Break "), style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmtTime(take.durationMs), style = Type.Body, color = Brand.Mist)
                    }
                    RoundIcon(Icons.Rounded.Share, size = 34.dp) { onShare(take) }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(if (confirm) Color(0xFFE5303F) else Color.Transparent)
                            .pressScale({ if (confirm) recorder.delete(take) else confirm = true }),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = if (confirm) Color.White else Brand.Mist, modifier = Modifier.size(19.dp)) }
                }
                if (playing) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp).clip(CircleShape).background(Brand.Line)) {
                        Box(Modifier.fillMaxWidth(recorder.playProgress.coerceIn(0f, 1f)).fillMaxHeight().background(Brand.EmberGradient))
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, active: Boolean = false, size: Dp = 40.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (active) Brand.EmberGradient else Brush.linearGradient(listOf(Brand.Line, Brand.Line)))
            .pressScale(onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
}
