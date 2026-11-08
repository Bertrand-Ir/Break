package app.breakpiano.ui.keyboard

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.breakpiano.data.BreakState
import app.breakpiano.data.LabelMode
import app.breakpiano.data.Notation
import app.breakpiano.ui.components.control
import app.breakpiano.ui.components.pressScale
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Manrope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val BLACK_H = 0.62f   // black key length as a fraction of white key length
private const val BLACK_W = 0.58f   // black key width as a fraction of white key width

/** Mutable geometry snapshot shared between the draw pass and the touch handler. */
private class Geo {
    var width = 0f
    var height = 0f
    var start = 0f
    var visible = 14
}

/**
 * One keyboard row: a mini-map scroller over the full 88 keys, then the playable keys.
 */
@Composable
fun KeyboardRow(
    model: KeyboardModel,
    state: BreakState,
    initialStart: Int,
    onStartChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.keysOnScreen.coerceIn(7, PianoLayout.whiteCount)
    val maxStart = (PianoLayout.whiteCount - visible).toFloat()
    val anim = remember { Animatable(initialStart.toFloat().coerceIn(0f, maxStart)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(visible) { anim.snapTo(anim.value.coerceIn(0f, maxStart)) }

    fun settle() = scope.launch {
        val t = anim.value.roundToInt().coerceIn(0, maxStart.toInt())
        anim.animateTo(t.toFloat(), spring(stiffness = Spring.StiffnessMediumLow))
        onStartChanged(t)
    }

    fun stepOctave(dir: Int) = scope.launch {
        val cur = anim.value.roundToInt()
        // jump to the next / previous C so the view always starts on a C
        var t = cur + dir
        while (t in 0..maxStart.toInt() && PianoLayout.whites[t] % 12 != 0) t += dir
        t = t.coerceIn(0, maxStart.toInt())
        anim.animateTo(t.toFloat(), spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f))
        onStartChanged(t)
    }

    Column(modifier) {
        KeyboardMiniMap(
            model = model,
            start = { anim.value },
            visible = visible,
            onScrollTo = { scope.launch { anim.snapTo(it.coerceIn(0f, maxStart)) } },
            onSettle = { settle() },
            onStep = { stepOctave(it) },
            modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 6.dp, vertical = 4.dp),
        )
        PianoKeys(
            model = model,
            state = state,
            start = { anim.value },
            visible = visible,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
fun PianoKeys(
    model: KeyboardModel,
    state: BreakState,
    start: () -> Float,
    visible: Int,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val measurer = rememberTextMeasurer(cacheSize = 192)
    val geo = remember { Geo() }

    val glissando by rememberUpdatedState(state.glissando)
    val dynamic by rememberUpdatedState(state.dynamicVelocity)
    val fixedVel by rememberUpdatedState(state.fixedVelocity)
    val haptics by rememberUpdatedState(state.haptics)

    fun hit(p: Offset): Int {
        val w = geo.width
        val h = geo.height
        if (w <= 0f || p.x < 0f || p.x > w) return -1
        val ww = w / geo.visible
        val fx = p.x / ww + geo.start
        val wi = floor(fx).toInt()
        if (wi !in 0 until PianoLayout.whiteCount) return -1
        if (p.y < h * BLACK_H) {
            for (j in intArrayOf(wi, wi - 1)) {
                if (j !in 0 until PianoLayout.whiteCount) continue
                val bn = PianoLayout.whites[j] + 1
                if (bn > PianoLayout.LAST || !PianoLayout.isBlack(bn)) continue
                val cx = j + 1 + PianoLayout.blackOffset(bn)
                if (fx >= cx - BLACK_W / 2 && fx <= cx + BLACK_W / 2) return bn
            }
        }
        return PianoLayout.whites[wi]
    }

    fun velocity(p: Offset, note: Int): Float {
        if (!dynamic) return fixedVel
        val h = max(1f, geo.height)
        val depth = if (PianoLayout.isBlack(note)) p.y / (h * BLACK_H) else p.y / h
        // Playing nearer the front edge of the key = more leverage = louder, like a real action.
        return (0.36f + 0.64f * depth.coerceIn(0f, 1f)).coerceIn(0.2f, 1f)
    }

    val touch = Modifier.pointerInput(model) {
        val owners = HashMap<Long, Int>()
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    for (c in event.changes) {
                        val id = c.id.value
                        when {
                            c.changedToDownIgnoreConsumed() -> {
                                val n = hit(c.position)
                                owners[id] = n
                                if (n >= 0) {
                                    model.press(n, velocity(c.position, n))
                                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            }
                            c.changedToUpIgnoreConsumed() -> {
                                val n = owners.remove(id) ?: -1
                                if (n >= 0) model.release(n)
                            }
                            c.pressed && glissando -> {
                                val n = hit(c.position)
                                val old = owners[id] ?: -1
                                if (n != old) {
                                    if (old >= 0) model.release(old)
                                    if (n >= 0) {
                                        model.press(n, velocity(c.position, n))
                                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    }
                                    owners[id] = n
                                }
                            }
                        }
                        c.consume()
                    }
                }
            }
        } finally {
            owners.values.forEach { if (it >= 0) model.release(it) }
        }
    }

    Box(
        modifier
            .clipToBounds()
            .then(touch)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val dp1 = 1.dp.toPx()
                val feltH = 5.dp.toPx()
                val ww = w / visible
                val bw = ww * BLACK_W
                val bh = h * BLACK_H
                val gap = max(dp1 * 1.2f, ww * 0.03f)
                val kw = ww - gap
                val r = min(ww * 0.09f, 6.dp.toPx())
                val rb = min(bw * 0.14f, 3.dp.toPx())
                val lipN = min(h * 0.035f, 8.dp.toPx())
                val lipP = lipN * 0.35f

                val whiteBody = Brush.verticalGradient(
                    0f to Color(0xFFD5CFC2), 0.045f to Color(0xFFF3EFE7), 0.5f to Color(0xFFFCFAF6),
                    0.92f to Color(0xFFF3EEE5), 1f to Color(0xFFE5DED1), startY = 0f, endY = h,
                )
                val whitePressed = Brush.verticalGradient(
                    0f to Color(0xFFA9A59E), 0.10f to Color(0xFFC6C2BB), 0.6f to Color(0xFFCFCBC4),
                    1f to Color(0xFFC4C0B8), startY = 0f, endY = h,
                )
                val sideShadowW = max(dp1 * 2.5f, kw * 0.07f)
                val sideShadowL = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent), startX = 0f, endX = sideShadowW)
                val sideShadowR = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)), startX = kw - sideShadowW, endX = kw)
                val lipBrushN = Brush.verticalGradient(listOf(Color(0xFFD8D0C1), Color(0xFFA59B8A)), startY = h - lipN, endY = h)
                val lipBrushP = Brush.verticalGradient(listOf(Color(0xFFC9C0B0), Color(0xFF9C9282)), startY = h - lipP, endY = h)
                val blackBody = Brush.verticalGradient(
                    listOf(Color(0xFF070708), Color(0xFF151518), Color(0xFF0B0B0D)), startY = 0f, endY = bh,
                )
                val inset = bw * 0.11f
                val blackTop = Brush.horizontalGradient(
                    0f to Color(0xFF16161A), 0.35f to Color(0xFF2A2A31), 0.6f to Color(0xFF24242A), 1f to Color(0xFF141417),
                    startX = inset, endX = bw - inset,
                )
                val blackTopPressed = Brush.horizontalGradient(
                    0f to Color(0xFF2A2A30), 0.4f to Color(0xFF3B3B42), 1f to Color(0xFF26262B),
                    startX = inset, endX = bw - inset,
                )
                val bevN = bh * 0.10f
                val bevP = bh * 0.045f
                val bevelN = Brush.verticalGradient(listOf(Color(0xFF41414B), Color(0xFF131316)), startY = bh - bevN, endY = bh)
                val bevelP = Brush.verticalGradient(listOf(Color(0xFF33333B), Color(0xFF101013)), startY = bh - bevP, endY = bh)
                val gloss = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0f)), startY = 0f, endY = bh * 0.8f)
                val feltBrush = Brush.verticalGradient(listOf(Color(0xFF5E1322), Color(0xFF3A0A14)), startY = 0f, endY = feltH)
                val feltShadow = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent), startY = feltH, endY = feltH + 12.dp.toPx())
                val bevelPath = Path()

                val labelSize = min(ww * 0.30f, 13.dp.toPx()).toSp()
                val labelStyleC = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = labelSize, color = Color(0xFF5A5347))
                val labelStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = labelSize, color = Color(0xFF9C9486))
                val octStyle = labelStyle.copy(fontSize = labelSize * 0.68f, fontWeight = FontWeight(700))
                val blackLabelStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = min(bw * 0.30f, 10.dp.toPx()).toSp(), color = Color.White.copy(alpha = 0.55f))

                onDrawBehind {
                    @Suppress("UNUSED_VARIABLE") val v = model.version
                    val s = start()
                    geo.width = w; geo.height = h; geo.start = s; geo.visible = visible
                    val mode = state.labelMode
                    val solfege = state.notation == Notation.Solfege
                    val flats = state.useFlats
                    val showOct = state.showOctave

                    drawRect(Color(0xFF050506))

                    val first = max(0, floor(s).toInt())
                    val last = min(PianoLayout.whiteCount - 1, ceil(s + visible).toInt())

                    // ── white keys ──
                    for (j in first..last) {
                        val note = PianoLayout.whites[j]
                        val left = (j - s) * ww + gap / 2
                        val pressed = model.down[note] > 0
                        val lip = if (pressed) lipP else lipN

                        drawRoundRect(if (pressed) whitePressed else whiteBody, Offset(left, -r), Size(kw, h + r), CornerRadius(r))
                        clipRect(left, h - lip, left + kw, h) {
                            drawRoundRect(if (pressed) lipBrushP else lipBrushN, Offset(left, h - lip - r), Size(kw, lip + r), CornerRadius(r))
                        }
                        drawLine(Color.White.copy(alpha = if (pressed) 0.35f else 0.7f), Offset(left + r * 0.6f, h - lip), Offset(left + kw - r * 0.6f, h - lip), dp1)
                        if (pressed) {
                            // Sunk below its neighbours: they cast shadow onto both edges,
                            // and the fallboard casts a deeper shadow across the top.
                            translate(left, 0f) {
                                drawRect(sideShadowL, size = Size(sideShadowW, h - lip))
                                drawRect(sideShadowR, Offset(kw - sideShadowW, 0f), Size(sideShadowW, h - lip))
                            }
                            drawRect(
                                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent), startY = feltH, endY = feltH + h * 0.22f),
                                Offset(left, 0f), Size(kw, feltH + h * 0.22f),
                            )
                        } else {
                            drawRect(Color.White.copy(alpha = 0.6f), Offset(left, 0f), Size(dp1, h - lip - r * 0.5f))
                            drawRect(Color.Black.copy(alpha = 0.07f), Offset(left + kw - dp1 * 1.5f, 0f), Size(dp1 * 1.5f, h - lip - r * 0.5f))
                        }

                        // label
                        val pc = note % 12
                        val showLabel = when (mode) {
                            LabelMode.Off -> false
                            LabelMode.C -> pc == 0
                            else -> true
                        }
                        val labelBottom = h - lipN - 7.dp.toPx() + if (pressed) 2.dp.toPx() else 0f
                        var labelTop = labelBottom
                        if (showLabel) {
                            val name = PianoLayout.name(note, solfege, flats)
                            val base = if (pc == 0) labelStyleC else labelStyle
                            val main = measurer.measure(name, base)
                            val oct = if (showOct) measurer.measure(PianoLayout.octave(note).toString(), octStyle) else null
                            val totalW = main.size.width + (oct?.size?.width ?: 0)
                            val tx = left + (kw - totalW) / 2
                            labelTop = labelBottom - main.size.height
                            drawText(main, topLeft = Offset(tx, labelTop))
                            if (oct != null) drawText(oct, topLeft = Offset(tx + main.size.width, labelBottom - oct.size.height - main.size.height * 0.08f))
                        }
                        if (note == 60) {
                            drawCircle(Color(0xFF8E877B), radius = 2.2f * dp1, center = Offset(left + kw / 2, labelTop - 6 * dp1))
                        }
                    }

                    // ── black keys ──
                    for (j in max(0, first - 1)..last) {
                        val bn = PianoLayout.whites[j] + 1
                        if (bn > PianoLayout.LAST || !PianoLayout.isBlack(bn)) continue
                        val cx = (j + 1 + PianoLayout.blackOffset(bn) - s) * ww
                        val bx = cx - bw / 2
                        if (bx > w || bx + bw < 0) continue
                        val pressed = model.down[bn] > 0
                        val bev = if (pressed) bevP else bevN
                        translate(bx, 0f) {
                            val sh = if (pressed) 1.5f * dp1 else 3f * dp1
                            drawRoundRect(Color.Black.copy(alpha = 0.28f), Offset(sh, -r), Size(bw, bh + sh * 1.6f + r), CornerRadius(rb * 1.5f))
                            drawRoundRect(blackBody, Offset(0f, -r), Size(bw, bh + r), CornerRadius(rb))
                            drawRoundRect(if (pressed) blackTopPressed else blackTop, Offset(inset, -r), Size(bw - inset * 2, bh - bev + r), CornerRadius(rb * 0.8f))
                            if (pressed) {
                                // pushed down between the white keys: shadow falls over its upper part
                                drawRect(
                                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), startY = feltH, endY = bh * 0.35f),
                                    Offset(0f, 0f), Size(bw, bh * 0.35f),
                                )
                            }
                            if (!pressed) drawRoundRect(gloss, Offset(inset + (bw - 2 * inset) * 0.16f, 0f), Size((bw - 2 * inset) * 0.2f, (bh - bev) * 0.86f), CornerRadius(rb))
                            bevelPath.reset()
                            bevelPath.moveTo(inset, bh - bev)
                            bevelPath.lineTo(bw - inset, bh - bev)
                            bevelPath.lineTo(bw - bw * 0.035f, bh - dp1 * 0.8f)
                            bevelPath.lineTo(bw * 0.035f, bh - dp1 * 0.8f)
                            bevelPath.close()
                            drawPath(bevelPath, if (pressed) bevelP else bevelN)
                            drawLine(Color.White.copy(alpha = if (pressed) 0.08f else 0.16f), Offset(inset, bh - bev), Offset(bw - inset, bh - bev), dp1)
                            if (mode == LabelMode.All) {
                                val t = measurer.measure(PianoLayout.name(bn, solfege, flats), blackLabelStyle)
                                drawText(t, topLeft = Offset((bw - t.size.width) / 2, bh - bev - t.size.height - 4 * dp1))
                            }
                        }
                    }

                    // ── felt strip ──
                    drawRect(feltShadow, Offset(0f, feltH), Size(w, 12.dp.toPx()))
                    drawRect(feltBrush, size = Size(w, feltH))
                }
            },
    )
}

