package com.seth.rvgandroid

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences telemetry for the accessibility binding lifecycle, so a
 * drop is diagnosable instead of a bare boolean: when it last connected,
 * when it last unbound (and whether the secure setting still listed us then),
 * how many unbinds, when the process last died, and the rebind history.
 *
 * Also the [RebindHistory] for [RebindManager] (attempt accounting per
 * unbind episode; resets when the service connects).
 */
class BindingState private constructor(private val prefs: SharedPreferences) : RebindHistory {

    companion object {
        private const val PREFS = "rvg-binding"
        private const val K_CONNECTED_AT = "lastConnectedAtMs"
        private const val K_UNBIND_AT = "lastUnbindAtMs"
        private const val K_UNBIND_COUNT = "unbindCount"
        private const val K_UNBIND_LISTED = "wasListedAtLastUnbind"
        private const val K_DESTROY_AT = "lastDestroyAtMs"
        private const val K_ATTEMPTS = "rebindAttemptsInEpisode"
        private const val K_LAST_ATTEMPT = "lastRebindAttemptMs"
        private const val K_LAST_OUTCOME = "lastRebindOutcome"

        fun from(ctx: Context): BindingState =
            BindingState(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    val lastConnectedAtMs: Long get() = prefs.getLong(K_CONNECTED_AT, 0)
    val lastUnbindAtMs: Long get() = prefs.getLong(K_UNBIND_AT, 0)
    val unbindCount: Int get() = prefs.getInt(K_UNBIND_COUNT, 0)
    val wasListedAtLastUnbind: Boolean get() = prefs.getBoolean(K_UNBIND_LISTED, false)
    val lastDestroyAtMs: Long get() = prefs.getLong(K_DESTROY_AT, 0)
    val lastOutcome: String? get() = prefs.getString(K_LAST_OUTCOME, null)

    override fun attemptsInEpisode(): Int = prefs.getInt(K_ATTEMPTS, 0)
    override fun lastAttemptMs(): Long = prefs.getLong(K_LAST_ATTEMPT, 0)

    override fun recordAttempt(nowMs: Long) {
        prefs.edit()
            .putInt(K_ATTEMPTS, attemptsInEpisode() + 1)
            .putLong(K_LAST_ATTEMPT, nowMs)
            .putString(K_LAST_OUTCOME, "ATTEMPT")
            .apply()
    }

    override fun recordOutcome(outcome: String) {
        prefs.edit().putString(K_LAST_OUTCOME, outcome).apply()
    }

    override fun recordConnected(nowMs: Long) {
        prefs.edit()
            .putLong(K_CONNECTED_AT, nowMs)
            .putInt(K_ATTEMPTS, 0)
            .putString(K_LAST_OUTCOME, "CONNECTED")
            .apply()
    }

    fun recordUnbind(nowMs: Long, listed: Boolean) {
        prefs.edit()
            .putLong(K_UNBIND_AT, nowMs)
            .putInt(K_UNBIND_COUNT, unbindCount + 1)
            .putBoolean(K_UNBIND_LISTED, listed)
            .apply()
    }

    fun recordDestroy(nowMs: Long) {
        prefs.edit().putLong(K_DESTROY_AT, nowMs).apply()
    }
}
