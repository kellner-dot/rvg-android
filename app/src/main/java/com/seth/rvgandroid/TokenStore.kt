package com.seth.rvgandroid

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Token storage. Prefers EncryptedSharedPreferences; falls back to plain
 * SharedPreferences if the security-crypto lib is unavailable.
 * Token is generated once at first run and shared with all Kavis via Drive
 * (manual step documented in INSTALL.md).
 */
object TokenStore {
    private const val PREFS = "rvg_prefs"
    private const val KEY_TOKEN = "token"

    private fun prefs(ctx: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx, PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun getToken(ctx: Context): String {
        val p = prefs(ctx)
        var t = p.getString(KEY_TOKEN, null)
        if (t.isNullOrEmpty()) {
            t = generate()
            p.edit().putString(KEY_TOKEN, t).apply()
        }
        return t
    }

    fun setToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, token).apply()
    }

    private fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
