package com.pureguard.filter.ui

import android.content.Context
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ContentInfoCompat
import androidx.core.view.ViewCompat

/**
 * 100% Paste-Proof EditText for the Tanya Chapter 41 unlock challenge.
 * Blocks ALL paste vectors:
 * 1. Gboard / SwiftKey / Samsung Keyboard clipboard toolbar chips (via InputFilter addedChars > 1 check)
 * 2. Android 12+ OnReceiveContentListener (SOURCE_CLIPBOARD / SOURCE_DRAG_AND_DROP)
 * 3. Standard context menu Paste / Copy / Cut (onTextContextMenuItem + ActionMode callbacks)
 * 4. Keyboard suggestion & clipboard strip suppression (TYPE_TEXT_VARIATION_VISIBLE_PASSWORD + NO_SUGGESTIONS)
 */
class NoPasteEditText(context: Context) : AppCompatEditText(context) {

    private var lastToastMs: Long = 0L

    init {
        isLongClickable = false
        setTextIsSelectable(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }

        // Keeps Hebrew text fully visible while instructing Gboard/SwiftKey/Samsung to suppress clipboard/suggestion bar
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

        val blockActionMode = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        customSelectionActionModeCallback = blockActionMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            customInsertionActionModeCallback = blockActionMode
        }

        // Defense 1: Reject Android 12+ clipboard / drag-and-drop content insertion
        ViewCompat.setOnReceiveContentListener(this, arrayOf("*/*")) { _, payload ->
            if (payload.source == ContentInfoCompat.SOURCE_CLIPBOARD ||
                payload.source == ContentInfoCompat.SOURCE_DRAG_AND_DROP
            ) {
                showPasteBlockedToast()
                null
            } else {
                payload
            }
        }

        // Defense 2: Killer InputFilter against Gboard clipboard bar & voice/paste multi-character bursts!
        // Normal typing (including composing keyboards) adds at most 1 net character per edit (addedChars <= 1).
        // Any paste from Gboard clipboard bar inserts >= 2 characters in one commitText() call and is instantly rejected!
        filters = arrayOf(
            InputFilter { source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int ->
                val incomingLen = end - start
                val replacedLen = dend - dstart
                val addedChars = incomingLen - replacedLen
                if (addedChars > 1 || (replacedLen == 0 && incomingLen > 1)) {
                    showPasteBlockedToast()
                    // Keep the existing text unchanged
                    dest.subSequence(dstart, dend)
                } else {
                    null
                }
            }
        )
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste ||
            id == android.R.id.pasteAsPlainText ||
            id == android.R.id.copy ||
            id == android.R.id.cut
        ) {
            showPasteBlockedToast()
            return false
        }
        return super.onTextContextMenuItem(id)
    }

    private fun showPasteBlockedToast() {
        val now = System.currentTimeMillis()
        if (now - lastToastMs > 2000L) {
            lastToastMs = now
            Toast.makeText(
                context,
                "🚫 הדבקת טקסט חסומה! יש להקליד ידנית אות-אות.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
