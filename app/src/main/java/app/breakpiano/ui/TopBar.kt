package app.breakpiano.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.breakpiano.audio.Beats
import app.breakpiano.audio.NativeEngine
import app.breakpiano.audio.Recorder
import app.breakpiano.data.BreakState
import app.breakpiano.data.Panel
import app.breakpiano.ui.components.IconChip
import app.breakpiano.ui.components.Led
import app.breakpiano.ui.components.Stepper
import app.breakpiano.ui.components.contentColor
import app.breakpiano.ui.components.control
import app.breakpiano.ui.components.pressScale
import app.breakpiano.ui.components.well
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type

val TopBarTwoRowHeight = 108.dp
val TopBarOneRowHeight = 58.dp

/**
 * Two rows for room — sound on top, performance underneath. In dual-keyboard mode it folds into
 * a single horizontally scrollable row so the keys get the height.
 */
@Composable
fun TopBar(
    state: BreakState,
    recorder: Recorder,
    userBeatName: String?,
    updateBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    val toggle: (Panel) -> Unit = { p -> state.panel = if (state.panel == p) Panel.None else p }
    if (state.dualKeyboard) {
        Row(
            modifier.fillMaxWidth().height(TopBarOneRowHeight).horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BreakMark(26.dp)
            InstrumentButton(state, compact = true) { toggle(Panel.Instruments) }
            TransposeControl(state)
            PedalButton(state, showLabel = false)
            MetronomeGroup(state) { toggle(Panel.Metronome) }
            BeatGroup(state, userBeatName, compact = true) { toggle(Panel.Beats) }
            LooperGroup(state)
            RecorderGroup(recorder) { toggle(Panel.Recordings) }
            IconChip(Icons.Rounded.Tune, "Settings", active = state.panel == Panel.Settings, badge = updateBadge) { toggle(Panel.Settings) }
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(TopBarTwoRowHeight).padding(horizontal = 12.dp, vertical = 7.dp)) {
        val compact = maxWidth < 720.dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (compact) BreakMark(26.dp) else BreakWordmark(markSize = 24.dp, fontSize = 21.sp)
                Spacer(Modifier.width(4.dp))
                InstrumentButton(state, compact = compact) { toggle(Panel.Instruments) }
                Spacer(Modifier.weight(1f))
                TransposeControl(state)
                PedalButton(state, showLabel = true)
                IconChip(Icons.Rounded.Tune, "Settings", active = state.panel == Panel.Settings, badge = updateBadge, size = 44.dp) { toggle(Panel.Settings) }
            }
            Row(
                Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetronomeGroup(state) { toggle(Panel.Metronome) }
                BeatGroup(state, userBeatName, compact = compact) { toggle(Panel.Beats) }
                LooperGroup(state)
                RecorderGroup(recorder) { toggle(Panel.Recordings) }
            }
        }
    }
}

// ── items ────────────────────────────────────────────────────────────────────

