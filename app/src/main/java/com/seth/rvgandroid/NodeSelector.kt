package com.seth.rvgandroid

/**
 * Pure node-selection logic — no Android types — so it is unit-testable
 * on the JVM without a device.
 *
 * Point rule (mapping study FIX + 1.2.0 build rule): among clickable
 * nodes whose screen bounds contain the point, the DEEPEST node wins;
 * ties go to the SMALLEST bounds area. Exact containment only — no
 * tolerance fallback: a tolerance would reintroduce the aim ambiguity
 * the node-click path exists to remove. If no candidate contains the
 * point, the answer is "no node", never a nearest guess.
 *
 * Selector rule (1.3.0): a Selector is a simple explicit JSON object
 * (NOT XPath). Every field present must match; absent fields are
 * ignored. text/contentDesc match by substring unless the matching
 * Exact flag is set. resourceId matches the full name OR the suffix
 * after the last '/' (so "com.example:id/login" matches "login").
 * bounds matches exactly; contains matches a point inside bounds
 * (same Rect.contains semantics: right/bottom edges excluded).
 */
object NodeSelector {

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val area: Long get() = (right - left).toLong() * (bottom - top).toLong()
        /** Rect.contains semantics: right/bottom edges excluded. */
        fun contains(x: Int, y: Int): Boolean =
            x >= left && x < right && y >= top && y < bottom
    }

    data class Candidate(
        val className: String,
        val bounds: Bounds,
        val clickable: Boolean,
        val depth: Int,
        val text: String? = null,
        val contentDesc: String? = null,
        val scrollable: Boolean = false,
        val checkable: Boolean = false,
        val checked: Boolean = false,
        val editable: Boolean = false,
        val focused: Boolean = false,
        val resourceId: String? = null
    )

    /** Selector: every non-null field must match. Null = ignored. */
    data class Selector(
        val text: String? = null,
        val textExact: Boolean = false,
        val contentDesc: String? = null,
        val contentDescExact: Boolean = false,
        val className: String? = null,
        val resourceId: String? = null,
        val clickable: Boolean? = null,
        val scrollable: Boolean? = null,
        val editable: Boolean? = null,
        val checkable: Boolean? = null,
        val checked: Boolean? = null,
        val focused: Boolean? = null,
        val bounds: Bounds? = null,
        val containsPoint: Pair<Int, Int>? = null
    )

    /** Returns the winning candidate, or null when none qualifies. */
    fun selectBest(candidates: List<Candidate>, x: Int, y: Int): Candidate? {
        var best: Candidate? = null
        for (c in candidates) {
            if (!c.clickable) continue
            if (!c.bounds.contains(x, y)) continue
            val b = best
            if (b == null ||
                c.depth > b.depth ||
                (c.depth == b.depth && c.bounds.area < b.bounds.area)
            ) {
                best = c
            }
        }
        return best
    }

    /** Deepest scrollable node containing the point (ties: smallest area). */
    fun selectDeepestScrollable(candidates: List<Candidate>, x: Int, y: Int): Candidate? {
        var best: Candidate? = null
        for (c in candidates) {
            if (!c.scrollable) continue
            if (!c.bounds.contains(x, y)) continue
            val b = best
            if (b == null ||
                c.depth > b.depth ||
                (c.depth == b.depth && c.bounds.area < b.bounds.area)
            ) {
                best = c
            }
        }
        return best
    }

    /** Deepest node of any kind containing the point (ties: smallest area). */
    fun selectDeepestAny(candidates: List<Candidate>, x: Int, y: Int): Candidate? {
        var best: Candidate? = null
        for (c in candidates) {
            if (!c.bounds.contains(x, y)) continue
            val b = best
            if (b == null ||
                c.depth > b.depth ||
                (c.depth == b.depth && c.bounds.area < b.bounds.area)
            ) {
                best = c
            }
        }
        return best
    }

    /** Largest-area scrollable node (scroll-until container fallback). */
    fun selectLargestScrollable(candidates: List<Candidate>): Candidate? {
        var best: Candidate? = null
        for (c in candidates) {
            if (!c.scrollable) continue
            val b = best
            if (b == null || c.bounds.area > b.bounds.area) best = c
        }
        return best
    }

    /** Deepest editable node containing the point (ties: smallest area). */
    fun selectDeepestEditable(candidates: List<Candidate>, x: Int, y: Int): Candidate? {
        var best: Candidate? = null
        for (c in candidates) {
            if (!c.editable) continue
            if (!c.bounds.contains(x, y)) continue
            val b = best
            if (b == null ||
                c.depth > b.depth ||
                (c.depth == b.depth && c.bounds.area < b.bounds.area)
            ) {
                best = c
            }
        }
        return best
    }

    /** True when every present selector field matches the candidate. */
    fun matches(c: Candidate, s: Selector): Boolean {
        if (s.text != null) {
            val t = c.text ?: return false
            if (s.textExact) { if (t != s.text) return false }
            else { if (!t.contains(s.text)) return false }
        }
        if (s.contentDesc != null) {
            val d = c.contentDesc ?: return false
            if (s.contentDescExact) { if (d != s.contentDesc) return false }
            else { if (!d.contains(s.contentDesc)) return false }
        }
        if (s.className != null && c.className != s.className) return false
        if (s.resourceId != null) {
            val r = c.resourceId ?: return false
            val suffix = r.substringAfterLast('/')
            if (r != s.resourceId && suffix != s.resourceId) return false
        }
        if (s.clickable != null && c.clickable != s.clickable) return false
        if (s.scrollable != null && c.scrollable != s.scrollable) return false
        if (s.editable != null && c.editable != s.editable) return false
        if (s.checkable != null && c.checkable != s.checkable) return false
        if (s.checked != null && c.checked != s.checked) return false
        if (s.focused != null && c.focused != s.focused) return false
        if (s.bounds != null && c.bounds != s.bounds) return false
        if (s.containsPoint != null &&
            !c.bounds.contains(s.containsPoint.first, s.containsPoint.second)
        ) return false
        return true
    }

    /** All candidates matching the selector, in tree (preorder) order. */
    fun selectBySelector(candidates: List<Candidate>, s: Selector): List<Candidate> =
        candidates.filter { matches(it, s) }
}
