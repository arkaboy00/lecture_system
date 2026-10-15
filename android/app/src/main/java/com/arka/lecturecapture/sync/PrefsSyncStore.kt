package com.arka.lecturecapture.sync

import android.content.Context
import com.arka.lecturecapture.core.sync.Ack
import com.arka.lecturecapture.core.sync.FileEntry
import com.arka.lecturecapture.core.sync.SyncMark
import com.arka.lecturecapture.core.sync.SyncStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Pairing token and the laptop's confirmations, in app-private storage (not in the lecture
 * folders: those stay raw data). Losing it (reinstall) only means pairing again and the
 * "on laptop" marks disappearing until the next sync.
 */
class PrefsSyncStore(context: Context) : SyncStore {
    private val prefs = context.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val listJson = ListSerializer(FileEntry.serializer())

    override fun token(): String? = prefs.getString("token", null)

    override fun saveToken(token: String) {
        prefs.edit().putString("token", token).commit()
    }

    override fun saveAck(ack: Ack) {
        prefs.edit().putString("ack:${ack.folder}", Json.encodeToString(listJson, ack.files)).commit()
    }

    fun forgetPairing() {
        prefs.edit().remove("token").commit()
    }

    fun mark(folder: File): SyncMark = SyncMark.of(folder, acked(folder.name))

    fun isOnLaptop(folder: File): Boolean = mark(folder) == SyncMark.SYNCED

    /** A folder renamed on the phone keeps its confirmation, so the archive shows "changed", not "never". */
    fun onFolderRenamed(oldName: String, newName: String) {
        if (oldName == newName) return
        val acked = acked(oldName) ?: return
        prefs.edit()
            .remove("ack:$oldName")
            .putString("ack:$newName", Json.encodeToString(listJson, SyncMark.renamed(acked, oldName, newName)))
            .commit()
    }

    fun onFolderDeleted(name: String) {
        prefs.edit().remove("ack:$name").commit()
    }

    private fun acked(name: String): List<FileEntry>? =
        prefs.getString("ack:$name", null)?.let { Json.decodeFromString(listJson, it) }
}
