package com.seth.rvgandroid

import android.app.PendingIntent
import android.os.Build

/**
 * Flags for the PackageInstaller commit-status receiver PendingIntent
 * (UpdateManager.attemptInstallSession).
 *
 * Must be MUTABLE: AOSP delivers every session result as a fill-in
 * Intent on the IntentSender, and PendingIntentRecord silently
 * discards the fill-in for an immutable PendingIntent. With
 * FLAG_IMMUTABLE the receiver fires with only its base intent and
 * reads status -1 + null confirmation intent ("pending user action
 * but no confirmation intent" — the 1.3.3/1.3.4 self-update failure,
 * reproduced live Nov 1, 2026). FLAG_MUTABLE exists from API 31 (S);
 * RVG minSdk is 29, hence the guard. If targetSdk ever moves to 35+,
 * Session.commit() throws IllegalArgumentException for an immutable
 * receiver, so this flag becomes load-bearing rather than merely
 * correct.
 *
 * The daily-alarm PendingIntent (UpdateManager.scheduleDaily) stays
 * FLAG_IMMUTABLE on purpose: it is a bare trigger with no fill-in
 * expected, and is not part of this fix.
 */
fun commitStatusReceiverFlags(sdkInt: Int = Build.VERSION.SDK_INT): Int =
    PendingIntent.FLAG_UPDATE_CURRENT or
        if (sdkInt >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
