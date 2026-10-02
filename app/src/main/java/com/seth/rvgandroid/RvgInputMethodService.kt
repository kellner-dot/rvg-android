package com.seth.rvgandroid

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "RVG Keyboard" — a null-UI input method that lets /rvd/input type into
 * ANY focused view, including terminal emulators (Termux) whose custom
 * TerminalView can never accept AccessibilityNodeInfo.ACTION_SET_TEXT.
 *
 * WHY THIS EXISTS (root cause of the Termux typing failure):
 * A sideloaded app has exactly two ways to put text into another app:
 *   1. Accessibility ACTION_SET_TEXT — only works on EditText fields.
 *   2. Being the active InputMethodService — then InputConnection.commitText
 *      / sendKeyEvent reach whatever view currently has input focus.
 * There is NO AccessibilityService.dispatchKeyEvent API (it doesn't exist in
 * the framework), and Instrumentation/InputManager injection needs the
 * INJECT_EVENTS permission, which only system apps / adb shell hold. So a
 * minimal IME is the only real path to remote terminal typing.
 *
 * SETUP (one time, by Seth): Settings -> System -> Keyboard -> On-screen
 * keyboard -> enable "RVG Keyboard", then switch to it (globe/keyboard
 * icon) whenever remote typing is needed. The keyboard shows no UI
 * (onEvaluateInputViewShown() = false); it only serves remote input.
 * /rvd/status reports "imeEnabled" / "imeActive" so remote callers know.
 */
class RvgInputMethodService : InputMethodService() {

    companion object {
        @Volatile
        var instance: RvgInputMethodService? = null
            private set

        /** True if the user has enabled RVG Keyboard in system settings. */
        fun isEnabled(ctx: Context): Boolean {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            return imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
        }

        /** True if RVG Keyboard is the CURRENT input method. */
        fun isActive(ctx: Context): Boolean {
            val cur = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            return cur != null && cur.startsWith(ctx.packageName + "/")
        }

        /**
         * Type text through the IME into whatever currently has input focus.
         * '\n' is sent as ENTER key events (terminals expect KEYCODE_ENTER);
         * everything else goes via commitText, exactly like Gboard does.
         * Must be callable from any thread; returns false when the IME isn't
         * active or there's no focused input connection.
         */
        fun typeText(text: String): Boolean {
            val ime = instance ?: return false
            if (text.isEmpty()) return true
            val ok = AtomicBoolean(false)
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    ok.set(ime.typeOnMainThread(text))
                } catch (_: Exception) {
                    ok.set(false)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(15, TimeUnit.SECONDS)
            return ok.get()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** No keyboard UI — this IME exists only to serve remote input. */
    override fun onCreateInputView(): View? = null
    override fun onEvaluateInputViewShown(): Boolean = false
    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun typeOnMainThread(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        var allOk = true
        val run = StringBuilder()
        fun flushRun() {
            if (run.isNotEmpty()) {
                if (!ic.commitText(run.toString(), 1)) allOk = false
                run.clear()
            }
        }
        for (c in text) {
            if (c == '\n') {
                flushRun()
                if (!ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                    allOk = false
                if (!ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
                    allOk = false
            } else {
                run.append(c)
            }
        }
        flushRun()
        return allOk
    }
}
