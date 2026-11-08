package app.breakpiano.ui.components

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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type

/** Tactile press: shrinks a touch while held, no ripple. */
fun Modifier.pressScale(onClick: () -> Unit, enabled: Boolean = true, pressedScale: Float = 0.95f): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "press")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * The one control surface of Break. Idle = a raised grey key with a soft top highlight.
 * Active = pressed in: darker grey, sitting below its neighbours with an inner shadow.
 */
fun Modifier.control(active: Boolean, shape: Shape = RoundedCornerShape(12.dp)): Modifier = this
    .clip(shape)
    .background(if (active) Brand.Pressed else Brand.Slate)
    .drawBehind {
        if (active) {
            val d = 7.dp.toPx()
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), endY = d), size = Size(size.width, d))
            drawRect(Color.Black.copy(alpha = 0.35f), size = Size(2.dp.toPx(), size.height))
        } else {
            drawRect(Color.White.copy(alpha = 0.07f), size = Size(size.width, 1.dp.toPx()))
            drawRect(Color.Black.copy(alpha = 0.25f), topLeft = Offset(0f, size.height - 1.5.dp.toPx()), size = Size(size.width, 1.5.dp.toPx()))
        }
    }
    .border(1.dp, if (active) Color.Black.copy(alpha = 0.6f) else Brand.SlateHi, shape)

/** A recessed well that groups controls. */
fun Modifier.well(shape: Shape = RoundedCornerShape(14.dp)): Modifier = this
    .clip(shape)
    .background(Brand.Night)
    .border(1.dp, Color.Black.copy(alpha = 0.5f), shape)

fun contentColor(active: Boolean) = if (active) Color.White else Brand.Ivory.copy(alpha = 0.88f)

@Composable
fun IconChip(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    size: Dp = 40.dp,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    Box(modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().control(active).pressScale(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = tint ?: contentColor(active), modifier = Modifier.size(size * 0.5f))
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
    Row(
        modifier
            .height(height)
            .control(active)
            .pressScale(onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Small status light — ivory when on, like the LED on a hardware button. */
@Composable
fun Led(on: Boolean, color: Color = Brand.Ivory) {
    Box(
        Modifier.size(6.dp).clip(CircleShape)
            .background(if (on) color else Color.White.copy(alpha = 0.12f)),
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
            .well(RoundedCornerShape(11.dp))
            .padding(3.dp),
    ) {
        val segW = maxWidth / options.size
        val x by animateDpAsState(segW * selected.coerceAtLeast(0), spring(dampingRatio = 0.85f, stiffness = 700f), label = "seg")
        if (selected >= 0) {
            Box(
                Modifier.offset(x = x).width(segW).fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp)).background(Brand.SlateHi)
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp)),
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
    val x by animateDpAsState(if (checked) 20.dp else 2.dp, spring(dampingRatio = 0.75f, stiffness = 700f), label = "sw")
    Box(
        modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (checked) Brand.Ivory else Brand.Night)
            .border(1.dp, if (checked) Color.Transparent else Brand.Line, CircleShape)
            .clickable { onChange(!checked) },
    ) {
        Box(Modifier.offset(x = x, y = 3.dp).size(20.dp).clip(CircleShape).background(if (checked) Brand.Obsidian else Brand.Mist))
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
            Text(valueText, style = Type.Mono, color = Brand.Mist)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Brand.Ivory,
                activeTrackColor = Brand.Ivory,
                inactiveTrackColor = Brand.Line,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
fun Stepper(text: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier, minWidth: Dp = 44.dp, label: String? = null) {
    Row(modifier.height(40.dp).well(RoundedCornerShape(12.dp)), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", onMinus)
        Column(Modifier.width(minWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            if (label != null) Text(label, style = Type.Caption.copy(fontSize = Type.Caption.fontSize * 0.8f), color = Brand.Dim, maxLines = 1)
            Text(text, style = Type.Mono, color = Brand.Ivory, textAlign = TextAlign.Center)
        }
        StepButton("+", onPlus)
    }
}

@Composable
private fun StepButton(sym: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(3.dp).size(width = 32.dp, height = 34.dp).control(false, RoundedCornerShape(9.dp)).pressScale(onClick, pressedScale = 0.88f),
        contentAlignment = Alignment.Center,
    ) { Text(sym, style = Type.Title, color = Brand.Ivory) }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.Ivory)
            .pressScale(onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = Brand.Obsidian, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.Heading, color = Brand.Obsidian)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, active: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier
            .height(46.dp)
            .control(active, RoundedCornerShape(14.dp))
            .pressScale(onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = Brand.Ivory, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.Heading, color = Brand.Ivory)
    }
}

/** Big round transport button (play / stop / record). */
@Composable
fun RoundButton(icon: ImageVector, active: Boolean, size: Dp = 56.dp, tint: Color? = null, contentDescription: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(size).control(active, CircleShape).pressScale(onClick, pressedScale = 0.9f),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint ?: contentColor(active), modifier = Modifier.size(size * 0.48f)) }
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
            .background(Brand.Graphite)
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

/**
 * Large centered window with room to breathe. Tapping the dimmed area outside closes it.
 */
@Composable
fun BigModal(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    headerExtra: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 1180.dp)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(24.dp))
                .background(Brand.Graphite)
                .border(1.dp, Brand.Line, RoundedCornerShape(24.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.widthIn(min = 140.dp)) {
                    Text(title, style = Type.Title, color = Brand.Ivory)
                    if (subtitle != null) Text(subtitle, style = Type.Body, color = Brand.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(16.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { headerExtra?.invoke(this) }
                Spacer(Modifier.width(10.dp))
                IconChip(Icons.Rounded.Close, "Close", size = 38.dp, onClick = onClose)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Line))
            Column(Modifier.fillMaxWidth().weight(1f), content = content)
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

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = Brand.Ivory) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Brand.Line)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
    }
}

@Composable
fun Centered(content: @Composable BoxScope.() -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
