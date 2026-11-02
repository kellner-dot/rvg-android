package com.seth.rvgandroid

/**
 * Programmatic keyboard toggle (1.3.4): switch the current input method
 * between the RVG keyboard and Gboard without the Settings dance.
 *
 * Mechanism: the app holds WRITE_SECURE_SETTINGS from the one-time adb
 * grant (`adb shell pm grant com.seth.rvgandroid
 * android.permission.WRITE_SECURE_SETTINGS`, persists across reboots)
 * and writes Settings.Secure.ENABLED_INPUT_METHODS /
 * Settings.Secure.DEFAULT_INPUT_METHOD — the same grant-backed pattern
 * the 1.3.2 rebind manager uses for ENABLED_ACCESSIBILITY_SERVICES.
 * Without the grant every write fails and the toggle refuses with
 * REFUSED_NO_GRANT; the manual Settings path is the fallback.
 *
 * HARD RULES — the toggle must never leave the phone with no usable
 * keyboard:
 *  - The default is never written to an id outside the enabled set
 *    (enable first, verify enabled, then switch).
 *  - The enabled set is only ever appended to, never pruned.
 *  - Switching AWAY from RVG refuses when no other keyboard exists
 *    (RVG stays active — the only keyboard there is).
 *
 * No android imports in this file: the whole flow is JVM-unit-testable
 * with fakes (see rvg-1.3.4-build/tests/). Production wiring lives in
 * ImeToggleAndroid.kt.
 */

/** Keys in Settings.Secure (see android.provider.Settings.Secure). */
object ImeSettingsKeys {
    const val ENABLED_INPUT_METHODS = "enabled_input_methods"
    const val DEFAULT_INPUT_METHOD = "default_input_method"
}

enum class ImeTarget { RVG, GBOARD }

data class ImeState(
    val current: String?,
    val enabled: List<String>,
    val grantHeld: Boolean
) {
    val rvgActive: Boolean get() = current == ImeToggle.RVG_COMPONENT
    val rvgEnabled: Boolean get() = enabled.contains(ImeToggle.RVG_COMPONENT)
    val gboardId: String?
        get() = enabled.firstOrNull { it.startsWith(ImeToggle.GBOARD_PACKAGE + "/") }
}

data class ImeSwitchResult(
    val outcome: String,
    val detail: String,
    val active: String?,
    val enabled: List<String>
)

class ImeToggle(
    private val store: SecureSettingsStore,
    private val enabledImes: () -> List<String>,
    private val allImes: () -> List<String>,
    private val isGrantHeld: () -> Boolean,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val RVG_COMPONENT =
            "com.seth.rvgandroid/com.seth.rvgandroid.RvgInputMethodService"
        const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        const val VERIFY_TIMEOUT_MS = 2_000L
        const val VERIFY_INTERVAL_MS = 100L

        /** Gboard's flattened id: enabled first, else any installed IME. */
        fun resolveGboard(enabled: List<String>, all: List<String>): String? =
            enabled.firstOrNull { it.startsWith(GBOARD_PACKAGE + "/") }
                ?: all.firstOrNull { it.startsWith(GBOARD_PACKAGE + "/") }
    }

    fun state(): ImeState {
        val cur = try {
            store.get(ImeSettingsKeys.DEFAULT_INPUT_METHOD)
        } catch (_: Exception) {
            null
        }
        val enabled = try {
            enabledImes()
        } catch (_: Exception) {
            emptyList()
        }
        val grant = try {
            isGrantHeld()
        } catch (_: Exception) {
            false
        }
        return ImeState(cur, enabled, grant)
    }

    fun switchTo(target: ImeTarget): ImeSwitchResult = when (target) {
        ImeTarget.RVG -> switchToComponent(RVG_COMPONENT)
        ImeTarget.GBOARD -> switchToGboard()
    }

    /** Flip to the other keyboard: RVG active → away from RVG, else → RVG. */
    fun toggle(): ImeSwitchResult {
        val s = state()
        return if (s.rvgActive) switchAwayFromRvg() else switchTo(ImeTarget.RVG)
    }

    /**
     * Teardown call: get off the RVG keyboard. Gboard first; if Gboard
     * is absent, the first other enabled IME; refuse when no other
     * keyboard exists (RVG stays active). No-op when RVG is not active.
     */
    fun switchAwayFromRvg(): ImeSwitchResult {
        val s = state()
        if (!s.rvgActive) {
            return result("ALREADY_ACTIVE", "RVG keyboard is not the active IME", s)
        }
        val gboard = resolveGboard(s.enabled, safeAll())
        if (gboard != null) return switchToComponent(gboard)
        val other = s.enabled.firstOrNull { it != RVG_COMPONENT }
        if (other != null) return switchToComponent(other)
        return result(
            "REFUSED_NO_FALLBACK",
            "no other keyboard enabled; RVG stays active", s
        )
    }

    private fun switchToGboard(): ImeSwitchResult {
        val s = state()
        if (!s.grantHeld) {
            return result(
                "REFUSED_NO_GRANT",
                "WRITE_SECURE_SETTINGS grant not held; use Settings", s
            )
        }
        val gboard = resolveGboard(s.enabled, safeAll())
            ?: return result("REFUSED_NO_FALLBACK", "Gboard is not installed", s)
        return switchToComponent(gboard)
    }

    private fun switchToComponent(component: String): ImeSwitchResult {
        val s0 = state()
        if (!s0.grantHeld) {
            return result(
                "REFUSED_NO_GRANT",
                "WRITE_SECURE_SETTINGS grant not held; use Settings", s0
            )
        }
        if (s0.current == component) {
            return result("ALREADY_ACTIVE", "$component is already the active IME", s0)
        }
        // Ensure enabled (append only — never remove entries).
        if (!s0.enabled.contains(component)) {
            val joined = (s0.enabled + component).joinToString(":")
            if (!safePut(ImeSettingsKeys.ENABLED_INPUT_METHODS, joined)) {
                return result(
                    "FAILED_WRITE", "could not write enabled_input_methods", state()
                )
            }
            val s1 = state()
            if (!s1.enabled.contains(component)) {
                return result(
                    "REFUSED_CANNOT_ENABLE", "$component could not be enabled", s1
                )
            }
        }
        if (!safePut(ImeSettingsKeys.DEFAULT_INPUT_METHOD, component)) {
            return result(
                "FAILED_WRITE", "could not write default_input_method", state()
            )
        }
        // Verify by poll: the setting readback is the source of truth.
        val deadline = nowMs() + VERIFY_TIMEOUT_MS
        while (true) {
            val cur = try {
                store.get(ImeSettingsKeys.DEFAULT_INPUT_METHOD)
            } catch (_: Exception) {
                null
            }
            if (cur == component) {
                return result("SWITCHED", "active IME is now $component", state())
            }
            if (nowMs() >= deadline) {
                return result(
                    "FAILED_VERIFY",
                    "default_input_method did not read back as $component", state()
                )
            }
            sleepMs(VERIFY_INTERVAL_MS)
        }
    }

    private fun safeAll(): List<String> = try {
        allImes()
    } catch (_: Exception) {
        emptyList()
    }

    private fun safePut(key: String, value: String): Boolean = try {
        store.put(key, value)
    } catch (_: Exception) {
        false
    }

    private fun result(outcome: String, detail: String, s: ImeState) =
        ImeSwitchResult(outcome, detail, s.current, s.enabled)
}
