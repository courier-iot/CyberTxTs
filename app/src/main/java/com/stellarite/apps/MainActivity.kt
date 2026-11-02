package com.stellarite.apps

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stellarite.apps.data.AppState
import com.stellarite.apps.data.Installer
import com.stellarite.apps.data.StoreApp
import com.stellarite.apps.data.Transfer
import com.stellarite.apps.ui.AddSourceScreen
import com.stellarite.apps.ui.CatalogScreen
import com.stellarite.apps.ui.ArchiveScreen
import com.stellarite.apps.ui.ArchivesScreen
import com.stellarite.apps.ui.SourcesPanel
import com.stellarite.apps.ui.DetailScreen
import com.stellarite.apps.ui.Mono
import com.stellarite.apps.ui.RestoreScreen
import com.stellarite.apps.ui.RosterSheet
import com.stellarite.apps.ui.SettingsScreen
import com.stellarite.apps.ui.StoreViewModel
import com.stellarite.apps.ui.spaceBackground
import com.stellarite.apps.ui.theme.SA
import com.stellarite.apps.ui.theme.StellariteTheme

class MainActivity : ComponentActivity() {
    private val vm: StoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { StellariteTheme { StellariteApp(vm) } }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system installer: refresh installed/update status.
        vm.recheck()
        // Coming back to the app: pick up repos and releases added since the last sync.
        vm.refreshIfStale()
    }
}

