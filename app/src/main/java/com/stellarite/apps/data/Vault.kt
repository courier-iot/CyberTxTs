package com.stellarite.apps.data

import com.stellarite.apps.Config
import java.io.IOException

/**
 * Saves and loads snapshots in a private repo in the Access Key owner's GitHub account.
 *
 * The only permission this strictly needs is Contents read and write on that one repo.
 * Creating the repo is attempted when it is missing, and if the key isn't allowed to
 * ([BackupException]) the user is told to create it by hand.
 */
class Vault(private val client: GitHubClient, private val prefs: Prefs) {

    private fun token(): String =
        prefs.token.ifBlank { throw BackupException("Add an Access Key in Uplink to use GitHub backup.") }

    private suspend fun owner(token: String): String =
        prefs.backupOwner.ifBlank { client.whoami(token).also { prefs.backupOwner = it } }

    /** Writes this device's section, keeping every other device's section as it is. */
    suspend fun push(local: DeviceSnapshot) {
        val token = token()
        val owner = owner(token)
        if (!client.repoExists(owner, Config.BACKUP_REPO, token)) {
            client.createPrivateRepo(token, Config.BACKUP_REPO, "Stellarite Apps snapshots")
        }
        repeat(3) {
            val remote = client.getFile(owner, Config.BACKUP_REPO, Config.BACKUP_FILE, token)
            val base = remote?.let { runCatching { Snapshot.parse(it.text) }.getOrNull() } ?: Snapshot()
            val merged = base.withDevice(prefs.deviceId, local)
            try {
                client.putFile(
                    owner, Config.BACKUP_REPO, Config.BACKUP_FILE, token,
                    merged.toText(), remote?.sha, "Snapshot from ${local.name}",
                )
                return
            } catch (_: StaleFileException) {
                // Another device saved first; read again and retry.
            }
        }
        throw IOException("Could not save the snapshot because another device was saving at the same time. Try again.")
    }

    suspend fun pull(): Snapshot {
        val token = token()
        val owner = owner(token)
        val remote = client.getFile(owner, Config.BACKUP_REPO, Config.BACKUP_FILE, token)
            ?: throw BackupException("No snapshot found in $owner/${Config.BACKUP_REPO} yet. Take one on another device first.")
        return Snapshot.parse(remote.text)
    }
}
