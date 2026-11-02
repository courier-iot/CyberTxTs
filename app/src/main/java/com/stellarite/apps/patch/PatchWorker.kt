package com.stellarite.apps.patch

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stellarite.apps.data.Catalog
import com.stellarite.apps.data.FDroidClient
import com.stellarite.apps.data.GitHubClient
import com.stellarite.apps.data.InstallResult
import com.stellarite.apps.data.Installer
import com.stellarite.apps.data.Prefs
import com.stellarite.apps.data.RateLimitException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Background job: finds newer releases for opted-in modules and installs them. */
class PatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (!prefs.backgroundActive) return Result.success()
        val token = prefs.token.ifBlank { null }
        val client = GitHubClient()

        return try {
            val catalog = Catalog(ctx, client, FDroidClient.withPrefs(prefs), prefs)
            val found = if (prefs.autoPatchActive) {
                catalog.load(withSuggested = true).apps
            } else {
                // Alerts only: no large index downloads, just a cheap look at installed suggestions.
                val base = catalog.load().apps
                base + catalog.installedSuggestions().filter { s -> base.none { it.repo.fullName == s.repo.fullName } }
            }
            val needAlert = mutableListOf<String>()
            val alertSig = mutableListOf<String>()
            for (app in found) {
                val repo = app.repo
                val release = app.release
                val apk = app.apk
                // Only modules installed through Stellarite Apps are known by package name.
                val pkg = prefs.packageFor(repo.fullName) ?: continue
                val installed = Installer.installedVersion(ctx, pkg) ?: continue
                if (Installer.sameVersion(installed, release.tagName)) continue
                if (!prefs.shouldAutoPatch(repo.fullName) || !prefs.autoPatchActive) {
                    // Not patched automatically: tell the user instead.
                    needAlert += repo.name
                    alertSig += repo.fullName + "@" + release.tagName
                    continue
                }

                val safeName = repo.fullName.replace('/', '_').replace(':', '_')
                val file = File(ctx.cacheDir, "apks/$safeName-${release.tagName}.apk")
                try {
                    client.download(apk, token, file) { }
                    val result = Installer.install(ctx, file, repo.name, background = true)
                    if (result == InstallResult.NEEDS_PERMISSION) break
                } catch (e: IOException) {
                    file.delete()
                    throw e
                }
            }
            if (prefs.updateAlerts && needAlert.isNotEmpty()) {
                val sig = alertSig.sorted().joinToString("|")
                if (sig != prefs.lastAlertSig) {
                    Notifier.updatesAvailable(ctx, needAlert)
                    prefs.lastAlertSig = sig
                }
            } else if (needAlert.isEmpty()) {
                prefs.lastAlertSig = ""
            }
            Result.success()
        } catch (_: RateLimitException) {
            Result.retry()
        } catch (_: IOException) {
            Result.retry()
        }
    }
}

object AutoPatch {
    private const val NAME = "auto-patch"

    /** Schedules or cancels the periodic patch job to match the saved settings. */
    fun sync(ctx: Context, prefs: Prefs) {
        val wm = WorkManager.getInstance(ctx)
        if (!prefs.backgroundActive) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val network = if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = PeriodicWorkRequestBuilder<PatchWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
