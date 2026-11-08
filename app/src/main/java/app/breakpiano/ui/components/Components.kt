package app.breakpiano.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type

/** Tactile press: shrinks a touch while held, no ripple. */
fun Modifier.pressScale(onClick: () -> Unit, enabled: Boolean = true, pressedScale: Float = 0.94f): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "press")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun IconChip(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    size: Dp = 40.dp,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (active) Brand.Ember else Brand.Slate, label = "chip")
    Box(modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .pressScale(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = if (active) Color.White else Brand.Ivory, modifier = Modifier.size(20.dp))
        }
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(10.dp)
                    .clip(CircleShape).background(Brand.Ember).border(2.dp, Brand.Obsidian, CircleShape),
            )
        }
    }
}

@Composable
fun Chip(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    height: Dp = 40.dp,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val bg by animateColorAsState(if (active) Brand.Ember else Brand.Slate, label = "chipbg")
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .pressScale(onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.Caption, color = Brand.Dim, modifier = modifier.padding(top = 14.dp, bottom = 8.dp))
}

@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
) {
    BoxWithConstraints(
        modifier
            .height(height)
            .clip(RoundedCornerShape(11.dp))
            .background(Brand.Night)
            .border(1.dp, Brand.Line, RoundedCornerShape(11.dp))
            .padding(3.dp),
    ) {
        val segW = maxWidth / options.size
        val x by animateDpAsState(segW * selected.coerceAtLeast(0), spring(dampingRatio = 0.8f, stiffness = 600f), label = "seg")
        if (selected >= 0) {
            Box(
                Modifier.offset(x = x).width(segW).fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp)).background(Brand.EmberGradient),
            )
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (i == selected) Color.White else Brand.Mist, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun BreakSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val x by animateDpAsState(if (checked) 20.dp else 2.dp, spring(dampingRatio = 0.7f, stiffness = 700f), label = "sw")
    val bg by animateColorAsState(if (checked) Brand.Ember else Brand.Line, label = "swbg")
    Box(
        modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable { onChange(!checked) },
    ) {
        Box(
            Modifier.offset(x = x, y = 3.dp).size(20.dp).clip(CircleShape).background(Color.White),
        )
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.Heading, color = Brand.Ivory)
            if (subtitle != null) Text(subtitle, style = Type.Body, color = Brand.Mist, modifier = Modifier.padding(top = 2.dp))
        }
        trailing()
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, Modifier.clickable { onChange(!checked) }) { BreakSwitch(checked, onChange) }
}

@Composable
fun SliderRow(
    title: String,
    value: Float,
    onChange: (Float) -> Unit,
    valueText: String,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onDone: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.Heading, color = Brand.Ivory, modifier = Modifier.weight(1f))
            Text(valueText, style = Type.Mono, color = Brand.EmberHi)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Brand.Ivory,
                activeTrackColor = Brand.Ember,
                inactiveTrackColor = Brand.Line,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
fun Stepper(text: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier, minWidth: Dp = 44.dp) {
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(Brand.Slate),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", onMinus)
        Text(text, style = Type.Mono, color = Brand.Ivory, textAlign = TextAlign.Center, modifier = Modifier.width(minWidth))
        StepButton("+", onPlus)
    }
}

@Composable
private fun StepButton(sym: String, onClick: () -> Unit) {
    Box(Modifier.size(width = 34.dp, height = 40.dp).pressScale(onClick, pressedScale = 0.85f), contentAlignment = Alignment.Center) {
        Text(sym, style = Type.Title, color = Brand.Ivory)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.EmberGradient)
            .pressScale(onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.Heading, color = Color.White)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.Slate)
            .pressScale(onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = Brand.Ivory, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.Heading, color = Brand.Ivory)
    }
}

/** Right-side sheet that floats over the keyboard; the visible keys stay playable. */
@Composable
fun SidePanel(
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    header: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1A21), Color(0xFF121217))))
            .border(1.dp, Brand.Line, RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null) { /* swallow touches */ },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.Title, color = Brand.Ivory)
                if (subtitle != null) Text(subtitle, style = Type.Body, color = Brand.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            header?.invoke(this)
            Spacer(Modifier.width(6.dp))
            IconChip(Icons.Rounded.Close, "Close", size = 36.dp, onClick = onClose)
        }
        val inner = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp)
        if (scrollable) {
            Column(inner.verticalScroll(rememberScrollState())) {
                content()
                Spacer(Modifier.height(20.dp))
            }
        } else {
            Column(inner, content = content)
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brand.Night)
            .border(1.dp, Brand.Line, RoundedCornerShape(18.dp))
            .padding(14.dp),
        content = content,
    )
}
