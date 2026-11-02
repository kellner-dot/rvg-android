package com.seth.rvgandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the PackageInstaller session result for a self-update.
 * Records the outcome in UpdateManager prefs so /rvd/status can
 * report it (check + install + version before/after, never silent).
 *
 * Statuses (decided by UpdateInstallDecision, JVM-tested):
 *  - SUCCESS: installed; clear any error.
 *  - PENDING_USER_ACTION (-1): the system is asking the app to show
 *    the install confirmation dialog. Launch the intent from
 *    Intent.EXTRA_INTENT (FLAG_ACTIVITY_NEW_TASK — a receiver has no
 *    activity context) and record "awaiting user confirmation", NOT a
 *    failure. The follow-up callback after the user answers lands
 *    here again (SUCCESS or FAILURE_ABORTED). 1.3.1 had no branch for
 *    this status and mis-recorded it as "install failed: status -1".
 *  - FAILURE_ABORTED: the user declined the dialog. Recorded as
 *    declined, not failed; the version stays recorded as the error
 *    version so the daily alarm does not nag, and the manual button
 *    still forces a retry.
 *  - Anything else: a real failure; record it and keep the old
 *    version running.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val attempted = intent.getStringExtra("attempted_version") ?: "?"
        val previous = intent.getStringExtra("previous_version") ?: "?"
        val confirmation =
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        when (UpdateInstallDecision.decide(status, confirmation != null)) {
            InstallOutcome.SUCCESS -> {
                UpdateManager.recordInstallResult(
                    context,
                    "installed $attempted (was $previous)"
                )
                UpdateManager.clearUpdateError(context)
            }
            InstallOutcome.PENDING_USER_ACTION -> {
                // confirmation is non-null here by the decision contract.
                val confirm = confirmation!!
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                    UpdateManager.recordInstallResult(
                        context,
                        "awaiting user confirmation for $attempted"
                    )
                    // A stale failure for an older version must not keep
                    // showing once this version is awaiting confirmation.
                    UpdateManager.clearUpdateError(context)
                } catch (e: Exception) {
                    UpdateManager.recordInstallResult(
                        context,
                        "could not launch install confirmation for $attempted: ${e.message}"
                    )
                    UpdateManager.recordUpdateError(
                        context, attempted, "confirmation launch failed"
                    )
                }
            }
            InstallOutcome.DECLINED -> {
                UpdateManager.recordInstallResult(
                    context,
                    "update declined by user for $attempted (was $previous)"
                )
                UpdateManager.recordUpdateError(
                    context, attempted, "update declined by user"
                )
            }
            InstallOutcome.FAILURE -> {
                // PENDING_USER_ACTION without an EXTRA_INTENT has nothing
                // to launch; say so specifically instead of "status -1".
                val msg = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    "pending user action but no confirmation intent"
                } else {
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: "status $status"
                }
                UpdateManager.recordInstallResult(
                    context, "install failed for $attempted: $msg"
                )
                UpdateManager.recordUpdateError(context, attempted, "install failed: $msg")
            }
        }
    }
}
