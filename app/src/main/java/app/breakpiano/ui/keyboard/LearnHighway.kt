package app.breakpiano.ui.keyboard

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.breakpiano.data.BreakState
import app.breakpiano.data.Notation
import app.breakpiano.learn.Grade
import app.breakpiano.learn.Hands
import app.breakpiano.learn.Learner
import app.breakpiano.ui.components.GhostButton
import app.breakpiano.ui.components.PrimaryButton
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Manrope
import app.breakpiano.ui.theme.Type
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val WrongRed = Color(0xFFFF4D57)

private fun handColor(hand: Int) = if (hand == 1) Brand.HandLeft else Brand.HandRight
private fun lighten(c: Color, f: Float) = Color(c.red + (1 - c.red) * f, c.green + (1 - c.green) * f, c.blue + (1 - c.blue) * f, c.alpha)

/** x position and width of a key in the highway, matching the keys below exactly. */
private fun keyLane(pitch: Int, s: Float, ww: Float): Pair<Float, Float> =
    if (PianoLayout.isBlack(pitch)) {
        val j = PianoLayout.whiteIndexOf(pitch - 1)
        val cx = (j + 1 + PianoLayout.blackOffset(pitch) - s) * ww
        val bw = ww * 0.52f
        (cx - bw / 2) to bw
    } else {
        val j = PianoLayout.whiteIndexOf(pitch)
        ((j - s) * ww + ww * 0.1f) to ww * 0.8f
    }

/**
 * Falling notes that land exactly on their keys. Glossy notes, a guiding lane for what's next,
 * a glowing impact as each note reaches its key, sparkles on hits, and live feedback.
 */
