package com.arka.lecturecapture.core.sync

import com.arka.lecturecapture.core.EventReader
import com.arka.lecturecapture.core.LectureState
import com.arka.lecturecapture.core.MetaFile
import com.arka.lecturecapture.core.Naming
import com.arka.lecturecapture.core.PART_SUFFIX
import java.io.File
import java.security.MessageDigest

/**
 * Lists what can be synced: every lecture folder with its files, sizes and SHA-256.
 * Not synced: `.pending/` (unsorted frames), `.part` and `.tmp` files, hidden files.
 * Hashes are cached by (size, mtime), so a second manifest only hashes what changed.
 */
class ManifestBuilder(private val root: File) {
    private data class Cached(val size: Long, val mtime: Long, val sha: String)

    private val cache = HashMap<String, Cached>()

    @Synchronized
    fun build(): Manifest {
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && File(f, Naming.EVENTS).isFile }
            ?: return Manifest(lectures = emptyList())
        val lectures = dirs.sortedBy { it.name }.map { dir ->
            val state = LectureState.replay(EventReader.read(File(dir, Naming.EVENTS)).records)
            LectureEntry(
                folder = dir.name,
                ended = state.isEnded,
                created = MetaFile.read(dir)?.created ?: state.started?.ts,
                files = files(dir).map { f ->
                    val rel = "${dir.name}/${f.relativeTo(dir).invariantSeparatorsPath}"
                    FileEntry(rel, f.length(), sha(rel, f))
                },
            )
        }
        return Manifest(lectures = lectures)
    }

    private fun files(dir: File): List<File> = dir.walkTopDown()
        .onEnter { it == dir || !(it.name.startsWith(".") || it.name == Naming.PENDING_DIR) }
        .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(PART_SUFFIX) && !it.name.endsWith(".tmp") }
        .sortedBy { it.path }
        .toList()

    private fun sha(key: String, file: File): String {
        val size = file.length()
        val mtime = file.lastModified()
        cache[key]?.let { if (it.size == size && it.mtime == mtime) return it.sha }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        cache[key] = Cached(size, mtime, sha)
        return sha
    }
}