@Composable
fun KeyboardMiniMap(
    model: KeyboardModel,
    start: () -> Float,
    visible: Int,
    onScrollTo: (Float) -> Unit,
    onSettle: () -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MiniArrow(left = true) { onStep(-1) }
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 6.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF0A0A0D))
                .pointerInput(visible) {
                    fun toStart(x: Float) = x / size.width * PianoLayout.whiteCount - visible / 2f
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onScrollTo(toStart(down.position.x))
                        drag(down.id) { c ->
                            onScrollTo(toStart(c.position.x))
                            c.consume()
                        }
                        onSettle()
                    }
                },
        ) {
            @Suppress("UNUSED_VARIABLE") val v = model.version
            val w = size.width
            val h = size.height
            val kw = w / PianoLayout.whiteCount
            val s = start()
            for (j in 0 until PianoLayout.whiteCount) {
                val n = PianoLayout.whites[j]
                val c = if (model.down[n] > 0) Color(0xFF8C8880) else Color(0xFFD9D3C8)
                drawRect(c, Offset(j * kw + 0.5f, 1f), Size(kw - 1f, h - 2f))
            }
            for (j in 0 until PianoLayout.whiteCount) {
                val bn = PianoLayout.whites[j] + 1
                if (bn > PianoLayout.LAST || !PianoLayout.isBlack(bn)) continue
                val cx = (j + 1) * kw
                val c = if (model.down[bn] > 0) Color(0xFF5E5E66) else Color(0xFF16161A)
                drawRect(c, Offset(cx - kw * 0.32f, 1f), Size(kw * 0.64f, h * 0.6f))
            }
            // dim what's off-screen, frame what's on-screen
            val x0 = s * kw
            val x1 = (s + visible) * kw
            drawRect(Color.Black.copy(alpha = 0.55f), Offset.Zero, Size(x0, h))
            drawRect(Color.Black.copy(alpha = 0.55f), Offset(x1, 0f), Size(w - x1, h))
            drawRoundRect(Brand.Ivory, Offset(x0 + 1f, 1f), Size(x1 - x0 - 2f, h - 2f), CornerRadius(3.dp.toPx()), style = Stroke(1.6.dp.toPx()))
        }
        MiniArrow(left = false) { onStep(1) }
    }
}

@Composable
private fun MiniArrow(left: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 34.dp, height = 22.dp)
            .control(false, RoundedCornerShape(6.dp))
            .pressScale(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (left) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = if (left) "Octave down" else "Octave up",
            tint = Brand.Ivory,
            modifier = Modifier.size(18.dp),
        )
    }
}
