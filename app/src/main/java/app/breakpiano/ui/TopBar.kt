package app.breakpiano.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.breakpiano.audio.Beats
import app.breakpiano.audio.Instrument
import app.breakpiano.audio.Recorder
import app.breakpiano.data.BreakState
import app.breakpiano.data.Panel
import app.breakpiano.ui.components.Chip
import app.breakpiano.ui.components.IconChip
import app.breakpiano.ui.components.Stepper
import app.breakpiano.ui.components.pressScale
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type

@Composable
fun TopBar(
    state: BreakState,
    recorder: Recorder,
    userBeatName: String?,
    updateBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
        val compact = maxWidth < 760.dp
        val tight = maxWidth < 640.dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (compact) BreakMark(26.dp) else BreakWordmark(markSize = 24.dp, fontSize = 21.sp)
            Spacer(Modifier.width(2.dp))
            InstrumentChip(state.instrument, state.layer, active = state.panel == Panel.Instruments, compact = tight) {
                state.panel = if (state.panel == Panel.Instruments) Panel.None else Panel.Instruments
            }
            Spacer(Modifier.weight(1f))

            // Transpose
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Stepper(
                    text = if (state.transpose == 0) "0" else "%+d".format(state.transpose),
                    onMinus = { state.transpose = (state.transpose - 1).coerceAtLeast(-12) },
                    onPlus = { state.transpose = (state.transpose + 1).coerceAtMost(12) },
                    minWidth = 34.dp,
                    modifier = Modifier.height(40.dp),
                )
            }

            // Sustain
            Chip(active = state.sustain, onClick = { state.toggleSustain() }) {
                Icon(Icons.Rounded.AllInclusive, "Sustain", tint = if (state.sustain) Color.White else Brand.Ivory, modifier = Modifier.size(18.dp))
                if (!compact) Text("Sustain", style = Type.Label, color = if (state.sustain) Color.White else Brand.Ivory)
            }

            // Metronome
            SplitChip(
                active = state.metronomeOn,
                onToggle = { state.toggleMetronome() },
                onOpen = { state.panel = if (state.panel == Panel.Metronome) Panel.None else Panel.Metronome },
                icon = { MetronomeGlyph(state.metronomeOn) },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${state.bpm}", style = Type.Mono, color = Brand.Ivory)
                    BeatDots(state.beatStatus, running = state.metronomeOn || state.beatPlaying)
                }
            }

            // Beats
            val beatActive = state.beatPlaying || state.userTrackPlaying
            SplitChip(
                active = beatActive,
                onToggle = {
                    when {
                        state.userTrackPlaying -> state.playUserTrack(false)
                        state.beatPlaying -> state.stopBeat()
                        else -> state.playBeat()
                    }
                },
                onOpen = { state.panel = if (state.panel == Panel.Beats) Panel.None else Panel.Beats },
                icon = {
                    Icon(if (beatActive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, "Play beat",
                        tint = if (beatActive) Color.White else Brand.Ivory, modifier = Modifier.size(20.dp))
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EqBars(playing = beatActive)
                    if (!tight) {
                        Spacer(Modifier.width(6.dp))
                        val name = if (state.userTrackPlaying && userBeatName != null) userBeatName
                        else (Beats.byId(state.beatStyleId)?.name ?: "Beats")
                        Text(name, style = Type.Label, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = if (compact) 64.dp else 96.dp))
                    }
                }
            }

            // Record
            SplitChip(
                active = recorder.isRecording,
                activeColor = Color(0xFFE5303F),
                onToggle = { recorder.toggle() },
                onOpen = { state.panel = if (state.panel == Panel.Recordings) Panel.None else Panel.Recordings },
                icon = { RecGlyph(recorder.isRecording) },
            ) {
                if (recorder.isRecording) {
                    val s = recorder.elapsedMs / 1000
                    Text("%d:%02d".format(s / 60, s % 60), style = Type.Mono, color = Brand.Ivory)
                } else {
                    Icon(Icons.Rounded.LibraryMusic, "Recordings", tint = Brand.Ivory, modifier = Modifier.size(18.dp))
                }
            }

            IconChip(Icons.Rounded.Tune, "Settings", active = state.panel == Panel.Settings, badge = updateBadge) {
                state.panel = if (state.panel == Panel.Settings) Panel.None else Panel.Settings
            }
        }
    }
}

