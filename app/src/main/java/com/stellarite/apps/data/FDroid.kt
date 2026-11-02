package com.stellarite.apps.data

import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.jar.JarFile

/** A search hit in an F-Droid-style index. */
data class FDroidEntry(val pkg: String, val name: String, val summary: String)

/** One app in the full A-Z archive list. */
data class ArchiveApp(
    val pkg: String,
    val name: String,
    val summary: String,
    val iconUrl: String?,
    val categories: List<String>,
    /** Epoch millis of the app's last update in the repo; 0 when unknown. */
    val updated: Long,
    /** True when a version exists for this device's CPU and Android version. */
    val fits: Boolean,
    /** True when the newest suitable version carries anti-features (ads, tracking, non-free services). */
    val flagged: Boolean,
)

/** One entry in an app's version history. */
data class FDroidVersionInfo(val name: String, val addedMs: Long, val size: Long)

/** Everything the app page shows about an F-Droid app, read from the verified index. */
data class FDroidDetails(
    val description: String,
    val categories: List<String>,
    val license: String,
    val updatedMs: Long,
    val minSdk: Int,
    val antiFeatures: List<String>,
    val screenshots: List<String>,
    val versions: List<FDroidVersionInfo>,
)

/** What was learned about a cached index. */
data class IndexMeta(val verified: Boolean, val fingerprint: String?, val count: Int, val syncedAt: Long)

enum class SyncStage { DOWNLOAD, VERIFY }

/** The newest installable version of a picked app. */
data class FDroidBuild(
    val pkg: String,
    val name: String,
    val summary: String,
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val sha256: String?,
    val size: Long,
)

/**
 * Reads F-Droid-style repositories (F-Droid, IzzyOnDroid, self-hosted ones) through their
 * `index-v2.json`. The index lists thousands of apps, so it is downloaded once, cached for a day,
 * and read with a streaming parser.
 *
 * Integrity, in layers:
 *  1. The index is checked against `entry.jar`, which the repo signs. F-Droid's signing key is
 *     pinned in the app. For other repos the key is remembered the first time and a later change
 *     is refused. A repo with no `entry.jar` is used but flagged as unverified.
 *  2. APK downloads are checked against the SHA-256 in the index.
 *  3. Android only accepts updates signed with the same key as the installed app.
 *
 * [knownKey] and [rememberKey] read and store the remembered signing key per repo.
 */
