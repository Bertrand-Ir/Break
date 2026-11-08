package app.breakpiano.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import app.breakpiano.audio.Instrument
import app.breakpiano.audio.InstrumentFamily

/** Line-art illustrations drawn in a 100×100 space: crisp at any size, zero APK weight. */
enum class Art {
    Grand, Upright, Harpsichord, StagePiano, Organ, PipeOrgan, Accordion, Harmonica,
    AcousticGuitar, ElectricGuitar, Banjo, Ukulele, Violin, Cello, Ensemble, Harp,
    BassGuitar, Trumpet, Horn, Sax, Flute, Choir, Synth, Mallets, Bell, Kalimba, SteelDrum,
    Sitar, Koto, Bagpipe,
}

fun artFor(inst: Instrument): Art = when (inst.id) {
    "grand", "bright", "egrand", "bellpiano", "pianostr" -> Art.Grand
    "honky" -> Art.Upright
    "harpsi", "harpsi2" -> Art.Harpsichord
    "tine", "tinech", "fmep", "fmepch", "tinefm", "bellep", "clav" -> Art.StagePiano
    "tonewheel", "tonewheel2", "percorgan", "rockorgan" -> Art.Organ
    "pipe", "pipe2", "reed" -> Art.PipeOrgan
    "accordion", "accordion2" -> Art.Accordion
    "harmonica" -> Art.Harmonica
    "nylon", "steel", "twelve" -> Art.AcousticGuitar
    "ukulele", "mandolin" -> Art.Ukulele
    "banjo" -> Art.Banjo
    "jazzgtr", "clean", "cleanch", "funk", "muted", "overdrive", "distortion" -> Art.ElectricGuitar
    "violin", "viola", "fiddle" -> Art.Violin
    "cello", "contrabass", "upright" -> Art.Cello
    "ensemble", "slowstr", "tremolo", "pizz", "orchestra" -> Art.Ensemble
    "harp" -> Art.Harp
    "finger", "pick", "fretless", "slap" -> Art.BassGuitar
    "trumpet", "trombone", "brass" -> Art.Trumpet
    "horn" -> Art.Horn
    "altosax", "tenorsax" -> Art.Sax
    "flute", "clarinet", "oboe", "panflute" -> Art.Flute
    "choir", "oohs", "synthvox" -> Art.Choir
    "vibes", "marimba", "glock" -> Art.Mallets
    "celesta", "musicbox", "tubular" -> Art.Bell
    "kalimba" -> Art.Kalimba
    "steeldrum" -> Art.SteelDrum
    "sitar", "shamisen" -> Art.Sitar
    "koto" -> Art.Koto
    "bagpipe" -> Art.Bagpipe
    else -> artFor(inst.family)
}

fun artFor(f: InstrumentFamily): Art = when (f) {
    InstrumentFamily.Piano -> Art.Grand
    InstrumentFamily.Electric -> Art.StagePiano
    InstrumentFamily.Organ -> Art.Organ
    InstrumentFamily.Guitar -> Art.AcousticGuitar
    InstrumentFamily.Strings -> Art.Violin
    InstrumentFamily.Bass -> Art.BassGuitar
    InstrumentFamily.Winds -> Art.Trumpet
    InstrumentFamily.Choir -> Art.Choir
    InstrumentFamily.Synth -> Art.Synth
    InstrumentFamily.Mallets -> Art.Mallets
    InstrumentFamily.World -> Art.Sitar
}

@Composable
fun InstrumentArt(art: Art, color: Color, modifier: Modifier = Modifier, weight: Float = 2.4f) {
    Canvas(modifier) { drawArt(art, color, weight) }
}

