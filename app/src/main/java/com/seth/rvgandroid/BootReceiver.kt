package com.seth.rvgandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the agent after device boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RvgService.start(context)
        }
    }
}
