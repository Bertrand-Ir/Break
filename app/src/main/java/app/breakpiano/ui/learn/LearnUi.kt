package app.breakpiano.ui.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.breakpiano.data.BreakState
import app.breakpiano.data.Panel
import app.breakpiano.learn.Arrangement as Part
import app.breakpiano.learn.Hands
import app.breakpiano.learn.Learner
import app.breakpiano.learn.Listen
import app.breakpiano.learn.MicListener
import app.breakpiano.learn.Song
import app.breakpiano.ui.components.BigModal
import app.breakpiano.ui.components.GhostButton
import app.breakpiano.ui.components.IconChip
import app.breakpiano.ui.components.PrimaryButton
import app.breakpiano.ui.components.ProgressLine
import app.breakpiano.ui.components.RoundButton
import app.breakpiano.ui.components.Segmented
import app.breakpiano.ui.components.control
import app.breakpiano.ui.components.pressScale
import app.breakpiano.ui.components.well
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type

private fun t(sec: Float): String { val s = sec.toInt().coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Transport + options shown above the keyboard while learning a song. */
@Composable
fun LearnBar(learner: Learner, modifier: Modifier = Modifier) {
    val song = learner.song ?: return
    Column(modifier.fillMaxWidth().padding(horizontal = 6.dp).well(RoundedCornerShape(14.dp))) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconChip(Icons.Rounded.Close, "Stop learning", size = 38.dp) { learner.close() }
            Column(Modifier.widthIn(max = 170.dp)) {
                Text(song.title, style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        learner.loading -> "Loading…"
                        learner.finished -> "Done · ${learner.accuracy}% accuracy"
                        learner.waiting -> "Play the lit keys"
                        else -> "${t(learner.position)} / ${t(learner.duration)}"
                    },
                    style = Type.Caption, color = if (learner.waiting) Brand.HandRight else Brand.Dim, maxLines = 1,
                )
            }
            IconChip(Icons.Rounded.Replay, "Restart", size = 38.dp) { learner.restart() }
            RoundButton(if (learner.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, learner.playing, 42.dp) { learner.toggle() }
            Segmented(listOf("Watch", "Wait for me"), if (learner.waitMode) 1 else 0, { learner.waitMode = it == 1 }, Modifier.width(190.dp))
            Segmented(Hands.entries.map { it.title }, learner.hands.ordinal, { learner.hands = Hands.entries[it]; learner.seek(learner.position) }, Modifier.width(180.dp))
            Segmented(Part.entries.map { it.title }, learner.arrangement.ordinal, { learner.changeArrangement(Part.entries[it]) }, Modifier.width(260.dp))
            val speeds = listOf(0.5f, 0.75f, 1f)
            Segmented(
                listOf("50%", "75%", "100%"),
                speeds.indexOfFirst { kotlin.math.abs(it - learner.speed) < 0.01f },
                { if (learner.listen != Listen.Original) learner.speed = speeds[it] },
                Modifier.width(170.dp),
            )
            val listens = if (song.hasAudio) Listen.entries else listOf(Listen.Piano, Listen.Off)
            Segmented(listens.map { it.title }, listens.indexOf(learner.listen), { learner.changeListen(listens[it]) }, Modifier.width(if (song.hasAudio) 240.dp else 160.dp))
            Box(Modifier.height(38.dp).control(false).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text("${learner.hits} ✓  ${learner.accuracy}%", style = Type.Mono, color = Brand.Ivory)
            }
        }
        // seek line
        Box(
            Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 12.dp, vertical = 5.dp)
                .pointerInput(learner.duration) { detectTapGestures { learner.seek(it.x / size.width * learner.duration) } },
        ) { ProgressLine(if (learner.duration > 0f) learner.position / learner.duration else 0f) }
    }
}

