package app.breakpiano.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.breakpiano.audio.BeatCompiler
import app.breakpiano.audio.BeatStyle
import app.breakpiano.audio.Beats
import app.breakpiano.audio.Kit
import app.breakpiano.audio.NativeEngine
import app.breakpiano.audio.UserBeat
import app.breakpiano.data.BreakState
import app.breakpiano.data.Panel
import app.breakpiano.ui.components.BigModal
import app.breakpiano.ui.components.BreakSwitch
import app.breakpiano.ui.components.Card
import app.breakpiano.ui.components.ProgressLine
import app.breakpiano.ui.components.RoundButton
import app.breakpiano.ui.components.Segmented
import app.breakpiano.ui.components.SliderRow
import app.breakpiano.ui.components.Stepper
import app.breakpiano.ui.components.ToggleRow
import app.breakpiano.ui.components.control
import app.breakpiano.ui.components.pressScale
import app.breakpiano.ui.components.well
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type
import kotlin.math.roundToInt

internal fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun kitName(kit: Int) = when (kit) {
    Kit.TR808 -> "808 kit"; Kit.ROOM -> "Room kit"; Kit.POWER -> "Power kit"; Kit.ELECTRONIC -> "Electronic kit"
    Kit.JAZZ -> "Jazz kit"; Kit.BRUSH -> "Brushes"; Kit.DANCE -> "Dance kit"; else -> "Studio kit"
}

@Composable
fun BeatsModal(
    state: BreakState,
    userBeats: List<UserBeat>,
    importProgress: Float?,
    onImport: () -> Unit,
    onPlayUserBeat: (UserBeat) -> Unit,
    onDeleteUserBeat: (UserBeat) -> Unit,
) {
    BigModal(
        title = "Beats",
        subtitle = "${Beats.styles.size} live grooves · or your own tracks",
        onClose = { state.panel = Panel.None },
        headerExtra = {
            Segmented(
                listOf("Styles", if (userBeats.isEmpty()) "My beats" else "My beats · ${userBeats.size}", "Mix"),
                state.beatsTab, { state.beatsTab = it }, Modifier.width(380.dp),
            )
        },
    ) {
        NowPlayingStrip(state)
        when (state.beatsTab) {
            0 -> StylesGrid(state)
            1 -> MyBeats(state, userBeats, importProgress, onImport, onPlayUserBeat, onDeleteUserBeat)
            else -> MixTab(state)
        }
    }
}

@Composable
private fun NowPlayingStrip(state: BreakState) {
    val style = Beats.byId(state.beatStyleId) ?: Beats.styles.first()
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp).well().padding(vertical = 10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(if (state.beatPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, state.beatPlaying, 52.dp) { state.toggleBeat() }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.widthIn(min = 120.dp, max = 180.dp)) {
                    Text(style.name, style = Type.Title, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    BigBeatDots(state.beatStatus, state.beatPlaying)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper("${state.bpm}", { state.setTempo(state.bpm - 1) }, { state.setTempo(state.bpm + 1) }, minWidth = 44.dp, label = "BPM")
                Spacer(Modifier.width(8.dp))
                TapButton { state.tapTempo() }
            }
        }
        item { Segmented(listOf("A · Main", "B · Lift"), state.beatVariation, { state.selectVariation(it) }, Modifier.width(190.dp)) }
        if (style.hasBass) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(if (style.bassMode <= 2) "808 KEY" else "BASS KEY", style = Type.Caption, color = Brand.Dim)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(12) { k ->
                            val sel = k == state.beatKey
                            Box(
                                Modifier.size(width = 30.dp, height = 28.dp).control(sel, RoundedCornerShape(8.dp)).pressScale({ state.beatKey = k }),
                                contentAlignment = Alignment.Center,
                            ) { Text(Beats.keyNames[k], style = Type.Label, color = if (sel) Color.White else Brand.Mist) }
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("BASS", style = Type.Caption, color = Brand.Dim)
                    Spacer(Modifier.height(4.dp))
                    BreakSwitch(state.bassOn, { state.bassOn = it })
                }
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("COUNT-IN", style = Type.Caption, color = Brand.Dim)
                Spacer(Modifier.height(4.dp))
                BreakSwitch(state.countIn, { state.countIn = it })
            }
        }
    }
}

