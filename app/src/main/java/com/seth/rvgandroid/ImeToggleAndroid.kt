package com.seth.rvgandroid

import android.content.Context
import android.view.inputmethod.InputMethodManager

/**
 * Thin Android adapter for the pure [ImeToggle]: IME lists come from
 * InputMethodManager, the secure store is [AndroidSecureSettings].
 */
object ImeToggleAndroid {
    fun create(ctx: Context): ImeToggle {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return ImeToggle(
            store = AndroidSecureSettings(ctx),
            enabledImes = { imm.enabledInputMethodList.map { it.id } },
            allImes = { imm.inputMethodList.map { it.id } },
            isGrantHeld = { AndroidSecureSettings.isGrantHeld(ctx) }
        )
    }

    fun state(ctx: Context): ImeState = create(ctx).state()

    /** Short display name for an active IME id. */
    fun shortName(id: String?): String = when {
        id == null -> "none"
        id == ImeToggle.RVG_COMPONENT -> "RVG Keyboard"
        id.startsWith(ImeToggle.GBOARD_PACKAGE + "/") -> "Gboard"
        else -> id.substringAfterLast("/").ifEmpty { id }
    }
}
