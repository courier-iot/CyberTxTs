package com.stellarite.apps.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Everything the store lists, before local install state is applied. */
data class CatalogResult(
    val apps: List<StoreApp>,
    /** Modules found by the main catalog (home archive, handle, or the Access Key's own repos). */
    val homeCount: Int,
    /** Modules found per extra source id. */
    val counts: Map<String, Int>,
    /** Per extra source id: why it failed, if it did. */
    val errors: Map<String, String>,
    /** Android repos of the main catalog with no APK release yet. */
    val pending: List<GhRepo> = emptyList(),
)

/** Loads the main catalog plus every enabled extra source. Shared by the UI and the Auto-Patch worker. */
class Catalog(
    private val ctx: Context,
    private val github: GitHubClient,
    private val fdroid: FDroidClient,
    private val prefs: Prefs,
) {
    private fun token(): String? = prefs.token.ifBlank { null }

    /** A failure in the main catalog is thrown; a failure in an extra source is reported but does not stop the rest. */
    suspend fun load(withSuggested: Boolean = false, onlyIfInstalled: Boolean = true): CatalogResult {
        val found = LinkedHashMap<String, StoreApp>()
        val (home, pending) = github.fetchHome(prefs.effectiveUser, token())
        home.forEach { found.putIfAbsent(it.first.fullName, toApp(it)) }

        val counts = mutableMapOf<String, Int>()
        val errors = mutableMapOf<String, String>()
        for (s in prefs.sources.filter { it.enabled }) {
            try {
                val items = fetchSource(s)
                counts[s.id] = items.size
                items.forEach { found.putIfAbsent(it.repo.fullName, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors[s.id] = e.message ?: "Could not load this source"
            }
        }
        if (withSuggested) {
            for (sg in Suggestions.ALL) {
                if (found.containsKey(sg.key)) continue
                if (onlyIfInstalled && Installer.installedVersion(ctx, sg.pkg) == null) continue
                try {
                    suggestedApp(sg, allowDownload = true)?.let { found[sg.key] = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors[sg.key] = e.message ?: "Could not load ${sg.title}"
                }
            }
        }
        return CatalogResult(found.values.toList(), home.size, counts, errors, pending.filter { p -> !found.containsKey(p.fullName) })
    }

    private fun toApp(t: Triple<GhRepo, GhRelease, GhAsset>) =
        StoreApp(t.first, t.second, t.third, null, null, AppState.NOT_INSTALLED)

    private suspend fun fetchSource(s: Source): List<StoreApp> = when (s.type) {
        SourceType.ACCOUNT -> github.fetchApps(s.value, token(), own = false).map(::toApp)
        SourceType.REPO -> listOfNotNull(github.fetchRepoApp(s.value, token())?.let(::toApp))
        SourceType.STARS -> {
            val t = token() ?: throw IOException("Starred repos need an Access Key.")
            github.fetchStarredApps(t).map(::toApp)
        }
        SourceType.FDROID -> fdroidApps(s)
    }

    /**
     * Suggested apps that are already on the device, so they appear in the module list and can be updated
     * even if they were installed before Stellarite Apps. Uses the cached index when there is one, otherwise a
     * placeholder whose real build is resolved when the user taps update.
     */
    suspend fun installedSuggestions(): List<StoreApp> {
        val out = mutableListOf<StoreApp>()
        for (sg in Suggestions.ALL) {
            val installed = Installer.installedVersion(ctx, sg.pkg) ?: continue
            prefs.setPackage(sg.key, sg.pkg)
            val resolved = try {
                suggestedApp(sg, allowDownload = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (resolved != null) { out += resolved; continue }
            val latest = fdroid.latestVersion(sg.repoUrl, sg.pkg) ?: installed
            val blank = GhAsset(id = 0, name = "", url = "", browserDownloadUrl = "")
            out += StoreApp(
                repo = GhRepo(sg.title, sg.key, sg.sub, "https://f-droid.org/packages/${sg.pkg}"),
                release = GhRelease(tagName = latest, name = latest),
                apk = blank,
                packageName = sg.pkg,
                installedVersion = installed,
                state = AppState.INSTALLED,
            )
        }
        return out
    }

    /**
     * The newest build of a suggested app. Without [allowDownload] this only answers from an index that is
     * already cached, so the home page never downloads a large index by surprise.
     */
    suspend fun suggestedApp(sg: Suggestion, allowDownload: Boolean, onProgress: (Float) -> Unit = {}): StoreApp? {
        if (!allowDownload && (!fdroid.isFresh(sg.repoUrl, ctx.cacheDir) || fdroid.cachedMeta(sg.repoUrl, ctx.cacheDir) == null)) {
            return null
        }
        val index = fdroid.indexFile(sg.repoUrl, ctx.cacheDir) { _, done, total -> if (total > 0) onProgress(done.toFloat() / total) }
        return fdroid.resolve(index, sg.repoUrl, setOf(sg.pkg)).firstOrNull()?.let { toFdroidApp(it, sg.repoUrl) }
    }

    private suspend fun fdroidApps(s: Source): List<StoreApp> {
        if (s.picks.isEmpty()) return emptyList()
        val index = fdroid.indexFile(s.value, ctx.cacheDir)
        return fdroid.resolve(index, s.value, s.picks.toSet()).map { toFdroidApp(it, s.value) }
    }

    private fun toFdroidApp(b: FDroidBuild, repoUrl: String): StoreApp {
        val onFdroidOrg = repoUrl.substringAfter("://").substringBefore('/').endsWith("f-droid.org")
        val key = FDROID_PREFIX + b.pkg
        prefs.setPackage(key, b.pkg) // the index tells us the package up front
        val asset = GhAsset(
            id = b.versionCode,
            name = b.apkUrl.substringAfterLast('/'),
            size = b.size,
            url = b.apkUrl,
            browserDownloadUrl = b.apkUrl,
            sha256 = b.sha256,
        )
        return StoreApp(
            repo = GhRepo(
                name = b.name,
                fullName = key,
                description = b.summary.ifBlank { null },
                htmlUrl = if (onFdroidOrg) "https://f-droid.org/packages/${b.pkg}" else repoUrl,
            ),
            release = GhRelease(tagName = b.versionName, name = b.versionName, assets = listOf(asset)),
            apk = asset,
            packageName = b.pkg,
            installedVersion = null,
            state = AppState.NOT_INSTALLED,
        )
    }
}