@Composable
private fun StellariteApp(vm: StoreViewModel) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showRoster by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // File target: the system file picker saves and loads stellarite-snapshot.json.
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(vm.snapshotText().toByteArray()) }
                vm.fileSnapshotSaved()
            } catch (e: Exception) {
                vm.reportMessage("Could not save the snapshot file.")
            }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                if (bytes.size > 2_000_000) error("File too large")
                vm.openRestoreFromText(bytes.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                vm.reportMessage("Could not read that file.")
            }
        }
    }

    val selectedApp = state.apps.firstOrNull { it.repo.fullName == selected }
    BackHandler(enabled = selectedApp != null) { selected = null }
    BackHandler(enabled = state.restore != null) { vm.closeRestore() }
    BackHandler(enabled = state.addSource != null) { vm.closeAddSource() }
    BackHandler(enabled = state.archive != null) { vm.closeFdroid() }

    LaunchedEffect(tab) { if (tab == 1) vm.loadArchiveCards() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    LaunchedEffect(selectedApp?.repo?.fullName) {
        selectedApp?.let {
            vm.loadReadme(it)
            vm.loadFdroidDetails(it)
        }
    }

    fun act(app: StoreApp) {
        if (state.transfers[app.repo.fullName] is Transfer.WaitingWifi) {
            vm.clearTransfer(app)
            vm.install(app, force = true)
            return
        }
        if (app.state == AppState.INSTALLED && app.packageName != null && state.transfers[app.repo.fullName] == null) {
            if (!Installer.open(ctx, app.packageName)) vm.install(app)
        } else {
            vm.clearTransfer(app)
            vm.install(app)
        }
    }

    fun setAutoPatch(on: Boolean) {
        vm.setAutoPatch(on)
        if (on) {
            // Background patching reports through notifications, which need permission on Android 13+.
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Switching off opens the roster so the few modules that should still patch can be ticked.
            showRoster = true
        }
    }

    Column(Modifier.fillMaxSize().spaceBackground().statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            val restore = state.restore
            val archiveUi = state.archive
            val addUi = state.addSource
            if (archiveUi != null) {
                ArchiveScreen(
                    ui = archiveUi,
                    onBack = vm::closeFdroid,
                    onQuery = vm::setArchiveQuery,
                    onFilters = vm::setArchiveFilters,
                    onShowFilters = vm::showArchiveFilters,
                    onToggle = vm::toggleArchivePick,
                    onConfirm = vm::confirmArchive,
                    onDownloadNow = vm::archiveDownloadNow,
                    onRefresh = vm::archiveRefresh,
                )
            } else if (addUi != null) {
                AddSourceScreen(
                    ui = addUi,
                    onInput = vm::setAddInput,
                    onQuick = vm::quickAddSource,
                    onCancel = vm::closeAddSource,
                    onAdd = vm::confirmAddSource,
                )
            } else if (restore != null) {
                RestoreScreen(
                    restore = restore,
                    onBack = vm::closeRestore,
                    onDevice = vm::selectRestoreDevice,
                    onMode = vm::setRestoreMode,
                    onToggle = vm::toggleRestoreModule,
                    onSettings = vm::setRestoreSettings,
                    onRestore = vm::runRestore,
                )
            } else if (selectedApp != null) {
                DetailScreen(
                    app = selectedApp,
                    transfer = state.transfers[selectedApp.repo.fullName],
                    readme = state.readmes[selectedApp.repo.fullName],
                    onBack = { selected = null },
                    onInstall = {
                        val held = state.transfers[selectedApp.repo.fullName] is Transfer.WaitingWifi
                        vm.clearTransfer(selectedApp)
                        vm.install(selectedApp, force = held)
                    },
                    onCancel = { vm.cancel(selectedApp) },
                    onOpenApp = { selectedApp.packageName?.let { Installer.open(ctx, it) } },
                    onUninstall = { selectedApp.packageName?.let { Installer.uninstall(ctx, it) } },
                    onRetryReadme = { vm.loadReadme(selectedApp, force = true) },
                    fdroid = state.fdroidDetails[selectedApp.repo.fullName],
                    holdImages = state.screenshotsWifiOnly && (ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered,
                    onLoadFdroid = { vm.loadFdroidDetails(selectedApp, force = it) },
                )
            } else if (tab == 0) {
                CatalogScreen(
                    state = state,
                    onRefresh = vm::refresh,
                    onQuery = vm::setQuery,
                    onFilter = vm::setFilter,
                    onOpen = { selected = it.repo.fullName },
                    onAction = ::act,
                    onSuggested = { item ->
                        val a = item.app
                        when {
                            a != null -> act(a)
                            item.installed -> Installer.open(ctx, item.s.pkg)
                            else -> vm.installSuggested(item)
                        }
                    },
                    onOpenArchives = { tab = 1 },
                )
            } else if (tab == 1) {
                ArchivesScreen(
                    cards = state.archives,
                    onOpen = { vm.openFdroid(it.repoUrl, it.label) },
                )
            } else {
                val total = state.apps.size
                val hasKey = vm.token.isNotBlank()
                val sourcesPanel = SourcesPanel(
                    homeTitle = when {
                        hasKey -> "Your GitHub"
                        vm.username.isNotBlank() -> "@${vm.username}"
                        else -> "Home Archive"
                    },
                    homeSub = when {
                        hasKey -> "all your repos"
                        vm.username.isNotBlank() -> "github.com/${vm.username}"
                        else -> "Made by The Courier"
                    },
                    homeCount = state.homeCount,
                    sources = state.sources,
                    counts = state.sourceCounts,
                    errors = state.sourceErrors,
                )
                val summary =
                    if (state.autoPatch) "${state.apps.count { it.repo.fullName in state.excluded }} of $total modules excluded"
                    else "${state.armed().size} of $total modules armed"
                SettingsScreen(
                    initialUsername = vm.username,
                    initialToken = vm.token,
                    onTheme = vm::setTheme,
                    autoPatch = state.autoPatch,
                    rosterSummary = summary,
                    onAutoPatch = ::setAutoPatch,
                    onOpenRoster = { showRoster = true },
                    wifiOnly = state.wifiOnly,
                    onWifiOnly = vm::setWifiOnly,
                    screenshotsWifiOnly = state.screenshotsWifiOnly,
                    onScreenshotsWifiOnly = vm::setScreenshotsWifiOnly,
                    updateAlerts = state.updateAlerts,
                    onUpdateAlerts = vm::setUpdateAlerts,
                    sources = sourcesPanel,
                    onSourceEnabled = vm::setSourceEnabled,
                    onSourceRemove = vm::removeSource,
                    onSourceEdit = vm::editFdroid,
                    onAddSource = vm::openAddSource,
                    vault = state.vault,
                    onVaultTarget = vm::setBackupTarget,
                    onAutoSnapshot = vm::setAutoSnapshot,
                    onSnapshotNow = {
                        if (state.vault.target == "file") exportFile.launch(Config.BACKUP_FILE_NAME)
                        else vm.snapshotNow()
                    },
                    onRestore = {
                        if (state.vault.target == "file") {
                            importFile.launch(arrayOf("application/json", "text/plain", "*/*"))
                        } else {
                            vm.openRestoreFromGithub()
                        }
                    },
                    onSave = { u, t ->
                        vm.saveSettings(u, t)
                        tab = 0
                    },
                    onClearCache = vm::clearCache,
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        if (selectedApp == null && state.restore == null && state.addSource == null && state.archive == null) {
            NavBar(tab) { tab = it }
        } else {
            Box(Modifier.navigationBarsPadding())
        }
    }

    state.suggestedPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = vm::dismissSuggestedPrompt,
            containerColor = SA.Nav,
            title = { Text("Download the ${p.s.title} index?", color = SA.Text) },
            text = {
                Text(
                    "You are not on Wi-Fi. ${p.s.title}'s index is large, so it will use a good amount of mobile data. " +
                        "It is downloaded once and kept for a day.",
                    color = SA.Muted,
                )
            },
            confirmButton = { TextButton({ vm.installSuggested(p, confirmed = true) }) { Text("DOWNLOAD", color = SA.Accent) } },
            dismissButton = { TextButton(vm::dismissSuggestedPrompt) { Text("WAIT FOR WI-FI", color = SA.Muted) } },
        )
    }

    if (showRoster) {
        RosterSheet(
            apps = state.apps,
            autoPatchOn = state.autoPatch,
            armedInitial = state.armed(),
            onDismiss = { showRoster = false },
            onCommit = {
                vm.saveRoster(it)
                showRoster = false
            },
        )
    }
}

@Composable
private fun NavBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SA.Nav)
            .drawBehind { drawLine(SA.Line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding()
    ) {
        NavItem("CATALOG", Icons.Outlined.GridView, selected == 0, Modifier.weight(1f)) { onSelect(0) }
        NavItem("ARCHIVES", Icons.Outlined.Storage, selected == 1, Modifier.weight(1f)) { onSelect(1) }
        NavItem("CORE", Icons.Outlined.Settings, selected == 2, Modifier.weight(1f)) { onSelect(2) }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(76.dp)
            .drawBehind {
                if (active) {
                    drawRect(SA.Accent, Offset.Zero, Size(size.width, 2.dp.toPx()))
                }
            }
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val c = if (active) SA.Text else SA.Muted
        Icon(icon, contentDescription = null, tint = c)
        Mono(label, size = 11.sp, color = c, spacing = 1.3.sp)
    }
}