@Composable
private fun StylesGrid(state: BreakState) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(220.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(Beats.styles, key = { it.id }) { s ->
            val selected = s.id == state.beatStyleId
            StyleCard(
                style = s,
                variation = if (selected) state.beatVariation else 0,
                selected = selected,
                playing = selected && state.beatPlaying,
                beatStatus = state.beatStatus,
            ) {
                if (selected && state.beatPlaying) state.stopBeat() else state.selectBeat(s.id)
            }
        }
    }
}

@Composable
private fun StyleCard(style: BeatStyle, variation: Int, selected: Boolean, playing: Boolean, beatStatus: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .clip(shape)
            .background(if (selected) Brand.Pressed else Brand.Slate)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Brand.Ivory.copy(alpha = 0.8f) else Brand.SlateHi, shape)
            .pressScale(onClick, pressedScale = 0.97f)
            .padding(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(12.dp)).background(Brand.Night).padding(8.dp)) {
            PatternPreview(style, variation, if (playing) beatStatus else -1, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(style.name, style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${style.bpm} BPM · ${kitName(style.kit)}${if (style.hasBass) " · bass" else ""}", style = Type.Caption, color = Brand.Dim, maxLines = 1)
            }
            Box(Modifier.size(34.dp).control(playing, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Text(style.vibe, style = Type.Body, color = Brand.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Draws the actual pattern as a step grid — kick, snare, hats, percussion, bass lanes. */
@Composable
private fun PatternPreview(style: BeatStyle, variation: Int, status: Int, modifier: Modifier) {
    val compiled = remember(style.id, variation) { BeatCompiler.compile(style, variation) }
    val lanes = remember(compiled) {
        fun laneOf(kind: Int, note: Int) = when {
            kind == 1 -> 4
            note == 35 || note == 36 -> 0
            note in 37..40 -> 1
            note == 42 || note == 44 || note == 46 -> 2
            note == 49 || note == 51 || note == 53 -> 2
            else -> 3
        }
        val steps = compiled.lengthTicks / 24
        val grid = Array(5) { IntArray(steps) }
        for (i in compiled.ticks.indices) {
            val lane = laneOf(compiled.kinds[i].toInt(), compiled.notes[i].toInt())
            val st = ((compiled.ticks[i] + 6) / 24).coerceIn(0, steps - 1)
            grid[lane][st] = maxOf(grid[lane][st], compiled.vels[i].toInt() and 0xff)
        }
        grid.filter { row -> row.any { it > 0 } }
    }
    val beatInBar = if (status >= 0) status and 0xff else -1
    Canvas(modifier) {
        if (lanes.isEmpty()) return@Canvas
        val steps = lanes[0].size
        val gap = 2.dp.toPx()
        val cw = (size.width - gap * (steps - 1)) / steps
        val ch = (size.height - gap * (lanes.size - 1)) / lanes.size
        val r = CornerRadius(minOf(cw, ch) * 0.3f)
        for (col in 0 until steps) {
            val beat = (col % 16) / 4
            val lit = beat == beatInBar
            for ((row, lane) in lanes.withIndex()) {
                val v = lane[col]
                val c = when {
                    v > 0 -> Brand.Ivory.copy(alpha = (0.35f + v / 127f * 0.65f) * (if (beatInBar >= 0 && !lit) 0.55f else 1f))
                    lit -> Color.White.copy(alpha = 0.10f)
                    col % 4 == 0 -> Color.White.copy(alpha = 0.07f)
                    else -> Color.White.copy(alpha = 0.035f)
                }
                drawRoundRect(c, Offset(col * (cw + gap), row * (ch + gap)), Size(cw, ch), r)
            }
        }
    }
}

@Composable
private fun MyBeats(
    state: BreakState,
    userBeats: List<UserBeat>,
    importProgress: Float?,
    onImport: () -> Unit,
    onPlay: (UserBeat) -> Unit,
    onDelete: (UserBeat) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(340.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth().height(72.dp).control(false, RoundedCornerShape(16.dp))
                    .pressScale(onImport, enabled = importProgress == null, pressedScale = 0.98f)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(42.dp).well(CircleShape), contentAlignment = Alignment.Center) {
                    if (importProgress != null) CircularProgressIndicator(progress = { importProgress }, color = Brand.Ivory, trackColor = Brand.Line, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                    else Icon(Icons.Rounded.Add, null, tint = Brand.Ivory, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (importProgress != null) "Importing… ${(importProgress * 100).roundToInt()}%" else "Upload a beat", style = Type.Heading, color = Brand.Ivory)
                    Text("MP3 · WAV · M4A · OGG · FLAC — saved here until you delete it, auto-levelled to sit under your keys", style = Type.Body, color = Brand.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (userBeats.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.LibraryMusic, null, tint = Brand.Dim, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Your beats live here", style = Type.Heading, color = Brand.Mist)
                    Text("Upload an instrumental and play along with it.", style = Type.Body, color = Brand.Dim)
                }
            }
        }
        items(userBeats, key = { it.id }) { b -> UserBeatRow(state, b, onPlay = { onPlay(b) }, onDelete = { onDelete(b) }) }
        if (userBeats.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ToggleRow("Loop my beat", "Repeat the track when it reaches the end", state.userLoop) { state.userLoop = it }
            }
        }
    }
}

@Composable
private fun UserBeatRow(state: BreakState, beat: UserBeat, onPlay: () -> Unit, onDelete: () -> Unit) {
    val isCurrent = state.userTrackId == beat.id
    val playing = isCurrent && state.userTrackPlaying
    val loading = isCurrent && state.userTrackLoading != null
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) { if (confirmDelete) { kotlinx.coroutines.delay(2500); confirmDelete = false } }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (isCurrent) Brand.Pressed else Brand.Slate)
            .border(1.dp, if (isCurrent) Brand.Ivory.copy(alpha = 0.5f) else Brand.SlateHi, shape).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).control(playing, CircleShape).pressScale(onPlay, enabled = !loading),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) CircularProgressIndicator(progress = { state.userTrackLoading ?: 0f }, color = Brand.Ivory, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(beat.name, style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${fmtTime(beat.durationMs)} · " + if (beat.gain < 0.95f) "levelled −%.0f dB".format(20 * kotlin.math.log10(1 / beat.gain)) else "levelled",
                    style = Type.Body, color = Brand.Mist,
                )
            }
            if (isCurrent && state.userLoop) Icon(Icons.Rounded.Repeat, null, tint = Brand.Dim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (confirmDelete) Brand.Rec else Color.Transparent)
                    .pressScale({ if (confirmDelete) onDelete() else confirmDelete = true }),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.DeleteOutline, if (confirmDelete) "Tap again to delete" else "Delete", tint = if (confirmDelete) Color.White else Brand.Mist, modifier = Modifier.size(19.dp)) }
        }
        if (isCurrent && !loading) {
            Box(
                Modifier.fillMaxWidth().height(24.dp).padding(top = 12.dp)
                    .pointerInput(Unit) { detectTapGestures { NativeEngine.seekUserTrack((it.x / size.width).coerceIn(0f, 1f)) } },
            ) { ProgressLine(state.userTrackPos) }
        }
    }
}

