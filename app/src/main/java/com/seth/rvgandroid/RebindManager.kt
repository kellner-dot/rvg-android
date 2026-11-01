package com.seth.rvgandroid

/**
 * Programmatic rebind of our own accessibility service after the system drops
 * the binding ("enabled but unbound").
 *
 * Mechanism (established pattern — Shizuku, FreeKiosk, adb-auto-enable,
 * PortalOverlays): the app holds WRITE_SECURE_SETTINGS from a one-time adb
 * grant (`adb shell pm grant com.seth.rvgandroid
 * android.permission.WRITE_SECURE_SETTINGS`, persists across reboots) and
 * rewrites Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.
 *
 * HARD RULE — the rebind runs ONLY when the secure setting still lists our
 * service. If the component is absent, the user turned it off (or never
 * enabled it): that is a deliberate user state, not a drop, and we do
 * nothing. The fix never re-enables something Seth deliberately disabled.
 *
 * TOGGLE, not re-assert: our drop leaves the service LISTED but unbound, so
 * writing the same value would be a no-op — the system has nothing to
 * re-evaluate. The rebind removes our component, writes, waits, re-adds it,
 * writes, sets accessibility_enabled=1, then polls for the binding to return.
 *
 * No android imports in this file: the whole flow is JVM-unit-testable with
 * fakes (see rvg-1.3.2-build/tests/). Production wiring lives in
 * AndroidSecureSettings.kt / BindingState.kt.
 */

/** Keys in Settings.Secure (see android.provider.Settings.Secure). */
object SecureSettingsKeys {
    const val ENABLED_ACCESSIBILITY_SERVICES = "enabled_accessibility_services"
    const val ACCESSIBILITY_ENABLED = "accessibility_enabled"
}

/** Minimal read/write surface over the secure settings. */
interface SecureSettingsStore {
    fun get(key: String): String?
    /** False on failure (the Android impl catches SecurityException). */
    fun put(key: String, value: String): Boolean
}

/** Attempt accounting for one unbind episode (resets when the service connects). */
interface RebindHistory {
    fun attemptsInEpisode(): Int
    fun lastAttemptMs(): Long
    fun recordAttempt(nowMs: Long)
    fun recordOutcome(outcome: String)
    fun recordConnected(nowMs: Long)
}

data class RebindResult(
    val outcome: String,
    val detail: String,
    val attemptsInEpisode: Int,
    val elapsedMs: Long
)

class RebindManager(
    private val store: SecureSettingsStore,
    private val history: RebindHistory,
    private val isBound: () -> Boolean,
    private val component: String = COMPONENT,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val COMPONENT =
            "com.seth.rvgandroid/com.seth.rvgandroid.RvgAccessibilityService"
        const val MAX_ATTEMPTS_PER_EPISODE = 3
        const val MIN_INTERVAL_MS = 60_000L
        const val TOGGLE_DELAY_MS = 500L
        const val POLL_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 250L

        fun isListed(store: SecureSettingsStore, component: String = COMPONENT): Boolean {
            val cur = try {
                store.get(SecureSettingsKeys.ENABLED_ACCESSIBILITY_SERVICES)
            } catch (_: Exception) {
                null
            }
            return cur != null && AccessibilityServicesList.containsService(cur, component)
        }
    }

    fun isListed(): Boolean = isListed(store, component)

    /**
     * Attempt a rebind. Never blocks the calling thread's contract beyond
     * ~11 s (toggle delay + poll window); callers on the main thread must
     * run this on a background thread.
     */
    fun rebind(): RebindResult {
        val start = nowMs()
        fun done(outcome: String, detail: String) =
            RebindResult(outcome, detail, history.attemptsInEpisode(), nowMs() - start)

        if (isBound()) return done("ALREADY_BOUND", "service already bound")
        if (!isListed()) {
            return done(
                "REFUSED_NOT_LISTED",
                "service not listed in enabled_accessibility_services; " +
                    "user state, not a drop — doing nothing"
            )
        }
        if (history.attemptsInEpisode() >= MAX_ATTEMPTS_PER_EPISODE) {
            return done("REFUSED_CAP", "attempt cap reached for this episode; needs a finger")
        }
        val last = history.lastAttemptMs()
        if (last > 0 && nowMs() - last < MIN_INTERVAL_MS) {
            return done("REFUSED_COOLDOWN", "last attempt too recent")
        }

        // Toggle: remove -> write -> delay -> re-add -> write. Re-read after
        // the remove so a concurrent writer (user toggling another service)
        // cannot lose its entry.
        val current = try {
            store.get(SecureSettingsKeys.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (_: Exception) {
            return done("ERROR", "read of enabled_accessibility_services failed")
        }
        if (!store.put(
                SecureSettingsKeys.ENABLED_ACCESSIBILITY_SERVICES,
                AccessibilityServicesList.withoutService(current, component)
            )
        ) {
            return done("ERROR", "remove write failed (missing WRITE_SECURE_SETTINGS grant?)")
        }
        sleepMs(TOGGLE_DELAY_MS)
        val afterRemove = try {
            store.get(SecureSettingsKeys.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (_: Exception) {
            return done("ERROR", "re-read after remove failed")
        }
        if (!store.put(
                SecureSettingsKeys.ENABLED_ACCESSIBILITY_SERVICES,
                AccessibilityServicesList.withService(afterRemove, component)
            )
        ) {
            return done("ERROR", "re-add write failed (missing WRITE_SECURE_SETTINGS grant?)")
        }
        if (!store.put(SecureSettingsKeys.ACCESSIBILITY_ENABLED, "1")) {
            return done("ERROR", "accessibility_enabled write failed")
        }
        history.recordAttempt(nowMs())

        val deadline = nowMs() + POLL_TIMEOUT_MS
        while (nowMs() < deadline) {
            if (isBound()) {
                history.recordOutcome("REBOUND")
                return done("REBOUND", "service rebound")
            }
            sleepMs(POLL_INTERVAL_MS)
        }
        history.recordOutcome("TIMEOUT")
        return done("TIMEOUT", "service did not rebind within the poll window")
    }
}