private class Pen(val ds: DrawScope, val c: Color, w: Float) {
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(width = w * 0.55f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val tint = c.copy(alpha = 0.10f)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, light: Boolean = false) =
        ds.drawLine(c.copy(alpha = if (light) 0.6f else 1f), Offset(x1, y1), Offset(x2, y2), if (light) thin.width else stroke.width, StrokeCap.Round)
    fun shape(p: Path, fill: Boolean = true, light: Boolean = false) {
        if (fill) ds.drawPath(p, tint)
        ds.drawPath(p, c.copy(alpha = if (light) 0.6f else 1f), style = if (light) thin else stroke)
    }
    fun rect(l: Float, t: Float, r: Float, b: Float, rad: Float = 2f, fill: Boolean = true, solid: Boolean = false, light: Boolean = false) {
        val o = Offset(l, t); val s = Size(r - l, b - t); val cr = CornerRadius(rad)
        when {
            solid -> ds.drawRoundRect(c, o, s, cr)
            else -> { if (fill) ds.drawRoundRect(tint, o, s, cr); ds.drawRoundRect(c.copy(alpha = if (light) 0.6f else 1f), o, s, cr, style = if (light) thin else stroke) }
        }
    }
    fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = true, solid: Boolean = false, light: Boolean = false) {
        when {
            solid -> ds.drawCircle(c, r, Offset(cx, cy))
            else -> { if (fill) ds.drawCircle(tint, r, Offset(cx, cy)); ds.drawCircle(c.copy(alpha = if (light) 0.6f else 1f), r, Offset(cx, cy), style = if (light) thin else stroke) }
        }
    }
    fun keys(l: Float, t: Float, r: Float, b: Float, n: Int, blacks: Boolean = true) {
        rect(l, t, r, b, 1.5f, fill = false)
        val w = (r - l) / n
        for (i in 1 until n) line(l + i * w, t, l + i * w, b, light = true)
        if (blacks) for (i in 0 until n - 1) {
            if (i % 7 == 2 || i % 7 == 6) continue
            rect(l + (i + 0.68f) * w, t, l + (i + 1.32f) * w, t + (b - t) * 0.6f, 0.6f, solid = true)
        }
    }
}

private fun path(block: Path.() -> Unit) = Path().apply(block)

