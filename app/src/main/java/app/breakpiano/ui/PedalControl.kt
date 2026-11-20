package app.breakpiano.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.breakpiano.data.BreakState
import app.breakpiano.ui.components.control
import app.breakpiano.ui.components.pressScale
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * How long a released note keeps ringing while the pedal is down: a smooth range from
 * 0.05 s (barely any) to 15 s, then Hold (rings until the pedal is turned off).
 * The slider is logarithmic, so the short end — where small changes matter most — is fine-grained.
 */
object PedalScale {
    const val MIN = 0.05f
    const val MAX = 15f
    const val HOLD = 600f
    private const val SPAN = 0.95f            // the last 5 % of the slider is "Hold"

    fun isHold(sec: Float) = sec >= HOLD
    fun toSlider(sec: Float): Float = if (isHold(sec)) 1f else (ln(sec / MIN) / ln(MAX / MIN) * SPAN).coerceIn(0f, SPAN)
    fun fromSlider(p: Float): Float = if (p > 0.975f) HOLD else MIN * (MAX / MIN).pow((p / SPAN).coerceIn(0f, 1f))

    fun label(sec: Float): String = when {
        isHold(sec) -> "Hold"
        sec < 1f -> "%.2f s".format(sec)
        sec < 10f -> "%.1f s".format(sec)
        else -> "%.0f s".format(sec)
    }

    /** One small step shorter (dir = −1) or longer (dir = +1). */
    fun step(sec: Float, dir: Int): Float = when {
        isHold(sec) -> if (dir < 0) MAX else HOLD
        dir > 0 && sec >= MAX - 0.01f -> HOLD
        else -> (sec * 1.1f.pow(dir)).coerceIn(MIN, MAX)
    }
}

@Composable
fun PedalAdjuster(state: BreakState, modifier: Modifier = Modifier) {
    val sec = state.pedalLength
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pedal length", style = Type.Heading, color = Brand.Ivory)
                Text(
                    if (PedalScale.isHold(sec)) "Notes ring until you turn the pedal off"
                    else "Notes keep ringing this long after you let go",
                    style = Type.Body, color = Brand.Mist,
                )
            }
            Text(PedalScale.label(sec), style = Type.Title, color = Brand.Ivory)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepKey("−") { state.pedalLength = PedalScale.step(state.pedalLength, -1) }
            Slider(
                value = PedalScale.toSlider(sec),
                onValueChange = { state.pedalLength = PedalScale.fromSlider(it) },
                colors = SliderDefaults.colors(
                    thumbColor = Brand.Ivory, activeTrackColor = Brand.Ivory, inactiveTrackColor = Brand.Line,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            StepKey("+") { state.pedalLength = PedalScale.step(state.pedalLength, 1) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.15f to "Tiny", 0.5f to "Short", 1.5f to "Medium", 4f to "Long", PedalScale.HOLD to "Hold").forEach { (v, name) ->
                val on = if (PedalScale.isHold(v)) PedalScale.isHold(sec) else abs(sec - v) < 0.01f
                Box(
                    Modifier.weight(1f).height(34.dp).control(on, RoundedCornerShape(10.dp)).pressScale({ state.pedalLength = v }),
                    contentAlignment = Alignment.Center,
                ) { Text(name, style = Type.Label, color = if (on) Color.White else Brand.Mist) }
            }
        }
    }
}

@Composable
private fun StepKey(sym: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 40.dp, height = 36.dp).control(false, RoundedCornerShape(10.dp)).pressScale(onClick, pressedScale = 0.88f),
        contentAlignment = Alignment.Center,
    ) { Text(sym, style = Type.Title, color = Brand.Ivory) }
}

