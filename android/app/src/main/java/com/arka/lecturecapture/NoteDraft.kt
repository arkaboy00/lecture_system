package com.arka.lecturecapture

import android.content.Context
import com.arka.lecturecapture.core.Stamp

/**
 * The note being typed, saved on every keystroke: a crash or a rotation must not lose it,
 * nor the moment the note field was opened (that moment is the note's time in the log).
 * At most one draft exists; it belongs to the lecture folder named [folder].
 */
data class NoteDraft(val folder: String, val opened: Stamp, val text: String) {
    companion object {
        private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("note_draft", Context.MODE_PRIVATE)

        fun load(context: Context, folder: String): NoteDraft? {
            val p = prefs(context)
            if (p.getString("folder", null) != folder) return null
            val ts = p.getString("ts", null) ?: return null
            val boot = p.getInt("boot", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            return NoteDraft(folder, Stamp(ts, p.getLong("mono", 0), boot), p.getString("text", "") ?: "")
        }

        fun save(context: Context, draft: NoteDraft) {
            prefs(context).edit()
                .putString("folder", draft.folder)
                .putString("ts", draft.opened.ts)
                .putLong("mono", draft.opened.mono)
                .putInt("boot", draft.opened.boot ?: Int.MIN_VALUE)
                .putString("text", draft.text)
                .commit()
        }

        fun clear(context: Context) {
            prefs(context).edit().clear().commit()
        }
    }
}
