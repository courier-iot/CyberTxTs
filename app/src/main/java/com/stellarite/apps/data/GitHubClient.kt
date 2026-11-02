package com.stellarite.apps.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.coroutineContext

class RateLimitException :
    IOException("GitHub rate limit reached. Add a personal access token in Settings.")

/** A problem with the Access Key or backup repo that the user can fix; the message says how. */
class BackupException(message: String) : IOException(message)

/** The snapshot file changed on GitHub between reading and writing it. */
class StaleFileException : IOException("The snapshot changed on GitHub.")

data class RemoteFile(val text: String, val sha: String)

class GitHubClient(private val http: OkHttpClient = OkHttpClient()) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun request(url: String, token: String?, accept: String = "application/vnd.github+json") =
        Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()

    /**
     * Without a token: public repos of [user].
     * With a token: every repo the token's owner owns, including private ones.
     */
    private suspend fun listRepos(
        user: String,
        token: String?,
        own: Boolean = !token.isNullOrBlank(),
    ): List<GhRepo> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<GhRepo>()
            for (page in 1..5) {
                val url = if (!own) {
                    "https://api.github.com/users/$user/repos?per_page=100&page=$page&type=owner"
                } else {
                    "https://api.github.com/user/repos?per_page=100&page=$page&affiliation=owner"
                }
                val batch = http.newCall(request(url, token)).execute().use { resp ->
                    if ((resp.code == 403 || resp.code == 429) &&
                        resp.header("X-RateLimit-Remaining") == "0"
                    ) throw RateLimitException()
                    if (resp.code == 404) throw IOException("GitHub user \"$user\" not found")
                    if (!resp.isSuccessful) throw IOException("GitHub error ${resp.code}")
                    json.decodeFromString<List<GhRepo>>(resp.body!!.string())
                }
                out += batch
                if (batch.size < 100) break
            }
            out
        }

    private suspend fun latestRelease(fullName: String, token: String?): GhRelease? =
        getRelease("https://api.github.com/repos/$fullName/releases/latest", token)

    private suspend fun releaseByTag(fullName: String, tag: String, token: String?): GhRelease? =
        getRelease("https://api.github.com/repos/$fullName/releases/tags/$tag", token)

    private suspend fun getRelease(url: String, token: String?): GhRelease? =
        withContext(Dispatchers.IO) {
            http.newCall(request(url, token)).execute().use { resp ->
                if ((resp.code == 403 || resp.code == 429) &&
                    resp.header("X-RateLimit-Remaining") == "0"
                ) throw RateLimitException()
                if (resp.code == 404) return@use null
                if (!resp.isSuccessful) throw IOException("GitHub error ${resp.code}")
                json.decodeFromString<GhRelease>(resp.body!!.string())
            }
        }

    /** The repo's README as raw Markdown, or null if it has none. Works for private repos with a token. */
    suspend fun fetchReadme(fullName: String, token: String?): String? =
        withContext(Dispatchers.IO) {
            val url = "https://api.github.com/repos/$fullName/readme"
            http.newCall(request(url, token, "application/vnd.github.raw+json")).execute().use { resp ->
                if ((resp.code == 403 || resp.code == 429) &&
                    resp.header("X-RateLimit-Remaining") == "0"
                ) throw RateLimitException()
                if (resp.code == 404) return@use null
                if (!resp.isSuccessful) throw IOException("GitHub error ${resp.code}")
                resp.body!!.string()
            }
        }

    /** Repos (no forks/archived) whose latest release ships an APK. */
    suspend fun fetchApps(
        user: String,
        token: String?,
        own: Boolean = !token.isNullOrBlank(),
    ): List<Triple<GhRepo, GhRelease, GhAsset>> {
        val repos = listRepos(user, token, own).filter { !it.fork && !it.archived }
        return withApkReleases(repos, token)
    }

    /** One repo (owner/name) if its latest release ships an APK, else null. */
    suspend fun fetchRepoApp(fullName: String, token: String?): Triple<GhRepo, GhRelease, GhAsset>? {
        val repo = withContext(Dispatchers.IO) {
            http.newCall(request("https://api.github.com/repos/$fullName", token)).execute().use { resp ->
                if ((resp.code == 403 || resp.code == 429) && resp.header("X-RateLimit-Remaining") == "0") {
                    throw RateLimitException()
                }
                if (resp.code == 404) return@use null
                if (!resp.isSuccessful) throw IOException("GitHub error ${resp.code}")
                json.decodeFromString<GhRepo>(resp.body!!.string())
            }
        } ?: return null
        return withApkReleases(listOf(repo), token).firstOrNull()
    }

    /** The first 100 repos the Access Key's owner has starred that ship an APK. Needs a token. */
    suspend fun fetchStarredApps(token: String): List<Triple<GhRepo, GhRelease, GhAsset>> {
        val repos = withContext(Dispatchers.IO) {
            http.newCall(request("https://api.github.com/user/starred?per_page=100", token)).execute().use { resp ->
                if ((resp.code == 403 || resp.code == 429) && resp.header("X-RateLimit-Remaining") == "0") {
                    throw RateLimitException()
                }
                if (resp.code == 401 || resp.code == 403) {
                    throw IOException("This Access Key can't read your starred repos.")
                }
                if (!resp.isSuccessful) throw IOException("GitHub error ${resp.code}")
                json.decodeFromString<List<GhRepo>>(resp.body!!.string())
            }
        }
        return withApkReleases(repos.filter { !it.archived }, token)
    }

    private suspend fun withApkReleases(
        repos: List<GhRepo>,
        token: String?,
    ): List<Triple<GhRepo, GhRelease, GhAsset>> = splitByRelease(repos, token).first

    /** (repos with an APK release, repos without one). */
    private suspend fun splitByRelease(
        repos: List<GhRepo>,
        token: String?,
    ): Pair<List<Triple<GhRepo, GhRelease, GhAsset>>, List<GhRepo>> {
        val gate = Semaphore(4)
        val results = coroutineScope {
            repos.map { repo ->
                async {
                    gate.withPermit {
                        val release = latestRelease(repo.fullName, token)
                        val apk = release?.let { pickApk(it) }
                        repo to (if (release != null && apk != null) Triple(repo, release, apk) else null)
                    }
                }
            }.awaitAll()
        }
        return results.mapNotNull { it.second } to results.filter { it.second == null }.map { it.first }
    }

    /**
     * Like [fetchApps], but also returns the account's Android-looking repos that have no APK release yet,
     * so the home page can show them as "No release yet".
     */
    suspend fun fetchHome(
        user: String,
        token: String?,
        own: Boolean = !token.isNullOrBlank(),
    ): Pair<List<Triple<GhRepo, GhRelease, GhAsset>>, List<GhRepo>> {
        val repos = listRepos(user, token, own).filter { !it.fork && !it.archived }
        val (found, missing) = splitByRelease(repos, token)
        val pending = missing.filter { r ->
            r.name != com.stellarite.apps.Config.BACKUP_REPO &&
                // GitHub reports no language for new or mixed repos, so those count too.
                (r.language == null || r.language.equals("Kotlin", true) || r.language.equals("Java", true) ||
                    r.topics.any { it.equals("android", true) || it.equals("stellarite", true) })
        }
        return found to pending
    }

    /**
     * The release and APK to restore a module from. With a [version] it looks for that exact
     * release (tried as given, with a "v" prefix, and without one); without, it uses the latest.
     */
    suspend fun resolveModule(fullName: String, version: String?, token: String?): Pair<GhRelease, GhAsset>? {
        val found: GhRelease? = if (version.isNullOrBlank()) {
            latestRelease(fullName, token)
        } else {
            val bare = version.removePrefix("v")
            releaseByTag(fullName, version, token)
                ?: releaseByTag(fullName, "v$bare", token)
                ?: releaseByTag(fullName, bare, token)
        }
        val release = found ?: return null
        val apk = pickApk(release) ?: return null
        return release to apk
    }

    // ---- Backup Vault -------------------------------------------------------------------------

    private val jsonType = "application/json".toMediaType()

    private fun write(method: String, url: String, token: String, body: String) =
        request(url, token).newBuilder().method(method, body.toRequestBody(jsonType)).build()

    private fun failIfRateLimited(resp: Response) {
        if ((resp.code == 403 || resp.code == 429) && resp.header("X-RateLimit-Remaining") == "0") {
            throw RateLimitException()
        }
    }

    /** Login of the account that owns [token]. */
    suspend fun whoami(token: String): String = withContext(Dispatchers.IO) {
        http.newCall(request("https://api.github.com/user", token)).execute().use { resp ->
            failIfRateLimited(resp)
            if (resp.code == 401) throw BackupException("GitHub rejected the Access Key.")
            if (!resp.isSuccessful) throw IOException("GitHub error ${resp.code}")
            json.parseToJsonElement(resp.body!!.string()).jsonObject["login"]?.jsonPrimitive?.content
                ?: throw IOException("Unexpected reply from GitHub")
        }
    }

    suspend fun repoExists(owner: String, repo: String, token: String): Boolean =
        withContext(Dispatchers.IO) {
            http.newCall(request("https://api.github.com/repos/$owner/$repo", token)).execute().use { resp ->
                failIfRateLimited(resp)
                when {
                    resp.isSuccessful -> true
                    resp.code == 404 -> false
                    resp.code == 401 -> throw BackupException("GitHub rejected the Access Key.")
                    else -> throw IOException("GitHub error ${resp.code}")
                }
            }
        }

    /**
     * Creates a private repo with a README (so it is never empty). Some Access Keys, notably
     * fine-grained tokens, are not allowed to create repos; that case gets a clear message
     * telling the user to create it by hand.
     */
    suspend fun createPrivateRepo(token: String, name: String, description: String) =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("name", name)
                put("description", description)
                put("private", true)
                put("auto_init", true)
            }.toString()
            http.newCall(write("POST", "https://api.github.com/user/repos", token, body)).execute().use { resp ->
                failIfRateLimited(resp)
                when {
                    resp.isSuccessful -> Unit
                    resp.code == 422 -> Unit // already exists
                    resp.code == 401 -> throw BackupException("GitHub rejected the Access Key.")
                    resp.code == 403 || resp.code == 404 -> throw BackupException(
                        "This Access Key can't create repositories. On GitHub, create a private repo named " +
                            "\"$name\" (tick Add a README), give the key Contents read and write on it, then try again."
                    )
                    else -> throw IOException("GitHub error ${resp.code}")
                }
            }
        }

    /** A file's text and blob sha, or null if the file (or repo) does not exist. */
    suspend fun getFile(owner: String, repo: String, path: String, token: String): RemoteFile? =
        withContext(Dispatchers.IO) {
            val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
            http.newCall(request(url, token)).execute().use { resp ->
                failIfRateLimited(resp)
                when {
                    resp.code == 404 -> null
                    resp.code == 401 -> throw BackupException("GitHub rejected the Access Key.")
                    resp.code == 403 -> throw BackupException(
                        "This Access Key can't read \"$repo\". Give it Contents read access to that repo."
                    )
                    !resp.isSuccessful -> throw IOException("GitHub error ${resp.code}")
                    else -> {
                        val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
                        val b64 = obj["content"]?.jsonPrimitive?.content ?: throw IOException("Unexpected reply from GitHub")
                        val sha = obj["sha"]?.jsonPrimitive?.content ?: throw IOException("Unexpected reply from GitHub")
                        RemoteFile(String(Base64.getMimeDecoder().decode(b64), Charsets.UTF_8), sha)
                    }
                }
            }
        }

    /** Creates the file, or updates it when [sha] (the current blob sha) is given. */
    suspend fun putFile(
        owner: String,
        repo: String,
        path: String,
        token: String,
        text: String,
        sha: String?,
        message: String,
    ) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
            if (sha != null) put("sha", sha)
        }.toString()
        val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
        http.newCall(write("PUT", url, token, body)).execute().use { resp ->
            failIfRateLimited(resp)
            when {
                resp.isSuccessful -> Unit
                resp.code == 409 || resp.code == 422 -> throw StaleFileException()
                resp.code == 401 -> throw BackupException("GitHub rejected the Access Key.")
                resp.code == 403 || resp.code == 404 -> throw BackupException(
                    "This Access Key can't write to \"$repo\". Give it Contents read and write on that repo."
                )
                else -> throw IOException("GitHub error ${resp.code}")
            }
        }
    }

    private fun pickApk(release: GhRelease): GhAsset? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        return apks.firstOrNull { it.name.contains("universal", true) || it.name.contains("release", true) }
            ?: apks.firstOrNull()
    }

    /** Streams the APK to [dest], reporting progress 0f..1f. */
    suspend fun download(
        asset: GhAsset,
        token: String?,
        dest: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // The Access Key is only ever sent to GitHub's own API, never to other hosts such as F-Droid.
        val viaGithubApi = !token.isNullOrBlank() && asset.url.startsWith("https://api.github.com/")
        val req = if (viaGithubApi) {
            request(asset.url, token, "application/octet-stream")
        } else {
            request(asset.browserDownloadUrl, null, "*/*")
        }
        val digest = asset.sha256?.let { MessageDigest.getInstance("SHA-256") }
        dest.parentFile?.mkdirs()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: asset.size
            var read = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest?.update(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (digest != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(asset.sha256, ignoreCase = true)) {
                dest.delete()
                throw IOException("Checksum mismatch, so the download was discarded.")
            }
        }
    }
}
