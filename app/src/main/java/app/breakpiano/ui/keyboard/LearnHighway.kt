package app.breakpiano.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.breakpiano.data.BreakState
import app.breakpiano.data.Notation
import app.breakpiano.learn.Hands
import app.breakpiano.learn.Learner
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Manrope
import kotlin.math.max
import kotlin.math.min

/**
 * Falling-notes lane that sits right above the keys and shares their exact geometry:
 * each bar lands on the key you should press, at the moment you should press it.
 */
@Composable
fun LearnHighway(learner: Learner, state: BreakState, start: () -> Float, visible: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    Canvas(modifier.clipToBounds()) {
        @Suppress("UNUSED_VARIABLE") val v = learner.version
        val w = size.width
        val h = size.height
        val s = start()
        val ww = w / visible
        val pos = learner.position
        val look = 3.2f                       // seconds of music visible above the keys
        val solfege = state.notation == Notation.Solfege
        val flats = state.useFlats

        drawRect(Color(0xFF0C0C10))
        // octave guides on every C, faint lines on every white key
        val first = max(0, s.toInt() - 1)
        val last = min(PianoLayout.whiteCount - 1, (s + visible).toInt() + 1)
        for (j in first..last) {
            val x = (j - s) * ww
            val isC = PianoLayout.whites[j] % 12 == 0
            drawRect(Color.White.copy(alpha = if (isC) 0.07f else 0.025f), Offset(x, 0f), Size(1.dp.toPx(), h))
        }

        val r = CornerRadius(min(ww * 0.18f, 5.dp.toPx()))
        val labelStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = (min(ww * 0.32f, 12.dp.toPx())).toSp(), color = Color(0xFF0B0B0F))
        for (n in learner.notes) {
            if (n.start > pos + look) break
            if (n.end < pos - 0.05f) continue
            val black = PianoLayout.isBlack(n.pitch)
            val x: Float
            val bw: Float
            if (black) {
                val j = PianoLayout.whiteIndexOf(n.pitch - 1)
                val cx = (j + 1 + PianoLayout.blackOffset(n.pitch) - s) * ww
                bw = ww * 0.52f; x = cx - bw / 2
            } else {
                val j = PianoLayout.whiteIndexOf(n.pitch)
                x = (j - s) * ww + ww * 0.1f; bw = ww * 0.8f
            }
            if (x > w || x + bw < 0) continue
            val yBottom = h - (n.start - pos) / look * h
            val yTop = h - (n.end - pos) / look * h
            val top = max(0f, yTop)
            val bottom = min(h, yBottom)
            if (bottom - top < 1f) continue
            val mine = when (learner.hands) { Hands.Both -> true; Hands.Right -> n.hand == 0; Hands.Left -> n.hand == 1 }
            val base = if (n.hand == 1) Brand.HandLeft else Brand.HandRight
            val now = pos >= n.start && pos <= n.end
            val c = when {
                !mine -> base.copy(alpha = 0.22f)
                now -> base
                else -> base.copy(alpha = 0.85f)
            }
            drawRoundRect(c, Offset(x, top), Size(bw, bottom - top), r)
            if (mine && bottom - top > 18.dp.toPx() && bw > 14.dp.toPx()) {
                val t = measurer.measure(PianoLayout.name(n.pitch, solfege, flats), labelStyle)
                if (t.size.width < bw) drawText(t, topLeft = Offset(x + (bw - t.size.width) / 2, bottom - t.size.height - 3.dp.toPx()))
            }
        }
        // the strike line where notes meet the keys
        drawRect(Color.White.copy(alpha = if (learner.waiting) 0.55f else 0.22f), Offset(0f, h - 2.dp.toPx()), Size(w, 2.dp.toPx()))
    }
}