@Composable
fun LearnHighway(learner: Learner, state: BreakState, start: () -> Float, visible: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 96)
    Box(modifier.clipToBounds()) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val v = learner.version
            val now = SystemClock.uptimeMillis()
            val w = size.width
            val h = size.height
            val s = start()
            val ww = w / visible
            val pos = learner.position
            val look = 3.0f / max(0.5f, learner.speed).coerceAtMost(1f)   // slower songs show a little more ahead
            val solfege = state.notation == Notation.Solfege
            val flats = state.useFlats
            val dp1 = 1.dp.toPx()

            // stage
            drawRect(Brush.verticalGradient(listOf(Color(0xFF09090D), Color(0xFF111118)), endY = h))
            val first = max(0, s.toInt() - 1)
            val last = min(PianoLayout.whiteCount - 1, (s + visible).toInt() + 1)
            for (j in first..last) {
                val x = (j - s) * ww
                val isC = PianoLayout.whites[j] % 12 == 0
                drawRect(Color.White.copy(alpha = if (isC) 0.08f else 0.025f), Offset(x, 0f), Size(dp1, h))
            }

            // lanes for what's next: a soft column from the note down to its key
            for (p in learner.nextPitches) {
                val (x, bw) = keyLane(p, s, ww)
                if (x > w || x + bw < 0) continue
                val c = handColor(learner.guideHand[p])
                drawRect(Brush.verticalGradient(listOf(c.copy(alpha = 0f), c.copy(alpha = 0.16f)), startY = 0f, endY = h), Offset(x, 0f), Size(bw, h))
            }

            // notes
            val r = CornerRadius(min(ww * 0.2f, 6.dp.toPx()))
            val labelStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = (min(ww * 0.32f, 13.dp.toPx())).toSp(), color = Color.White)
            for (n in learner.notes) {
                if (n.start > pos + look) break
                if (n.end < pos - 0.05f) continue
                val (x, bw) = keyLane(n.pitch, s, ww)
                if (x > w || x + bw < 0) continue
                val yBottom = h - (n.start - pos) / look * h
                val yTop = h - (n.end - pos) / look * h
                val top = max(-10f, yTop)
                val bottom = min(h, yBottom)
                if (bottom - top < 1f) continue
                val mine = when (learner.hands) { Hands.Both -> true; Hands.Right -> n.hand == 0; Hands.Left -> n.hand == 1 }
                val base = handColor(n.hand)
                if (!mine) {
                    drawRoundRect(base.copy(alpha = 0.18f), Offset(x, top), Size(bw, bottom - top), r)
                    continue
                }
                val isNext = n.pitch in learner.nextPitches && n.start >= pos - 0.05f
                val playingNow = pos >= n.start && pos <= n.end
                val approach = (1f - (n.start - pos) / look).coerceIn(0f, 1f)
                // glow behind notes that are close or sounding
                if (isNext || playingNow) {
                    val g = 4.dp.toPx()
                    drawRoundRect(base.copy(alpha = 0.35f), Offset(x - g, top - g), Size(bw + 2 * g, bottom - top + 2 * g), CornerRadius(r.x + g))
                }
                // glossy body: lit top edge → saturated base
                val alpha = if (isNext || playingNow) 1f else 0.55f + 0.35f * approach
                drawRoundRect(
                    Brush.verticalGradient(listOf(lighten(base, 0.45f).copy(alpha = alpha), base.copy(alpha = alpha)), startY = top, endY = bottom),
                    Offset(x, top), Size(bw, bottom - top), r,
                )
                // specular stripe + bright rim on top
                drawRoundRect(Color.White.copy(alpha = 0.28f * alpha), Offset(x + bw * 0.14f, top + 3 * dp1), Size(bw * 0.16f, max(0f, bottom - top - 6 * dp1)), CornerRadius(bw * 0.08f))
                drawRoundRect(Color.White.copy(alpha = 0.5f * alpha), Offset(x + 2 * dp1, top + 1.5f * dp1), Size(bw - 4 * dp1, 2 * dp1), CornerRadius(dp1))
                if (bottom - top > 20.dp.toPx() && bw > 14.dp.toPx()) {
                    val t = measurer.measure(PianoLayout.name(n.pitch, solfege, flats), labelStyle)
                    if (t.size.width < bw) drawText(t, topLeft = Offset(x + (bw - t.size.width) / 2, bottom - t.size.height - 4 * dp1))
                }
            }

            // the strike line where notes enter the keys
            val waitPulse = if (learner.waiting) 0.5f + 0.5f * sin(now / 140.0).toFloat() else 0f
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f + 0.2f * waitPulse)), startY = h - 26.dp.toPx(), endY = h), Offset(0f, h - 26.dp.toPx()), Size(w, 26.dp.toPx()))
            drawRect(Color.White.copy(alpha = 0.35f + 0.5f * waitPulse), Offset(0f, h - 2 * dp1), Size(w, 2 * dp1))

            // impact glow where a sounding note pours into its key
            for (n in learner.notes) {
                if (n.start > pos) break
                if (n.end < pos) continue
                val (x, bw) = keyLane(n.pitch, s, ww)
                val c = handColor(n.hand)
                drawCircleGlow(Offset(x + bw / 2, h), bw * 1.1f, c, 0.55f)
            }

            // sparkles and red bursts from feedback events
            for (f in learner.fx) {
                val age = (now - f.at) / 1000f
                if (age > 0.7f) continue
                val (x, bw) = keyLane(f.pitch, s, ww)
                val cx = x + bw / 2
                when (f.grade) {
                    Grade.Wrong -> drawCircleGlow(Offset(cx, h), bw * (0.8f + age), WrongRed, 0.6f * (1 - age / 0.7f))
                    Grade.Miss -> {}
                    else -> {
                        val c = handColor(f.hand)
                        drawCircleGlow(Offset(cx, h), bw * (1f + age * 1.5f), c, 0.5f * (1 - age / 0.7f))
                        for (k in 0 until 9) {
                            val a = Math.PI * (0.15 + 0.7 * k / 8.0)
                            val dist = (60.dp.toPx() * age + 6.dp.toPx())
                            val px = cx + (cos(a) * dist).toFloat()
                            val py = h - (sin(a) * dist).toFloat() + 80.dp.toPx() * age * age
                            drawCircle((if (k % 2 == 0) Color.White else lighten(c, 0.3f)).copy(alpha = (1 - age / 0.7f).coerceIn(0f, 1f)), (2.6f - age * 2f).coerceAtLeast(0.6f) * dp1, Offset(px, py))
                        }
                    }
                }
            }

            // grade pop + streak
            val g = learner.lastGrade
            val gAge = (now - learner.lastGradeAt) / 1000f
            if (g != null && gAge < 0.8f) {
                val c = when (g) { Grade.Perfect -> Color.White; Grade.Great -> Brand.HandRight; Grade.Good -> Brand.HandLeft; Grade.Miss -> Brand.Mist; Grade.Wrong -> WrongRed }
                val scale = 1f + 0.25f * (1 - min(1f, gAge / 0.15f))
                val style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = (22 * scale).sp, color = c.copy(alpha = (1 - gAge / 0.8f).coerceIn(0f, 1f)))
                val t = measurer.measure(g.label, style)
                drawText(t, topLeft = Offset((w - t.size.width) / 2, h * 0.18f - gAge * 14.dp.toPx()))
            }
            if (learner.streak >= 3) {
                val st = measurer.measure("${learner.streak} in a row", TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 14.sp, color = Brand.Ivory))
                drawText(st, topLeft = Offset(w - st.size.width - 14.dp.toPx(), 10.dp.toPx()))
            }

            // count-in
            if (pos < 0f && learner.playing) {
                val n = ceil(-pos).toInt().coerceIn(1, 3)
                val frac = (-pos) - (n - 1)
                val style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = (54 + 18 * frac).sp, color = Color.White.copy(alpha = (0.35f + 0.65f * frac).coerceIn(0f, 1f)))
                val t = measurer.measure("$n", style)
                drawText(t, topLeft = Offset((w - t.size.width) / 2, (h - t.size.height) / 2))
            } else if (pos < 0f && !learner.playing && !learner.finished) {
                val t = measurer.measure("Press ▶ — the notes fall onto the keys to play", TextStyle(fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 15.sp, color = Brand.Mist))
                drawText(t, topLeft = Offset((w - t.size.width) / 2, (h - t.size.height) / 2))
            }
        }

        if (learner.finished) Results(learner, Modifier.align(Alignment.Center))
    }
}

