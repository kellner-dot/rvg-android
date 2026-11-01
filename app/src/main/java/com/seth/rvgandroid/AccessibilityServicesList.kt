package com.seth.rvgandroid

/**
 * Pure helpers for the `enabled_accessibility_services` secure setting.
 *
 * The setting is ONE colon-separated string shared by every accessibility app
 * on the phone. A writer must append its own component, never overwrite the
 * whole value (writing just your own component switches everyone else's off).
 *
 * No android imports: these are JVM-unit-testable.
 */
object AccessibilityServicesList {

    /** True when [component] (e.g. "com.foo/com.foo.MyService") is in the list. */
    fun containsService(existing: String?, component: String): Boolean {
        if (existing.isNullOrBlank()) return false
        return existing.split(':').any { it.trim() == component }
    }

    /** [existing] with [component] appended; unchanged when already present. */
    fun withService(existing: String?, component: String): String {
        if (containsService(existing, component)) return existing ?: ""
        val base = existing?.trim()?.trimEnd(':') ?: ""
        return if (base.isEmpty()) component else "$base:$component"
    }

    /** [existing] with [component] removed; "" when nothing is left. */
    fun withoutService(existing: String?, component: String): String {
        if (existing.isNullOrBlank()) return ""
        return existing.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != component }
            .joinToString(":")
    }
}