class FDroidClient(
    private val http: OkHttpClient = defaultHttp(),
    private val knownKey: (String) -> String? = { null },
    private val rememberKey: (String, String) -> Unit = { _, _ -> },
) {

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Signing-key SHA-256 fingerprints that are built into the app. */
        private val PINNED = mapOf(
            "https://f-droid.org/repo" to "43238D512C1E5EB2D6569F4A3AFBF5523418B82E0A3ED1552770ABB9A9C9CCAB",
        )

        /** Long timeouts: the index is tens of megabytes and mobile connections stall now and then. */
        private fun defaultHttp() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "StellariteApps/1.0 (Android)").build())
            }
            .build()

        fun withPrefs(prefs: Prefs) = FDroidClient(
            knownKey = { prefs.repoKey(it) },
            rememberKey = { url, fp -> prefs.setRepoKey(url, fp) },
        )

        private fun norm(url: String) = url.trim().trimEnd('/').lowercase(Locale.ROOT)
    }

    private data class Version(
        val code: Long,
        val name: String,
        val file: String,
        val sha256: String?,
        val size: Long,
        val abis: List<String>,
        val minSdk: Int,
        val flagged: Boolean = false,
    )

    private data class PackageInfo(
        val name: String,
        val summary: String,
        val versions: List<Version>,
        val icon: String?,
        val categories: List<String>,
        val updated: Long,
    )

    private fun indexPath(repoUrl: String, cacheDir: File): File {
        val dir = File(cacheDir, "fdroid").apply { mkdirs() }
        return File(dir, Integer.toHexString(norm(repoUrl).hashCode()) + ".json")
    }

    private fun metaPath(index: File) = File(index.parentFile, index.name + ".meta")

    /** True when a cached copy younger than a day exists, so opening the archive needs no download. */
    fun isFresh(repoUrl: String, cacheDir: File): Boolean {
        val f = indexPath(repoUrl, cacheDir)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < DAY_MS
    }

    fun cachedMeta(repoUrl: String, cacheDir: File): IndexMeta? {
        val f = indexPath(repoUrl, cacheDir)
        val m = metaPath(f)
        if (!f.exists() || !m.exists()) return null
        return try {
            val p = Properties().apply { m.inputStream().use { load(it) } }
            IndexMeta(
                p.getProperty("verified") == "true",
                p.getProperty("fp")?.ifBlank { null },
                p.getProperty("count")?.toIntOrNull() ?: 0,
                f.lastModified(),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun saveCount(repoUrl: String, cacheDir: File, count: Int) {
        val m = metaPath(indexPath(repoUrl, cacheDir))
        if (!m.exists()) return
        val p = Properties().apply { m.inputStream().use { load(it) } }
        p.setProperty("count", count.toString())
        m.outputStream().use { p.store(it, null) }
    }

    /** Size of the index download in bytes, or -1 when the server doesn't say. */
    suspend fun indexSize(repoUrl: String): Long = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(repoUrl.trimEnd('/') + "/index-v2.json").head().build()
            http.newCall(req).execute().use { if (it.isSuccessful) it.header("Content-Length")?.toLongOrNull() ?: -1L else -1L }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Downloads (or reuses the cached copy of) the repo's index and verifies it against the repo's
     * signed `entry.jar`. Throws if verification fails; a failed index is deleted, never used.
     */
    suspend fun indexFile(
        repoUrl: String,
        cacheDir: File,
        force: Boolean = false,
        onProgress: (SyncStage, Long, Long) -> Unit = { _, _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val base = repoUrl.trimEnd('/')
        val file = indexPath(repoUrl, cacheDir)
        if (!force && isFresh(repoUrl, cacheDir) && metaPath(file).exists()) return@withContext file

        val part = File(file.parentFile, file.name + ".part")
        var lastError: Exception? = null
        var done = false
        for (attempt in 1..3) {
            try {
                downloadIndex(base, part, onProgress)
                done = true
                break
            } catch (e: HttpFailure) {
                part.delete()
                throw e
            } catch (e: IOException) {
                part.delete()
                lastError = e
                if (attempt < 3) kotlinx.coroutines.delay(1500L * attempt)
            }
        }
        if (!done) throw IOException(friendlyNetworkError(lastError ?: IOException()), lastError)

        onProgress(SyncStage.VERIFY, 0, 0)
        val result = try {
            verify(base, part, cacheDir)
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        file.delete()
        if (!part.renameTo(file)) throw IOException("Could not save the index")
        val p = Properties()
        p.setProperty("verified", result.first.toString())
        p.setProperty("fp", result.second ?: "")
        metaPath(file).outputStream().use { p.store(it, null) }
        file
    }

    /** One attempt at fetching the index. Asks for gzip and unpacks it here, so progress follows the wire bytes. */
    private fun downloadIndex(base: String, part: File, onProgress: (SyncStage, Long, Long) -> Unit) {
        val req = Request.Builder().url("$base/index-v2.json")
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) throw HttpFailure("This repository doesn't offer index-v2.json.")
            if (!resp.isSuccessful) throw HttpFailure("The server answered ${resp.code} for the index.")
            val body = resp.body ?: throw IOException("Empty index")
            val total = body.contentLength()
            val counted = CountingStream(body.byteStream()) { onProgress(SyncStage.DOWNLOAD, it, total) }
            val stream = if (resp.header("Content-Encoding").equals("gzip", ignoreCase = true)) GZIPInputStream(counted) else counted
            stream.use { input -> part.outputStream().use { input.copyTo(it, 64 * 1024) } }
        }
    }

    /** Returns (verified, signing-key fingerprint). */
    private fun verify(base: String, index: File, cacheDir: File): Pair<Boolean, String?> {
        val pinned = PINNED[norm(base)]
        val jar = File(index.parentFile, index.name + ".jar")
        try {
            val req = Request.Builder().url("$base/entry.jar").build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    if (pinned != null) throw IOException("The repository's signature file is missing.")
                    return false to null
                }
                if (!resp.isSuccessful) throw IOException("Could not download the signature file (${resp.code})")
                val body = resp.body ?: throw IOException("Empty signature file")
                body.byteStream().use { input -> jar.outputStream().use { input.copyTo(it) } }
            }

            val fp: String
            val entryText: String
            JarFile(jar, true).use { jf ->
                val entry = jf.getJarEntry("entry.json") ?: throw IOException("The signature file has no entry.json.")
                entryText = jf.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                val certs = entry.certificates
                if (certs.isNullOrEmpty()) throw IOException("The repository's index is not signed.")
                fp = sha256Hex(certs[0].encoded).uppercase(Locale.ROOT)
            }

            if (pinned != null && fp != pinned) {
                throw IOException("The index is signed with an unexpected key. It was rejected.")
            }
            if (pinned == null) {
                val known = knownKey(norm(base))
                if (known != null && !known.equals(fp, ignoreCase = true)) {
                    throw IOException("This repository's signing key changed since you first used it. It was rejected.")
                }
                if (known == null) rememberKey(norm(base), fp)
            }

            val expected = JSONObject(entryText).getJSONObject("index").getString("sha256")
            val actual = fileSha256(index)
            if (!expected.equals(actual, ignoreCase = true)) {
                throw IOException("The index doesn't match its signature. It was rejected.")
            }
            return true to fp
        } finally {
            jar.delete()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fileSha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The newest version F-Droid advertises for [pkg], from its small public API, or null when the repo has
     * no such API. Used only to show an update hint; installs still come from the verified index.
     */
    suspend fun latestVersion(repoUrl: String, pkg: String): String? = withContext(Dispatchers.IO) {
        val host = repoUrl.substringAfter("://").substringBefore('/')
        if (!host.endsWith("f-droid.org")) return@withContext null
        try {
            val req = Request.Builder().url("https://f-droid.org/api/v1/packages/$pkg").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                val list = json.optJSONArray("packages") ?: return@withContext null
                val suggested = json.optLong("suggestedVersionCode", -1)
                var best: JSONObject? = null
                for (i in 0 until list.length()) {
                    val o = list.getJSONObject(i)
                    if (o.optLong("versionCode") == suggested) { best = o; break }
                    if (best == null) best = o
                }
                best?.optString("versionName")?.ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Full details for one package, or null if the index doesn't list it. */
    suspend fun details(index: File, repoUrl: String, pkg: String): FDroidDetails? = withContext(Dispatchers.IO) {
        val base = repoUrl.trimEnd('/')
        var out: FDroidDetails? = null
        scan(index, only = setOf(pkg)) { r, _ -> out = readDetails(r, base) }
        out
    }

    private data class VInfo(val code: Long, val name: String, val added: Long, val size: Long, val minSdk: Int, val anti: List<String>)

    private fun readDetails(r: JsonReader, base: String): FDroidDetails {
        var desc = ""
        var license = ""
        var updated = 0L
        val cats = mutableListOf<String>()
        val shots = mutableListOf<String>()
        val vers = mutableListOf<VInfo>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "metadata" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "description" -> desc = readLocalized(r)
                            "license" -> license = readString(r)
                            "lastUpdated" -> updated = readLong(r)
                            "categories" -> {
                                if (r.peek() == JsonToken.BEGIN_ARRAY) {
                                    r.beginArray()
                                    while (r.hasNext()) cats += readString(r)
                                    r.endArray()
                                } else r.skipValue()
                            }
                            "screenshots" -> shots += readShots(r, base)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                "versions" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        r.nextName()
                        vers += readVInfo(r)
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        val sorted = vers.sortedByDescending { it.code }
        val newest = sorted.firstOrNull()
        return FDroidDetails(
            description = desc.replace(Regex("<[^>]+>"), "").trim(),
            categories = cats,
            license = license,
            updatedMs = updated,
            minSdk = newest?.minSdk ?: 0,
            antiFeatures = newest?.anti ?: emptyList(),
            screenshots = shots.take(8),
            versions = sorted.take(8).map { FDroidVersionInfo(it.name, it.added, it.size) },
        )
    }

    private fun readVInfo(r: JsonReader): VInfo {
        var code = 0L
        var name = ""
        var added = 0L
        var size = 0L
        var minSdk = 0
        val anti = mutableListOf<String>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "added" -> added = readLong(r)
                "file" -> {
                    r.beginObject()
                    while (r.hasNext()) { if (r.nextName() == "size") size = readLong(r) else r.skipValue() }
                    r.endObject()
                }
                "manifest" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "versionName" -> name = readString(r)
                            "versionCode" -> code = readLong(r)
                            "usesSdk" -> {
                                if (r.peek() == JsonToken.BEGIN_OBJECT) {
                                    r.beginObject()
                                    while (r.hasNext()) { if (r.nextName() == "minSdkVersion") minSdk = readLong(r).toInt() else r.skipValue() }
                                    r.endObject()
                                } else r.skipValue()
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                "antiFeatures" -> {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject()
                        while (r.hasNext()) { anti += r.nextName(); r.skipValue() }
                        r.endObject()
                    } else r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return VInfo(code, name, added, size, minSdk, anti)
    }

    /** screenshots is {"phone": {"en-US": [{"name": "/pkg/en-US/phoneScreenshots/1.png"}]}}. */
    private fun readShots(r: JsonReader, base: String): List<String> {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return emptyList() }
        var phone: List<String> = emptyList()
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "phone" && r.peek() == JsonToken.BEGIN_OBJECT) phone = readShotFiles(r, base) else r.skipValue()
        }
        r.endObject()
        return phone
    }

    private fun readShotFiles(r: JsonReader, base: String): List<String> {
        var exact: List<String>? = null
        var first: List<String>? = null
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            if (r.peek() == JsonToken.BEGIN_ARRAY) {
                val list = mutableListOf<String>()
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) {
                        var n = ""
                        r.beginObject()
                        while (r.hasNext()) { if (r.nextName() == "name") n = readString(r) else r.skipValue() }
                        r.endObject()
                        if (n.isNotBlank()) list += base + (if (n.startsWith("/")) n else "/$n")
                    } else r.skipValue()
                }
                r.endArray()
                if (first == null) first = list
                if (key.equals("en-US", ignoreCase = true)) exact = list
            } else r.skipValue()
        }
        r.endObject()
        return exact ?: first ?: emptyList()
    }

    /** Every app in the index, for the A-Z archive screen. */
    suspend fun browse(index: File, repoUrl: String): List<ArchiveApp> = withContext(Dispatchers.IO) {
        val base = repoUrl.trimEnd('/')
        val out = ArrayList<ArchiveApp>(4096)
        scan(index) { r, pkg ->
            val info = readPackage(r, wantVersions = true)
            val suitable = info.versions.filter { fitsDevice(it) }
            val best = suitable.maxByOrNull { it.code }
            val icon = info.icon?.let { base + (if (it.startsWith("/")) it else "/$it") }
            out += ArchiveApp(
                pkg, info.name.ifBlank { pkg }, info.summary, icon, info.categories, info.updated,
                fits = best != null, flagged = best?.flagged ?: false,
            )
        }
        out
    }

    private fun fitsDevice(v: Version) =
        (v.abis.isEmpty() || v.abis.any { it in Build.SUPPORTED_ABIS }) &&
            v.minSdk <= Build.VERSION.SDK_INT && v.file.isNotBlank()

    /** Apps whose name, summary or package id contains [query], sorted by name. */
    suspend fun search(index: File, query: String, limit: Int = 50): List<FDroidEntry> =
        withContext(Dispatchers.IO) {
            val q = query.trim().lowercase(Locale.ROOT)
            val out = mutableListOf<FDroidEntry>()
            scan(index) { r, pkg ->
                val info = readPackage(r, wantVersions = false)
                val name = info.name
                val summary = info.summary
                val hit = q.isEmpty() ||
                    name.lowercase(Locale.ROOT).contains(q) ||
                    summary.lowercase(Locale.ROOT).contains(q) ||
                    pkg.lowercase(Locale.ROOT).contains(q)
                if (hit) out += FDroidEntry(pkg, name.ifBlank { pkg }, summary)
            }
            out.sortedBy { it.name.lowercase(Locale.ROOT) }.take(limit)
        }

    /** The newest version of each package in [packages] that this device can run. */
    suspend fun resolve(index: File, repoUrl: String, packages: Set<String>): List<FDroidBuild> =
        withContext(Dispatchers.IO) {
            val base = repoUrl.trimEnd('/')
            val out = mutableListOf<FDroidBuild>()
            scan(index, only = packages) { r, pkg ->
                val info = readPackage(r, wantVersions = true)
                val name = info.name
                val summary = info.summary
                val best = info.versions
                    .filter { fitsDevice(it) }
                    .maxByOrNull { it.code }
                if (best != null) {
                    val path = if (best.file.startsWith("/")) best.file else "/${best.file}"
                    out += FDroidBuild(
                        pkg, name.ifBlank { pkg }, summary, best.name.ifBlank { best.code.toString() },
                        best.code, base + path, best.sha256, best.size,
                    )
                }
            }
            out
        }

    // ---- Streaming parser ---------------------------------------------------------------------

    /**
     * Walks the "packages" object. [visit] is called for packages in [only] (or all of them)
     * and must consume the package's value; every other package is skipped.
     */
    private fun scan(file: File, only: Set<String>? = null, visit: (JsonReader, String) -> Unit) {
        JsonReader(file.bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "packages") {
                    r.beginObject()
                    while (r.hasNext()) {
                        val pkg = r.nextName()
                        if (only == null || pkg in only) visit(r, pkg) else r.skipValue()
                    }
                    r.endObject()
                } else {
                    r.skipValue()
                }
            }
            r.endObject()
        }
    }

    private fun readPackage(r: JsonReader, wantVersions: Boolean): PackageInfo {
        var name = ""
        var summary = ""
        var icon: String? = null
        var updated = 0L
        val cats = mutableListOf<String>()
        var versions: List<Version> = emptyList()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "metadata" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "name" -> name = readLocalized(r)
                            "summary" -> summary = readLocalized(r)
                            "icon" -> icon = readIcon(r)
                            "lastUpdated" -> updated = readLong(r)
                            "categories" -> {
                                if (r.peek() == JsonToken.BEGIN_ARRAY) {
                                    r.beginArray()
                                    while (r.hasNext()) cats += readString(r)
                                    r.endArray()
                                } else r.skipValue()
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                "versions" -> if (wantVersions) versions = readVersions(r) else r.skipValue()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return PackageInfo(name, summary, versions, icon, cats, updated)
    }

    /** icon is {"en-US": {"name": "/pkg/en-US/icon.png", ...}}; prefer en-US, else the first. */
    private fun readIcon(r: JsonReader): String? {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
        var exact: String? = null
        var first: String? = null
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            if (r.peek() == JsonToken.BEGIN_OBJECT) {
                var n: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "name") n = readString(r).ifBlank { null } else r.skipValue()
                }
                r.endObject()
                if (first == null) first = n
                if (key.equals("en-US", ignoreCase = true)) exact = n
            } else r.skipValue()
        }
        r.endObject()
        return exact ?: first
    }

    private fun readVersions(r: JsonReader): List<Version> {
        val out = mutableListOf<Version>()
        r.beginObject()
        while (r.hasNext()) {
            r.nextName() // version hash
            out += readVersion(r)
        }
        r.endObject()
        return out
    }

    private fun readVersion(r: JsonReader): Version {
        var file = ""
        var sha: String? = null
        var size = 0L
        var code = 0L
        var vName = ""
        var minSdk = 0
        val abis = mutableListOf<String>()
        var flagged = false
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "antiFeatures" -> {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject()
                        while (r.hasNext()) { r.nextName(); r.skipValue(); flagged = true }
                        r.endObject()
                    } else r.skipValue()
                }
                "file" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "name" -> file = readString(r)
                            "sha256" -> sha = readString(r).ifBlank { null }
                            "size" -> size = readLong(r)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                "manifest" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "versionName" -> vName = readString(r)
                            "versionCode" -> code = readLong(r)
                            "nativecode" -> {
                                if (r.peek() == JsonToken.BEGIN_ARRAY) {
                                    r.beginArray()
                                    while (r.hasNext()) abis += readString(r)
                                    r.endArray()
                                } else r.skipValue()
                            }
                            "usesSdk" -> {
                                if (r.peek() == JsonToken.BEGIN_OBJECT) {
                                    r.beginObject()
                                    while (r.hasNext()) {
                                        if (r.nextName() == "minSdkVersion") minSdk = readLong(r).toInt() else r.skipValue()
                                    }
                                    r.endObject()
                                } else r.skipValue()
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Version(code, vName, file, sha, size, abis, minSdk, flagged)
    }

    /** Index text is localized as {"en-US": "..."}; prefer English, else the first entry. */
    private fun readLocalized(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING -> r.nextString()
        JsonToken.BEGIN_OBJECT -> {
            var exact: String? = null
            var english: String? = null
            var first: String? = null
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName()
                if (r.peek() == JsonToken.STRING) {
                    val v = r.nextString()
                    if (first == null) first = v
                    if (key.equals("en-US", ignoreCase = true)) exact = v
                    else if (key.startsWith("en", ignoreCase = true) && english == null) english = v
                } else r.skipValue()
            }
            r.endObject()
            exact ?: english ?: first ?: ""
        }
        else -> { r.skipValue(); "" }
    }

    private fun readString(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextString()
        else -> { r.skipValue(); "" }
    }

    private fun readLong(r: JsonReader): Long = when (r.peek()) {
        JsonToken.NUMBER -> r.nextDouble().toLong()
        JsonToken.STRING -> r.nextString().toLongOrNull() ?: 0L
        else -> { r.skipValue(); 0L }
    }
}

/** An HTTP answer that retrying won't change (404, 500 and so on). */
private class HttpFailure(message: String) : IOException(message)

/** Counts the bytes read from the wire, so progress is right even when the body is gzip-compressed. */
private class CountingStream(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
    private var total = 0L
    override fun read(): Int = super.read().also { if (it >= 0) { total++; onBytes(total) } }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) { total += it; onBytes(total) } }
}

/** A plain-language reason for a failed transfer. */
private fun friendlyNetworkError(e: Exception): String = when {
    e is SocketTimeoutException -> "The connection timed out. Check your signal and try again."
    e is UnknownHostException -> "No internet connection."
    e is SocketException || e.message?.contains("abort", ignoreCase = true) == true ->
        "The connection was interrupted. Try again on a steadier connection."
    else -> e.message ?: "The download failed."
}
