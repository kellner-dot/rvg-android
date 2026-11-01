package com.seth.rvgandroid

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Production [RebindManager.SecureSettingsStore]: reads/writes
 * Settings.Secure. Writes need WRITE_SECURE_SETTINGS, held only after the
 * one-time adb grant (`adb shell pm grant com.seth.rvgandroid
 * android.permission.WRITE_SECURE_SETTINGS`). Without the grant every write
 * throws SecurityException and is reported as false — the manager then
 * refuses the rebind instead of crashing.
 */
class AndroidSecureSettings(private val ctx: Context) : SecureSettingsStore {

    override fun get(key: String): String? = try {
        Settings.Secure.getString(ctx.contentResolver, key)
    } catch (_: Exception) {
        null
    }

    override fun put(key: String, value: String): Boolean = try {
        Settings.Secure.putString(ctx.contentResolver, key, value)
    } catch (_: Exception) {
        false
    }

    companion object {
        fun isGrantHeld(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
    }
}
