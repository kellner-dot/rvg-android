package com.seth.rvgandroid

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import kotlin.concurrent.thread
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update checker + installer (v1.2.0 upgrade of the v1.0.9 design).
 *
 * What changed from v1.0.9, and why:
 *  - BEFORE: check version -> in-app dialog asks -> download to
 *    externalCacheDir -> ACTION_VIEW intent -> the user taps Install.
 *    The old code's own comment states the platform reality honestly:
 *    "a sideloaded user app cannot silently update itself."
 *  - NOW (Seth's word: "make it auto update"): a newer release is
 *    downloaded AUTOMATICALLY (no in-app ask dialog), verified, and
 *    installed through a PackageInstaller session. A session install
 *    is an UPDATE, not an uninstall: token, settings, and app-private
 *    state are preserved, there is no dark period, and no token
 *    re-registration. The metered-data caution (metadata-only daily
 *    checks, no auto-download) is superseded by Seth's auto-update word.
 *
 * HONEST LIMIT, stated plainly (do not promise past it): 1.3.3
 * ATTEMPTS a genuinely silent self-update, but it is best-effort,
 * not proven. installViaSession tries SessionParams.requireUserAction
 * = NOT_REQUIRED first (API 31+ only), which AOSP honors for an
 * installer updating itself when it declares
 * UPDATE_PACKAGES_WITHOUT_USER_ACTION and targets API 29+. If the
 * platform rejects the silent params, the install is retried once
 * with the default params, and the platform's confirmation dialog is
 * surfaced through the STATUS_PENDING_USER_ACTION branch in
 * UpdateInstallReceiver (which launches it — 1.3.1 never did, and
 * mis-recorded the pending state as "install failed: status -1").
 * Until the pilot observes either a zero-tap install or the
 * confirmation dialog on the phone, "auto update" means automatic
 * check + download + verify + staged session — not a proven zero-tap
 * install.
 *
 * Gates (all enforced before any install is attempted, see
 * UpdateVerify): source host allowlist, signer-certificate equality
 * with the installed app, strictly-greater versionCode. Any gate
 * failure refuses the install, records the reason in prefs (visible
 * via /rvd/status), deletes the APK, and leaves the old version
 * running — never bricks the control channel.
 *
 * Source of truth: Gitea releases on kellner-dot/rvg-android ONLY
 * (tailnet host; anonymous read, no secrets, no auth — the repo must
 * be anonymously readable or the check fails soft, as it does today).
 *   GET https://kavivpn.tail1cffd9.ts.net/api/v1/repos/kellner-dot/rvg-android/releases/latest
 * No secrets, no auth, no Play, no mirrors, no other source.
 *
 * Triggers:
 *  - onAppLaunch(): schedule daily check; auto-update if a pending
 *    newer version exists (and is not error-skipped) or the daily
 *    check is stale.
 *  - checkNow(): manual "Check for updates" button (forces retry
 *    past a recorded error).
 *  - scheduleDaily(): AlarmManager once-daily check; the receiver
 *    now auto-updates too (no activity needed for the session path).
 *  - Every transition is recorded in prefs and surfaced through
 *    /rvd/status (updateAvailable, updateVersion, updateError,
 *    lastUpdateCheckMs, lastUpdateResult).
 */
object UpdateManager {

    // ===== PER-APP CONFIG (this is the only line that differs per app) =====
    private const val GITHUB_REPO = "kellner-dot/rvg-android"
    // =======================================================================

    private const val PREFS = "self_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_PENDING_VERSION = "pending_version"
    private const val KEY_UPDATE_ERROR_VERSION = "update_error_version"
    private const val KEY_UPDATE_ERROR_MSG = "update_error_msg"
    private const val KEY_LAST_RESULT = "last_update_result"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val APK_NAME = "update.apk"
    private const val MAX_REDIRECTS = 5

    data class ReleaseInfo(val version: String, val apkUrl: String)

    /** Call from MainActivity.onCreate(). */
    fun onAppLaunch(activity: Activity) {
        scheduleDaily(activity)
        val pending = getPending(activity)
        val last = lastCheckMs(activity)
        val stale = System.currentTimeMillis() - last > CHECK_INTERVAL_MS
        if ((pending != null && isNewer(pending, installedVersion(activity))) || stale) {
            autoUpdate(activity, force = false, userInitiated = false)
        }
    }

    /** Manual "Check for updates" button. Forces retry past a recorded error. */
    fun checkNow(activity: Activity) {
        Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show()
        autoUpdate(activity, force = true, userInitiated = true)
    }

    /** Once-daily version check + auto-update (receiver path). */
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

    /**
     * Full auto-update flow on a background thread: check -> (skip if
     * errored and not forced) -> download to the PRIVATE files dir ->
     * verify (source, signer, versionCode) -> PackageInstaller session.
     * On any failure: record the reason, delete the APK, keep the old
     * version running. Never throws to the caller.
     */
    fun autoUpdate(context: Context, force: Boolean, userInitiated: Boolean) {
        thread {
            try {
                val info = fetchLatest()
                markChecked(context)
                if (info == null) {
                    recordUpdateError(context, null, "update check failed")
                    if (userInitiated) toastOnUi(context, "Update check failed — try again later.")
                    return@thread
                }
                if (!isNewer(info.version, installedVersion(context))) {
                    clearPending(context)
                    clearUpdateError(context)
                    if (userInitiated) {
                        toastOnUi(context, "You're on the latest version (v${installedVersion(context)}).")
                    }
                    return@thread
                }
                if (!force && getErrorVersion(context) == info.version) {
                    // A previous attempt at this exact version failed;
                    // wait for a new version or a manual forced retry.
                    return@thread
                }
                setPending(context, info.version)
                val apk = downloadApkPrivate(context, info.apkUrl)
                if (apk == null) {
                    recordUpdateError(context, info.version, "download failed or source refused")
                    if (userInitiated) toastOnUi(context, "Download failed — try again later.")
                    return@thread
                }
                val verified = verifyApk(context, apk)
                if (verified != null) {
                    apk.delete()
                    recordUpdateError(context, info.version, verified)
                    if (userInitiated) toastOnUi(context, "Update refused: $verified")
                    return@thread
                }
                val previous = installedVersion(context)
                if (installViaSession(context, apk, info.version, previous)) {
                    clearPending(context)
                    recordInstallResult(context, "install started for ${info.version} (was $previous)")
                    if (userInitiated) toastOnUi(context, "Installing update v${info.version}…")
                } else {
                    apk.delete()
                    recordUpdateError(context, info.version, "session install failed")
                    if (userInitiated) toastOnUi(context, "Install failed — old version still running.")
                }
            } catch (e: Exception) {
                recordUpdateError(context, null, "auto-update error: ${e.message}")
                if (userInitiated) toastOnUi(context, "Update failed: ${e.message}")
            }
        }
    }

    // ---------- install ----------

    /** Private files dir — never shared Downloads (unreliable there) and
     *  never the uninstall-wiped inbox. */
    private fun updateDir(context: Context): File =
        File(context.filesDir, "update").apply { mkdirs() }

    /**
     * Downloads with manual redirect following so EVERY hop's host is
     * validated against the allowlist before the body is read.
     * Returns the file, or null on any failure/refusal.
     */
    private fun downloadApkPrivate(context: Context, startUrl: String): File? {
        var url = URL(startUrl)
        if (!UpdateVerify.allowedAssetHost(url.host)) return null
        var conn: HttpURLConnection? = null
        try {
            for (hop in 0..MAX_REDIRECTS) {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20000
                    readTimeout = 120000
                    setRequestProperty("User-Agent", "KaviSelfUpdate/1.0")
                }
                val code = conn.responseCode
                if (code in 301..308) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    conn.disconnect()
                    url = URL(url, loc)
                    if (!UpdateVerify.allowedAssetHost(url.host)) return null
                    if (hop == MAX_REDIRECTS) return null
                    continue
                }
                if (code != 200) return null
                val out = File(updateDir(context), APK_NAME)
                conn.inputStream.use { inp ->
                    FileOutputStream(out).use { inp.copyTo(it) }
                }
                return if (out.length() > 0) out else null
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Verifies a downloaded APK. Returns null when it passes, or a
     * human-readable refusal reason when it must not be installed.
     */
    private fun verifyApk(context: Context, apk: File): String? {
        val pm = context.packageManager
        val archive = try {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: Exception) {
            null
        } ?: return "unparseable APK"
        if (archive.packageName != context.packageName) return "wrong package: ${archive.packageName}"
        @Suppress("DEPRECATION")
        val installed = try {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: Exception) {
            null
        } ?: return "installed package info unavailable"
        val archiveCerts = archive.signingInfo?.apkContentsSigners
            ?.map { it.toByteArray() } ?: return "downloaded APK has no signing certificates"
        val installedCerts = installed.signingInfo?.apkContentsSigners
            ?.map { it.toByteArray() } ?: return "installed app has no signing certificates"
        if (!UpdateVerify.signaturesMatch(installedCerts, archiveCerts)) {
            return "signer mismatch — refusing (would not install as an update)"
        }
        if (!UpdateVerify.isUpgrade(archive.longVersionCode, installed.longVersionCode)) {
            return "not an upgrade (downloaded ${archive.longVersionCode} <= installed ${installed.longVersionCode})"
        }
        return null
    }

    /**
     * Installs via a PackageInstaller session (an UPDATE: state
     * preserved, no uninstall, no token re-register). Returns true
     * when a session committed; the result callback lands in
     * UpdateInstallReceiver. On a successful commit the temp APK is
     * deleted here (the session already owns the bytes); on failure
     * it is left for the caller, which deletes it.
     *
     * 1.3.3: tries a genuinely silent install first (API 31+ only —
     * setRequireUserAction was added in API 31; minSdk is 29). AOSP
     * honors USER_ACTION_NOT_REQUIRED for an installer updating itself
     * when it declares UPDATE_PACKAGES_WITHOUT_USER_ACTION and targets
     * API 29+. If the silent attempt fails SYNCHRONOUSLY (the platform
     * rejecting the flag at createSession/commit), the failed session
     * is abandoned and the install is retried once with the default
     * params — the old, proven path, which surfaces the confirmation
     * dialog via STATUS_PENDING_USER_ACTION. A synchronous failure
     * therefore cannot brick the update; the async result (SUCCESS or
     * PENDING_USER_ACTION) still arrives at the receiver either way.
     */
    private fun installViaSession(
        context: Context,
        apk: File,
        version: String,
        previous: String
    ): Boolean {
        if (attemptInstallSession(context, apk, version, previous, silent = true)) {
            return true
        }
        return attemptInstallSession(context, apk, version, previous, silent = false)
    }

    /**
     * One session attempt. Returns true when the session committed.
     * On any synchronous failure the session (if created) is abandoned
     * and false is returned; the APK file is left for the caller (it
     * deletes it on the failure path, or after a successful commit the
     * session owns the bytes and the file is deleted here).
     */
    private fun attemptInstallSession(
        context: Context,
        apk: File,
        version: String,
        previous: String,
        silent: Boolean
    ): Boolean {
        var sessionId = -1
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName(context.packageName)
            params.setSize(apk.length())
            if (silent && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                )
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("update", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
                    putExtra("attempted_version", version)
                    putExtra("previous_version", previous)
                }
                val pi = PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    // MUST be mutable: AOSP delivers the session result as
                    // a fill-in Intent, silently discarded for an immutable
                    // PendingIntent (1.3.3/1.3.4 failure). See
                    // commitStatusReceiverFlags.
                    commitStatusReceiverFlags()
                )
                session.commit(pi.intentSender)
            }
            apk.delete()
            true
        } catch (_: Exception) {
            if (sessionId >= 0) {
                try {
                    context.packageManager.packageInstaller.abandonSession(sessionId)
                } catch (_: Exception) {
                    // Best effort only; the system cleans up abandoned sessions.
                }
            }
            false
        }
    }

    // ---------- network ----------

    internal fun fetchLatest(): ReleaseInfo? {
        val url = URL("https://kavivpn.tail1cffd9.ts.net/api/v1/repos/$GITHUB_REPO/releases/latest")
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

    // ---------- prefs / status reporting ----------

    internal fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    fun updateAvailable(context: Context): Boolean {
        val pending = getPending(context) ?: return false
        return isNewer(pending, installedVersion(context))
    }

    fun updateVersion(context: Context): String? = getPending(context)

    fun lastUpdateError(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UPDATE_ERROR_MSG, null)

    fun lastCheckMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0)

    fun lastUpdateResult(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_RESULT, null)

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

    private fun getErrorVersion(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UPDATE_ERROR_VERSION, null)

    internal fun recordUpdateError(context: Context, version: String?, msg: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UPDATE_ERROR_MSG, msg)
            .putString(KEY_UPDATE_ERROR_VERSION, version ?: getPending(context))
            .apply()
    }

    internal fun clearUpdateError(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_UPDATE_ERROR_MSG)
            .remove(KEY_UPDATE_ERROR_VERSION)
            .apply()
    }

    internal fun recordInstallResult(context: Context, result: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_RESULT, result).apply()
    }

    private fun toastOnUi(context: Context, msg: String) {
        if (context is Activity) {
            context.runOnUiThread {
                if (!context.isFinishing) {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
