package com.stellarite.apps.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class SourceType { ACCOUNT, REPO, STARS, FDROID }

/**
 * An extra place to get modules from, on top of the main catalog.
 *
 * [value] depends on the type: a GitHub login (ACCOUNT), "owner/repo" (REPO), nothing (STARS),
 * or the repository address (FDROID). For FDROID, [picks] are the package ids the user chose,
 * because an F-Droid index has thousands of apps and only the picked ones join the catalog.
 */
@Serializable
data class Source(
    val id: String,
    val type: SourceType,
    val value: String,
    val label: String,
    val enabled: Boolean = true,
    val picks: List<String> = emptyList(),
) {
    fun sameTarget(other: Source): Boolean =
        type == other.type && value.equals(other.value, ignoreCase = true)
}

object SourceLinks {
    const val FDROID_URL = "https://f-droid.org/repo"
    const val IZZY_URL = "https://apt.izzysoft.de/fdroid/repo"

    private val githubPrefix = Regex("^(https?://)?(www\\.)?github\\.com", RegexOption.IGNORE_CASE)
    private val namePart = Regex("^[A-Za-z0-9._-]+$")

    private fun make(type: SourceType, value: String, label: String) =
        Source(UUID.randomUUID().toString(), type, value, label)

    fun stars() = make(SourceType.STARS, "", "Your starred repos")

    fun fdroid(url: String, label: String) = make(SourceType.FDROID, url.trimEnd('/'), label)

    /**
     * Works out what a pasted link points at, without any network call.
     * github.com/owner -> account, github.com/owner/repo (or owner/repo) -> single repo,
     * an https address -> F-Droid-style repository. Returns null if it is none of those.
     */
    fun parse(raw: String): Source? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val isGithub = githubPrefix.containsMatchIn(text)
        if (!isGithub && text.startsWith("http", ignoreCase = true)) {
            if (!text.startsWith("https://", ignoreCase = true)) return null
            val base = text
                .removeSuffix("/index-v2.json").removeSuffix("/index-v1.jar").removeSuffix("/entry.json")
                .trimEnd('/')
            val host = base.substringAfter("://").substringBefore('/')
            if (!host.contains('.')) return null
            val label = when {
                host.endsWith("f-droid.org") -> "F-Droid"
                host.contains("izzysoft") -> "IzzyOnDroid"
                else -> host
            }
            return fdroid(base, label)
        }

        val parts = text
            .substringBefore('?').substringBefore('#')
            .replaceFirst(githubPrefix, "")
            .trim('/')
            .removePrefix("@")
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(2)
        return when {
            parts.size == 1 && namePart.matches(parts[0]) -> make(SourceType.ACCOUNT, parts[0], parts[0])
            parts.size == 2 && namePart.matches(parts[0]) && namePart.matches(parts[1].removeSuffix(".git")) ->
                make(SourceType.REPO, "${parts[0]}/${parts[1].removeSuffix(".git")}", parts[1].removeSuffix(".git"))
            else -> null
        }
    }
}
