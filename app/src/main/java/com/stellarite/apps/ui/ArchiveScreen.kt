package com.stellarite.apps.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.stellarite.apps.data.ArchiveApp
import com.stellarite.apps.ui.theme.SA
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** Browse a whole F-Droid-style repository A to Z and tick the apps that should join the catalog. */
@Composable
fun ArchiveScreen(
    ui: ArchiveUi,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onFilters: (ArchiveFilters) -> Unit,
    onShowFilters: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDownloadNow: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = SA.Text)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Wordmark(ui.label.uppercase(Locale.US))
                Mono(
                    when (ui.phase) {
                        ArchivePhase.READY -> "ARCHIVE · A TO Z"
                        ArchivePhase.DATA_WARNING -> "FIRST-TIME SYNC"
                        ArchivePhase.SYNC -> "SYNCING"
                        ArchivePhase.ERROR -> "SYNC FAILED"
                    },
                    spacing = 1.5.sp,
                )
            }
            if (ui.phase == ArchivePhase.READY) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Re-sync the index", tint = SA.Text)
                }
            }
        }

        when (ui.phase) {
            ArchivePhase.READY -> Browse(ui, onQuery, onFilters, onShowFilters, onToggle, onConfirm)
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (ui.phase == ArchivePhase.DATA_WARNING) DataWarning(ui, onBack, onDownloadNow)
                if (ui.phase == ArchivePhase.ERROR) {
                    Column(Modifier.fillMaxWidth().panel(border = SA.Alert).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionLabel("COULD NOT SYNC")
                        Text(ui.error ?: "Unknown error", color = SA.Alert, fontSize = 14.sp, lineHeight = 20.sp)
                        SaButton("TRY AGAIN", onRefresh, Modifier.fillMaxWidth(), BtnStyle.OutlineLight, height = 54.dp)
                    }
                }
                if (ui.phase == ArchivePhase.SYNC) SyncPanel(ui)
            }
        }
    }

    if (ui.showFilters) FilterSheet(ui, onFilters) { onShowFilters(false) }
}

@Composable
private fun DataWarning(ui: ArchiveUi, onWait: () -> Unit, onGo: () -> Unit) {
    Column(Modifier.fillMaxWidth().panel(border = SA.Update).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusChip("MOBILE DATA", SA.Update)
        val size = if (ui.sizeBytes > 0) "about ${ui.sizeBytes / 1_000_000} MB" else "large"
        Text("The index is $size", color = SA.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(
            "You are not on Wi-Fi. It is downloaded once, kept on this device and refreshed once a day.",
            color = SA.Muted, fontSize = 14.sp, lineHeight = 20.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SaButton("WAIT FOR WI-FI", onWait, Modifier.weight(1f), BtnStyle.OutlineLight, height = 48.dp)
            SaButton("DOWNLOAD NOW", onGo, Modifier.weight(1f), BtnStyle.Accent, height = 48.dp, glow = true)
        }
    }
}

@Composable
private fun SyncPanel(ui: ArchiveUi) {
    Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionLabel("INDEX SYNC")
        if (ui.step == 0 && ui.total > 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Mono("DOWNLOADING", color = SA.Accent, spacing = 1.3.sp)
                Mono("${ui.bytes / 1_000_000} / ${ui.total / 1_000_000} MB", spacing = 1.3.sp)
            }
            LinearProgressIndicator(
                progress = { (ui.bytes.toFloat() / ui.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp), color = SA.Accent, trackColor = SA.Line,
            )
        } else {
            if (ui.step == 0 && ui.bytes > 0) Mono("DOWNLOADING · ${ui.bytes / 1_000_000} MB", color = SA.Accent, spacing = 1.3.sp)
            LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp), color = SA.Accent, trackColor = SA.Line)
        }
        Step("Download index", "From the repository", ui.step, 0)
        Step("Verify signature", "Checked against the repository's signing key", ui.step, 1)
        Step("Build list", "Prepared in memory so A to Z scrolling is instant", ui.step, 2)
        Text(
            "If the signature check fails, the index is rejected and nothing is installed from it.",
            color = SA.Muted, fontSize = 13.sp, lineHeight = 18.sp,
        )
    }
}

