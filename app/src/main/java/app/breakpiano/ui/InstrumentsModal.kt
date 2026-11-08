package app.breakpiano.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.breakpiano.audio.Instrument
import app.breakpiano.audio.InstrumentFamily
import app.breakpiano.audio.Instruments
import app.breakpiano.audio.Previewer
import app.breakpiano.data.BreakState
import app.breakpiano.data.Panel
import app.breakpiano.ui.components.BigModal
import app.breakpiano.ui.components.Segmented
import app.breakpiano.ui.components.SliderRow
import app.breakpiano.ui.components.control
import app.breakpiano.ui.components.pressScale
import app.breakpiano.ui.components.well
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type
import kotlin.math.roundToInt

@Composable
fun InstrumentsModal(state: BreakState, previewer: Previewer) {
    var target by remember { mutableIntStateOf(0) } // 0 main · 1 layer
    var family by remember { mutableStateOf(state.instrument.family) }
    val grid = rememberLazyGridState()
    LaunchedEffect(family) { grid.scrollToItem(0) }
    DisposableEffect(Unit) { onDispose { previewer.stop() } }

    BigModal(
        title = "Instruments",
        subtitle = "${Instruments.all.size} sounds · ▶ to preview, tap to play",
        onClose = { state.panel = Panel.None },
        headerExtra = {
            Segmented(
                listOf("Main sound", if (state.layer != null) "Layer · ${state.layer!!.name}" else "Add a layer"),
                target, { target = it }, Modifier.width(360.dp),
            )
            Spacer(Modifier.weight(1f))
            NowSelected(state)
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            // ── family rail ──
            LazyColumn(
                Modifier.width(210.dp).fillMaxHeight().background(Brand.Night),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(InstrumentFamily.entries.toList()) { f ->
                    val sel = f == family
                    Row(
                        Modifier.fillMaxWidth().height(50.dp)
                            .then(if (sel) Modifier.control(true) else Modifier.clip(RoundedCornerShape(12.dp)))
                            .clickable { family = f }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InstrumentArt(artFor(f), if (sel) Brand.Ivory else Brand.Mist, Modifier.size(32.dp), weight = 3.2f)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.title, style = Type.Heading, color = if (sel) Brand.Ivory else Brand.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Instruments.of(f).size} sounds", style = Type.Caption, color = Brand.Dim)
                        }
                    }
                }
            }
            // ── cards ──
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (target == 1) LayerBar(state)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(176.dp),
                    state = grid,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(Instruments.of(family), key = { it.id }) { inst ->
                        val selected = if (target == 0) inst.id == state.instrumentId else inst.id == state.layerId
                        InstrumentCard(
                            inst = inst,
                            selected = selected,
                            previewing = previewer.playingId == inst.id,
                            onPreview = { previewer.toggle(inst) },
                            onSelect = {
                                previewer.stop()
                                if (target == 0) state.instrumentId = inst.id else state.layerId = inst.id
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NowSelected(state: BreakState) {
    Row(Modifier.height(40.dp).well(RoundedCornerShape(12.dp)).padding(start = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        InstrumentArt(artFor(state.instrument), Brand.Ivory, Modifier.size(28.dp), weight = 3.4f)
        Spacer(Modifier.width(8.dp))
        Text(
            state.instrument.name + (state.layer?.let { " + ${it.name}" } ?: ""),
            style = Type.Label, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(200.dp),
        )
    }
}

@Composable
private fun LayerBar(state: BreakState) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp).well().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (state.layer == null) {
                Text("Stack a second sound under every note", style = Type.Heading, color = Brand.Ivory)
                Text("Pick any instrument below — e.g. Concert Grand + Slow Strings", style = Type.Body, color = Brand.Mist)
            } else {
                SliderRow("Layer level · ${state.layer!!.name}", state.layerMix, { state.layerMix = it }, "${(state.layerMix * 100).roundToInt()}%")
            }
        }
        if (state.layer != null) {
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier.height(38.dp).control(false).pressScale({ state.layerId = "" }).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Close, null, tint = Brand.Ivory, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Remove layer", style = Type.Label, color = Brand.Ivory)
            }
        }
    }
}

@Composable
private fun InstrumentCard(
    inst: Instrument,
    selected: Boolean,
    previewing: Boolean,
    onPreview: () -> Unit,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .clip(shape)
            .background(if (selected) Brand.Pressed else Brand.Slate)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Brand.Ivory.copy(alpha = 0.8f) else Brand.SlateHi, shape)
            .pressScale(onSelect, pressedScale = 0.97f),
    ) {
        // art stage
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).padding(8.dp)
                .clip(RoundedCornerShape(13.dp)).background(Brand.Night),
            contentAlignment = Alignment.Center,
        ) {
            val t = rememberInfiniteTransition(label = "pv")
            val pulse by t.animateFloat(1f, 1.04f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "pulse")
            val k = if (previewing) pulse else 1f
            AssetPhoto("inst", inst.id, Modifier.fillMaxSize().graphicsLayer { scaleX = k; scaleY = k }) {
                InstrumentArt(
                    artFor(inst), Brand.Ivory,
                    Modifier.fillMaxSize().padding(10.dp).graphicsLayer { scaleX = k; scaleY = k },
                )
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp).clip(CircleShape).background(Brand.Ivory),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, tint = Brand.Obsidian, modifier = Modifier.size(16.dp)) }
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(38.dp)
                    .control(previewing, CircleShape).pressScale(onPreview, pressedScale = 0.88f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (previewing) "Stop preview" else "Preview",
                    tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 2.dp)) {
            Text(inst.name, style = Type.Heading, color = Brand.Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(inst.blurb, style = Type.Body, color = Brand.Mist, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(36.dp))
        }
    }
}
