package com.seth.rvgandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Fired once daily by UpdateManager's AlarmManager schedule.
 * Version-metadata check only (~2KB) — never auto-downloads the APK.
 * If a newer version exists, a pending-update flag is saved and the
 * next app launch prompts Seth to download & install (one tap).
 */
class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        thread {
            try {
                val info = UpdateManager.fetchLatest() ?: return@thread
                UpdateManager.markChecked(context)
                if (UpdateManager.isNewer(info.version, UpdateManager.installedVersion(context))) {
                    UpdateManager.setPending(context, info.version)
                }
            } catch (e: Exception) {
                // Silent — the next launch/manual check will retry.
            }
        }
    }
}
