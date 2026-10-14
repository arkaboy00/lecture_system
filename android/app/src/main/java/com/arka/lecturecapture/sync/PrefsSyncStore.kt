package com.arka.lecturecapture.sync

import android.content.Context
import com.arka.lecturecapture.core.sync.Ack
import com.arka.lecturecapture.core.sync.FileEntry
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

    /**
     * True when the laptop confirmed every file currently in the folder with the same size.
     * Sizes are enough here: lecture files only ever grow or appear, they are not edited.
     */
    fun isOnLaptop(folder: File): Boolean {
        val acked = prefs.getString("ack:${folder.name}", null)?.let { Json.decodeFromString(listJson, it) } ?: return false
        val sizes = acked.associate { it.path to it.size }
        val current = folder.walkTopDown()
            .onEnter { it == folder || !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") && !it.name.endsWith(".tmp") }
            .toList()
        return current.isNotEmpty() && current.all { sizes["${folder.name}/${it.relativeTo(folder).invariantSeparatorsPath}"] == it.length() }
    }
}
