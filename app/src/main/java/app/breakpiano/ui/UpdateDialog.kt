package app.breakpiano.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.breakpiano.BuildConfig
import app.breakpiano.ui.components.GhostButton
import app.breakpiano.ui.components.PrimaryButton
import app.breakpiano.ui.theme.Brand
import app.breakpiano.ui.theme.Type
import app.breakpiano.update.UpdateState
import app.breakpiano.update.Updater
import kotlin.math.roundToInt

/** Shown over the keyboard when GitHub has a newer build than the one installed. */
@Composable
fun UpdateDialog(updater: Updater) {
    val s = updater.state
    val visible = !updater.dismissed && (s is UpdateState.Available || s is UpdateState.Downloading ||
        s is UpdateState.NeedsPermission || s is UpdateState.ReadyToInstall)
    if (!visible) return
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(440.dp).clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1D1D25), Color(0xFF131318))))
                .border(1.dp, Brand.Line, RoundedCornerShape(26.dp))
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(Brand.Obsidian).border(1.dp, Brand.Line, RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center,
                ) { BreakMark(32.dp) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("New version of Break", style = Type.Title, color = Brand.Ivory)
                    val rel = when (s) {
                        is UpdateState.Available -> s.release
                        is UpdateState.Downloading -> s.release
                        is UpdateState.NeedsPermission -> s.release
                        is UpdateState.ReadyToInstall -> s.release
                        else -> null
                    }
                    Text("${BuildConfig.VERSION_NAME}  →  ${rel?.versionName ?: ""}", style = Type.Body, color = Brand.EmberHi)
                }
            }
            when (s) {
                is UpdateState.Available -> {
                    if (s.release.notes.isNotBlank()) {
                        Text("What's new", style = Type.Caption, color = Brand.Dim, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                        Column(Modifier.heightIn(max = 130.dp).verticalScroll(rememberScrollState())) {
                            Text(s.release.notes, style = Type.Body, color = Brand.Mist)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row {
                        GhostButton("Later", Modifier.weight(1f)) { updater.dismissed = true }
                        Spacer(Modifier.width(10.dp))
                        PrimaryButton("Update now", Modifier.weight(1.6f), icon = Icons.Rounded.SystemUpdate) { updater.download(s.release) }
                    }
                }
                is UpdateState.Downloading -> {
                    Text("Downloading… ${(s.progress * 100).roundToInt()}%", style = Type.Heading, color = Brand.Ivory, modifier = Modifier.padding(top = 20.dp))
                    ProgressLine(s.progress)
                    Text("Keep playing — Android will ask you to confirm when it's ready.", style = Type.Body, color = Brand.Mist, modifier = Modifier.padding(top = 10.dp))
                }
                is UpdateState.ReadyToInstall -> {
                    Text("Installing — confirm on the system prompt.", style = Type.Heading, color = Brand.Ivory, modifier = Modifier.padding(top = 20.dp))
                    Spacer(Modifier.height(16.dp))
                    Row {
                        GhostButton("Close", Modifier.weight(1f)) { updater.dismissed = true }
                        Spacer(Modifier.width(10.dp))
                        PrimaryButton("Install", Modifier.weight(1.4f)) { updater.install(s.release, s.file) }
                    }
                }
                is UpdateState.NeedsPermission -> {
                    Text(
                        "One-time step: allow Break to install its own updates. You'll be taken to Android settings — flip the switch and come back.",
                        style = Type.Body, color = Brand.Mist, modifier = Modifier.padding(top = 18.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Row {
                        GhostButton("Later", Modifier.weight(1f)) { updater.dismissed = true }
                        Spacer(Modifier.width(10.dp))
                        PrimaryButton("Allow installs", Modifier.weight(1.6f)) { updater.openInstallPermissionSettings() }
                    }
                }
                else -> {}
            }
        }
    }
}