fun DrawScope.drawArt(art: Art, color: Color, weight: Float = 2.4f) {
    val s = size.minDimension / 100f
    val ox = (size.width - 100f * s) / 2f
    val oy = (size.height - 100f * s) / 2f
    withTransform({ translate(ox, oy); scale(s, s, pivot = Offset.Zero) }) {
        val p = Pen(this, color, weight)
        when (art) {
            Art.Grand -> {
                p.shape(path {
                    moveTo(18f, 22f); lineTo(58f, 22f)
                    cubicTo(70f, 22f, 72f, 36f, 76f, 46f); cubicTo(80f, 57f, 86f, 62f, 86f, 72f)
                    lineTo(86f, 76f); lineTo(18f, 76f); close()
                })
                p.shape(path { moveTo(24f, 28f); lineTo(56f, 28f); cubicTo(64f, 28f, 66f, 38f, 70f, 48f); cubicTo(73f, 57f, 78f, 62f, 79f, 70f); lineTo(24f, 70f); close() }, fill = false, light = true)
                for (i in 0..5) p.line(30f + i * 7f, 32f, 34f + i * 7.5f, 68f, light = true)
                p.keys(18f, 76f, 86f, 86f, 14)
            }
            Art.Upright -> {
                p.rect(22f, 16f, 78f, 66f, 3f)
                p.rect(34f, 26f, 66f, 42f, 1.5f, light = true)
                p.keys(16f, 62f, 84f, 72f, 14)
                p.line(24f, 72f, 24f, 88f); p.line(76f, 72f, 76f, 88f)
                p.line(18f, 88f, 30f, 88f); p.line(70f, 88f, 82f, 88f)
            }
            Art.Harpsichord -> {
                p.shape(path { moveTo(20f, 24f); lineTo(46f, 24f); lineTo(84f, 70f); lineTo(84f, 76f); lineTo(20f, 76f); close() })
                for (i in 0..4) p.line(26f + i * 6f, 30f, 30f + i * 10f, 70f, light = true)
                p.keys(20f, 76f, 84f, 86f, 13)
                p.line(26f, 86f, 26f, 94f); p.line(78f, 86f, 78f, 94f)
            }
            Art.StagePiano -> {
                p.rect(8f, 38f, 92f, 62f, 5f)
                p.keys(14f, 47f, 86f, 59f, 18)
                for (i in 0..3) p.circle(16f + i * 6f, 42.5f, 1.6f, solid = true)
                p.line(24f, 62f, 40f, 88f); p.line(40f, 62f, 24f, 88f)
                p.line(60f, 62f, 76f, 88f); p.line(76f, 62f, 60f, 88f)
            }
            Art.Organ -> {
                p.rect(14f, 22f, 86f, 60f, 4f)
                for (i in 0..8) { val x = 28f + i * 5.5f; p.line(x, 27f, x, 33f + (i % 3) * 2f); p.circle(x, 34f + (i % 3) * 2f, 1.3f, solid = true) }
                p.keys(20f, 42f, 80f, 49f, 12, blacks = false)
                p.keys(18f, 51f, 82f, 58f, 12, blacks = false)
                p.rect(16f, 60f, 84f, 84f, 2f, fill = false)
                for (i in 0..6) p.line(24f + i * 9f, 78f, 28f + i * 9f, 84f, light = true)
            }
            Art.PipeOrgan -> {
                val tops = floatArrayOf(40f, 31f, 23f, 15f, 15f, 23f, 31f, 40f)
                for (i in 0..7) {
                    val x = 19f + i * 8.2f
                    p.rect(x, tops[i], x + 6f, 70f, 3f)
                    p.shape(path { moveTo(x + 1.2f, 60f); lineTo(x + 3f, 56f); lineTo(x + 4.8f, 60f) }, fill = false, light = true)
                }
                p.rect(14f, 70f, 86f, 88f, 2f)
                p.keys(24f, 74f, 76f, 81f, 10, blacks = false)
            }
            Art.Accordion -> {
                p.rect(14f, 26f, 30f, 78f, 3f)
                p.rect(70f, 26f, 86f, 78f, 3f)
                val bel = path {
                    moveTo(30f, 28f)
                    for (i in 0..7) { lineTo(30f + i * 5f + 2.5f, if (i % 2 == 0) 24f else 28f) }
                    lineTo(70f, 28f); lineTo(70f, 76f)
                    for (i in 7 downTo 0) { lineTo(30f + i * 5f + 2.5f, if (i % 2 == 0) 80f else 76f) }
                    lineTo(30f, 76f); close()
                }
                p.shape(bel)
                for (i in 1..7) p.line(30f + i * 5f, 27f, 30f + i * 5f, 77f, light = true)
                for (i in 0..5) p.line(17f, 32f + i * 7.5f, 27f, 32f + i * 7.5f, light = true)
                for (r in 0..4) for (c in 0..1) p.circle(75f + c * 6f, 34f + r * 9f, 1.6f, solid = true)
            }
            Art.Harmonica -> {
                p.rect(10f, 38f, 90f, 62f, 5f)
                p.line(12f, 50f, 88f, 50f, light = true)
                for (i in 0..9) p.rect(16f + i * 7f, 41f, 20.5f + i * 7f, 47f, 1f, solid = true)
            }
            Art.AcousticGuitar, Art.Ukulele -> rotate(-32f, Offset(50f, 50f)) {
                val k = if (art == Art.Ukulele) 0.82f else 1f
                withTransform({ scale(k, k, Offset(50f, 60f)) }) {
                    p.shape(path {
                        moveTo(50f, 46f); cubicTo(62f, 46f, 66f, 54f, 62f, 62f)
                        cubicTo(75f, 65f, 76f, 92f, 50f, 92f); cubicTo(24f, 92f, 25f, 65f, 38f, 62f)
                        cubicTo(34f, 54f, 38f, 46f, 50f, 46f); close()
                    })
                    p.circle(50f, 68f, 6f, fill = false)
                    p.line(43f, 82f, 57f, 82f)
                    p.rect(47f, 8f, 53f, 47f, 1f)
                    p.rect(45.5f, 1f, 54.5f, 10f, 2f)
                    p.line(48.8f, 10f, 48.8f, 82f, light = true); p.line(51.2f, 10f, 51.2f, 82f, light = true)
                }
            }
            Art.ElectricGuitar, Art.BassGuitar -> rotate(-32f, Offset(50f, 50f)) {
                val bass = art == Art.BassGuitar
                p.shape(path {
                    moveTo(46f, 52f); cubicTo(40f, 42f, 30f, 46f, 32f, 56f)
                    cubicTo(25f, 62f, 24f, 76f, 31f, 84f); cubicTo(38f, 92f, 56f, 93f, 64f, 85f)
                    cubicTo(71f, 77f, 70f, 66f, 63f, 61f); cubicTo(68f, 54f, 64f, 44f, 56f, 49f)
                    lineTo(53f, 52f); close()
                })
                p.rect(43f, 62f, 57f, 66f, 1f, solid = true)
                if (!bass) p.rect(43f, 70f, 57f, 74f, 1f, solid = true)
                p.circle(60f, 80f, 2f, fill = false); p.circle(55f, 85f, 2f, fill = false)
                val top = if (bass) 2f else 8f
                p.rect(47.5f, top, 52.5f, 54f, 1f)
                for (i in 1..6) p.line(47.5f, top + i * 6.5f, 52.5f, top + i * 6.5f, light = true)
                p.rect(46f, top - 7f, 54f, top + 1f, 2f)
                p.line(49f, top, 49f, 78f, light = true); p.line(51f, top, 51f, 78f, light = true)
            }
            Art.Banjo -> rotate(-32f, Offset(50f, 50f)) {
                p.circle(50f, 70f, 19f)
                p.circle(50f, 70f, 14f, fill = false, light = true)
                p.rect(47.5f, 6f, 52.5f, 51f, 1f)
                p.rect(46f, 0f, 54f, 8f, 2f)
                p.line(49f, 8f, 49f, 80f, light = true); p.line(51f, 8f, 51f, 80f, light = true)
                p.line(45f, 80f, 55f, 80f)
            }
            Art.Violin, Art.Cello -> rotate(if (art == Art.Violin) -28f else -12f, Offset(50f, 50f)) {
                val k = if (art == Art.Cello) 1.08f else 0.92f
                withTransform({ scale(k, k, Offset(50f, 60f)) }) { violinBody(p) }
                if (art == Art.Cello) p.line(50f, 96f, 50f, 100f)
                p.line(14f, 74f, 88f, 40f); p.line(16f, 77f, 88f, 44f, light = true)
            }
            Art.Ensemble -> {
                rotate(-22f, Offset(34f, 54f)) { withTransform({ scale(0.66f, 0.66f, Offset(34f, 54f)); translate(-16f, -6f) }) { violinBody(p) } }
                rotate(18f, Offset(66f, 54f)) { withTransform({ scale(0.66f, 0.66f, Offset(66f, 54f)); translate(16f, -6f) }) { violinBody(p) } }
                p.line(18f, 88f, 82f, 22f); p.line(20f, 90f, 84f, 24f, light = true)
            }
            Art.Harp -> {
                p.shape(path {
                    moveTo(26f, 90f); lineTo(26f, 16f)
                    cubicTo(38f, 8f, 52f, 22f, 64f, 22f); cubicTo(72f, 22f, 76f, 16f, 82f, 20f)
                    lineTo(38f, 90f); close()
                })
                for (i in 0..8) {
                    val x = 32f + i * 5f
                    val top = 16f + (x - 26f) * 0.12f + if (x < 56) (56f - x) * 0.18f else 0f
                    val bot = 90f - (x - 38f) * (70f / 44f)
                    if (bot - 4 > top + 4) p.line(x, top + 4f, x, bot - 3f, light = true)
                }
                p.line(20f, 92f, 44f, 92f)
            }
            Art.Trumpet -> {
                p.line(8f, 48f, 58f, 48f)
                p.rect(4f, 46f, 10f, 50f, 1f, solid = true)
                p.rect(28f, 50f, 60f, 64f, 7f, fill = false)
                for (i in 0..2) { val x = 34f + i * 7f; p.rect(x, 36f, x + 4.5f, 50f, 1f); p.circle(x + 2.25f, 33f, 2.6f, solid = true) }
                p.shape(path { moveTo(58f, 45f); cubicTo(70f, 43f, 80f, 36f, 92f, 28f); lineTo(92f, 70f); cubicTo(80f, 62f, 70f, 56f, 58f, 54f); close() })
            }
            Art.Horn -> {
                p.circle(44f, 56f, 24f)
                p.circle(44f, 56f, 15f, fill = false, light = true)
                p.shape(path { moveTo(58f, 42f); cubicTo(66f, 34f, 72f, 24f, 78f, 12f); lineTo(94f, 26f); cubicTo(82f, 32f, 72f, 40f, 64f, 50f); close() })
                for (i in 0..2) p.rect(38f + i * 6f, 40f, 42f + i * 6f, 50f, 1f, solid = true)
                p.line(24f, 66f, 10f, 74f)
            }
            Art.Sax -> {
                p.line(34f, 12f, 52f, 18f)
                p.rect(28f, 9f, 36f, 14f, 1.5f, solid = true)
                p.shape(path {
                    moveTo(50f, 16f); lineTo(58f, 16f); lineTo(62f, 66f)
                    cubicTo(63f, 82f, 52f, 90f, 41f, 85f); cubicTo(33f, 81f, 31f, 72f, 33f, 64f)
                    lineTo(25f, 58f); lineTo(41f, 50f); lineTo(42f, 64f)
                    cubicTo(42f, 72f, 46f, 76f, 50f, 74f); cubicTo(54f, 72f, 54f, 68f, 54f, 64f); close()
                })
                for (i in 0..5) p.circle(59.5f, 26f + i * 7f, 2f, solid = true)
            }
            Art.Flute -> rotate(-22f, Offset(50f, 50f)) {
                p.rect(6f, 46f, 94f, 54f, 3f)
                p.rect(14f, 48f, 20f, 52f, 2f, solid = true)
                for (i in 0..6) p.circle(34f + i * 8f, 50f, 2.2f, fill = false)
                p.line(30f, 46f, 30f, 54f, light = true); p.line(84f, 46f, 84f, 54f, light = true)
            }
            Art.Choir -> {
                val heads = listOf(Triple(28f, 44f, 9f), Triple(72f, 44f, 9f), Triple(50f, 36f, 10.5f))
                for ((x, y, r) in heads) {
                    p.shape(path { moveTo(x - r * 1.9f, y + r * 4f); cubicTo(x - r * 1.9f, y + r * 1.4f, x + r * 1.9f, y + r * 1.4f, x + r * 1.9f, y + r * 4f) })
                    p.circle(x, y, r)
                    p.circle(x, y + r * 0.35f, r * 0.28f, solid = true)
                }
                p.line(80f, 12f, 80f, 24f); p.circle(77f, 25f, 3f, solid = true); p.line(80f, 12f, 86f, 15f)
            }
            Art.Synth -> {
                p.rect(8f, 26f, 92f, 74f, 6f)
                p.rect(14f, 32f, 46f, 50f, 2f)
                p.ds.drawPath(path {
                    moveTo(17f, 41f)
                    var x = 17f
                    while (x < 43f) { val nx = x + 6.5f; quadraticTo(x + 1.6f, 34f, x + 3.25f, 41f); quadraticTo(x + 4.9f, 48f, nx, 41f); x = nx }
                }, color, style = p.thin)
                for (i in 0..2) { val cx = 58f + i * 11f; p.circle(cx, 40f, 4.6f); p.line(cx, 40f, cx + 2.5f, 36.5f) }
                p.keys(14f, 56f, 86f, 69f, 14)
            }
            Art.Mallets -> {
                p.line(14f, 34f, 86f, 40f, light = true); p.line(14f, 70f, 86f, 64f, light = true)
                for (i in 0..7) { val x = 14f + i * 9.3f; p.rect(x, 22f + i * 2.6f, x + 6.6f, 82f - i * 2.6f, 2f) }
                p.line(60f, 6f, 88f, 30f); p.circle(58f, 5f, 4f, solid = true)
                p.line(80f, 4f, 94f, 32f); p.circle(79f, 3f, 4f, solid = true)
            }
            Art.Bell -> {
                p.circle(50f, 14f, 3.5f, fill = false)
                p.shape(path {
                    moveTo(50f, 18f); cubicTo(36f, 18f, 32f, 34f, 32f, 50f); cubicTo(32f, 62f, 26f, 66f, 20f, 72f)
                    lineTo(80f, 72f); cubicTo(74f, 66f, 68f, 62f, 68f, 50f); cubicTo(68f, 34f, 64f, 18f, 50f, 18f); close()
                })
                p.circle(50f, 78f, 4.5f, solid = true)
                p.line(14f, 30f, 20f, 34f, light = true); p.line(86f, 30f, 80f, 34f, light = true); p.line(12f, 48f, 19f, 48f, light = true); p.line(88f, 48f, 81f, 48f, light = true)
            }
            Art.Kalimba -> {
                p.rect(20f, 18f, 80f, 88f, 12f)
                p.circle(50f, 72f, 7f, fill = false)
                p.line(26f, 32f, 74f, 32f)
                for (i in 0..8) { val x = 30f + i * 5f; p.line(x, 26f, x, 58f - kotlin.math.abs(x - 50f) * 0.75f) }
            }
            Art.SteelDrum -> {
                p.ds.drawOval(p.tint, Offset(14f, 26f), Size(72f, 28f))
                p.ds.drawOval(color, Offset(14f, 26f), Size(72f, 28f), style = p.stroke)
                p.shape(path { moveTo(14f, 40f); lineTo(14f, 70f); cubicTo(14f, 86f, 86f, 86f, 86f, 70f); lineTo(86f, 40f) }, fill = false)
                for ((cx, cy) in listOf(36f to 36f, 50f to 33f, 64f to 36f, 44f to 44f, 58f to 44f)) p.ds.drawOval(color.copy(alpha = 0.6f), Offset(cx - 5f, cy - 3f), Size(10f, 6f), style = p.thin)
                p.line(22f, 10f, 40f, 30f); p.line(78f, 10f, 60f, 30f)
            }
            Art.Sitar -> rotate(-28f, Offset(50f, 50f)) {
                p.circle(50f, 76f, 16f)
                p.circle(50f, 10f, 5f)
                p.rect(47f, 14f, 53f, 62f, 1f)
                for (i in 0..6) p.line(46f, 20f + i * 6f, 54f, 20f + i * 6f, light = true)
                p.line(49f, 14f, 49f, 86f, light = true); p.line(51f, 14f, 51f, 86f, light = true)
            }
            Art.Koto -> rotate(-16f, Offset(50f, 50f)) {
                p.rect(4f, 40f, 96f, 60f, 7f)
                for (i in 0..5) p.line(8f, 44f + i * 2.6f, 92f, 44f + i * 2.6f, light = true)
                for ((x, y) in listOf(22f to 47f, 34f to 52f, 46f to 45f, 58f to 50f, 70f to 54f, 80f to 47f))
                    p.shape(path { moveTo(x - 2.5f, y + 3f); lineTo(x, y - 2f); lineTo(x + 2.5f, y + 3f); close() }, fill = true)
            }
            Art.Bagpipe -> {
                p.line(48f, 46f, 66f, 8f); p.circle(66f, 8f, 2.5f, solid = true)
                p.line(54f, 48f, 80f, 16f); p.circle(80f, 16f, 2.5f, solid = true)
                p.line(60f, 52f, 92f, 30f); p.circle(92f, 30f, 2.5f, solid = true)
                p.ds.drawOval(p.tint, Offset(22f, 44f), Size(48f, 32f))
                p.ds.drawOval(color, Offset(22f, 44f), Size(48f, 32f), style = p.stroke)
                p.line(34f, 72f, 26f, 96f)
                for (i in 0..3) p.circle(31.8f - i * 1.9f, 78f + i * 4.6f, 1.2f, solid = true)
                p.line(28f, 50f, 12f, 38f)
            }
        }
    }
}

private fun violinBody(p: Pen) {
    p.shape(path {
        moveTo(50f, 40f)
        cubicTo(58f, 40f, 64f, 42f, 63f, 50f); cubicTo(62f, 55f, 58f, 56f, 58f, 60f)
        cubicTo(58f, 64f, 66f, 66f, 66f, 76f); cubicTo(66f, 88f, 58f, 92f, 50f, 92f)
        cubicTo(42f, 92f, 34f, 88f, 34f, 76f); cubicTo(34f, 66f, 42f, 64f, 42f, 60f)
        cubicTo(42f, 56f, 38f, 55f, 37f, 50f); cubicTo(36f, 42f, 42f, 40f, 50f, 40f); close()
    })
    p.line(44f, 62f, 44f, 72f, light = true); p.line(56f, 62f, 56f, 72f, light = true)
    p.line(46f, 80f, 54f, 80f)
    p.rect(48f, 14f, 52f, 41f, 1f)
    p.circle(50f, 11f, 3.6f, fill = false)
    p.line(49.2f, 14f, 49.2f, 80f, light = true); p.line(50.8f, 14f, 50.8f, 80f, light = true)
}
