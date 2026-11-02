package com.stellarite.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellarite.apps.data.AppState
import com.stellarite.apps.data.StoreApp
import com.stellarite.apps.data.Transfer
import com.stellarite.apps.data.displayRepo
import com.stellarite.apps.ui.theme.SA
import java.util.Locale

private val appIcons: List<ImageVector> = listOf(
    Icons.Outlined.Description, Icons.Outlined.Checklist, Icons.Outlined.Folder,
    Icons.Outlined.Schedule, Icons.Outlined.Apps, Icons.Outlined.Extension, Icons.Outlined.Terminal,
)

/** Stable per-app glyph, since apps don't ship their own artwork through GitHub releases. */
fun iconFor(name: String): ImageVector = appIcons[Math.floorMod(name.hashCode(), appIcons.size)]

fun formatSize(bytes: Long): String =
    if (bytes <= 0) "" else String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

@Composable
fun CatalogScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (Filter) -> Unit,
    onOpen: (StoreApp) -> Unit,
    onAction: (StoreApp) -> Unit,
    onSuggested: (SuggestedItem) -> Unit,
    onOpenArchives: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val q = state.query.trim()
    val visible = state.apps.filter { a ->
        (q.isEmpty() || a.repo.name.contains(q, true) || (a.repo.description ?: "").contains(q, true)) &&
            when (state.filter) {
                Filter.ALL -> true
                Filter.UPDATES -> a.state == AppState.UPDATE_AVAILABLE
                Filter.INSTALLED -> a.state != AppState.NOT_INSTALLED
            }
    }
    val updates = state.apps.count { it.state == AppState.UPDATE_AVAILABLE }
    val installed = state.apps.count { it.state != AppState.NOT_INSTALLED }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandLogo(if (SA.isSteam) 36.dp else 30.dp)
                Wordmark("STELLARITE", Modifier.weight(1f))
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(44.dp)
                        .background(SA.Panel, SA.tileShape)
                        .border(1.dp, if (SA.isSteam) SA.Line3 else SA.Line2, SA.tileShape),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Sync releases from GitHub", tint = SA.Text)
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (SA.isSteam) {
                        Box(Modifier.size(10.dp).background(SA.Installed, CircleShape).border(2.dp, SA.Line3, CircleShape))
                    } else {
                        Box(Modifier.size(6.dp).background(SA.Accent, CircleShape))
                    }
                    val text = when {
                        state.loading -> "SYNCING…"
                        else -> "SYNCED · ${state.upToDate}/${state.apps.size} MODULES"
                    }
                    Mono(if (SA.isSteam) text.lowercase().replaceFirstChar { it.uppercase() } else text, spacing = 1.sp, maxLines = 1)
                    if (!SA.isSteam && updates > 0 && !state.loading) {
                        StatusChip("● $updates UPDATE${if (updates == 1) "" else "S"}", SA.Update)
                    }
                }
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = SA.Accent, trackColor = SA.Line)
                } else {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(SA.Line))
                }
            }
        }

        item {
            SaField(
                value = state.query,
                onChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = if (SA.isSteam) "Search the archive" else "Search applications",
                leading = { Icon(Icons.Outlined.Search, null, tint = SA.Muted) },
                mono = false,
            )
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SA.Panel, SA.controlShape)
                    .border(1.dp, if (SA.isSteam) SA.CardLine else SA.Line, SA.controlShape)
            ) {
                Segment("ALL ${state.apps.size}", state.filter == Filter.ALL, Modifier.weight(1f)) { onFilter(Filter.ALL) }
                Segment("UPDATES $updates", state.filter == Filter.UPDATES, Modifier.weight(1f)) { onFilter(Filter.UPDATES) }
                Segment("INSTALLED $installed", state.filter == Filter.INSTALLED, Modifier.weight(1f)) { onFilter(Filter.INSTALLED) }
            }
        }

        if (state.error != null) {
            item {
                Column(
                    Modifier.fillMaxWidth().panel(border = SA.Alert).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Mono("UPLINK FAILED", color = SA.Alert, size = 12.sp, spacing = 1.6.sp)
                    Text(state.error, color = SA.Text, fontSize = 14.sp)
                    SaButton("RETRY", onRefresh, style = BtnStyle.OutlineAlert)
                }
            }
        }

        items(visible, key = { it.repo.fullName }) { app ->
            AppCard(
                app = app,
                transfer = state.transfers[app.repo.fullName],
                onOpen = { onOpen(app) },
                onAction = { onAction(app) },
            )
        }

        if (!state.loading && state.error == null && visible.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().panel().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Mono("NO MODULES FOUND", size = 12.sp, spacing = 1.6.sp)
                    Text(
                        if (state.apps.isEmpty())
                            "${state.source} has no repos whose latest release includes an APK."
                        else "Nothing matches this filter.",
                        color = SA.Text,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        if (q.isEmpty() && state.filter == Filter.ALL && state.pending.isNotEmpty()) {
            items(state.pending, key = { "pending-" + it.fullName }) { PendingCard(it) }
        }

        val offers = state.suggested.filter { s -> !s.installed && state.apps.none { it.repo.fullName == s.s.key } }
        if (q.isEmpty() && state.filter == Filter.ALL && offers.isNotEmpty()) {
            item(key = "suggested-label") { SectionLabel("SUGGESTED", Modifier.padding(top = 6.dp)) }
            items(offers, key = { "suggested-" + it.s.key }) { s ->
                SuggestedCard(s, state.transfers[s.s.key], onAction = { onSuggested(s) })
            }
            item(key = "suggested-note") {
                Mono(
                    "Prefer to browse their whole catalog from here? Open ARCHIVES.",
                    Modifier.clickable(onClick = onOpenArchives).padding(vertical = 6.dp),
                    spacing = 0.8.sp,
                )
            }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .background(if (selected) SA.PanelRaised else Color.Transparent)
            .drawBehind {
                if (selected) {
                    val h = 2.dp.toPx()
                    drawRect(SA.Accent, Offset(0f, size.height - h), Size(size.width, h))
                }
            }
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Mono(
            label,
            size = 12.sp,
            color = if (selected) SA.Text else SA.Muted,
            spacing = 1.sp,
            weight = if (SA.isSteam) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun AppCard(app: StoreApp, transfer: Transfer?, onOpen: () -> Unit, onAction: () -> Unit) {
    val tint = when (app.state) {
        AppState.INSTALLED -> SA.Installed
        AppState.UPDATE_AVAILABLE -> SA.Update
        AppState.NOT_INSTALLED -> SA.Muted
    }
    val tag = app.release.tagName
    val statusText = when (app.state) {
        AppState.INSTALLED -> "$tag · Installed"
        AppState.UPDATE_AVAILABLE -> "${app.installedVersion} → $tag"
        AppState.NOT_INSTALLED -> listOf(tag, formatSize(app.apk.size)).filter { it.isNotEmpty() }.joinToString(" · ")
    }
    Row(
        Modifier.fillMaxWidth().panel(accent = true).clickable(onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .background(SA.Void, SA.tileShape)
                .border(SA.tileBorder, if (SA.isSteam) SA.Line3 else SA.Line2, SA.tileShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(app.repo.name), null, tint = tint, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                app.repo.name,
                color = SA.Text,
                fontSize = if (SA.isSteam) 18.sp else 16.sp,
                fontWeight = if (SA.isSteam) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Mono(displayRepo(app.repo.fullName), Modifier.padding(top = 2.dp), maxLines = 1)
            Spacer(Modifier.height(6.dp))
            if (transfer is Transfer.WaitingWifi) {
                Mono("$statusText · WAITING FOR WI-FI", color = SA.Update, maxLines = 1)
            } else {
                Mono(statusText, color = if (app.state == AppState.NOT_INSTALLED) SA.Muted else tint, maxLines = 1)
            }
        }
        ActionButton(app, transfer, onAction)
    }
}

@Composable
fun ActionButton(app: StoreApp, transfer: Transfer?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val m = modifier.widthIn(min = 88.dp)
    when (transfer) {
        is Transfer.Downloading ->
            SaButton("${(transfer.progress * 100).toInt()}%", {}, m, BtnStyle.OutlineLight, enabled = false)
        Transfer.Installing -> SaButton("…", {}, m, BtnStyle.OutlineLight, enabled = false)
        Transfer.WaitingWifi -> SaButton("DOWNLOAD\nANYWAY", onAction, modifier.widthIn(min = 112.dp), BtnStyle.OutlineLight)
        is Transfer.Failed -> SaButton("RETRY", onAction, m, BtnStyle.OutlineAlert)
        null -> when (app.state) {
            AppState.INSTALLED -> SaButton("OPEN", onAction, m, BtnStyle.OutlineLight)
            AppState.UPDATE_AVAILABLE -> SaButton("UPDATE", onAction, m, BtnStyle.Update)
            AppState.NOT_INSTALLED -> SaButton("INSTALL", onAction, m, BtnStyle.Accent)
        }
    }
}
