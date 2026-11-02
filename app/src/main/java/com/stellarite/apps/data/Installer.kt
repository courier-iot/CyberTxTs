package com.stellarite.apps.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.stellarite.apps.patch.InstallResultReceiver
import java.io.File

enum class InstallResult { STARTED, NEEDS_PERMISSION }

object Installer {

    const val EXTRA_LABEL = "stellarite.label"

    @Suppress("DEPRECATION")
    fun packageNameOf(ctx: Context, apk: File): String? =
        ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName

    @Suppress("DEPRECATION")
    fun installedVersion(ctx: Context, pkg: String?): String? {
        if (pkg == null) return null
        return try {
            ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: "installed"
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Installs [apk] through a PackageInstaller session, so Stellarite Apps becomes the installer of
     * record. That is what lets Android 12+ apply later updates without another confirmation.
     * The outcome arrives in [InstallResultReceiver]. The APK file is deleted once it is handed over.
     *
     * With [background] set, a missing install permission just reports [InstallResult.NEEDS_PERMISSION]
     * instead of opening Settings.
     */
    fun install(ctx: Context, apk: File, label: String, background: Boolean = false): InstallResult {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            if (!background) requestPermission(ctx)
            return InstallResult.NEEDS_PERMISSION
        }
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("module.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java).putExtra(EXTRA_LABEL, label)
            val pending = PendingIntent.getBroadcast(
                ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        apk.delete()
        return InstallResult.STARTED
    }

    fun requestPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun uninstall(ctx: Context, pkg: String) {
        ctx.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun open(ctx: Context, pkg: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** True when [installed] and [tag] describe the same version (ignores a leading "v" and trailing ".0"). */
    fun sameVersion(installed: String, tag: String): Boolean {
        fun parts(v: String): List<Int>? =
            Regex("""\d+(\.\d+)*""").find(v)?.value?.split('.')?.map { it.toInt() }
                ?.dropLastWhile { it == 0 }
        val a = parts(installed)
        val b = parts(tag)
        return if (a != null && b != null) a == b
        else installed.trim().removePrefix("v").equals(tag.trim().removePrefix("v"), ignoreCase = true)
    }
}
