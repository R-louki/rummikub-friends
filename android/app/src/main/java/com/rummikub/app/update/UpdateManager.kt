package com.rummikub.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.rummikub.app.BuildConfig
import com.rummikub.core.network.ReleaseChecker
import com.rummikub.core.network.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class InstallResult { STARTED, NEEDS_PERMISSION, FAILED }

/**
 * Built-in updates for the directly distributed APK (no store account needed): looks for a newer
 * GitHub release, downloads its APK and hands it to Android's installer.
 *
 * The Play Store variant never updates itself (Google forbids it, the store does it): there
 * [BuildConfig.SELF_UPDATE] is false and nothing here runs.
 */
class UpdateManager(
    private val context: Context,
    private val checker: ReleaseChecker = ReleaseChecker("R-louki/rummikub-friends")
) {
    /** A newer release than the installed version, or null (up to date, offline, or Play variant). */
    suspend fun checkForUpdate(): ReleaseInfo? {
        if (!BuildConfig.SELF_UPDATE) return null
        val latest = try { checker.latest() } catch (_: Exception) { null } ?: return null
        return latest.takeIf { ReleaseChecker.isNewer(BuildConfig.VERSION_NAME, it.versionName) }
    }

    /** Downloads the APK into the app cache and reports progress from 0 to 1. */
    suspend fun download(info: ReleaseInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "Rummikub-Friends-${info.versionName}.apk")
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        target
    }

    /** Opens Android's installer on the downloaded APK, asking for the "install unknown apps" permission first if needed. */
    fun install(activity: Activity, apk: File): InstallResult {
        return try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                )
                return InstallResult.NEEDS_PERMISSION
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            activity.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            InstallResult.STARTED
        } catch (_: Exception) {
            InstallResult.FAILED
        }
    }
}
