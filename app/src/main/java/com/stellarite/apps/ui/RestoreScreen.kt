package com.stellarite.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellarite.apps.data.DeviceSnapshot
import com.stellarite.apps.data.ModStatus
import com.stellarite.apps.data.displayRepo
import com.stellarite.apps.data.SnapModule
import com.stellarite.apps.data.statusOf
import com.stellarite.apps.ui.theme.SA
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RestoreScreen(
    restore: RestoreUi,
    onBack: () -> Unit,
    onDevice: (String) -> Unit,
    onMode: (RestoreMode) -> Unit,
    onToggle: (String) -> Unit,
    onSettings: (Boolean) -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = restore.snapshot
    val device = snapshot?.devices?.get(restore.deviceId)
    val running = restore.progress != null

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack, enabled = !running) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = SA.Text)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Wordmark("RESTORE")
                if (restore.source.isNotEmpty()) Mono("FROM ${restore.source}", spacing = 1.5.sp, maxLines = 1)
            }
        }

        when {
            restore.loading -> {
                Mono("READING SNAPSHOT…", spacing = 1.5.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = SA.Accent, trackColor = SA.Line)
            }

            restore.error != null -> Column(
                Modifier.fillMaxWidth().panel(border = SA.Alert).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Mono("RESTORE FAILED", color = SA.Alert, size = 12.sp, spacing = 1.6.sp)
                Text(restore.error, color = SA.Text, fontSize = 14.sp, lineHeight = 20.sp)
            }

            snapshot != null && device != null -> {
                SnapshotPanel(restore, snapshot.devices, device, onDevice, onMode)
                ModuleList(restore, device, onToggle)
                SettingsTick(restore.includeSettings, onSettings)

                restore.progress?.let {
                    Mono(it.uppercase(Locale.US), color = SA.Accent, spacing = 1.2.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = SA.Accent, trackColor = SA.Line)
                }
                restore.done?.let { Text(it, color = SA.Text, fontSize = 14.sp, lineHeight = 20.sp) }

                Text(
                    "Android asks you to confirm each install. Modules install one after another, so keep this screen open.",
                    color = SA.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                )
                val count = restore.selected.size
                SaButton(
                    when {
                        running -> "RESTORING…"
                        count == 0 && restore.includeSettings -> "RESTORE SETTINGS"
                        else -> "RESTORE $count ${if (count == 1) "MODULE" else "MODULES"}"
                    },
                    onRestore,
                    Modifier.fillMaxWidth(),
                    BtnStyle.Accent,
                    enabled = !running && (count > 0 || restore.includeSettings),
                    height = 54.dp,
                    glow = true,
                )
            }
        }
    }
}

@Composable
private fun SnapshotPanel(
    restore: RestoreUi,
    devices: Map<String, DeviceSnapshot>,
    device: DeviceSnapshot,
    onDevice: (String) -> Unit,
    onMode: (RestoreMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionLabel("SNAPSHOT")
        if (devices.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.entries.sortedByDescending { it.value.savedAt }.forEach { (id, d) ->
                    val on = id == restore.deviceId
                    Mono(
                        (if (on) "● " else "○ ") + d.name.uppercase(Locale.US),
                        Modifier
                            .border(1.dp, if (on) SA.Accent else SA.Line2, SA.controlShape)
                            .clickable(role = Role.RadioButton) { onDevice(id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        size = 12.sp,
                        color = if (on) SA.Text else SA.Muted,
                        maxLines = 1,
                    )
                }
            }
        }
        val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(device.savedAt)).uppercase(Locale.US)
        Mono("${device.name.uppercase(Locale.US)} · $stamp · ${device.modules.size} MODULES", spacing = 1.sp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Mono("VERSION MODE", spacing = 1.3.sp)
            PairToggle(
                "AS RECORDED", "LATEST",
                if (restore.mode == RestoreMode.RECORDED) 0 else 1,
                { onMode(if (it == 0) RestoreMode.RECORDED else RestoreMode.LATEST) },
            )
        }
    }
}

@Composable
private fun ModuleList(restore: RestoreUi, device: DeviceSnapshot, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Mono("${restore.selected.size} OF ${device.modules.size} MODULES SELECTED", color = SA.Accent, spacing = 1.3.sp)
        if (device.modules.isEmpty()) {
            Text("This snapshot has no modules.", color = SA.Muted, fontSize = 14.sp)
        }
        device.modules.forEach { m ->
            ModuleRow(m, restore.installed[m.repo], m.repo in restore.selected, restore.progress == null) { onToggle(m.repo) }
        }
    }
}

@Composable
private fun ModuleRow(m: SnapModule, installed: String?, on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val status = statusOf(m, installed)
    val (label, color) = when (status) {
        ModStatus.MISSING -> "MISSING" to SA.Update
        ModStatus.INSTALLED -> "INSTALLED" to SA.Installed
        ModStatus.DIFFERENT -> "HAS ${installed ?: "?"}" to SA.Update
    }
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (status == ModStatus.INSTALLED && !on) 0.55f else 1f)
            .heightIn(min = 64.dp)
            .panel(border = if (on) SA.Accent else SA.CardLine, cut = 8.dp)
            .toggleable(value = on, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(
            checked = on,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = SA.Accent,
                uncheckedColor = SA.Muted,
                checkmarkColor = SA.Void,
            ),
        )
        Column(Modifier.weight(1f)) {
            Text(m.name, color = SA.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Mono("${displayRepo(m.repo)} · ${m.version ?: "?"}", maxLines = 1)
        }
        StatusChip(label, color)
    }
}

@Composable
private fun SettingsTick(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(
            checked = on,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = SA.Accent, uncheckedColor = SA.Muted, checkmarkColor = SA.Void),
        )
        Text("Also restore skin, Patch Roster and sources", color = SA.Text, fontSize = 16.sp)
    }
}
