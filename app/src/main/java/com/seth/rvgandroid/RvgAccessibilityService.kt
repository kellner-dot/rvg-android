package com.seth.rvgandroid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Accessibility service providing tap / swipe / key input without root.
 * Mirrors the Windows/Mac RVG /rvd/input endpoint semantics.
 */
class RvgAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RvgAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        instance = this
        try {
            BindingState.from(this).recordConnected(System.currentTimeMillis())
        } catch (_: Exception) {
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        try {
            // Record whether the secure setting still listed us: listed-but-
            // unbound is a system drop (rebindable); absent means the user
            // turned the service off (never re-enable that).
            val listed = RebindManager.isListed(AndroidSecureSettings(this))
            BindingState.from(this).recordUnbind(System.currentTimeMillis(), listed)
        } catch (_: Exception) {
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try {
            BindingState.from(this).recordDestroy(System.currentTimeMillis())
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Tap at (x, y) in screen pixels. Returns true if the gesture dispatched.
     *  durationMs is the stroke duration (default 50 ms, the v1.1.0 value);
     *  it is now a parameter instead of a hardcoded constant so the driver
     *  can vary it — it is NOT expected to fix penetration-class buttons
     *  (the mapping study ruled duration out for that class). */
    fun tap(x: Float, y: Float, durationMs: Long = 50): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGestureSync(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** Swipe from (x1,y1) to (x2,y2) over durationMs. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGestureSync(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** Long-press at (x, y). */
    fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 800)
        return dispatchGestureSync(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** Press a hardware/software key via global action mapping. */
    fun key(name: String): Boolean {
        // Single character -> route through the RVG keyboard IME (works in
        // terminals and other non-EditText views); falls back to false when
        // the IME isn't the active keyboard.
        if (name.length == 1) return RvgInputMethodService.typeText(name)
        return when (name.lowercase()) {
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "recents", "overview" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quicksettings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "powerdialog", "power" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            "lock", "lockscreen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            else -> false
        }
    }

    /**
     * Type text into the currently focused editable field.
     *
     * NOTE on limits: this uses ACTION_SET_TEXT, which only EditText-style
     * fields accept. Terminal emulators (Termux) and other custom views need
     * the RVG keyboard IME instead — see RvgInputMethodService. The /rvd/input
     * "type" command tries this fast path first, then the IME automatically.
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return focused.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args
        )
    }

    /**
     * Node-based activation (the mapping study's PRIMARY fix).
     *
     * Walks rootInActiveWindow and selects the deepest clickable node
     * whose screen bounds contain (x, y) (ties → smallest bounds),
     * via NodeSelector. This is a DIFFERENT dispatch path from
     * dispatchGesture: view-level accessibility activation (the path
     * TalkBack-style interaction uses) instead of gesture injection.
     * Whether a given non-penetrating surface responds to it is
     * unproven until the device pilot — these methods report exactly
     * what happened so the pilot can tell "no node" apart from
     * "node clicked but ignored".
     *
     * Node lifecycle: every visited node is collected and recycled
     * AFTER the walk and AFTER any action on the winner. Nodes are
     * deliberately not recycled mid-walk: an early best candidate
     * can be an ancestor of a later, deeper winner, and recycling it
     * mid-traversal would corrupt the walk. These calls are
     * infrequent (one per gated element), so the transient node
     * list is cheap.
     */
    data class NodeClickResult(
        val found: Boolean,
        val node: NodeSelector.Candidate?,
        val performed: Boolean
    )

    /** Read-only node query for a point: the winning node, or null. */
    fun queryNodeAt(x: Float, y: Float): NodeSelector.Candidate? {
        return findNodeAt(x.toInt(), y.toInt(), null).first
    }

    /** Node-click at a point: found + performed are reported separately. */
    fun clickNodeAt(x: Float, y: Float): NodeClickResult {
        val (best, performed) = findNodeAt(x.toInt(), y.toInt()) { node ->
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Exception) {
                false
            }
        }
        return NodeClickResult(
            found = best != null,
            node = best,
            performed = performed
        )
    }

    /**
     * One walk. Selects the winner, runs [onWinner] on its live node
     * BEFORE recycling (so clicks act on a live node), then recycles
     * every visited node. Returns (winner, action result or false
     * when no action was requested).
     */
    private fun findNodeAt(
        x: Int,
        y: Int,
        onWinner: ((AccessibilityNodeInfo) -> Boolean)?
    ): Pair<NodeSelector.Candidate?, Boolean> {
        val root = rootInActiveWindow ?: return Pair(null, false)
        val visited = ArrayList<AccessibilityNodeInfo>()
        val pairs = ArrayList<Pair<NodeSelector.Candidate, AccessibilityNodeInfo>>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            visited.add(node)
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (node.isClickable) {
                pairs.add(
                    Pair(
                        NodeSelector.Candidate(
                            className = node.className?.toString() ?: "?",
                            bounds = NodeSelector.Bounds(rect.left, rect.top, rect.right, rect.bottom),
                            clickable = true,
                            depth = depth
                        ),
                        node
                    )
                )
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) visit(child, depth + 1)
            }
        }
        try {
            visit(root, 0)
        } catch (_: Exception) {
            // A tree that mutates mid-walk still yields whatever was collected.
        }
        val best = NodeSelector.selectBest(pairs.map { it.first }, x, y)
        val nodeRef = pairs.firstOrNull { it.first === best }?.second
        var performed = false
        if (best != null && nodeRef != null && onWinner != null) {
            performed = try { onWinner(nodeRef) } catch (_: Exception) { false }
        }
        for (n in visited) {
            try { n.recycle() } catch (_: Exception) {}
        }
        return Pair(best, performed)
    }

    // ---------- 1.3.0: tree dump, selectors, scroll, show, set-text, long-click, windows ----------
    //
    // Walk discipline (same as findNodeAt): nodes are collected live,
    // the winner's action runs BEFORE recycling, and every collected
    // node is recycled in a finally block. Results returned to callers
    // are pure Candidates, never live nodes.

    private data class Collected(
        val candidates: List<NodeSelector.Candidate>,
        val childCounts: List<Int>,
        val live: List<AccessibilityNodeInfo>,
        val truncated: Boolean,
        val totalVisited: Int
    )

    private fun candidateOf(node: AccessibilityNodeInfo, depth: Int): NodeSelector.Candidate {
        val rect = Rect()
        try { node.getBoundsInScreen(rect) } catch (_: Exception) {}
        return NodeSelector.Candidate(
            className = node.className?.toString() ?: "?",
            bounds = NodeSelector.Bounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            depth = depth,
            text = node.text?.toString(),
            contentDesc = node.contentDescription?.toString(),
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            editable = node.isEditable,
            focused = node.isFocused,
            resourceId = try { node.viewIdResourceName } catch (_: Exception) { null }
        )
    }

    /**
     * One walk of [root]. maxDepth caps descent; maxNodes caps
     * collection (nodes obtained past the cap are recycled at once and
     * truncated=true is reported). The caller recycles [Collected.live].
     */
    private fun collectFrom(
        root: AccessibilityNodeInfo,
        maxDepth: Int,
        maxNodes: Int
    ): Collected {
        val candidates = ArrayList<NodeSelector.Candidate>()
        val childCounts = ArrayList<Int>()
        val live = ArrayList<AccessibilityNodeInfo>()
        var truncated = false
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (candidates.size >= maxNodes) {
                truncated = true
                try { node.recycle() } catch (_: Exception) {}
                return
            }
            visited++
            live.add(node)
            candidates.add(candidateOf(node, depth))
            childCounts.add(node.childCount)
            if (depth >= maxDepth) return
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) visit(child, depth + 1)
                if (candidates.size >= maxNodes) { truncated = true; return }
            }
        }
        try { visit(root, 0) } catch (_: Exception) {
            // A tree that mutates mid-walk still yields what was collected.
        }
        return Collected(candidates, childCounts, live, truncated, visited)
    }

    private fun recycleCollected(c: Collected) {
        for (n in c.live) {
            try { n.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * Runs [select] over one walk and [action] on the winner's live node
     * BEFORE recycling. Identity (===) maps the winner back to its live
     * node — never equals(), which could confuse identical twins.
     */
    private fun actOnWinner(
        select: (List<NodeSelector.Candidate>) -> NodeSelector.Candidate?,
        action: (AccessibilityNodeInfo) -> Boolean,
        maxNodes: Int = 20000,
        maxDepth: Int = 100
    ): Pair<NodeSelector.Candidate?, Boolean> {
        val root = rootInActiveWindow ?: return Pair(null, false)
        val c = collectFrom(root, maxDepth, maxNodes)
        try {
            val winner = select(c.candidates) ?: return Pair(null, false)
            val idx = c.candidates.indexOfFirst { it === winner }
            if (idx < 0) return Pair(winner, false)
            val performed = try { action(c.live[idx]) } catch (_: Exception) { false }
            return Pair(winner, performed)
        } finally {
            recycleCollected(c)
        }
    }

    /** One fresh walk filtered by selector; null when there is no window. */
    private fun matchOnce(
        sel: NodeSelector.Selector,
        maxNodes: Int = 20000,
        maxDepth: Int = 100
    ): List<NodeSelector.Candidate>? {
        val root = rootInActiveWindow ?: return null
        val c = collectFrom(root, maxDepth, maxNodes)
        try {
            return NodeSelector.selectBySelector(c.candidates, sel)
        } finally {
            recycleCollected(c)
        }
    }

    // ----- tree dump -----

    data class TreeDump(
        val nodes: List<NodeSelector.Candidate>,
        val childCounts: List<Int>,
        val truncated: Boolean,
        val totalVisited: Int
    )

    /**
     * Flat preorder dump of the active window. Each node carries depth
     * and childCount, so the caller can rebuild nesting without a
     * nested payload. interactiveOnly keeps clickable, scrollable,
     * editable, checkable, or text/content-bearing nodes (depths and
     * childCounts stay in original-tree terms).
     */
    fun dumpTree(maxNodes: Int, maxDepth: Int, interactiveOnly: Boolean): TreeDump? {
        val root = rootInActiveWindow ?: return null
        val c = collectFrom(root, maxDepth, maxNodes)
        try {
            if (!interactiveOnly) {
                return TreeDump(c.candidates, c.childCounts, c.truncated, c.totalVisited)
            }
            val kept = ArrayList<NodeSelector.Candidate>()
            val keptCounts = ArrayList<Int>()
            for (i in c.candidates.indices) {
                val n = c.candidates[i]
                if (n.clickable || n.scrollable || n.editable || n.checkable ||
                    !n.text.isNullOrEmpty() || !n.contentDesc.isNullOrEmpty()
                ) {
                    kept.add(n)
                    keptCounts.add(c.childCounts[i])
                }
            }
            return TreeDump(kept, keptCounts, c.truncated, c.totalVisited)
        } finally {
            recycleCollected(c)
        }
    }

    // ----- selectors -----

    data class SelectorPick(
        val node: NodeSelector.Candidate?,
        val matchCount: Int,
        val performed: Boolean
    )

    /** Query by selector: winner at [index] plus the total match count. */
    fun findBySelector(sel: NodeSelector.Selector, index: Int): Pair<NodeSelector.Candidate?, Int>? {
        val matches = matchOnce(sel) ?: return null
        return Pair(matches.getOrNull(index), matches.size)
    }

    /** Tap (ACTION_CLICK) the selector match at [index]. */
    fun clickBySelector(sel: NodeSelector.Selector, index: Int): SelectorPick {
        val (winner, performed) = actOnWinner(
            select = { NodeSelector.selectBySelector(it, sel).getOrNull(index) },
            action = { node ->
                try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                catch (_: Exception) { false }
            }
        )
        val count = matchOnce(sel)?.size ?: 0
        return SelectorPick(winner, count, performed)
    }

    // ----- scroll -----

    /** Scroll the deepest scrollable node containing the point. */
    fun scrollNodeAtPoint(x: Int, y: Int, backward: Boolean): SelectorPick {
        val action = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val (winner, performed) = actOnWinner(
            select = { NodeSelector.selectDeepestScrollable(it, x, y) },
            action = { node ->
                try { node.performAction(action) } catch (_: Exception) { false }
            }
        )
        return SelectorPick(winner, if (winner != null) 1 else 0, performed)
    }

    /** Scroll the first scrollable selector match. */
    fun scrollNodeBySelector(sel: NodeSelector.Selector, backward: Boolean): SelectorPick {
        val action = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val (winner, performed) = actOnWinner(
            select = { NodeSelector.selectBySelector(it, sel).firstOrNull { n -> n.scrollable } },
            action = { node ->
                try { node.performAction(action) } catch (_: Exception) { false }
            }
        )
        return SelectorPick(winner, if (winner != null) 1 else 0, performed)
    }

    private fun scrollContainerOnce(
        backward: Boolean,
        containerPoint: Pair<Int, Int>?
    ): Boolean {
        val action = if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val (_, performed) = actOnWinner(
            select = { list ->
                if (containerPoint != null) NodeSelector.selectDeepestScrollable(
                    list, containerPoint.first, containerPoint.second
                ) else NodeSelector.selectLargestScrollable(list)
            },
            action = { node ->
                try { node.performAction(action) } catch (_: Exception) { false }
            }
        )
        return performed
    }

    data class ScrollUntilResult(
        val found: Boolean,
        val node: NodeSelector.Candidate?,
        val scrolls: Int
    )

    /**
     * Scroll a container until the selector matches (or attempts run
     * out). Container: deepest scrollable at [containerPoint] when
     * given, else the largest scrollable node. scrolls = scrolls
     * actually performed. A failed scroll stops the loop early rather
     * than burning attempts on an unscrollable container.
     */
    fun scrollUntilVisible(
        sel: NodeSelector.Selector,
        backward: Boolean,
        maxAttempts: Int,
        containerPoint: Pair<Int, Int>?
    ): ScrollUntilResult {
        var scrolls = 0
        while (scrolls <= maxAttempts) {
            val matches = matchOnce(sel)
            if (matches != null && matches.isNotEmpty()) {
                return ScrollUntilResult(true, matches[0], scrolls)
            }
            if (scrolls == maxAttempts) break
            if (!scrollContainerOnce(backward, containerPoint)) break
            scrolls++
            try { Thread.sleep(300) } catch (_: Exception) {}
        }
        return ScrollUntilResult(false, null, scrolls)
    }

    // ----- wait -----

    data class WaitResult(
        val found: Boolean,
        val node: NodeSelector.Candidate?,
        val attempts: Int,
        val elapsedMs: Long
    )

    /** Poll the tree until the selector matches or [timeoutMs] expires. */
    fun waitForElement(
        sel: NodeSelector.Selector,
        timeoutMs: Long,
        intervalMs: Long
    ): WaitResult {
        val start = android.os.SystemClock.elapsedRealtime()
        var attempts = 0
        while (true) {
            attempts++
            val matches = matchOnce(sel)
            if (matches != null && matches.isNotEmpty()) {
                return WaitResult(
                    true, matches[0], attempts,
                    android.os.SystemClock.elapsedRealtime() - start
                )
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            if (elapsed >= timeoutMs) return WaitResult(false, null, attempts, elapsed)
            val sleepFor = minOf(intervalMs, timeoutMs - elapsed)
            try { Thread.sleep(sleepFor) } catch (_: Exception) {
                return WaitResult(
                    false, null, attempts,
                    android.os.SystemClock.elapsedRealtime() - start
                )
            }
        }
    }

    // ----- show on screen -----

    /** Bring the deepest node containing the point into view. */
    fun showNodeAtPoint(x: Int, y: Int): SelectorPick {
        val (winner, performed) = actOnWinner(
            select = { NodeSelector.selectDeepestAny(it, x, y) },
            action = { node ->
                try { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) }
                catch (_: Exception) { false }
            }
        )
        return SelectorPick(winner, if (winner != null) 1 else 0, performed)
    }

    /** Bring the first selector match into view. */
    fun showNodeBySelector(sel: NodeSelector.Selector): SelectorPick {
        val (winner, performed) = actOnWinner(
            select = { NodeSelector.selectBySelector(it, sel).getOrNull(0) },
            action = { node ->
                try { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) }
                catch (_: Exception) { false }
            }
        )
        return SelectorPick(winner, if (winner != null) 1 else 0, performed)
    }

    // ----- set text on an arbitrary node -----

    private fun setTextAction(text: String): (AccessibilityNodeInfo) -> Boolean = { node ->
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        try { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
        catch (_: Exception) { false }
    }

    /** Set text on the deepest editable node containing the point. */
    fun setTextAtPoint(x: Int, y: Int, text: String): SelectorPick {
        val (winner, performed) = actOnWinner(
            select = { NodeSelector.selectDeepestEditable(it, x, y) },
            action = setTextAction(text)
        )
        return SelectorPick(winner, if (winner != null) 1 else 0, performed)
    }

    /**
     * Set text on the first editable selector match. When matches exist
     * but none is editable, the node is reported with performed=false
     * and the caller distinguishes via the error string (ApiServer
     * re-checks editability for that message).
     */
    fun setTextBySelector(sel: NodeSelector.Selector, text: String): SelectorPick {
        val (winner, performed) = actOnWinner(
            select = { NodeSelector.selectBySelector(it, sel).firstOrNull { n -> n.editable } },
            action = setTextAction(text)
        )
        val count = matchOnce(sel)?.size ?: 0
        return SelectorPick(winner, count, performed)
    }

    // ----- long click -----

    /** Long-click the deepest clickable node containing the point. */
    fun longClickAtPoint(x: Int, y: Int): SelectorPick {
        val (winner, performed) = actOnWinner(
            select = { NodeSelector.selectBest(it, x, y) },
            action = { node ->
                try { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
                catch (_: Exception) { false }
            }
        )
        return SelectorPick(winner, if (winner != null) 1 else 0, performed)
    }

    /** Long-click the first selector match. */
    fun longClickBySelector(sel: NodeSelector.Selector): SelectorPick {
        val (winner, performed) = actOnWinner(
            select = { NodeSelector.selectBySelector(it, sel).getOrNull(0) },
            action = { node ->
                try { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
                catch (_: Exception) { false }
            }
        )
        val count = matchOnce(sel)?.size ?: 0
        return SelectorPick(winner, count, performed)
    }

    // ----- windows walk -----

    data class WindowInfo(
        val id: Int,
        val type: Int,
        val focused: Boolean,
        val active: Boolean,
        val bounds: NodeSelector.Bounds,
        val rootClass: String?,
        val nodeCount: Int,
        val truncated: Boolean
    )

    /**
     * Every window the service can retrieve, with per-window root class
     * and node count. Needs flagRetrieveInteractiveWindows (1.3.0
     * config) for interactive windows to appear. This is the
     * discriminator for Settings-sub-page node-blankness: radios in a
     * non-active window explain a null nodequery on the active tree.
     */
    fun windowsWalk(): List<WindowInfo> {
        val out = ArrayList<WindowInfo>()
        val wins = try { windows } catch (_: Exception) { null } ?: return out
        for (w in wins) {
            val rect = Rect()
            try { w.getBoundsInScreen(rect) } catch (_: Exception) {}
            val root = try { w.root } catch (_: Exception) { null }
            var nodeCount = 0
            var truncated = false
            var rootClass: String? = null
            if (root != null) {
                rootClass = root.className?.toString()
                val c = collectFrom(root, 100, 20000)
                try {
                    nodeCount = c.totalVisited
                    truncated = c.truncated
                } finally {
                    recycleCollected(c)
                }
            }
            out.add(
                WindowInfo(
                    id = w.id, type = w.type, focused = w.isFocused, active = w.isActive,
                    bounds = NodeSelector.Bounds(rect.left, rect.top, rect.right, rect.bottom),
                    rootClass = rootClass, nodeCount = nodeCount, truncated = truncated
                )
            )
        }
        return out
    }

    /**
     * Screenshot via AccessibilityService.takeScreenshot() (API 30+).
     * No MediaProjection consent dialog, no foreground service, no per-reboot
     * re-grant — the accessibility permission (already granted) is sufficient.
     * Blocks the calling thread up to timeoutMs for the async callback.
     * Returns PNG bytes, or null on failure / unsupported API level.
     */
    fun takeScreenshotPng(timeoutMs: Long = 8000): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>(null)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    java.util.concurrent.Executor { it.run() },
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hwBitmap = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer, screenshot.colorSpace)
                                // Hardware bitmaps can't be PNG-compressed; copy to software.
                                result.set(hwBitmap?.copy(Bitmap.Config.ARGB_8888, false))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                                latch.countDown()
                            }
                        }
                        override fun onFailure(errorCode: Int) {
                            latch.countDown()
                        }
                    })
            } catch (e: Exception) {
                e.printStackTrace()
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val bmp = result.get() ?: return null
        return try {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    private fun dispatchGestureSync(gesture: GestureDescription): Boolean {        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok.set(true); latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        latch.await(5, TimeUnit.SECONDS)
        return ok.get()
    }
}
