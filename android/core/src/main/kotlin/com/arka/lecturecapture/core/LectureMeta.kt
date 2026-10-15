package com.arka.lecturecapture.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * lecture.json: current names and flags of a lecture. Unlike events.jsonl it is rewritten,
 * always atomically (temp file + fsync + rename), so it is either the old or the new version.
 * events.jsonl stays the source of truth; this file can be rebuilt from it.
 */
@Serializable
data class LectureMeta(
    val v: Int = FORMAT_VERSION,
    val subject: String,
    val title: String,
    val kind: LectureKind = LectureKind.LECTURE,
    val created: String,
    /** Set together with lecture_end: the folder is complete and ready for processing. */
    val ended: String? = null,
    val device: String? = null,
    val app: String? = null,
    /** See [LectureStart.part]. */
    val part: Int? = null,
)

object MetaFile {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun read(dir: File): LectureMeta? {
        val file = File(dir, Naming.META)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(LectureMeta.serializer(), file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    fun write(dir: File, meta: LectureMeta) {
        val target = File(dir, Naming.META)
        val tmp = File(dir, Naming.META + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write((json.encodeToString(LectureMeta.serializer(), meta) + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        syncDir(dir)
    }

    /** Makes the rename durable. Not supported everywhere (e.g. Windows), so failures are ignored. */
    internal fun syncDir(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
        }
    }

    /** Rebuilds metadata from the log when lecture.json is missing or unreadable. */
    fun fromState(state: LectureState, created: String): LectureMeta = LectureMeta(
        subject = state.subject,
        title = state.title,
        kind = state.kind ?: LectureKind.LECTURE,
        created = state.started?.ts ?: created,
        ended = state.ended?.ts,
        part = state.part.takeIf { it > 1 },
    )
}
