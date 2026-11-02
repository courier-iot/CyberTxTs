package com.stellarite.apps.ui

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stellarite.apps.data.AppState
import com.stellarite.apps.data.ArchiveApp
import com.stellarite.apps.data.Catalog
import com.stellarite.apps.data.DeviceSnapshot
import com.stellarite.apps.data.FDROID_PREFIX
import com.stellarite.apps.data.FDroidClient
import com.stellarite.apps.data.GhRepo
import com.stellarite.apps.data.GitHubClient
import com.stellarite.apps.data.InstallResult
import com.stellarite.apps.data.Installer
import com.stellarite.apps.data.ModStatus
import com.stellarite.apps.data.Prefs
import com.stellarite.apps.data.ReadmeState
import com.stellarite.apps.data.SnapModule
import com.stellarite.apps.data.Snapshot
import com.stellarite.apps.data.Source
import com.stellarite.apps.data.SourceLinks
import com.stellarite.apps.data.SourceType
import com.stellarite.apps.data.StoreApp
import com.stellarite.apps.data.SyncStage
import com.stellarite.apps.data.Suggestions
import com.stellarite.apps.data.Transfer
import com.stellarite.apps.data.Vault
import com.stellarite.apps.data.statusOf
import com.stellarite.apps.patch.AutoPatch
import com.stellarite.apps.ui.theme.SA
import com.stellarite.apps.ui.theme.ThemeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class Filter { ALL, UPDATES, INSTALLED }

data class UiState(
    val loading: Boolean = false,
    val error: String? = null,
    val apps: List<StoreApp> = emptyList(),
    val source: String = "",
    val transfers: Map<String, Transfer> = emptyMap(),
    val readmes: Map<String, ReadmeState> = emptyMap(),
    val filter: Filter = Filter.ALL,
    val query: String = "",
    val message: String? = null,
    val autoPatch: Boolean = false,
    val excluded: Set<String> = emptySet(),
    val included: Set<String> = emptySet(),
    val vault: VaultUi = VaultUi(),
    val restore: RestoreUi? = null,
    val sources: List<Source> = emptyList(),
    val homeCount: Int = 0,
    val sourceCounts: Map<String, Int> = emptyMap(),
    val sourceErrors: Map<String, String> = emptyMap(),
    val addSource: AddSourceUi? = null,
    val archive: ArchiveUi? = null,
    val suggested: List<SuggestedItem> = emptyList(),
    val pending: List<GhRepo> = emptyList(),
    val wifiOnly: Boolean = true,
    val screenshotsWifiOnly: Boolean = true,
    val updateAlerts: Boolean = true,
    val fdroidDetails: Map<String, FDroidDetailsState> = emptyMap(),
    val suggestedPrompt: SuggestedItem? = null,
    val archives: List<ArchiveCard> = emptyList(),
) {
    /** Modules that are installed and on the latest release. */
    val upToDate: Int get() = apps.count { it.state == AppState.INSTALLED }

    /** Modules currently set to patch themselves, given the Auto-Patch mode. */
    fun armed(): Set<String> {
        val all = apps.map { it.repo.fullName }.toSet()
        return if (autoPatch) all - excluded else all intersect included
    }
}

class StoreViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val client = GitHubClient()
    private val fdroid = FDroidClient.withPrefs(prefs)
    private val catalog = Catalog(app, client, fdroid, prefs)
    private val ctx: Context get() = getApplication<Application>()
    private val jobs = mutableMapOf<String, Job>()

    private val _state = MutableStateFlow(
        UiState(
            source = sourceLabel(),
            autoPatch = prefs.autoPatchOn,
            excluded = prefs.patchExcluded,
            included = prefs.patchIncluded,
            sources = prefs.sources,
            wifiOnly = prefs.wifiOnly,
            screenshotsWifiOnly = prefs.screenshotsWifiOnly,
            updateAlerts = prefs.updateAlerts,
            vault = VaultUi(
                target = prefs.backupTarget,
                autoSnapshot = prefs.autoSnapshot,
                hasKey = prefs.token.isNotBlank(),
                owner = prefs.backupOwner.ifBlank { null },
                lastAt = prefs.lastSnapshotAt,
                lastCount = prefs.lastSnapshotCount,
            ),
        )
    )
    val state: StateFlow<UiState> = _state

    private val vaultRepo = Vault(client, prefs)

    val username: String get() = prefs.username
    val token: String get() = prefs.token

    init {
        SA.theme = ThemeId.fromKey(prefs.theme)
        AutoPatch.sync(ctx, prefs)
        loadArchiveCards()
        refresh()
    }

    private fun sourceLabel(): String = when {
        prefs.token.isNotBlank() -> "Your GitHub"
        prefs.username.isNotBlank() -> "@${prefs.username}"
        else -> "The Home Archive"
    }

    private fun tokenOrNull(): String? = prefs.token.ifBlank { null }

    private var refreshJob: Job? = null
    private var lastRefreshAt = 0L

    /**
     * Re-reads the catalog when the app comes back to the front after a while, so a repo or release
     * added in the meantime shows up without a manual sync. Spaced out to stay inside GitHub's
     * 60 requests per hour limit when no Access Key is set.
     */
    fun refreshIfStale() {
        if (_state.value.loading) return
        if (System.currentTimeMillis() - lastRefreshAt < 15 * 60_000L) return
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        lastRefreshAt = System.currentTimeMillis()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, source = sourceLabel()) }
            try {
                val result = catalog.load()
                val extra = catalog.installedSuggestions().filter { x -> result.apps.none { it.repo.fullName == x.repo.fullName } }
                val apps = (result.apps + extra)
                    .map { withLocalState(it) }
                    .sortedWith(
                        compareBy<StoreApp> { it.state != AppState.UPDATE_AVAILABLE }
                            .thenBy { it.repo.name.lowercase() }
                    )
                _state.update {
                    it.copy(
                        loading = false,
                        apps = apps,
                        readmes = emptyMap(),
                        homeCount = result.homeCount,
                        pending = result.pending,
                        sourceCounts = result.counts,
                        sourceErrors = result.errors,
                    )
                }
                loadSuggested()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Could not reach GitHub") }
            }
        }
    }

    /** Re-reads what is installed on the device (call when returning from the installer). */
    fun recheck() {
        resumeWaiting()
        _state.update { s ->
            s.copy(
                apps = s.apps.map { withLocalState(it) },
                suggested = s.suggested.map { i ->
                    i.copy(app = i.app?.let { a -> withLocalState(a) }, installed = Installer.installedVersion(ctx, i.s.pkg) != null)
                },
            )
        }
        refreshRestoreStatus()
        maybeAutoSnapshot()
    }

    private fun withLocalState(a: StoreApp): StoreApp {
        val pkg = prefs.packageFor(a.repo.fullName)
        val installed = Installer.installedVersion(ctx, pkg)
        val st = when {
            installed == null -> AppState.NOT_INSTALLED
            Installer.sameVersion(installed, a.release.tagName) -> AppState.INSTALLED
            else -> AppState.UPDATE_AVAILABLE
        }
        return a.copy(packageName = pkg, installedVersion = installed, state = st)
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setFilter(f: Filter) = _state.update { it.copy(filter = f) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---- Theme --------------------------------------------------------------------------------

    fun setTheme(t: ThemeId) {
        prefs.theme = t.key
        SA.theme = t
        maybeAutoSnapshot()
    }

    // ---- Auto-Patch ---------------------------------------------------------------------------

    fun setAutoPatch(on: Boolean) {
        prefs.autoPatchOn = on
        _state.update { it.copy(autoPatch = on) }
        AutoPatch.sync(ctx, prefs)
        maybeAutoSnapshot()
    }

    /**
     * Saves the Patch Roster. [armed] is the set of modules that should patch themselves.
     * ON: everything not armed becomes an exception. OFF: the armed set is the opt-in list.
     */
    fun saveRoster(armed: Set<String>) {
        val all = _state.value.apps.map { it.repo.fullName }.toSet()
        if (prefs.autoPatchOn) {
            prefs.patchExcluded = (prefs.patchExcluded - all) + (all - armed)
        } else {
            prefs.patchIncluded = (prefs.patchIncluded - all) + (armed intersect all)
        }
        _state.update {
            it.copy(excluded = prefs.patchExcluded, included = prefs.patchIncluded)
        }
        AutoPatch.sync(ctx, prefs)
        maybeAutoSnapshot()
    }

    // ---- README -------------------------------------------------------------------------------

    fun loadReadme(app: StoreApp, force: Boolean = false) {
        val key = app.repo.fullName
        if (key.startsWith(FDROID_PREFIX)) {
            // F-Droid apps have no GitHub README to fetch.
            _state.update { it.copy(readmes = it.readmes + (key to ReadmeState.Missing)) }
            return
        }
        val current = _state.value.readmes[key]
        if (!force && (current is ReadmeState.Loaded || current == ReadmeState.Loading || current == ReadmeState.Missing)) return
        _state.update { it.copy(readmes = it.readmes + (key to ReadmeState.Loading)) }
        viewModelScope.launch {
            val result = try {
                val text = client.fetchReadme(key, tokenOrNull())
                if (text == null) ReadmeState.Missing else ReadmeState.Loaded(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReadmeState.Failed(e.message ?: "Could not load the README")
            }
            _state.update { it.copy(readmes = it.readmes + (key to result)) }
        }
    }

    // ---- Install ------------------------------------------------------------------------------

    private fun setTransfer(key: String, t: Transfer?) = _state.update { s ->
        s.copy(transfers = if (t == null) s.transfers - key else s.transfers + (key to t))
    }

    fun install(app: StoreApp, force: Boolean = false) {
        val key = app.repo.fullName
        // A suggested app known only by its installed version has no build yet: resolve it first.
        if (app.apk.url.isBlank()) {
            Suggestions.ALL.firstOrNull { it.key == key }?.let { installSuggested(SuggestedItem(it)) }
            return
        }
        if (!force && prefs.wifiOnly && isMetered()) {
            waiting[key] = app
            setTransfer(key, Transfer.WaitingWifi)
            return
        }
        waiting.remove(key)
        if (jobs[key]?.isActive == true) return
        val safeName = key.replace('/', '_').replace(':', '_')
        val file = File(ctx.cacheDir, "apks/$safeName-${app.release.tagName}.apk")
        jobs[key] = viewModelScope.launch {
            setTransfer(key, Transfer.Downloading(0f))
            try {
                client.download(app.apk, tokenOrNull(), file) { p ->
                    setTransfer(key, Transfer.Downloading(p))
                }
                setTransfer(key, Transfer.Installing)
                val pkg = Installer.packageNameOf(ctx, file)
                    ?: error("Downloaded file is not a valid APK")
                prefs.setPackage(key, pkg)
                val result = Installer.install(ctx, file, app.repo.name)
                setTransfer(key, null)
                if (result == InstallResult.NEEDS_PERMISSION) {
                    file.delete()
                    _state.update {
                        it.copy(message = "Grant deploy clearance, then tap Install again.")
                    }
                }
                recheck()
            } catch (e: CancellationException) {
                file.delete()
                setTransfer(key, null)
                throw e
            } catch (e: Exception) {
                file.delete()
                setTransfer(key, Transfer.Failed(e.message ?: "Transfer failed"))
            }
        }
    }

    fun cancel(app: StoreApp) {
        waiting.remove(app.repo.fullName)
        jobs[app.repo.fullName]?.cancel()
    }

    fun clearTransfer(app: StoreApp) = setTransfer(app.repo.fullName, null)

    fun clearCache() {
        File(ctx.cacheDir, "apks").deleteRecursively()
        _state.update { it.copy(message = "Payload cache purged.") }
    }

    fun saveSettings(username: String, token: String) {
        prefs.username = username
        prefs.token = token
        _state.update {
            it.copy(vault = it.vault.copy(hasKey = prefs.token.isNotBlank(), owner = null, note = null))
        }
        refresh()
        AutoPatch.sync(ctx, prefs)
    }

    fun setWifiOnly(on: Boolean) {
        prefs.wifiOnly = on
        _state.update { it.copy(wifiOnly = on) }
        AutoPatch.sync(ctx, prefs)
        if (!on) resumeWaiting()
    }

    fun setScreenshotsWifiOnly(on: Boolean) {
        prefs.screenshotsWifiOnly = on
        _state.update { it.copy(screenshotsWifiOnly = on) }
    }

    fun setUpdateAlerts(on: Boolean) {
        prefs.updateAlerts = on
        _state.update { it.copy(updateAlerts = on) }
        AutoPatch.sync(ctx, prefs)
    }

    /** Downloads held back for Wi-Fi, started again when Wi-Fi is back or the setting is switched off. */
    private val waiting = mutableMapOf<String, StoreApp>()

    private fun resumeWaiting() {
        if (waiting.isEmpty() || (prefs.wifiOnly && isMetered())) return
        val list = waiting.values.toList()
        waiting.clear()
        list.forEach { install(it, force = true) }
    }

    fun reportMessage(text: String) = _state.update { it.copy(message = text) }

    // ---- Sources ------------------------------------------------------------------------------

    private var detectJob: Job? = null
    private var fdroidJob: Job? = null
    private var searchJob: Job? = null

    private fun setSources(list: List<Source>) {
        prefs.sources = list
        _state.update { it.copy(sources = list) }
    }

    fun setSourceEnabled(id: String, on: Boolean) {
        setSources(prefs.sources.map { if (it.id == id) it.copy(enabled = on) else it })
        refresh()
    }

    fun removeSource(id: String) {
        setSources(prefs.sources.filterNot { it.id == id })
        refresh()
    }

    private fun updateAdd(f: AddSourceUi.() -> AddSourceUi) =
        _state.update { s -> s.addSource?.let { s.copy(addSource = it.f()) } ?: s }

    fun openAddSource() = _state.update { it.copy(addSource = AddSourceUi()) }

    fun closeAddSource() {
        detectJob?.cancel()
        _state.update { it.copy(addSource = null) }
    }

    fun setAddInput(text: String) {
        detectJob?.cancel()
        updateAdd { AddSourceUi(input = text) }
        if (text.isBlank()) return
        val src = SourceLinks.parse(text)
        if (src == null) {
            updateAdd { copy(error = "That doesn't look like a GitHub or F-Droid link.") }
            return
        }
        detect(src, debounce = true)
    }

    /** kind: "stars", "fdroid" or "izzy". */
    fun quickAddSource(kind: String) {
        detectJob?.cancel()
        val src = when (kind) {
            "stars" -> SourceLinks.stars()
            "fdroid" -> SourceLinks.fdroid(SourceLinks.FDROID_URL, "F-Droid")
            else -> SourceLinks.fdroid(SourceLinks.IZZY_URL, "IzzyOnDroid")
        }
        updateAdd { AddSourceUi() }
        detect(src, debounce = false)
    }

    private fun detect(src: Source, debounce: Boolean) {
        detectJob = viewModelScope.launch {
            if (debounce) delay(450) // wait for typing to pause
            updateAdd { copy(detected = src, checking = true) }
            if (prefs.sources.any { it.sameTarget(src) }) {
                updateAdd { copy(checking = false, error = "Already in your sources.") }
                return@launch
            }
            try {
                when (src.type) {
                    SourceType.ACCOUNT -> {
                        val items = client.fetchApps(src.value, tokenOrNull(), own = false)
                        updateAdd {
                            copy(
                                checking = false,
                                summary = if (items.isEmpty()) {
                                    "${src.value} has no repos with APK releases yet."
                                } else {
                                    "${items.size} ${if (items.size == 1) "repo" else "repos"} with APK releases. All of them join your catalog."
                                },
                                preview = items.take(5).map { PreviewLine(it.first.name, it.second.tagName) },
                            )
                        }
                    }
                    SourceType.REPO -> {
                        val item = client.fetchRepoApp(src.value, tokenOrNull())
                        updateAdd {
                            if (item == null) {
                                copy(checking = false, error = "No APK release found in ${src.value}.")
                            } else {
                                copy(
                                    checking = false,
                                    summary = "One repo with an APK release.",
                                    preview = listOf(PreviewLine(item.first.name, item.second.tagName)),
                                )
                            }
                        }
                    }
                    SourceType.STARS -> updateAdd {
                        if (tokenOrNull() == null) {
                            copy(checking = false, error = "Starred repos need an Access Key. Add one in Uplink first.")
                        } else {
                            copy(checking = false, summary = "Your first 100 starred repos with APK releases join your catalog.")
                        }
                    }
                    SourceType.FDROID -> updateAdd {
                        copy(checking = false, summary = "F-Droid-style repository. Next you search it and pick the apps to add.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateAdd { copy(checking = false, error = e.message ?: "Could not check that link") }
            }
        }
    }

    fun confirmAddSource() {
        val a = _state.value.addSource ?: return
        val src = a.detected ?: return
        if (a.checking || a.error != null) return
        if (src.type == SourceType.FDROID) {
            closeAddSource()
            openFdroid(src.value, src.label)
            return
        }
        setSources(prefs.sources + src)
        closeAddSource()
        refresh()
    }

    // ---- F-Droid app pages ------------------------------------------------------------------------

    private fun putDetails(key: String, s: FDroidDetailsState) =
        _state.update { it.copy(fdroidDetails = it.fdroidDetails + (key to s)) }

    /** Reads the app's description, screenshots and history from the verified index. */
    fun loadFdroidDetails(app: StoreApp, force: Boolean = false) {
        val key = app.repo.fullName
        if (!key.startsWith(FDROID_PREFIX)) return
        if (!force && _state.value.fdroidDetails[key] is FDroidDetailsState.Loaded) return
        val pkg = key.removePrefix(FDROID_PREFIX)
        val repoUrl = prefs.sources.firstOrNull { it.type == SourceType.FDROID && pkg in it.picks }?.value
            ?: Suggestions.ALL.firstOrNull { it.key == key }?.repoUrl
            ?: SourceLinks.FDROID_URL
        if (!force && !fdroid.isFresh(repoUrl, ctx.cacheDir) && prefs.wifiOnly && isMetered()) {
            putDetails(key, FDroidDetailsState.NeedsWifi)
            return
        }
        putDetails(key, FDroidDetailsState.Loading)
        viewModelScope.launch {
            try {
                val index = fdroid.indexFile(repoUrl, ctx.cacheDir)
                val d = fdroid.details(index, repoUrl, pkg)
                putDetails(key, if (d != null) FDroidDetailsState.Loaded(d) else FDroidDetailsState.Failed("This app isn't in the index any more."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                putDetails(key, FDroidDetailsState.Failed(e.message ?: "Could not read the index"))
            }
        }
    }

    // ---- Suggested: recommended apps on the home page ------------------------------------------

    /** Shows what is known without touching the network; a large index is never downloaded by surprise. */
    fun loadSuggested() {
        viewModelScope.launch {
            val items = Suggestions.ALL.map { sg ->
                val app = try {
                    catalog.suggestedApp(sg, allowDownload = false)?.let { withLocalState(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                prefs.setPackage(sg.key, sg.pkg) // known up front, so an app installed earlier is recognised
                val old = _state.value.suggested.firstOrNull { it.s.key == sg.key }
                SuggestedItem(
                    sg, app ?: old?.app, old?.preparing ?: false,
                    installed = Installer.installedVersion(ctx, sg.pkg) != null,
                )
            }
            _state.update { it.copy(suggested = items) }
        }
    }

    private fun updateSuggested(key: String, f: SuggestedItem.() -> SuggestedItem) =
        _state.update { s -> s.copy(suggested = s.suggested.map { if (it.s.key == key) it.f() else it }) }

    fun dismissSuggestedPrompt() = _state.update { it.copy(suggestedPrompt = null) }

    /** Installs a suggested app that has no known build yet: reads the verified index first. */
    fun installSuggested(item: SuggestedItem, confirmed: Boolean = false) {
        val sg = item.s
        if (jobs[sg.key]?.isActive == true || item.preparing) return
        if (!confirmed && !fdroid.isFresh(sg.repoUrl, ctx.cacheDir) && isMetered()) {
            _state.update { it.copy(suggestedPrompt = item) }
            return
        }
        _state.update { it.copy(suggestedPrompt = null) }
        updateSuggested(sg.key) { copy(preparing = true) }
        setTransfer(sg.key, Transfer.Downloading(0f))
        viewModelScope.launch {
            try {
                val app = catalog.suggestedApp(sg, allowDownload = true) { p -> setTransfer(sg.key, Transfer.Downloading(p * 0.9f)) }?.let { withLocalState(it) }
                    ?: error("${sg.title} has no build for this device in the index.")
                updateSuggested(sg.key) { copy(app = app, preparing = false) }
                install(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateSuggested(sg.key) { copy(preparing = false) }
                setTransfer(sg.key, null)
                _state.update { it.copy(message = e.message ?: "Could not prepare ${sg.title}") }
            }
        }
    }

    // ---- Archives: browse a large F-Droid-style repository -------------------------------------

    private val archiveCache = mutableMapOf<String, List<ArchiveApp>>()
    private var archiveJob: Job? = null

    private fun updateArchive(f: ArchiveUi.() -> ArchiveUi) =
        _state.update { s -> s.archive?.let { s.copy(archive = it.f()) } ?: s }

    /** Cards for the Archives tab: F-Droid plus any other index sources the user added. */
    fun loadArchiveCards() {
        val list = mutableListOf<Triple<String, String, String>>()
        list += Triple(SourceLinks.FDROID_URL, "F-Droid", "The official free and open-source Android app repository.")
        prefs.sources.filter { it.type == SourceType.FDROID }.forEach { s ->
            if (list.none { it.first.equals(s.value, ignoreCase = true) }) {
                list += Triple(s.value, s.label, "An F-Droid-style repository.")
            }
        }
        val cards = list.map { (url, label, blurb) ->
            val meta = fdroid.cachedMeta(url, ctx.cacheDir)
            val picked = prefs.sources.firstOrNull {
                it.type == SourceType.FDROID && it.value.equals(url, ignoreCase = true)
            }?.picks?.size ?: 0
            ArchiveCard(url, label, blurb, meta?.count?.takeIf { it > 0 }, meta?.verified, meta?.syncedAt ?: 0L, picked)
        }
        _state.update { it.copy(archives = cards) }
    }

    fun editFdroid(source: Source) = openFdroid(source.value, source.label)

    fun openFdroid(repoUrl: String, label: String) {
        archiveJob?.cancel()
        val existing = prefs.sources.firstOrNull {
            it.type == SourceType.FDROID && it.value.equals(repoUrl, ignoreCase = true)
        }
        val selected = existing?.picks?.toSet() ?: emptySet()
        _state.update { it.copy(archive = ArchiveUi(repoUrl, label, selected = selected)) }
        archiveJob = viewModelScope.launch {
            val cached = fdroid.isFresh(repoUrl, ctx.cacheDir)
            if (!cached && isMetered()) {
                val size = fdroid.indexSize(repoUrl)
                updateArchive { copy(phase = ArchivePhase.DATA_WARNING, sizeBytes = size) }
            } else {
                runArchiveSync(force = false)
            }
        }
    }

    private fun isMetered(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    fun archiveDownloadNow() {
        archiveJob?.cancel()
        updateArchive { copy(phase = ArchivePhase.SYNC, step = 0, bytes = 0, total = -1, error = null) }
        archiveJob = viewModelScope.launch { runArchiveSync(force = false) }
    }

    fun archiveRefresh() {
        archiveJob?.cancel()
        updateArchive { copy(phase = ArchivePhase.SYNC, step = 0, bytes = 0, total = -1, error = null) }
        archiveJob = viewModelScope.launch { runArchiveSync(force = true) }
    }

    private suspend fun runArchiveSync(force: Boolean) {
        val ui = _state.value.archive ?: return
        updateArchive { copy(phase = ArchivePhase.SYNC, step = 0) }
        try {
            var lastPct = -1
            val file = fdroid.indexFile(ui.repoUrl, ctx.cacheDir, force) { stage, done, total ->
                // With an unknown size, update about every megabyte instead of every percent.
                val pct = if (total > 0) (done * 100 / total).toInt() else (done / 1_000_000).toInt()
                when (stage) {
                    SyncStage.DOWNLOAD -> if (pct != lastPct) {
                        lastPct = pct
                        updateArchive { copy(step = 0, bytes = done, total = total) }
                    }
                    SyncStage.VERIFY -> updateArchive { copy(step = 1) }
                }
            }
            updateArchive { copy(step = 2) }
            val key = ui.repoUrl.lowercase() + "@" + file.lastModified()
            val apps = archiveCache[key] ?: fdroid.browse(file, ui.repoUrl).also {
                archiveCache.keys.removeAll { k -> k.startsWith(ui.repoUrl.lowercase() + "@") }
                archiveCache[key] = it
                fdroid.saveCount(ui.repoUrl, ctx.cacheDir, it.size)
            }
            val meta = fdroid.cachedMeta(ui.repoUrl, ctx.cacheDir)
            val cats = apps.flatMap { it.categories }.distinct().sortedBy { it.lowercase() }
            updateArchive { copy(phase = ArchivePhase.READY, all = apps, categories = cats, verified = meta?.verified ?: false) }
            applyArchiveFilters()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateArchive { copy(phase = ArchivePhase.ERROR, error = e.message ?: "Could not read the archive") }
        }
    }

    private fun applyArchiveFilters() {
        val ui = _state.value.archive ?: return
        val f = ui.filters
        val q = ui.query.trim().lowercase()
        val now = System.currentTimeMillis()
        val minUpdated = when (f.updated) {
            UpdatedRange.ANY -> 0L
            UpdatedRange.YEAR -> now - 365L * 86_400_000
            UpdatedRange.MONTH -> now - 30L * 86_400_000
        }
        val list = ui.all.filter { a ->
            (!f.fitsDevice || a.fits) &&
                (!f.hideAntiFeatures || !a.flagged) &&
                (f.category == null || f.category in a.categories) &&
                (minUpdated == 0L || a.updated >= minUpdated) &&
                (q.isEmpty() || a.name.lowercase().contains(q) || a.summary.lowercase().contains(q) || a.pkg.lowercase().contains(q))
        }
        val sorted = when (f.sort) {
            ArchiveSort.AZ -> list.sortedBy { it.name.lowercase() }
            ArchiveSort.ZA -> list.sortedByDescending { it.name.lowercase() }
            ArchiveSort.NEWEST -> list.sortedByDescending { it.updated }
        }
        updateArchive { copy(shown = sorted) }
    }

    fun setArchiveQuery(q: String) {
        updateArchive { copy(query = q) }
        applyArchiveFilters()
    }

    fun setArchiveFilters(f: ArchiveFilters) {
        updateArchive { copy(filters = f) }
        applyArchiveFilters()
    }

    fun showArchiveFilters(show: Boolean) = updateArchive { copy(showFilters = show) }

    fun toggleArchivePick(pkg: String) =
        updateArchive { copy(selected = if (pkg in selected) selected - pkg else selected + pkg) }

    fun confirmArchive() {
        val ui = _state.value.archive ?: return
        val list = prefs.sources.toMutableList()
        val i = list.indexOfFirst { it.type == SourceType.FDROID && it.value.equals(ui.repoUrl, ignoreCase = true) }
        when {
            ui.selected.isEmpty() -> if (i >= 0) list.removeAt(i)
            i >= 0 -> list[i] = list[i].copy(picks = ui.selected.sorted())
            else -> list += Source(
                UUID.randomUUID().toString(), SourceType.FDROID, ui.repoUrl, ui.label, true, ui.selected.sorted(),
            )
        }
        setSources(list)
        closeFdroid()
        refresh()
    }

    fun closeFdroid() {
        archiveJob?.cancel()
        _state.update { it.copy(archive = null) }
        loadArchiveCards()
    }

    // ---- Backup Vault: snapshots --------------------------------------------------------------

    private fun updateVault(f: VaultUi.() -> VaultUi) = _state.update { it.copy(vault = it.vault.f()) }

    /** What this device would record right now. Modules come from every repo installed through the store. */
    private fun localSnapshot(): DeviceSnapshot {
        val modules = prefs.knownModules().mapNotNull { (repo, pkg) ->
            Installer.installedVersion(ctx, pkg)?.let { SnapModule(repo, pkg, it) }
        }.sortedBy { it.repo }
        return DeviceSnapshot(
            name = Build.MODEL ?: "Android device",
            savedAt = System.currentTimeMillis(),
            modules = modules,
            theme = prefs.theme,
            autoPatchOn = prefs.autoPatchOn,
            excluded = prefs.patchExcluded.sorted(),
            included = prefs.patchIncluded.sorted(),
            sources = prefs.sources,
        )
    }

    fun setBackupTarget(target: String) {
        prefs.backupTarget = target
        updateVault { copy(target = target, note = null) }
        maybeAutoSnapshot()
    }

    fun setAutoSnapshot(on: Boolean) {
        prefs.autoSnapshot = on
        updateVault { copy(autoSnapshot = on) }
        if (on) maybeAutoSnapshot()
    }

    /** GitHub target only; the file target goes through the system file picker in the activity. */
    fun snapshotNow() = pushSnapshot(manual = true)

    private fun pushSnapshot(manual: Boolean) {
        if (_state.value.vault.busy) return
        val snap = localSnapshot()
        viewModelScope.launch {
            updateVault { copy(busy = true, note = null) }
            try {
                vaultRepo.push(snap)
                recordSnapshot(snap)
                updateVault { copy(busy = false, owner = prefs.backupOwner.ifBlank { null }) }
                if (manual) reportMessage("Snapshot saved.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateVault { copy(busy = false, note = e.message ?: "Snapshot failed") }
            }
        }
    }

    private fun recordSnapshot(snap: DeviceSnapshot) {
        prefs.lastSnapshotAt = snap.savedAt
        prefs.lastSnapshotCount = snap.modules.size
        prefs.lastSnapshotHash = snap.fingerprint()
        updateVault { copy(lastAt = snap.savedAt, lastCount = snap.modules.size, note = null) }
    }

    /** Saves a snapshot after installs, removals, skin or roster changes, when Auto-Snapshot is on. */
    private fun maybeAutoSnapshot() {
        if (!prefs.autoSnapshot || prefs.backupTarget != "github" || prefs.token.isBlank()) return
        if (_state.value.vault.busy) return
        val fp = localSnapshot().fingerprint()
        if (fp == prefs.lastSnapshotHash || fp == prefs.lastAttemptHash) return
        prefs.lastAttemptHash = fp
        pushSnapshot(manual = false)
    }

    /** File target: the text the activity writes to the file the user picked. */
    fun snapshotText(): String = Snapshot().withDevice(prefs.deviceId, localSnapshot()).toText()

    fun fileSnapshotSaved() {
        val snap = localSnapshot()
        recordSnapshot(snap)
        reportMessage("Snapshot saved to file.")
    }

    // ---- Backup Vault: restore ----------------------------------------------------------------

    private fun updateRestore(f: (RestoreUi) -> RestoreUi) =
        _state.update { s -> s.restore?.let { s.copy(restore = f(it)) } ?: s }

    private fun installedNow(modules: List<SnapModule>): Map<String, String?> =
        modules.associate { it.repo to Installer.installedVersion(ctx, it.pkg ?: prefs.packageFor(it.repo)) }

    private fun refreshRestoreStatus() = updateRestore { r ->
        val dev = r.snapshot?.devices?.get(r.deviceId) ?: return@updateRestore r
        r.copy(installed = installedNow(dev.modules))
    }

    private fun defaultSelection(dev: DeviceSnapshot, installed: Map<String, String?>): Set<String> =
        dev.modules.filter { statusOf(it, installed[it.repo]) != ModStatus.INSTALLED }.map { it.repo }.toSet()

    fun openRestoreFromGithub() {
        _state.update { it.copy(restore = RestoreUi(loading = true)) }
        viewModelScope.launch {
            try {
                val snap = vaultRepo.pull()
                val owner = prefs.backupOwner
                showRestore(snap, "GITHUB · $owner")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(restore = RestoreUi(error = e.message ?: "Could not read the snapshot")) }
            }
        }
    }

    fun openRestoreFromText(text: String) {
        try {
            showRestore(Snapshot.parse(text), "FILE")
        } catch (e: Exception) {
            _state.update { it.copy(restore = RestoreUi(error = e.message ?: "Could not read that file")) }
        }
    }

    private fun showRestore(snap: Snapshot, source: String) {
        // Prefer the newest snapshot from another device; fall back to this device's own.
        val pick = snap.devices.entries.filter { it.key != prefs.deviceId }.maxByOrNull { it.value.savedAt }
            ?: snap.devices.entries.firstOrNull()
        if (pick == null) {
            _state.update { it.copy(restore = RestoreUi(error = "That snapshot is empty.")) }
            return
        }
        val installed = installedNow(pick.value.modules)
        _state.update {
            it.copy(
                restore = RestoreUi(
                    source = source,
                    snapshot = snap,
                    deviceId = pick.key,
                    installed = installed,
                    selected = defaultSelection(pick.value, installed),
                )
            )
        }
    }

    fun closeRestore() = _state.update { it.copy(restore = null) }

    fun selectRestoreDevice(id: String) = updateRestore { r ->
        val dev = r.snapshot?.devices?.get(id) ?: return@updateRestore r
        val installed = installedNow(dev.modules)
        r.copy(deviceId = id, installed = installed, selected = defaultSelection(dev, installed))
    }

    fun setRestoreMode(mode: RestoreMode) = updateRestore { it.copy(mode = mode) }

    fun toggleRestoreModule(repo: String) = updateRestore {
        it.copy(selected = if (repo in it.selected) it.selected - repo else it.selected + repo)
    }

    fun setRestoreSettings(on: Boolean) = updateRestore { it.copy(includeSettings = on) }

    private fun applySettings(dev: DeviceSnapshot) {
        prefs.theme = dev.theme
        SA.theme = ThemeId.fromKey(dev.theme)
        prefs.autoPatchOn = dev.autoPatchOn
        prefs.patchExcluded = dev.excluded.toSet()
        prefs.patchIncluded = dev.included.toSet()
        // Sources are added, never removed: anything this device already has stays.
        val merged = prefs.sources.toMutableList()
        dev.sources.forEach { s -> if (merged.none { it.sameTarget(s) }) merged += s }
        prefs.sources = merged
        _state.update {
            it.copy(
                autoPatch = dev.autoPatchOn,
                excluded = prefs.patchExcluded,
                included = prefs.patchIncluded,
                sources = merged,
            )
        }
        AutoPatch.sync(ctx, prefs)
    }

    /** Finds the release to install for one recorded module. F-Droid apps come from the loaded catalog. */
    private suspend fun restoreTarget(m: SnapModule, mode: RestoreMode, fdroidApps: Map<String, StoreApp>): StoreApp? {
        if (m.repo.startsWith(FDROID_PREFIX)) return fdroidApps[m.repo]
        val resolved = client.resolveModule(
            m.repo,
            if (mode == RestoreMode.RECORDED) m.version else null,
            tokenOrNull(),
        ) ?: return null
        val (release, apk) = resolved
        val repo = GhRepo(name = m.name, fullName = m.repo, htmlUrl = "https://github.com/${m.repo}")
        return StoreApp(repo, release, apk, m.pkg, null, AppState.NOT_INSTALLED)
    }

    /** Installs the ticked modules one after another; Android still confirms each install. */
    fun runRestore() {
        val r = _state.value.restore ?: return
        if (r.progress != null) return
        val dev = r.snapshot?.devices?.get(r.deviceId) ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            updateRestore { it.copy(done = "Grant Deploy Clearance in Control Core first, then restore.") }
            return
        }
        val chosen = dev.modules.filter { it.repo in r.selected }
        viewModelScope.launch {
            // Teach the store about modules that are already on this device.
            dev.modules.forEach { m ->
                if (m.pkg != null && Installer.installedVersion(ctx, m.pkg) != null) prefs.setPackage(m.repo, m.pkg)
            }
            if (r.includeSettings) applySettings(dev)
            val problems = mutableListOf<String>()
            // F-Droid apps are found through the catalog, which needs the sources loaded first.
            val fdroidApps: Map<String, StoreApp> =
                if (chosen.any { it.repo.startsWith(FDROID_PREFIX) }) {
                    try {
                        catalog.load(withSuggested = true, onlyIfInstalled = false).apps.associateBy { it.repo.fullName }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problems += "F-Droid sources: ${e.message ?: "could not load"}"
                        emptyMap()
                    }
                } else emptyMap()
            for ((i, m) in chosen.withIndex()) {
                updateRestore { it.copy(progress = "Restoring ${i + 1} of ${chosen.size} · ${m.name}", done = null) }
                try {
                    val app = restoreTarget(m, r.mode, fdroidApps)
                    if (app == null) {
                        problems += if (m.repo.startsWith(FDROID_PREFIX)) {
                            "${m.name}: not found in its F-Droid source (tick \"sources\" to restore those too)"
                        } else {
                            "${m.name}: no APK release found"
                        }
                        continue
                    }
                    val before = Installer.installedVersion(ctx, m.pkg ?: prefs.packageFor(m.repo))
                    install(app, force = true)
                    jobs[m.repo]?.join()
                    val failed = _state.value.transfers[m.repo] as? Transfer.Failed
                    if (failed != null) {
                        problems += "${m.name}: ${failed.message}"
                        setTransfer(m.repo, null)
                        continue
                    }
                    awaitInstallChange(prefs.packageFor(m.repo), before)
                    recheck()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problems += "${m.name}: ${e.message ?: "failed"}"
                }
            }
            recheck()
            updateRestore {
                it.copy(
                    progress = null,
                    done = if (problems.isEmpty()) "Restore complete." else "Finished with problems: " + problems.joinToString("; "),
                )
            }
        }
    }

    /** Waits (up to a minute) for the user to confirm the system install prompt. */
    private suspend fun awaitInstallChange(pkg: String?, before: String?) {
        if (pkg == null) return
        repeat(60) {
            if (Installer.installedVersion(ctx, pkg) != before) return
            delay(1000)
        }
    }
}
