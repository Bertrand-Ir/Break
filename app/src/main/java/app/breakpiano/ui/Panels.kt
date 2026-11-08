package app.breakpiano.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.breakpiano.audio.Recorder
import app.breakpiano.audio.Take
import app.breakpiano.data.BreakState
import app.breakpiano.data.Panel
import app.breakpiano.ui.components.Card
import app.breakpiano.ui.components.ProgressLine
import app.breakpiano.ui.components.RoundButton
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

private fun tempoName(bpm: Int) = when {
    bpm < 60 -> "Largo"; bpm < 76 -> "Adagio"; bpm < 108 -> "Andante"
    bpm < 120 -> "Moderato"; bpm < 168 -> "Allegro"; else -> "Presto"
}

@Composable
fun MetronomePanel(state: BreakState, modifier: Modifier = Modifier) {
    val signatures = listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8)
    SidePanel("Metronome", "Locked to the beats and the looper", onClose = { state.panel = Panel.None }, modifier = modifier) {
        Card(Modifier.padding(top = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${state.bpm}", style = Type.Display, color = Brand.Ivory)
                        Spacer(Modifier.width(6.dp))
                        Text("BPM", style = Type.Label, color = Brand.Dim, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Text(tempoName(state.bpm), style = Type.Body, color = Brand.Mist)
                }
                RoundButton(if (state.metronomeOn) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, state.metronomeOn, 62.dp) { state.toggleMetronome() }
            }
            Spacer(Modifier.height(10.dp))
            BigBeatDots(state.beatStatus, state.metronomeOn || state.beatPlaying)
            SliderRow("Tempo", state.bpm.toFloat(), { state.setTempo(it.roundToInt()) }, "${state.bpm}", range = 30f..260f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper("±1", { state.setTempo(state.bpm - 1) }, { state.setTempo(state.bpm + 1) }, minWidth = 34.dp)
                Spacer(Modifier.width(8.dp))
                Stepper("±5", { state.setTempo(state.bpm - 5) }, { state.setTempo(state.bpm + 5) }, minWidth = 34.dp)
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

@Composable
fun RecordingsPanel(state: BreakState, recorder: Recorder, onShare: (Take) -> Unit, modifier: Modifier = Modifier) {
    SidePanel("Recordings", "Everything you hear — keys, loop, beats", onClose = { state.panel = Panel.None }, modifier = modifier) {
        Card(Modifier.padding(top = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(
                    if (recorder.isRecording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord, recorder.isRecording, 58.dp,
                    tint = if (recorder.isRecording) Color.White else Brand.Rec,
                ) { recorder.toggle() }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (recorder.isRecording) "Recording · ${fmtTime(recorder.elapsedMs)}" else "Ready to record", style = Type.Title, color = Brand.Ivory)
                    Text(if (recorder.isRecording) "Tap to stop and save" else "Studio-quality WAV of the full mix", style = Type.Body, color = Brand.Mist)
                }
            }
        }
        SectionLabel("Looper")
        Card {
            Text("Build a song by yourself", style = Type.Heading, color = Brand.Ivory)
            Text(
                "Tap LOOP and play a part. Tap again — it loops. Play over it, or tap again to overdub another layer. " +
                    "With a beat or metronome running, loops lock to the bar. Hit REC any time to capture all of it.",
                style = Type.Body, color = Brand.Mist, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            SliderRow("Loop volume", state.loopVol, { state.loopVol = it }, "${(state.loopVol * 100).roundToInt()}")
        }
        SectionLabel("Takes")
        if (recorder.takes.isEmpty()) {
            Text("No takes yet. Hit record, play something beautiful.", style = Type.Body, color = Brand.Dim, modifier = Modifier.padding(vertical = 12.dp))
        }
        recorder.takes.forEach { take ->
            val playing = recorder.playingTake == take.file
            var confirm by remember(take.file) { mutableStateOf(false) }
            LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(2500); confirm = false } }
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(shape)
                    .background(if (playing) Brand.Pressed else Brand.Slate).border(1.dp, Brand.SlateHi, shape).padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, playing, 40.dp) {
                        if (playing) recorder.stopPlayback() else recorder.play(take)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(take.name.removePrefix("Break "), style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmtTime(take.durationMs), style = Type.Body, color = Brand.Mist)
                    }
                    RoundButton(Icons.Rounded.Share, false, 34.dp) { onShare(take) }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(if (confirm) Brand.Rec else Color.Transparent)
                            .pressScale({ if (confirm) recorder.delete(take) else confirm = true }),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = if (confirm) Color.White else Brand.Mist, modifier = Modifier.size(19.dp)) }
                }
                if (playing) ProgressLine(recorder.playProgress, Modifier.padding(top = 10.dp))
            }
        }
    }
}
