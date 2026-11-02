package com.stellarite.apps.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.stellarite.apps.BuildConfig
import com.stellarite.apps.Config
import com.stellarite.apps.data.Source
import com.stellarite.apps.data.SourceType
import com.stellarite.apps.ui.theme.SA
import com.stellarite.apps.ui.theme.ThemeId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    initialUsername: String,
    initialToken: String,
    onTheme: (ThemeId) -> Unit,
    autoPatch: Boolean,
    rosterSummary: String,
    onAutoPatch: (Boolean) -> Unit,
    onOpenRoster: () -> Unit,
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    screenshotsWifiOnly: Boolean,
    onScreenshotsWifiOnly: (Boolean) -> Unit,
    updateAlerts: Boolean,
    onUpdateAlerts: (Boolean) -> Unit,
    sources: SourcesPanel,
    onSourceEnabled: (String, Boolean) -> Unit,
    onSourceRemove: (String) -> Unit,
    onSourceEdit: (Source) -> Unit,
    onAddSource: () -> Unit,
    vault: VaultUi,
    onVaultTarget: (String) -> Unit,
    onAutoSnapshot: (Boolean) -> Unit,
    onSnapshotNow: () -> Unit,
    onRestore: () -> Unit,
    onSave: (username: String, token: String) -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var username by remember { mutableStateOf(initialUsername) }
    var token by remember { mutableStateOf(initialToken) }
    var canInstall by remember { mutableStateOf(ctx.packageManager.canRequestPackageInstalls()) }

    LifecycleResumeEffect(Unit) {
        canInstall = ctx.packageManager.canRequestPackageInstalls()
        onPauseOrDispose { }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandLogo(if (SA.isSteam) 36.dp else 30.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Wordmark("CONTROL CORE")
                Mono("STORED ON THIS DEVICE ONLY", spacing = 1.5.sp)
            }
        }

        Panel("UPLINK") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Mono("OPERATOR HANDLE (BLANK = HOME ARCHIVE)", spacing = 1.3.sp)
                SaField(username, { username = it }, Modifier.fillMaxWidth(), placeholder = "Home Archive")
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Mono("ACCESS KEY (OPTIONAL)", spacing = 1.3.sp)
                SaField(token, { token = it }, Modifier.fillMaxWidth(), password = true)
                Text(
                    "Sign in with a key to browse your own repos, including private ones, and to avoid GitHub's 60 requests per hour limit. Leave both blank to return to the Home Archive.",
                    color = SA.Muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        SourcesSection(sources, onSourceEnabled, onSourceRemove, onSourceEdit, onAddSource)

        Panel("VISOR SKIN") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeId.entries.forEach { t ->
                    ThemeTile(t, selected = SA.theme == t, modifier = Modifier.weight(1f)) { onTheme(t) }
                }
            }
        }

        Panel("AUTO-PATCH") {
            SettingRow("Auto-Patch Protocol", "Modules patch themselves in the background") {
                SaSwitch(autoPatch, onAutoPatch, "Auto-Patch Protocol")
            }
            Divider()
            SettingRow("Patch Roster", rosterSummary) {
                SaButton("CONFIGURE", onOpenRoster, style = BtnStyle.OutlineLight)
            }
        }

        Panel("DOWNLOADS & ALERTS") {
            SettingRow("Wi-Fi only downloads", "Large downloads wait until you're on Wi-Fi. You can still tap DOWNLOAD ANYWAY on any one of them.") {
                SaSwitch(wifiOnly, onWifiOnly, "Wi-Fi only downloads")
            }
            Divider()
            SettingRow("Screenshots on Wi-Fi only", "F-Droid screenshots are separate image downloads. Turn off to always load them.") {
                SaSwitch(screenshotsWifiOnly, onScreenshotsWifiOnly, "Screenshots on Wi-Fi only")
            }
            Divider()
            SettingRow("Update alerts", "With Auto-Patch off, get a notification when modules have updates. Nothing installs by itself.") {
                SaSwitch(updateAlerts, onUpdateAlerts, "Update alerts")
            }
        }

        VaultPanel(vault, onVaultTarget, onAutoSnapshot, onSnapshotNow, onRestore)

        Panel("DEPLOYMENT CORE") {
            SettingRow("Deploy Clearance", "Android asks once per source app") {
                if (canInstall) {
                    Mono("GRANTED", Modifier.borderBadge(SA.Installed), color = SA.Installed, spacing = 1.sp)
                } else {
                    SaButton("ALLOW", {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        )
                    }, style = BtnStyle.OutlineAlert)
                }
            }
            Divider()
            SettingRow("Payload Cache", "Cached installers on this device") {
                SaButton("PURGE", onClearCache, style = BtnStyle.OutlineAlert)
            }
        }

        SaButton("COMMIT & SYNC", { onSave(username, token) }, Modifier.fillMaxWidth(), BtnStyle.Accent, height = 54.dp, glow = true)

        Mono(
            "STELLARITE APPS v${BuildConfig.VERSION_NAME} · TEST BUILD",
            Modifier.fillMaxWidth(),
            spacing = 1.4.sp,
        )
    }
}

