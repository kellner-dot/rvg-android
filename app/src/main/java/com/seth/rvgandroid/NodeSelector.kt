package com.seth.rvgandroid

/**
 * Pure node-selection logic — no Android types — so it is unit-testable
 * on the JVM without a device.
 *
 * Rule (mapping study FIX + build brief): among clickable nodes whose
 * screen bounds contain the point, the DEEPEST node wins; ties go to
 * the SMALLEST bounds area. Exact containment only — no tolerance
 * fallback: a tolerance would reintroduce the aim ambiguity the
 * node-click path exists to remove. If no candidate contains the
 * point, the answer is "no node", never a nearest guess.
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
        val depth: Int
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
}
