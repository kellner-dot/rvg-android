package com.seth.rvgandroid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
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

    /** Tap at (x, y) in screen pixels. Returns true if the gesture dispatched. */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
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