private fun iconOf(type: SourceType): ImageVector = when (type) {
    SourceType.ACCOUNT -> Icons.Outlined.Person
    SourceType.REPO -> Icons.Outlined.Folder
    SourceType.STARS -> Icons.Outlined.StarBorder
    SourceType.FDROID -> Icons.Outlined.Storage
}

private fun tagOf(type: SourceType): String = when (type) {
    SourceType.ACCOUNT -> "ACCOUNT"
    SourceType.REPO -> "REPO"
    SourceType.STARS -> "STARS"
    SourceType.FDROID -> "INDEX"
}

private fun modulesText(n: Int) = "$n ${if (n == 1) "MODULE" else "MODULES"}"

@Composable
private fun SourcesSection(
    panel: SourcesPanel,
    onEnabled: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (Source) -> Unit,
    onAdd: () -> Unit,
) {
    Panel("SOURCES") {
        SourceRow(
            icon = Icons.Outlined.Person,
            name = panel.homeTitle,
            sub = panel.homeSub,
            tag = "MAIN",
            count = modulesText(panel.homeCount),
            enabled = true,
            home = true,
        )
        panel.sources.forEach { s ->
            Divider()
            SourceRow(
                icon = iconOf(s.type),
                name = s.label,
                sub = when (s.type) {
                    SourceType.ACCOUNT -> "github.com/${s.value}"
                    SourceType.REPO -> "github.com/${s.value}"
                    SourceType.STARS -> "needs your Access Key"
                    SourceType.FDROID -> s.value.removePrefix("https://")
                },
                tag = tagOf(s.type),
                count = when {
                    !s.enabled -> "PAUSED"
                    s.type == SourceType.FDROID -> "${s.picks.size} PICKED"
                    else -> modulesText(panel.counts[s.id] ?: 0)
                },
                enabled = s.enabled,
                error = panel.errors[s.id],
                editLabel = if (s.type == SourceType.FDROID) "PICK APPS" else null,
                onEdit = { onEdit(s) },
                onRemove = { onRemove(s.id) },
                onToggle = { onEnabled(s.id, it) },
            )
        }
        SaButton("+ ADD SOURCE", onAdd, Modifier.fillMaxWidth(), BtnStyle.OutlineLight, height = 54.dp)
        Text(
            "Only add sources you trust. Stellarite installs whatever a source publishes, and Android only accepts updates signed with the same key.",
            color = SA.Muted, fontSize = 13.sp, lineHeight = 18.sp,
        )
    }
}