@Composable
private fun InstrumentButton(state: BreakState, compact: Boolean, onClick: () -> Unit) {
    val inst = state.instrument
    val layer = state.layer
    Row(
        Modifier.height(44.dp).control(state.panel == Panel.Instruments).pressScale(onClick).padding(start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).well(RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            InstrumentArt(artFor(inst), Brand.Ivory, Modifier.size(28.dp), weight = 3.4f)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.widthIn(max = if (compact) 110.dp else 170.dp)) {
            Text(inst.name, style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(layer?.let { "+ ${it.name}" } ?: inst.family.title, style = Type.Caption, color = Brand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Brand.Mist, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TransposeControl(state: BreakState) {
    Stepper(
        text = if (state.transpose == 0) "0" else "%+d".format(state.transpose),
        onMinus = { state.transpose = (state.transpose - 1).coerceAtLeast(-12) },
        onPlus = { state.transpose = (state.transpose + 1).coerceAtMost(12) },
        minWidth = 54.dp,
        label = "TRANSPOSE",
        modifier = Modifier.height(44.dp),
    )
}

@Composable
private fun PedalButton(state: BreakState, showLabel: Boolean) {
    Row(
        Modifier.height(44.dp).control(state.pedal).pressScale({ state.togglePedal() }).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Led(state.pedal)
        PedalGlyph(contentColor(state.pedal), pressed = state.pedal)
        if (showLabel) Text("Pedal", style = Type.Label, color = contentColor(state.pedal))
    }
}

/** A piano damper pedal seen from the side; it tilts down when engaged. */
@Composable
private fun PedalGlyph(tint: Color, pressed: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        val sw = 1.8.dp.toPx()
        val tipY = if (pressed) h * 0.62f else h * 0.42f
        drawLine(tint, Offset(w * 0.1f, h * 0.88f), Offset(w * 0.9f, h * 0.88f), sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.78f, h * 0.88f), Offset(w * 0.78f, h * 0.55f), sw, cap = StrokeCap.Round)
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.86f, h * 0.52f)
            lineTo(w * 0.22f, tipY)
            quadraticTo(w * 0.08f, tipY + h * 0.02f, w * 0.12f, tipY + h * 0.12f)
            lineTo(w * 0.86f, h * 0.60f)
            close()
        }
        drawPath(p, tint)
    }
}

/** A recessed tray holding a toggle on the left and a label/opener on the right. */
@Composable
private fun Group(
    active: Boolean,
    onToggle: () -> Unit,
    onOpen: (() -> Unit)?,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
) {
    Row(Modifier.height(44.dp).well(RoundedCornerShape(13.dp)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).control(active, RoundedCornerShape(10.dp)).pressScale(onToggle, pressedScale = 0.9f), contentAlignment = Alignment.Center) { icon() }
        Box(
            Modifier.fillMaxHeight().then(if (onOpen != null) Modifier.pressScale(onOpen) else Modifier).padding(start = 9.dp, end = 10.dp),
            contentAlignment = Alignment.Center,
        ) { label() }
    }
}

@Composable
private fun MetronomeGroup(state: BreakState, onOpen: () -> Unit) {
    Group(
        active = state.metronomeOn,
        onToggle = { state.toggleMetronome() },
        onOpen = onOpen,
        icon = { MetronomeGlyph(contentColor(state.metronomeOn)) },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${state.bpm} BPM", style = Type.Mono, color = Brand.Ivory)
            BeatDots(state.beatStatus, running = state.metronomeOn || state.beatPlaying)
        }
    }
}

@Composable
private fun BeatGroup(state: BreakState, userBeatName: String?, compact: Boolean, onOpen: () -> Unit) {
    val active = state.beatPlaying || state.userTrackPlaying
    Group(
        active = active,
        onToggle = {
            when {
                state.userTrackPlaying -> state.playUserTrack(false)
                state.beatPlaying -> state.stopBeat()
                else -> state.playBeat()
            }
        },
        onOpen = onOpen,
        icon = { Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, "Play beat", tint = contentColor(active), modifier = Modifier.size(20.dp)) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EqBars(playing = active)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("BEAT", style = Type.Caption.copy(fontSize = 8.5.sp), color = Brand.Dim)
                val name = if (state.userTrackPlaying && userBeatName != null) userBeatName else (Beats.byId(state.beatStyleId)?.name ?: "Beats")
                Text(name, style = Type.Label, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (compact) 84.dp else 120.dp))
            }
        }
    }
}

