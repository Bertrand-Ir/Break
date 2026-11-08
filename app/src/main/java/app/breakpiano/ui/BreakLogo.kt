package app.breakpiano.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type

/**
 * The Break mark: three ivory keys with the black keys cut out as negative space.
 * The middle key glows ember and sits lower — it's the key being played, the "break".
 * Same geometry as the launcher icon (108-unit viewport, mark bounds x 31.5–76.5, y 30–80).
 */
private fun keyPath(vararg pts: Float): Path = Path().apply {
    // pts encodes M/H/V/Q commands generated from the vector icon
    var i = 0
    while (i < pts.size) {
        when (pts[i].toInt()) {
            0 -> { moveTo(pts[i + 1], pts[i + 2]); i += 3 }
            1 -> { lineTo(pts[i + 1], pts[i + 2]); i += 3 }
            2 -> { quadraticTo(pts[i + 1], pts[i + 2], pts[i + 3], pts[i + 4]); i += 5 }
            else -> i++
        }
    }
    close()
}

private const val M = 0f
private const val L = 1f
private const val Q = 2f

private val key1 = keyPath(
    M, 33f, 30f, L, 40f, 30f, Q, 41.5f, 30f, 41.5f, 31.5f, L, 41.5f, 56.5f, Q, 41.5f, 58f, 43f, 58f,
    L, 44f, 58f, Q, 45.5f, 58f, 45.5f, 59.5f, L, 45.5f, 72f, Q, 45.5f, 76f, 41.5f, 76f, L, 35.5f, 76f,
    Q, 31.5f, 76f, 31.5f, 72f, L, 31.5f, 31.5f, Q, 31.5f, 30f, 33f, 30f,
)
private val key2 = keyPath(
    M, 52.5f, 34f, L, 55.5f, 34f, Q, 57f, 34f, 57f, 35.5f, L, 57f, 56.5f, Q, 57f, 58f, 58.5f, 58f,
    L, 59f, 58f, Q, 60.5f, 58f, 60.5f, 59.5f, L, 60.5f, 76f, Q, 60.5f, 80f, 56.5f, 80f, L, 51.5f, 80f,
    Q, 47.5f, 80f, 47.5f, 76f, L, 47.5f, 59.5f, Q, 47.5f, 58f, 49f, 58f, L, 49.5f, 58f, Q, 51f, 58f, 51f, 56.5f,
    L, 51f, 35.5f, Q, 51f, 34f, 52.5f, 34f,
)
private val key3 = keyPath(
    M, 68f, 30f, L, 75f, 30f, Q, 76.5f, 30f, 76.5f, 31.5f, L, 76.5f, 72f, Q, 76.5f, 76f, 72.5f, 76f,
    L, 66.5f, 76f, Q, 62.5f, 76f, 62.5f, 72f, L, 62.5f, 59.5f, Q, 62.5f, 58f, 64f, 58f, L, 65f, 58f,
    Q, 66.5f, 58f, 66.5f, 56.5f, L, 66.5f, 31.5f, Q, 66.5f, 30f, 68f, 30f,
)

@Composable
fun BreakMark(size: Dp, modifier: Modifier = Modifier, mono: Color? = null) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 50f
        val ivory = mono?.let { Brush.verticalGradient(listOf(it, it)) }
            ?: Brush.verticalGradient(listOf(Color.White, Color(0xFFE6DFD2)), startY = 30f, endY = 76f)
        val ember = mono?.let { Brush.verticalGradient(listOf(it, it)) }
            ?: Brush.verticalGradient(listOf(Brand.EmberHi, Brand.EmberLo), startY = 34f, endY = 80f)
        // center the 45×50 mark inside the box
        val ox = (this.size.width - 45f * s) / 2f
        val oy = (this.size.height - 50f * s) / 2f
        translate(ox, oy) {
            scale(s, s, pivot = Offset.Zero) {
                translate(-31.5f, -30f) {
                    drawPath(key1, ivory)
                    drawPath(key2, ember)
                    drawPath(key3, ivory)
                }
            }
        }
    }
}

@Composable
fun BreakWordmark(modifier: Modifier = Modifier, markSize: Dp = 22.dp, fontSize: TextUnit = 22.sp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        BreakMark(markSize)
        Spacer(Modifier.width(markSize * 0.36f))
        Text("break", style = Type.Wordmark.copy(fontSize = fontSize), color = Brand.Ivory)
    }
}
