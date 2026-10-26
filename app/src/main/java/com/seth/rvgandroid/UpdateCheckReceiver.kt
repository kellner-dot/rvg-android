package com.seth.rvgandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Fired once daily by UpdateManager's AlarmManager schedule.
 * v1.2.0: the daily check now AUTO-UPDATES when a newer version
 * exists (download + verify + PackageInstaller session, no activity
 * needed). Failures are recorded in prefs and surfaced via
 * /rvd/status; the old version keeps running.
 */
class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        thread {
            try {
                UpdateManager.autoUpdate(context, force = false, userInitiated = false)
            } catch (_: Exception) {
                // Silent — the next launch/manual check will retry.
            }
        }
    }
}
