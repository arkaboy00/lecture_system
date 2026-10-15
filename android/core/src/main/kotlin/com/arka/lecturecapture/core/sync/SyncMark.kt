package com.arka.lecturecapture.core.sync

import java.io.File

/** What the laptop holds of one lecture folder, judged from its last [Ack]. */
enum class SyncMark {
    /** No confirmation for this folder. */
    NEVER,
    /** Confirmed earlier, but files were added or changed since. */
    CHANGED,
    /** Every current file is confirmed with the same size. */
    SYNCED;

    companion object {
        /**
         * Sizes are enough: audio and photos are never rewritten, and every edit appends to
         * events.jsonl, so a changed folder always has a file of a different size.
         */
        fun of(folder: File, acked: List<FileEntry>?): SyncMark {
            if (acked == null) return NEVER
            val sizes = acked.associate { it.path to it.size }
            val current = syncableFiles(folder)
            val all = current.isNotEmpty() && current.all { sizes["${folder.name}/${it.relativeTo(folder).invariantSeparatorsPath}"] == it.length() }
            return if (all) SYNCED else CHANGED
        }

        /** The confirmation of a folder renamed on the phone, with paths under the new name. */
        fun renamed(acked: List<FileEntry>, oldName: String, newName: String): List<FileEntry> =
            acked.map { it.copy(path = newName + it.path.removePrefix(oldName)) }
    }
}