private fun DrawScope.drawCircleGlow(center: Offset, radius: Float, color: Color, alpha: Float) {
    if (radius <= 0f || alpha <= 0f) return
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), center = center, radius = radius), radius, center)
}

@Composable
private fun Results(learner: Learner, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(Brand.Graphite.copy(alpha = 0.96f))
            .border(1.dp, Brand.Line, RoundedCornerShape(22.dp)).padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (learner.stars >= 2) "Beautifully played!" else if (learner.stars == 1) "Nice — keep going!" else "Good start!", style = Type.Title, color = Brand.Ivory)
        Spacer(Modifier.height(6.dp))
        Row {
            for (i in 1..3) Text("★", fontSize = 34.sp, color = if (i <= learner.stars) Color(0xFFFFD36B) else Brand.Line)
        }
        Spacer(Modifier.height(4.dp))
        Text("${learner.accuracy}% accuracy · ${learner.hits} notes · best streak ${learner.bestStreak}", style = Type.Body, color = Brand.Mist)
        Spacer(Modifier.height(14.dp))
        Row {
            GhostButton("Done", Modifier.height(42.dp)) { learner.close() }
            Spacer(Modifier.width(10.dp))
            PrimaryButton("Play again", Modifier.height(42.dp), icon = Icons.Rounded.Replay) { learner.restart(); learner.play() }
        }
    }
}