@Composable
private fun SourceRow(
    icon: ImageVector,
    name: String,
    sub: String,
    tag: String,
    count: String,
    enabled: Boolean,
    home: Boolean = false,
    error: String? = null,
    editLabel: String? = null,
    onEdit: () -> Unit = {},
    onRemove: () -> Unit = {},
    onToggle: ((Boolean) -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .background(SA.Void, SA.tileShape)
                .border(1.dp, if (SA.isSteam) SA.Line3 else SA.Line2, SA.tileShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (enabled) SA.Accent else SA.Muted, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(name, color = SA.Text, fontSize = if (SA.isSteam) 17.sp else 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Mono(sub, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(tag, SA.Muted)
                Mono(count, maxLines = 1)
                if (home) StatusChip("HOME", SA.Installed)
            }
            if (!home) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (editLabel != null) {
                        Mono(
                            editLabel,
                            Modifier.clickable(role = Role.Button, onClick = onEdit).padding(vertical = 10.dp),
                            color = SA.Accent, weight = FontWeight.SemiBold,
                        )
                    }
                    Mono(
                        "REMOVE",
                        Modifier.clickable(role = Role.Button, onClick = onRemove).padding(vertical = 10.dp),
                        color = SA.Alert, weight = FontWeight.SemiBold,
                    )
                }
            }
            if (error != null) Text(error, color = SA.Alert, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (onToggle != null) SaSwitch(enabled, onToggle, "Use $name")
    }
}

@Composable
private fun VaultPanel(
    vault: VaultUi,
    onTarget: (String) -> Unit,
    onAutoSnapshot: (Boolean) -> Unit,
    onSnapshotNow: () -> Unit,
    onRestore: () -> Unit,
) {
    val github = vault.target == "github"
    val canUse = !github || vault.hasKey
    val stamp = if (vault.lastAt > 0L) {
        SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(vault.lastAt)).uppercase(Locale.US)
    } else null

    Panel("BACKUP VAULT") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Mono("SYNC TARGET", spacing = 1.3.sp)
            PairToggle("FILE", "GITHUB", if (github) 1 else 0, { onTarget(if (it == 1) "github" else "file") })
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(SA.Void, SA.controlShape)
                .border(1.dp, SA.Line, SA.controlShape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (github) {
                Mono(
                    "${vault.owner ?: "your-handle"}/${Config.BACKUP_REPO}",
                    size = 13.sp, color = SA.Text, spacing = 0.sp,
                )
                Text("Private repo in your GitHub, created on first snapshot", color = SA.Muted, fontSize = 13.sp)
            } else {
                Mono(Config.BACKUP_FILE_NAME, size = 13.sp, color = SA.Text, spacing = 0.sp)
                Text(
                    "Saved wherever you choose: Drive, Syncthing, a USB stick or a NAS",
                    color = SA.Muted, fontSize = 13.sp,
                )
            }
        }

        if (github && !vault.hasKey) {
            Text("Add an Access Key in Uplink and commit it to use GitHub backup.", color = SA.Update, fontSize = 13.sp)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vault.linked) StatusChip("● LINKED", SA.Installed) else StatusChip("○ NOT LINKED", SA.Muted)
            Mono(
                if (stamp != null) "LAST SNAPSHOT · $stamp · ${vault.lastCount} MODULES" else "NO SNAPSHOT YET",
                spacing = 1.sp,
            )
        }
        vault.note?.let { Text(it, color = SA.Alert, fontSize = 13.sp, lineHeight = 18.sp) }

        if (github) {
            Divider()
            SettingRow("Auto-Snapshot", "Save after installs, removals and roster changes") {
                SaSwitch(vault.autoSnapshot, onAutoSnapshot, "Auto-Snapshot")
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SaButton(
                if (vault.busy) "SAVING…" else "SNAPSHOT NOW", onSnapshotNow,
                Modifier.weight(1f), BtnStyle.Accent, enabled = canUse && !vault.busy, height = 54.dp, glow = true,
            )
            SaButton("RESTORE", onRestore, Modifier.weight(1f), BtnStyle.OutlineLight, enabled = canUse && !vault.busy, height = 54.dp)
        }
        Text(
            "Stores your module list, versions, skin and Patch Roster. Never your Access Key or any app data.",
            color = SA.Muted, fontSize = 13.sp, lineHeight = 18.sp,
        )
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionLabel(title)
        content()
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SA.Line))
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = SA.Text,
                fontSize = if (SA.isSteam) 17.sp else 15.sp,
                fontWeight = if (SA.isSteam) FontWeight.SemiBold else FontWeight.Medium,
            )
            Text(subtitle, color = SA.Muted, fontSize = 13.sp)
        }
        trailing()
    }
}

@Composable
private fun ThemeTile(theme: ThemeId, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val swatches: List<Color> = when (theme) {
        ThemeId.CYBER -> listOf(Color(0xFF0C0F14), Color(0xFF4DE8F4), Color(0xFFF2384F), Color(0xFFE6E9EE))
        ThemeId.STEAM -> listOf(Color(0xFF2A1F17), Color(0xFFD9824A), Color(0xFFB08D57), Color(0xFFF0E3C8))
    }
    val shape = SA.controlShape
    Column(
        modifier
            .heightIn(min = 96.dp)
            .background(SA.Void, shape)
            .border(if (selected && SA.isSteam) 2.dp else 1.dp, if (selected) SA.Accent else SA.Line2, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            swatches.forEach { c ->
                Box(Modifier.size(width = 22.dp, height = 8.dp).background(c).border(1.dp, SA.Line2))
            }
        }
        Mono(theme.label, size = 12.sp, color = SA.Text, spacing = 1.sp, weight = FontWeight.Medium)
        Mono(
            if (selected) "● ACTIVE" else "○ STANDBY",
            color = if (selected) SA.Accent else SA.Muted,
            spacing = 1.sp,
        )
    }
}