/** Song library: import audio (transcribed on-device) or MIDI, then learn. */
@Composable
fun SongsModal(
    state: BreakState,
    songs: List<Song>,
    importStage: Pair<String, Float>?,
    onImportAudio: () -> Unit,
    onImportMidi: () -> Unit,
    onLearn: (Song) -> Unit,
    onDelete: (Song) -> Unit,
    listen: ListenControls,
    onRename: (Song, String) -> Unit,
    onRedetect: (Song, Int) -> Unit,
    redetecting: String?,
) {
    var editing by remember { mutableStateOf<Song?>(null) }
    var deleting by remember { mutableStateOf<Song?>(null) }
    Box(Modifier.fillMaxSize()) {
    BigModal(
        title = "Learn songs",
        subtitle = "Turn a song into keys, then learn it note by note",
        onClose = { state.panel = Panel.None },
        headerExtra = {
            Spacer(Modifier.weight(1f))
            PrimaryButton("Listen", Modifier.height(40.dp), icon = Icons.Rounded.Mic) { if (importStage == null) listen.onOpen() }
            Spacer(Modifier.width(8.dp))
            GhostButton("Import audio", Modifier.height(40.dp), icon = Icons.Rounded.GraphicEq) { if (importStage == null && !listen.mic.recording) onImportAudio() }
            Spacer(Modifier.width(8.dp))
            GhostButton("Import MIDI", Modifier.height(40.dp), icon = Icons.Rounded.MusicNote) { if (importStage == null && !listen.mic.recording) onImportMidi() }
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(300.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (listen.open && importStage == null) {
                item(span = { GridItemSpan(maxLineSpan) }) { ListenPanel(listen) }
            }
            if (importStage != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().well().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(progress = { importStage.second }, color = Brand.Ivory, trackColor = Brand.Line, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(importStage.first, style = Type.Heading, color = Brand.Ivory)
                            Text("Break listens to the recording on your phone and writes out the notes. Longer songs take a little longer.", style = Type.Body, color = Brand.Mist)
                        }
                        Text("${(importStage.second * 100).toInt()}%", style = Type.Mono, color = Brand.Ivory)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Listen hears any song playing near your phone — or you singing it. Clear recordings give the best keys; " +
                        "for full band mixes, learn with Melody. MIDI files give perfect, exact notes.",
                    style = Type.Body, color = Brand.Dim,
                )
            }
            if (songs.isEmpty() && importStage == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.School, null, tint = Brand.Dim, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Your songs will appear here", style = Type.Heading, color = Brand.Mist)
                        Text("Tap Listen and play a song or hum a tune — or import an audio or MIDI file.", style = Type.Body, color = Brand.Dim)
                    }
                }
            }
            items(songs, key = { it.id }) { s ->
                SongCard(s, onLearn = { onLearn(s) }, onDelete = { deleting = s }, onEdit = { editing = s }, busy = redetecting == s.id)
            }
        }
    }
    editing?.let { e ->
        val live = songs.firstOrNull { it.id == e.id } ?: e
        EditSongDialog(
            song = live,
            busy = redetecting == live.id,
            onSave = { name -> onRename(live, name); editing = null },
            onRedetect = { level -> onRedetect(live, level) },
            onDelete = { deleting = live },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { d ->
        ConfirmDelete(d, onConfirm = { onDelete(d); deleting = null; editing = null }, onDismiss = { deleting = null })
    }
    }
}

@Composable
private fun SongCard(song: Song, onLearn: () -> Unit, onDelete: () -> Unit, onEdit: () -> Unit, busy: Boolean) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Brand.Slate).border(1.dp, Brand.SlateHi, shape).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).well(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(when (song.source) { "midi" -> Icons.Rounded.MusicNote; "voice", "listen" -> Icons.Rounded.Mic; else -> Icons.Rounded.LibraryMusic }, null, tint = Brand.Ivory, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                Text(song.title, style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${t(song.durationMs / 1000f)} · ${song.noteCount} notes · ${song.sourceLabel}",
                    style = Type.Body, color = Brand.Mist)
            }
            Box(Modifier.size(36.dp).clip(CircleShape).pressScale(onEdit), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(color = Brand.Ivory, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Icon(Icons.Rounded.Edit, "Rename", tint = Brand.Mist, modifier = Modifier.size(19.dp))
            }
            Box(Modifier.size(36.dp).clip(CircleShape).pressScale(onDelete), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DeleteOutline, "Delete", tint = Brand.Mist, modifier = Modifier.size(19.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Learn this song", Modifier.fillMaxWidth().height(42.dp), icon = Icons.Rounded.School, onClick = onLearn)
    }
}

/** Everything the Listen panel needs from the activity. */
class ListenControls(
    val open: Boolean,
    val voice: Boolean,
    val mic: MicListener,
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val onMode: (Boolean) -> Unit,
    val onRecord: () -> Unit,
    val onDone: () -> Unit,
)

@Composable
private fun ListenPanel(c: ListenControls) {
    val rec = c.mic.recording
    Row(Modifier.fillMaxWidth().well(RoundedCornerShape(20.dp)).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Segmented(listOf("Play a song", "Sing or hum"), if (c.voice) 1 else 0, { if (!rec) c.onMode(it == 1) }, Modifier.width(300.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                if (c.voice) "Sing or hum the tune — “da da da”, “tang tang tang”. Clear, separate notes work best."
                else "Play the song from YouTube, Spotify or any speaker near the phone. Louder music and a quiet room give the best keys.",
                style = Type.Heading, color = Brand.Ivory,
            )
            Text(
                if (c.voice) "Break turns your voice into piano notes you can learn and play."
                else "Break listens through the microphone for as long as the song plays (up to 10 minutes).",
                style = Type.Body, color = Brand.Mist, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(14.dp))
            LevelMeter(c.mic.levels, rec, Modifier.fillMaxWidth().height(46.dp))
        }
        Spacer(Modifier.width(22.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(84.dp).control(rec, CircleShape).pressScale({ if (rec) c.onDone() else c.onRecord() }, pressedScale = 0.92f),
                contentAlignment = Alignment.Center,
            ) {
                if (rec) Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Brand.Rec))
                else Icon(Icons.Rounded.Mic, "Start listening", tint = Brand.Rec, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(if (rec) t(c.mic.elapsedMs / 1000f) else "Tap to listen", style = Type.Mono, color = if (rec) Brand.Rec else Brand.Mist)
            Text(if (rec) "Tap ■ when the song ends" else " ", style = Type.Caption, color = Brand.Dim)
            Spacer(Modifier.height(6.dp))
            GhostButton(if (rec) "Cancel" else "Close", Modifier.height(36.dp)) { c.onClose() }
        }
    }
}

@Composable
private fun LevelMeter(levels: List<Float>, live: Boolean, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val n = 64
        val gap = 2.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        val start = n - levels.size
        for (i in 0 until n) {
            val v = if (i >= start) levels[i - start] else 0f
            val bh = (size.height * (0.06f + 0.94f * v)).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                if (live) Brand.Ivory.copy(alpha = 0.35f + 0.65f * v) else Brand.Line,
                androidx.compose.ui.geometry.Offset(i * (bw + gap), (size.height - bh) / 2),
                androidx.compose.ui.geometry.Size(bw, bh),
                androidx.compose.ui.geometry.CornerRadius(bw / 2),
            )
        }
    }
}

