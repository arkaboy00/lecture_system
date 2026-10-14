package com.arka.lecturecapture.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object Naming {
    const val EVENTS = "events.jsonl"
    const val META = "lecture.json"
    const val AUDIO_DIR = "audio"
    const val PHOTOS_DIR = "photos"
    const val PENDING_DIR = ".pending"

    private val FOLDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")
    private val PHOTO_TIME = DateTimeFormatter.ofPattern("HHmmss")
    private const val MAX_PART = 60

    /** `ГГГГ-ММ-ДД_ЧЧММ_<subject>_<title>`; empty parts are omitted. */
    fun folderName(start: ZonedDateTime, subject: String, title: String): String =
        listOf(start.format(FOLDER_TIME), sanitize(subject), sanitize(title))
            .filter { it.isNotEmpty() }
            .joinToString("_")

    /**
     * Makes a string safe as a single path component on Android and Windows (the folder is
     * copied to a laptop). Cyrillic is kept; whitespace becomes '-'; '_' is reserved as the
     * separator between name parts.
     */
    fun sanitize(text: String): String {
        val cleaned = buildString {
            for (ch in text.trim()) {
                when {
                    ch.isISOControl() -> {}
                    ch in "\\/:*?\"<>|" -> {}
                    ch == '_' || ch.isWhitespace() -> append('-')
                    else -> append(ch)
                }
            }
        }
        return cleaned.replace(Regex("-{2,}"), "-").trim('-', '.', ' ').take(MAX_PART).trimEnd('-', '.')
    }

    fun audioFile(index: Int, extension: String = "ogg"): String = "$AUDIO_DIR/%03d.%s".format(index, extension)

    /** `photos/HHMMSS_n.jpg`, n starting at 1 and skipping names in [taken]. */
    fun photoFile(shutter: ZonedDateTime, taken: Set<String>): String {
        val base = shutter.format(PHOTO_TIME)
        var n = 1
        while ("$PHOTOS_DIR/${base}_$n.jpg" in taken) n++
        return "$PHOTOS_DIR/${base}_$n.jpg"
    }

    /** Name of a not-yet-sorted frame; it carries the shutter stamp so it survives a crash. */
    fun pendingName(shot: PendingShot): String =
        "${shot.boot ?: "x"}_${shot.mono}_${shot.wallMillis}.jpg"

    fun parsePending(name: String): PendingShot? {
        val parts = name.removeSuffix(".jpg").split('_')
        if (parts.size != 3 || !name.endsWith(".jpg")) return null
        val boot = if (parts[0] == "x") null else parts[0].toIntOrNull() ?: return null
        val mono = parts[1].toLongOrNull() ?: return null
        val wall = parts[2].toLongOrNull() ?: return null
        return PendingShot(boot, mono, wall)
    }

    /** "Лекция 5" → next is "Лекция 6"; numbering is per subject. */
    fun nextNumberedTitle(prefix: String, existingTitles: Iterable<String>): String {
        val pattern = Regex("^${Regex.escape(prefix)}\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val max = existingTitles.mapNotNull { pattern.find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "$prefix ${max + 1}"
    }
}

/** Shutter moment of a pending frame. */
data class PendingShot(val boot: Int?, val mono: Long, val wallMillis: Long)