@Composable
private fun LooperGroup(state: BreakState) {
    val st = state.loopState
    val blink = rememberInfiniteTransition(label = "blink").animateFloat(1f, 0.25f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "b")
    val b by blink
    Row(Modifier.height(44.dp).well(RoundedCornerShape(13.dp)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
        // main button: record → loop → overdub → loop …
        val active = st in 1..4 || st == 6
        Box(
            Modifier.size(38.dp).control(active, RoundedCornerShape(10.dp)).pressScale({ state.looper(NativeEngine.LOOP_MAIN) }, pressedScale = 0.9f),
            contentAlignment = Alignment.Center,
        ) {
            when (st) {
                1 -> Icon(Icons.Rounded.FiberManualRecord, "Waiting for the bar", tint = Brand.Rec, modifier = Modifier.size(16.dp).alpha(b))
                2 -> Icon(Icons.Rounded.FiberManualRecord, "Recording loop", tint = Brand.Rec, modifier = Modifier.size(18.dp))
                3, 4 -> {
                    LoopRing(state.loopPos, if (st == 4) Brand.Rec else Brand.Ivory, Modifier.size(30.dp))
                    Icon(Icons.Rounded.Loop, null, tint = if (st == 4) Brand.Rec else Color.White, modifier = Modifier.size(15.dp))
                }
                5 -> Icon(Icons.Rounded.PlayArrow, "Play loop", tint = Brand.Ivory, modifier = Modifier.size(20.dp))
                6 -> Icon(Icons.Rounded.PlayArrow, "Waiting for the bar", tint = Brand.Ivory, modifier = Modifier.size(20.dp).alpha(b))
                else -> Icon(Icons.Rounded.Loop, "Record a loop", tint = Brand.Ivory, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.padding(horizontal = 9.dp).width(74.dp)) {
            Text("LOOPER", style = Type.Caption.copy(fontSize = 8.5.sp), color = Brand.Dim)
            val label = when (st) {
                1, 6 -> "Next bar…"
                2 -> "Rec %.1fs".format(state.loopRecSec)
                3 -> "Tap: dub"
                4 -> "Dubbing"
                5 -> "%.1fs loop".format(state.loopLenSec)
                else -> "Tap: record"
            }
            Text(label, style = Type.Label, color = if (st == 2 || st == 4) Brand.Rec else Brand.Ivory, maxLines = 1)
        }
        if (st != 0) {
            val stopIcon = if (st == 5 || st == 6) Icons.Rounded.PlayArrow else Icons.Rounded.Stop
            Box(Modifier.size(38.dp).control(false, RoundedCornerShape(10.dp)).pressScale({ state.looper(NativeEngine.LOOP_STOP) }, pressedScale = 0.9f), contentAlignment = Alignment.Center) {
                Icon(stopIcon, if (stopIcon == Icons.Rounded.Stop) "Stop loop" else "Play loop", tint = Brand.Ivory, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(38.dp).control(false, RoundedCornerShape(10.dp)).pressScale({ state.looper(NativeEngine.LOOP_CLEAR) }, pressedScale = 0.9f), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Clear loop", tint = Brand.Mist, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun RecorderGroup(recorder: Recorder, onOpen: () -> Unit) {
    Group(
        active = recorder.isRecording,
        onToggle = { recorder.toggle() },
        onOpen = onOpen,
        icon = { RecGlyph(recorder.isRecording) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("RECORD", style = Type.Caption.copy(fontSize = 8.5.sp), color = Brand.Dim)
                if (recorder.isRecording) {
                    val s = recorder.elapsedMs / 1000
                    Text("%d:%02d".format(s / 60, s % 60), style = Type.Label, color = Brand.Rec)
                } else Text("${recorder.takes.size} takes", style = Type.Label, color = Brand.Ivory)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.LibraryMusic, "Recordings", tint = Brand.Mist, modifier = Modifier.size(16.dp))
        }
    }
}

// ── small glyphs ─────────────────────────────────────────────────────────────

@Composable
private fun LoopRing(progress: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = 2.2.dp.toPx()
        drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w))
        drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
private fun BeatDots(status: Int, running: Boolean) {
    val beats = if (status >= 0) ((status shr 8) and 0xff).coerceIn(1, 7) else 4
    val cur = if (status >= 0 && running) status and 0xff else -1
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 3.dp)) {
        repeat(beats.coerceAtMost(6)) { i ->
            Canvas(Modifier.size(width = if (i == 0) 7.dp else 5.dp, height = 3.dp)) {
                drawRoundRect(if (i == cur) Brand.Ivory else Color.White.copy(alpha = 0.14f), cornerRadius = CornerRadius(size.height))
            }
        }
    }
}

@Composable
private fun MetronomeGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width; val h = size.height
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.36f, h * 0.08f); lineTo(w * 0.64f, h * 0.08f)
            lineTo(w * 0.86f, h * 0.92f); lineTo(w * 0.14f, h * 0.92f); close()
        }
        drawPath(body, tint, style = Stroke(width = 1.8.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawLine(tint, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.74f, h * 0.22f), 1.8.dp.toPx(), cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.24f, h * 0.74f), Offset(w * 0.76f, h * 0.74f), 1.4.dp.toPx())
    }
}

@Composable
private fun RecGlyph(recording: Boolean) {
    val t = rememberInfiniteTransition(label = "rec")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "reca")
    Icon(Icons.Rounded.FiberManualRecord, "Record", tint = if (recording) Brand.Rec.copy(alpha = a) else Brand.Rec, modifier = Modifier.size(18.dp))
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
            drawRoundRect(if (playing) Brand.Ivory else Brand.Dim, Offset(i * bw * 1.9f, size.height - bh), Size(bw, bh), CornerRadius(bw / 2))
        }
    }
}
