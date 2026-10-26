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
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
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
