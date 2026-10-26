package com.seth.rvgandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the PackageInstaller session result for a self-update.
 * Records the outcome in UpdateManager prefs so /rvd/status can
 * report it (check + install + version before/after, never silent).
 * On failure the attempted version goes back to pending so a later
 * daily/manual check retries it; the old version keeps running.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val attempted = intent.getStringExtra("attempted_version") ?: "?"
        val previous = intent.getStringExtra("previous_version") ?: "?"
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateManager.recordInstallResult(
                    context,
                    "installed $attempted (was $previous)"
                )
                UpdateManager.clearUpdateError(context)
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "status $status"
                UpdateManager.recordInstallResult(
                    context, "install failed for $attempted: $msg"
                )
                UpdateManager.recordUpdateError(context, attempted, "install failed: $msg")
            }
        }
    }
}
