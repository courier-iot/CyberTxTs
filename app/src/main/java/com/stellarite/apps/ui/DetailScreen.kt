package com.stellarite.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellarite.apps.data.AppState
import com.stellarite.apps.data.FDROID_PREFIX
import com.stellarite.apps.data.ReadmeState
import com.stellarite.apps.data.StoreApp
import com.stellarite.apps.data.Transfer
import com.stellarite.apps.data.displayRepo
import com.stellarite.apps.ui.theme.SA

private const val README_MAX_CHARS = 12_000

@Composable
fun DetailScreen(
    app: StoreApp,
    transfer: Transfer?,
    readme: ReadmeState?,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onOpenApp: () -> Unit,
    onUninstall: () -> Unit,
    onRetryReadme: () -> Unit,
    fdroid: FDroidDetailsState? = null,
    holdImages: Boolean = false,
    onLoadFdroid: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uri = LocalUriHandler.current
    val tag = app.release.tagName

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(44.dp)
                    .background(SA.Panel, SA.tileShape)
                    .border(1.dp, if (SA.isSteam) SA.Line3 else SA.Line2, SA.tileShape),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to catalog", tint = SA.Text)
            }
            Mono(
                "APPLICATION RECORD · ${app.repo.name.take(3).uppercase()}-${(app.apk.id % 10000).toString().padStart(4, '0')}",
                size = 12.sp, spacing = 1.9.sp, maxLines = 1,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(80.dp)
                    .background(if (SA.isSteam) SA.Void else SA.Panel, SA.tileShape)
                    .border(SA.tileBorder, if (SA.isSteam) SA.Line3 else SA.Accent, SA.tileShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(iconFor(app.repo.name), null, tint = SA.Installed, modifier = Modifier.size(40.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(app.repo.name, color = SA.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Mono(displayRepo(app.repo.fullName), size = 12.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(tag, SA.Accent)
                    when {
                        transfer is Transfer.Downloading || transfer == Transfer.Installing -> Badge("IN TRANSIT", SA.Update)
                        app.state == AppState.INSTALLED -> Badge("INSTALLED", SA.Muted)
                        app.state == AppState.UPDATE_AVAILABLE -> Badge("UPDATE READY", SA.Update)
                        else -> Badge("NOT INSTALLED", SA.Muted)
                    }
                }
            }
        }

        when (transfer) {
            is Transfer.Downloading, Transfer.Installing -> TransferPanel(app, transfer, onCancel)
            else -> {
                if (transfer is Transfer.Failed) {
                    Column(
                        Modifier.fillMaxWidth().panel(border = SA.Alert).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Mono("TRANSFER FAILED", color = SA.Alert, size = 12.sp, spacing = 1.6.sp)
                        Text(transfer.message, color = SA.Text, fontSize = 14.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val size = formatSize(app.apk.size)
                    when {
                        transfer is Transfer.WaitingWifi ->
                            SaButton("WAITING FOR WI-FI · DOWNLOAD ANYWAY", onInstall, Modifier.fillMaxWidth(), BtnStyle.Update, height = 54.dp, glow = true)
                        transfer is Transfer.Failed ->
                            SaButton("RETRY", onInstall, Modifier.fillMaxWidth(), BtnStyle.Accent, height = 54.dp, glow = true)
                        app.state == AppState.NOT_INSTALLED ->
                            SaButton(
                                if (size.isEmpty()) "INSTALL" else "INSTALL · $size",
                                onInstall, Modifier.fillMaxWidth(), BtnStyle.Accent, height = 54.dp, glow = true,
                            )
                        app.state == AppState.UPDATE_AVAILABLE -> {
                            SaButton("UPDATE TO $tag", onInstall, Modifier.fillMaxWidth(), BtnStyle.Update, height = 54.dp, glow = true)
                            SaButton("OPEN", onOpenApp, Modifier.fillMaxWidth(), BtnStyle.OutlineLight)
                        }
                        else -> SaButton("OPEN", onOpenApp, Modifier.fillMaxWidth(), BtnStyle.Accent, height = 54.dp, glow = true)
                    }
                    SaButton(
                        if (app.repo.fullName.startsWith(FDROID_PREFIX)) "VIEW SOURCE PAGE" else "VIEW ON GITHUB",
                        { uri.openUri(app.repo.htmlUrl) },
                        Modifier.fillMaxWidth(),
                        BtnStyle.OutlineLight,
                    )
                    if (app.state != AppState.NOT_INSTALLED && app.packageName != null) {
                        SaButton("UNINSTALL", onUninstall, Modifier.fillMaxWidth(), BtnStyle.OutlineAlert)
                    }
                }
            }
        }

        MetaGrid(
            listOf(
                "VERSION" to tag,
                "SIZE" to formatSize(app.apk.size).ifEmpty { "—" },
                "RELEASED" to (app.release.publishedAt?.take(10) ?: "—"),
                "INSTALLED" to (app.installedVersion ?: app.packageName ?: "Not on this device"),
            )
        )

        Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("RELEASE NOTES · $tag")
            val notes = app.release.body?.trim().orEmpty()
            Text(
                when {
                    notes.isEmpty() -> app.repo.description ?: "No release notes provided."
                    notes.length > 700 -> notes.take(700).trimEnd() + "…"
                    else -> notes
                },
                color = SA.Text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }

        if (app.repo.fullName.startsWith(FDROID_PREFIX)) {
            FDroidSections(
                state = fdroid,
                holdImages = holdImages,
                onLoad = onLoadFdroid,
                sourceHost = app.repo.htmlUrl.substringAfter("://").substringBefore('/'),
            )
        }

        // F-Droid apps have no GitHub README, so this panel only shows for GitHub modules.
        if (!app.repo.fullName.startsWith(FDROID_PREFIX)) Column(
            Modifier.fillMaxWidth().panel().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("README")
            Mono("● LIVE FROM GITHUB · ${displayRepo(app.repo.fullName)}", color = SA.Accent, spacing = 1.sp, maxLines = 1)
            when (readme) {
                null, ReadmeState.Loading -> Mono("FETCHING README…", size = 12.sp, spacing = 1.4.sp)
                ReadmeState.Missing -> Text(
                    "This repo doesn't have a README yet.",
                    color = SA.Muted, fontSize = 14.sp,
                )
                is ReadmeState.Failed -> {
                    Text(readme.message, color = SA.Text, fontSize = 14.sp)
                    SaButton("RETRY", onRetryReadme, style = BtnStyle.OutlineAlert)
                }
                is ReadmeState.Loaded -> {
                    val truncated = readme.text.length > README_MAX_CHARS
                    MarkdownView(readme.text.take(README_MAX_CHARS), app.repo.htmlUrl)
                    if (truncated) {
                        Mono("README TRIMMED — SEE GITHUB FOR THE REST", spacing = 1.sp)
                    }
                    SaButton(
                        "OPEN FULL README ON GITHUB",
                        { uri.openUri(app.repo.htmlUrl + "#readme") },
                        Modifier.fillMaxWidth(),
                        BtnStyle.OutlineLight,
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Mono(
        text,
        Modifier
            .border(1.dp, color, SA.controlShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        spacing = 0.9.sp,
        maxLines = 1,
    )
}

@Composable
private fun MetaGrid(items: List<Pair<String, String>>) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (SA.isSteam) SA.CardLine else SA.Line, SA.controlShape)
            .background(SA.Panel)
    ) {
        items.chunked(2).forEachIndexed { rowIdx, pair ->
            if (rowIdx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SA.Line))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                pair.forEachIndexed { i, (label, value) ->
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(SA.Line))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Mono(label, spacing = 1.5.sp)
                        Text(
                            value,
                            color = SA.Text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferPanel(app: StoreApp, transfer: Transfer?, onCancel: () -> Unit) {
    val progress = (transfer as? Transfer.Downloading)?.progress ?: 1f
    val total = app.apk.size
    Column(
        Modifier.fillMaxWidth().panel(border = SA.Accent).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Mono(if (transfer is Transfer.Downloading) "DOWNLOADING" else "HANDING OFF", size = 12.sp, spacing = 1.7.sp)
            Mono("${(progress * 100).toInt()}%", size = 22.sp, color = SA.Text, weight = FontWeight.Medium)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(SA.Line)) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).background(SA.Accent))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Mono(
                if (total > 0) "${formatSize((total * progress).toLong())} / ${formatSize(total)}" else "",
                spacing = 0.7.sp,
            )
            Mono(app.apk.name, spacing = 0.7.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false).padding(start = 12.dp))
        }
        SaButton("CANCEL TRANSFER", onCancel, Modifier.fillMaxWidth(), BtnStyle.OutlineAlert, height = 48.dp)
        SectionLabel("TRANSFER SEQUENCE")
        val active = if (transfer is Transfer.Downloading) 1 else 3
        listOf(if (app.repo.fullName.startsWith(FDROID_PREFIX)) "Fetch build from the F-Droid index" else "Fetch release from GitHub", "Download APK", "Verify package", "Hand off to Android installer")
            .forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val (icon, tint) = when {
                        i < active -> Icons.Outlined.CheckCircle to SA.Accent
                        i == active -> Icons.Outlined.RadioButtonChecked to SA.Accent
                        else -> Icons.Outlined.RadioButtonUnchecked to SA.Muted
                    }
                    Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                    Text(label, color = if (i <= active) SA.Text else SA.Muted, fontSize = 15.sp)
                }
            }
    }
}