@Composable
private fun MixTab(state: BreakState) {
    Row(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(Modifier.weight(1f)) {
            SliderRow("Keys", state.keysVol, { state.keysVol = it }, "${(state.keysVol * 100).roundToInt()}")
            SliderRow("Beats", state.beatVol, { state.beatVol = it }, "${(state.beatVol * 100).roundToInt()}")
            SliderRow("My beats", state.userVol, { state.userVol = it }, "${(state.userVol * 100).roundToInt()}")
        }
        Card(Modifier.weight(1f)) {
            SliderRow("Loop", state.loopVol, { state.loopVol = it }, "${(state.loopVol * 100).roundToInt()}")
            SliderRow("Metronome", state.metroVol, { state.metroVol = it }, "${(state.metroVol * 100).roundToInt()}")
            ToggleRow("Smart mix", "Levels imported beats and dips the beat ~3 dB while you play", state.smartMix) { state.smartMix = it }
        }
    }
}

@Composable
internal fun TapButton(onTap: () -> Unit) {
    var flash by remember { mutableIntStateOf(0) }
    LaunchedEffect(flash) { if (flash % 2 == 1) { kotlinx.coroutines.delay(90); flash++ } }
    Row(
        Modifier.height(40.dp).control(flash % 2 == 1)
            .pointerInput(Unit) { detectTapGestures(onPress = { onTap(); flash++ }) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.TouchApp, null, tint = Brand.Ivory, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Tap", style = Type.Label, color = Brand.Ivory)
    }
}

@Composable
internal fun BigBeatDots(status: Int, running: Boolean) {
    val beats = if (status >= 0) ((status shr 8) and 0xff).coerceIn(1, 12) else 4
    val cur = if (running && status >= 0) status and 0xff else -1
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(beats) { i ->
            Box(
                Modifier.size(width = if (i == 0) 22.dp else 14.dp, height = 6.dp).clip(CircleShape)
                    .background(if (i == cur) Brand.Ivory else Brand.Line),
            )
        }
    }
}
