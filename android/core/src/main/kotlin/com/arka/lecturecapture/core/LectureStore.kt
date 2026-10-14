package com.arka.lecturecapture.core

import java.io.File

data class LectureSummary(
    val dir: File,
    val subject: String,
    val title: String,
    val started: Stamp?,
    val ended: Boolean,
    /** Has an audio segment without audio_stop: the recording process died. */
    val recording: Boolean,
    val photos: Int,
    val segments: Int,
    val problems: Int,
)

/** The `Lectures` root folder: scanning, creating and opening lecture folders. */
class LectureStore(val root: File, private val clock: Clock) {

    /** All lecture folders, newest first. Folders without events.jsonl are ignored. */
    fun list(): List<LectureSummary> {
        val dirs = root.listFiles { f -> f.isDirectory && File(f, Naming.EVENTS).isFile } ?: return emptyList()
        return dirs.sortedByDescending { it.name }.map { dir ->
            val read = EventReader.read(File(dir, Naming.EVENTS))
            val state = LectureState.replay(read.records)
            LectureSummary(
                dir = dir,
                subject = state.subject,
                title = state.title,
                started = state.started,
                ended = state.isEnded,
                recording = state.recording != null,
                photos = state.photos.size,
                segments = state.segments.size,
                problems = read.problems.size + state.warnings.size,
            )
        }
    }

    fun unfinished(): List<LectureSummary> = list().filter { !it.ended }

    /** Subjects used before, most recent first. */
    fun subjects(): List<String> = list().map { it.subject }.filter { it.isNotBlank() }.distinct()

    fun create(subject: String, title: String, device: String? = null, app: String? = null): Lecture {
        ensureRoot()
        return Lecture.create(root, subject, title, clock, device, app)
    }

    fun open(dir: File): Lecture = Lecture.open(dir, clock)

    /** Creates the root with a .nomedia marker so lecture photos do not flood the gallery. */
    fun ensureRoot() {
        if (!root.isDirectory) check(root.mkdirs()) { "cannot create $root" }
        File(root, ".nomedia").let { if (!it.exists()) it.createNewFile() }
    }
}
