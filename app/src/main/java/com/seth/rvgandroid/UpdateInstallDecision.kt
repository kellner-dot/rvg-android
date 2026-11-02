package com.seth.rvgandroid

/**
 * Pure decision logic for a PackageInstaller session result.
 * No android imports, so it is JVM-unit-testable.
 *
 * The status literals are the PackageInstaller constants, verified
 * against android-34 android.jar (javap -constants):
 *   STATUS_PENDING_USER_ACTION = -1, STATUS_SUCCESS = 0,
 *   STATUS_FAILURE_ABORTED = 3, other failures 1/2/4/5/6/7/8.
 * The unit test anchors these literals against the real
 * PackageInstaller constants, so any drift fails loudly.
 *
 * Background (1.3.3): 1.3.1's receiver treated every non-success
 * status as a failure. When the system answers STATUS_PENDING_USER_
 * ACTION (-1) it is asking the app to launch the install confirmation
 * dialog from Intent.EXTRA_INTENT — recording that as "install failed:
 * status -1" and never showing the dialog strands the update. The
 * receiver now branches on this decision instead.
 */
enum class InstallOutcome {
    /** Session installed. */
    SUCCESS,
    /** System wants the user to confirm; launch EXTRA_INTENT. */
    PENDING_USER_ACTION,
    /** User declined the confirmation dialog — declined, not failed. */
    DECLINED,
    /** Any other failure status. */
    FAILURE
}

object UpdateInstallDecision {
    fun decide(status: Int, hasConfirmationIntent: Boolean): InstallOutcome =
        when (status) {
            0 -> InstallOutcome.SUCCESS
            -1 -> if (hasConfirmationIntent) InstallOutcome.PENDING_USER_ACTION
                  else InstallOutcome.FAILURE
            3 -> InstallOutcome.DECLINED
            else -> InstallOutcome.FAILURE
        }
}