/** Rename a song (and re-run note detection on its recording). Sits high so the keyboard can't cover it. */
@Composable
private fun EditSongDialog(song: Song, busy: Boolean, onSave: (String) -> Unit, onRedetect: (Int) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var level by remember(song.id) { mutableStateOf(song.cleanLevel) }
    var name by remember(song.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(song.title, androidx.compose.ui.text.TextRange(0, song.title.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(song.id) { focus.requestFocus() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 18.dp).width(460.dp).clip(RoundedCornerShape(22.dp)).background(Brand.Graphite)
                .border(1.dp, Brand.Line, RoundedCornerShape(22.dp))
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
        ) {
            Text("Edit song", style = Type.Title, color = Brand.Ivory)
            Text("${song.sourceLabel} · ${t(song.durationMs / 1000f)} · ${song.noteCount} notes", style = Type.Body, color = Brand.Mist)
            Spacer(Modifier.height(14.dp))
            Text("NAME", style = Type.Caption, color = Brand.Dim)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = name,
                onValueChange = { if (it.text.length <= 60) name = it },
                singleLine = true,
                textStyle = Type.Title.copy(color = Brand.Ivory),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Brand.Ivory),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSave(name.text) }),
                modifier = Modifier.fillMaxWidth().well(RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
                    .focusRequester(focus),
            )
            if (song.isVoice) {
                Spacer(Modifier.height(14.dp))
                Text("CLEAN-UP", style = Type.Caption, color = Brand.Dim)
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("Gentle", "Balanced", "Strict"), level, { level = it }, Modifier.fillMaxWidth())
                Text(
                    when (level) {
                        0 -> "Keeps quick little notes — for fast or decorated singing."
                        2 -> "Only clear, steady sung notes — removes every stray sound."
                        else -> "Removes sniffs, breaths, beeps and background — keeps your melody."
                    },
                    style = Type.Body, color = Brand.Mist, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.hasAudio) {
                    GhostButton(if (busy) "Detecting…" else "Detect again", Modifier.height(42.dp), icon = Icons.Rounded.Replay) { if (!busy) onRedetect(level) }
                    Spacer(Modifier.width(8.dp))
                }
                GhostButton("Delete", Modifier.height(42.dp), icon = Icons.Rounded.DeleteOutline) { onDelete() }
                Spacer(Modifier.weight(1f))
                GhostButton("Cancel", Modifier.height(42.dp)) { onDismiss() }
                Spacer(Modifier.width(8.dp))
                PrimaryButton("Save", Modifier.height(42.dp)) { onSave(name.text) }
            }
        }
    }
}

/** Clear, one-step confirmation before a song (and its recording) is removed. */
@Composable
private fun ConfirmDelete(song: Song, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(420.dp).clip(RoundedCornerShape(22.dp)).background(Brand.Graphite)
                .border(1.dp, Brand.Line, RoundedCornerShape(22.dp))
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .padding(22.dp),
        ) {
            Text("Delete this song?", style = Type.Title, color = Brand.Ivory)
            Spacer(Modifier.height(6.dp))
            Text("“${song.title}” and ${if (song.hasAudio) "its recording and notes" else "its notes"} will be removed from Break.",
                style = Type.Body, color = Brand.Mist)
            Spacer(Modifier.height(18.dp))
            Row {
                Spacer(Modifier.weight(1f))
                GhostButton("Cancel", Modifier.height(44.dp)) { onDismiss() }
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(Brand.Rec).pressScale(onConfirm).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete", style = Type.Heading, color = Color.White)
                }
            }
        }
    }
}