@Composable
private fun InstrumentChip(inst: Instrument, layer: Instrument?, active: Boolean, compact: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) Brand.Ember.copy(alpha = 0.18f) else Brand.Slate, label = "inst")
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .pressScale(onClick)
            .padding(start = 5.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InstrumentTile(inst, 30.dp)
        Spacer(Modifier.width(9.dp))
        Column {
            Text(inst.name, style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (compact) 92.dp else 150.dp))
            Text(
                if (layer != null) "+ ${layer.name}" else inst.family.title,
                style = Type.Caption, color = if (layer != null) Brand.EmberHi else Brand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (compact) 92.dp else 150.dp),
            )
        }
    }
}

@Composable
fun InstrumentTile(inst: Instrument, size: Dp, selected: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(inst.family.tint, inst.family.tint2))),
        contentAlignment = Alignment.Center,
    ) {
        Text(inst.monogram, style = Type.Mono.copy(fontSize = Type.Mono.fontSize * (size.value / 30f)), color = Color(0xFF15151A))
        if (selected) {
            Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter).background(Brand.Ember))
        }
    }
}

@Composable
private fun SplitChip(
    active: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    activeColor: Color = Brand.Ember,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
) {
    val iconBg by animateColorAsState(if (active) activeColor else Color.Transparent, label = "split")
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(Brand.Slate),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(iconBg).pressScale(onToggle, pressedScale = 0.88f),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Box(
            Modifier.fillMaxHeight().pressScale(onOpen).padding(start = 8.dp, end = 11.dp),
            contentAlignment = Alignment.Center,
        ) { label() }
    }
}

@Composable
private fun BeatDots(status: Int, running: Boolean) {
    val beats = if (status >= 0) ((status shr 8) and 0xff).coerceIn(1, 7) else 4
    val cur = if (status >= 0 && running) status and 0xff else -1
    val countIn = status >= 0 && (status shr 16) and 1 == 1
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
        repeat(beats.coerceAtMost(6)) { i ->
            val on = i == cur
            Box(
                Modifier.size(width = if (i == 0) 6.dp else 4.dp, height = 3.dp).clip(CircleShape)
                    .background(
                        when {
                            on && countIn -> Brand.Ivory
                            on -> if (i == 0) Brand.EmberHi else Brand.Ember
                            else -> Brand.Line
                        },
                    ),
            )
        }
    }
}

@Composable
private fun MetronomeGlyph(active: Boolean) {
    val tint = if (active) Color.White else Brand.Ivory
    Canvas(Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.36f, h * 0.08f); lineTo(w * 0.64f, h * 0.08f)
            lineTo(w * 0.86f, h * 0.92f); lineTo(w * 0.14f, h * 0.92f); close()
        }
        drawPath(body, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawLine(tint, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.74f, h * 0.22f), 1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(tint, Offset(w * 0.24f, h * 0.74f), Offset(w * 0.76f, h * 0.74f), 1.4.dp.toPx())
    }
}

@Composable
private fun RecGlyph(recording: Boolean) {
    val t = rememberInfiniteTransition(label = "rec")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "reca")
    Icon(
        Icons.Rounded.FiberManualRecord, "Record",
        tint = if (recording) Color.White.copy(alpha = a) else Color(0xFFFF4D5E),
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun EqBars(playing: Boolean) {
    val t = rememberInfiniteTransition(label = "eq")
    val p1 by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "e1")
    val p2 by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(330), RepeatMode.Reverse), label = "e2")
    val p3 by t.animateFloat(0.4f, 0.95f, infiniteRepeatable(tween(510), RepeatMode.Reverse), label = "e3")
    Canvas(Modifier.size(width = 16.dp, height = 16.dp)) {
        val bars = if (playing) floatArrayOf(p1, p2, p3, p2 * 0.8f) else floatArrayOf(0.35f, 0.6f, 0.45f, 0.3f)
        val bw = size.width / 7f
        bars.forEachIndexed { i, v ->
            val bh = size.height * v
            drawRoundRect(
                if (playing) Brand.EmberHi else Brand.Mist,
                topLeft = Offset(i * bw * 1.9f, size.height - bh),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bw / 2),
            )
        }
    }
}