@Composable
private fun Step(title: String, sub: String, current: Int, index: Int) {
    val done = current > index
    val now = current == index
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Mono(
            if (done) "✓" else if (now) "●" else "○",
            modifier = Modifier.width(18.dp),
            size = 15.sp,
            color = if (done) SA.Installed else if (now) SA.Accent else SA.Muted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (done || now) SA.Text else SA.Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = SA.Muted, fontSize = 13.sp)
        }
    }
}

// ---- Browse ---------------------------------------------------------------------------------

private sealed interface Row2 {
    data class Header(val letter: Char) : Row2
    data class Item(val app: ArchiveApp) : Row2
}

private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#"

private fun letterOf(name: String): Char {
    val c = name.firstOrNull()?.uppercaseChar() ?: '#'
    return if (c in 'A'..'Z') c else '#'
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Browse(
    ui: ArchiveUi,
    onQuery: (String) -> Unit,
    onFilters: (ArchiveFilters) -> Unit,
    onShowFilters: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val f = ui.filters
    val grouped = f.sort != ArchiveSort.NEWEST
    val rows = remember(ui.shown, grouped) {
        val out = ArrayList<Row2>(ui.shown.size + 30)
        var last: Char? = null
        for (a in ui.shown) {
            if (grouped) {
                val l = letterOf(a.name)
                if (l != last) { out += Row2.Header(l); last = l }
            }
            out += Row2.Item(a)
        }
        out
    }
    val headerAt = remember(rows) {
        val m = HashMap<Char, Int>()
        rows.forEachIndexed { i, r -> if (r is Row2.Header) m[r.letter] = i }
        m
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var railH by remember { mutableIntStateOf(1) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SaField(ui.query, onQuery, Modifier.fillMaxWidth(), placeholder = "Search ${NumberFormat.getIntegerInstance().format(ui.all.size)} apps")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(if (f.fitsDevice) "✓ FITS THIS DEVICE" else "FITS THIS DEVICE", f.fitsDevice) { onFilters(f.copy(fitsDevice = !f.fitsDevice)) }
                FilterChip("CATEGORY${f.category?.let { ": ${it.uppercase(Locale.US)}" } ?: " ▾"}", f.category != null) { onShowFilters(true) }
                FilterChip("UPDATED${if (f.updated != UpdatedRange.ANY) ": ${updatedWord(f.updated)}" else " ▾"}", f.updated != UpdatedRange.ANY) { onShowFilters(true) }
                FilterChip("SORT: ${sortWord(f.sort)} ▾", false) { onShowFilters(true) }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Text(
                    "Nothing matches these filters.",
                    color = SA.Muted, fontSize = 14.sp,
                    modifier = Modifier.padding(20.dp),
                )
            }
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = if (grouped) 38.dp else 20.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEachIndexed { i, r ->
                    when (r) {
                        is Row2.Header -> stickyHeader(key = "h${r.letter}") {
                            Box(Modifier.fillMaxWidth().background(SA.Void.copy(alpha = 0.94f)).padding(vertical = 4.dp)) {
                                Mono(r.letter.toString(), size = 13.sp, color = SA.Accent, spacing = 3.sp, weight = FontWeight.SemiBold)
                            }
                        }
                        is Row2.Item -> item(key = r.app.pkg) {
                            AppRow(r.app, r.app.pkg in ui.selected) { onToggle(r.app.pkg) }
                        }
                    }
                }
            }

            if (grouped && rows.isNotEmpty()) {
                fun jump(y: Float) {
                    val idx = (y / railH * LETTERS.length).toInt().coerceIn(0, LETTERS.length - 1)
                    val target = (idx until LETTERS.length).firstNotNullOfOrNull { headerAt[LETTERS[it]] }
                        ?: (idx downTo 0).firstNotNullOfOrNull { headerAt[LETTERS[it]] }
                    if (target != null) scope.launch { state.scrollToItem(target) }
                }
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(vertical = 8.dp)
                        .fillMaxHeight()
                        .width(24.dp)
                        .onSizeChanged { railH = it.height.coerceAtLeast(1) }
                        .pointerInput(rows) { detectTapGestures { jump(it.y) } }
                        .pointerInput(rows) { detectVerticalDragGestures { change, _ -> jump(change.position.y) } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    LETTERS.forEach { c ->
                        Mono(c.toString(), size = 9.sp, color = if (c in headerAt) SA.Accent else SA.Line3, spacing = 0.sp)
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(SA.Nav)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Mono("${ui.selected.size} SELECTED", color = SA.Accent, spacing = 1.3.sp)
                Text(
                    "${NumberFormat.getIntegerInstance().format(ui.shown.size)} of ${NumberFormat.getIntegerInstance().format(ui.all.size)} shown",
                    color = SA.Muted, fontSize = 13.sp,
                )
            }
            SaButton(
                if (ui.selected.isEmpty()) "SAVE" else "ADD ${ui.selected.size} ${if (ui.selected.size == 1) "APP" else "APPS"}",
                onConfirm, Modifier.width(170.dp), BtnStyle.Accent, height = 48.dp, glow = true,
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = if (on) SA.Accent else SA.Muted
    Mono(
        label,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .border(1.dp, c, SA.controlShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        size = 11.sp, color = c, spacing = 1.sp, weight = FontWeight.SemiBold, maxLines = 1,
    )
}

@Composable
private fun AppRow(app: ArchiveApp, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .panel(border = if (on) SA.Accent else SA.CardLine, cut = 8.dp)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(
            checked = on,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = SA.Accent, uncheckedColor = SA.Muted, checkmarkColor = SA.Void),
        )
        Box(
            Modifier
                .size(44.dp)
                .clip(if (SA.isSteam) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(2.dp))
                .background(SA.Void)
                .border(1.dp, SA.Line2, if (SA.isSteam) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // The letter shows until (or unless) the real icon loads on top of it.
            Mono(app.name.take(1).uppercase(Locale.US), size = 18.sp, color = SA.Accent, weight = FontWeight.SemiBold)
            if (app.iconUrl != null) {
                AsyncImage(model = app.iconUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(app.name, color = SA.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Mono(app.pkg, size = 10.5.sp, maxLines = 1)
            if (app.summary.isNotBlank()) Text(app.summary, color = SA.Muted, fontSize = 13.sp, maxLines = 1)
            if (!app.fits || app.flagged) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                    if (!app.fits) StatusChip("NOT FOR THIS DEVICE", SA.Muted)
                    if (app.flagged) StatusChip("ANTI-FEATURES", SA.Update)
                }
            }
        }
    }
}

private fun updatedWord(u: UpdatedRange) = when (u) {
    UpdatedRange.ANY -> "ANY"
    UpdatedRange.YEAR -> "THIS YEAR"
    UpdatedRange.MONTH -> "THIS MONTH"
}

private fun sortWord(s: ArchiveSort) = when (s) {
    ArchiveSort.AZ -> "A–Z"
    ArchiveSort.ZA -> "Z–A"
    ArchiveSort.NEWEST -> "NEWEST"
}

// ---- Filters sheet --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(ui: ArchiveUi, onApply: (ArchiveFilters) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(ui.filters) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = SA.Nav,
        contentColor = SA.Text,
        scrimColor = Color(0xB8050709),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Wordmark("FILTERS")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fits this device", color = SA.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Matches your CPU and Android version", color = SA.Muted, fontSize = 13.sp)
                }
                SaSwitch(f.fitsDevice, { f = f.copy(fitsDevice = it) }, "Fits this device")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hide apps with anti-features", color = SA.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Ads, tracking, non-free network services", color = SA.Muted, fontSize = 13.sp)
                }
                SaSwitch(f.hideAntiFeatures, { f = f.copy(hideAntiFeatures = it) }, "Hide apps with anti-features")
            }
            Mono("CATEGORY", spacing = 1.3.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("ALL", f.category == null) { f = f.copy(category = null) }
                ui.categories.forEach { c ->
                    FilterChip(c.uppercase(Locale.US), f.category == c) { f = f.copy(category = c) }
                }
            }
            Mono("UPDATED", spacing = 1.3.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UpdatedRange.entries.forEach { u ->
                    FilterChip(updatedWord(u), f.updated == u) { f = f.copy(updated = u) }
                }
            }
            Mono("SORT", spacing = 1.3.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArchiveSort.entries.forEach { s ->
                    FilterChip(sortWord(s), f.sort == s) { f = f.copy(sort = s) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SaButton("RESET", { f = ArchiveFilters() }, Modifier.weight(1f), BtnStyle.OutlineLight, height = 54.dp)
                SaButton("APPLY", { onApply(f); onDismiss() }, Modifier.weight(1f), BtnStyle.Accent, height = 54.dp, glow = true)
            }
        }
    }
}
