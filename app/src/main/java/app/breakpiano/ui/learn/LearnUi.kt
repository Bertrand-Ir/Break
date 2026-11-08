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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
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
) {
    BigModal(
        title = "Learn songs",
        subtitle = "Turn a song into keys, then learn it note by note",
        onClose = { state.panel = Panel.None },
        headerExtra = {
            Spacer(Modifier.weight(1f))
            PrimaryButton("Import audio", Modifier.height(40.dp), icon = Icons.Rounded.GraphicEq) { if (importStage == null) onImportAudio() }
            Spacer(Modifier.width(8.dp))
            GhostButton("Import MIDI", Modifier.height(40.dp), icon = Icons.Rounded.MusicNote) { if (importStage == null) onImportMidi() }
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(300.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                    "Works best with clear recordings: a solo piano or guitar, a singer, or you humming the melody. " +
                        "Full band mixes work too — use Melody to learn the tune. MIDI files give perfect, exact notes.",
                    style = Type.Body, color = Brand.Dim,
                )
            }
            if (songs.isEmpty() && importStage == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.School, null, tint = Brand.Dim, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Your songs will appear here", style = Type.Heading, color = Brand.Mist)
                        Text("Import an audio file or a MIDI file to get started.", style = Type.Body, color = Brand.Dim)
                    }
                }
            }
            items(songs, key = { it.id }) { s -> SongCard(s, onLearn = { onLearn(s) }, onDelete = { onDelete(s) }) }
        }
    }
}

@Composable
private fun SongCard(song: Song, onLearn: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(2500); confirm = false } }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Brand.Slate).border(1.dp, Brand.SlateHi, shape).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).well(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(if (song.fromMidi) Icons.Rounded.MusicNote else Icons.Rounded.LibraryMusic, null, tint = Brand.Ivory, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${t(song.durationMs / 1000f)} · ${song.noteCount} notes · ${if (song.fromMidi) "MIDI" else "Transcribed"}",
                    style = Type.Body, color = Brand.Mist)
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (confirm) Brand.Rec else Color.Transparent)
                    .pressScale({ if (confirm) onDelete() else confirm = true }),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.DeleteOutline, if (confirm) "Tap again to delete" else "Delete", tint = if (confirm) Color.White else Brand.Mist, modifier = Modifier.size(19.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Learn this song", Modifier.fillMaxWidth().height(42.dp), icon = Icons.Rounded.School, onClick = onLearn)
    }
}
