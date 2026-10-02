package com.seth.rvgandroid

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlin.concurrent.thread
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update checker. MIRRORED in KaviGuard Android (same file, only
 * GITHUB_REPO differs) so both apps stay on one consistent design.
 *
 * Source of truth: GitHub releases on the app's public repo.
 *   GET https://api.github.com/repos/<owner>/<repo>/releases/latest
 * No secrets, no auth, no extra servers — the APK rides as a release asset.
 *
 * Android reality: a sideloaded user app cannot silently update itself.
 * Best achievable UX: check version -> download APK in background ->
 * fire the system install intent -> the user taps "Install" once.
 * PackageManager enforces same-signer, so a tampered APK can't install.
 *
 * Triggers:
 *  - onAppLaunch(): call from MainActivity.onCreate. Shows a pending-update
 *    dialog if the daily check found one; otherwise checks if >24h elapsed.
 *  - checkNow(): manual "Check for updates" button.
 *  - scheduleDaily(): AlarmManager once-daily check (version metadata only,
 *    ~2KB — no auto-download, Seth is on metered data). Re-scheduled on
 *    every launch and on BOOT_COMPLETED.
 */
object UpdateManager {

    // ===== PER-APP CONFIG (this is the only line that differs per app) =====
    private const val GITHUB_REPO = "kellner-dot/rvg-android"
    // =======================================================================

    private const val PREFS = "self_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_PENDING_VERSION = "pending_version"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val APK_NAME = "update.apk"

    data class ReleaseInfo(val version: String, val apkUrl: String)

    /** Call from MainActivity.onCreate(). */
    fun onAppLaunch(activity: Activity) {
        scheduleDaily(activity)
        val pending = getPending(activity)
        if (pending != null && isNewer(pending, installedVersion(activity))) {
            askToUpdate(activity, pending, null)
            return
        }
        val last = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0)
        if (System.currentTimeMillis() - last > CHECK_INTERVAL_MS) {
            checkInBackground(activity, activity, userInitiated = false)
        }
    }

    /** Manual "Check for updates" button. */
    fun checkNow(activity: Activity) {
        Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show()
        checkInBackground(activity, activity, userInitiated = true)
    }

    /** Once-daily version check. Version metadata only — never auto-downloads. */
    fun scheduleDaily(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, UpdateCheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR,
            AlarmManager.INTERVAL_DAY,
            pi
        )
    }

    internal fun checkInBackground(
        context: Context,
        activity: Activity?,
        userInitiated: Boolean
    ) {
        thread {
            try {
                val info = fetchLatest()
                markChecked(context)
                if (info == null) {
                    if (userInitiated) toastOnUi(activity, "Update check failed — try again later.")
                    return@thread
                }
                if (isNewer(info.version, installedVersion(context))) {
                    setPending(context, info.version)
                    if (activity != null && !activity.isFinishing) {
                        activity.runOnUiThread { askToUpdate(activity, info.version, info.apkUrl) }
                    }
                } else {
                    clearPending(context)
                    if (userInitiated) {
                        toastOnUi(activity, "You're on the latest version (v${installedVersion(context)}).")
                    }
                }
            } catch (e: Exception) {
                if (userInitiated) toastOnUi(activity, "Update check failed: ${e.message}")
            }
        }
    }

    private fun askToUpdate(activity: Activity, version: String, apkUrl: String?) {
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("Version $version is ready.\n\nDownload and install now? You'll tap \"Install\" once on the system screen.")
            .setPositiveButton("Download & Install") { _, _ -> downloadAndInstall(activity, version, apkUrl) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, version: String, apkUrl: String?) {
        val progress = AlertDialog.Builder(activity)
            .setTitle("Downloading v$version…")
            .setMessage("Please wait.")
            .setCancelable(false)
            .show()
        thread {
            try {
                val url = apkUrl ?: fetchLatest()?.apkUrl
                val apk = url?.let { downloadApk(activity, it) }
                activity.runOnUiThread {
                    progress.dismiss()
                    if (apk != null && apk.exists()) {
                        clearPending(activity)
                        fireInstall(activity, apk)
                    } else {
                        Toast.makeText(activity, "Download failed — try again later.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun fireInstall(activity: Activity, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }

    // ---------- network ----------

    internal fun fetchLatest(): ReleaseInfo? {
        val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "KaviSelfUpdate/1.0")
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            var tag = json.optString("tag_name", "")
            if (tag.startsWith("v")) tag = tag.substring(1)
            if (tag.isEmpty()) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name", "").endsWith(".apk")) {
                    return ReleaseInfo(tag, a.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadApk(context: Context, url: String): File? {
        val dir = context.externalCacheDir ?: return null
        val out = File(dir, APK_NAME)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 120000
            setRequestProperty("User-Agent", "KaviSelfUpdate/1.0")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) return null
            conn.inputStream.use { inp ->
                FileOutputStream(out).use { inp.copyTo(it) }
            }
            return if (out.length() > 0) out else null
        } finally {
            conn.disconnect()
        }
    }

    // ---------- version compare ----------

    /** Returns >0 if a is newer than b. */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    internal fun isNewer(latest: String, installed: String): Boolean =
        compareVersions(latest, installed) > 0

    internal fun installedVersion(context: Context): String {
        @Suppress("DEPRECATION")
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        return pi.versionName ?: "0.0.0"
    }

    // ---------- prefs ----------

    internal fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    private fun getPending(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_VERSION, null)

    internal fun setPending(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING_VERSION, version).apply()
    }

    private fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING_VERSION).apply()
    }

    private fun toastOnUi(activity: Activity?, msg: String) {
        activity?.runOnUiThread {
            if (!activity.isFinishing) Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }
}
