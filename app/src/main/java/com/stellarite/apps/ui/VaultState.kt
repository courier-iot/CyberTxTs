package com.stellarite.apps.ui

import com.stellarite.apps.data.Snapshot

/** State of the Backup Vault panel in Control Core. */
data class VaultUi(
    /** "github" or "file". */
    val target: String = "github",
    val autoSnapshot: Boolean = false,
    val hasKey: Boolean = false,
    /** Login that owns the backup repo, once known. */
    val owner: String? = null,
    val lastAt: Long = 0L,
    val lastCount: Int = 0,
    val busy: Boolean = false,
    /** Problem to show under the status line, if any. */
    val note: String? = null,
) {
    val linked: Boolean get() = lastAt > 0L && note == null
}

enum class RestoreMode { RECORDED, LATEST }

/** State of the Restore screen. A non-null value means the screen is showing. */
data class RestoreUi(
    val loading: Boolean = false,
    val error: String? = null,
    val source: String = "",
    val snapshot: Snapshot? = null,
    val deviceId: String? = null,
    val mode: RestoreMode = RestoreMode.RECORDED,
    /** Repo full names ticked for restore. */
    val selected: Set<String> = emptySet(),
    val includeSettings: Boolean = true,
    /** Installed versionName per repo on this device right now, null when not installed. */
    val installed: Map<String, String?> = emptyMap(),
    /** Set while a restore is running, e.g. "Restoring 2 of 3 · Orbit Notes". */
    val progress: String? = null,
    val done: String? = null,
)
