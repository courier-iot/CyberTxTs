package com.stellarite.apps

/** App-wide constants. */
object Config {
    /**
     * GitHub user or organization whose releases form the built-in Stellarite catalog.
     * Works for both user accounts and organizations.
     */
    const val DEFAULT_OWNER = "courier-iot"

    /** Private repo (in the Access Key owner's account) that holds Backup Vault snapshots. */
    const val BACKUP_REPO = "stellarite-backup"
    const val BACKUP_FILE = "snapshot.json"
    const val BACKUP_FILE_NAME = "stellarite-snapshot.json"
}
