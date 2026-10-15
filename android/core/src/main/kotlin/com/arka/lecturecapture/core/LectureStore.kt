package com.arka.lecturecapture.core

import java.io.File

data class LectureSummary(
    val dir: File,
    val subject: String,
    val title: String,
    val started: Stamp?,
    val ended: Boolean,
    val kind: LectureKind?,
    /** Has an audio segment without audio_stop: the recording process died. */
    val recording: Boolean,
    val photos: Int,
    val segments: Int,
    /** Total recorded audio, ms (file durations where known). */
    val audioMs: Long,
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
                kind = state.kind,
                recording = state.recording != null,
                photos = state.photos.size,
                segments = state.segments.size,
                // A segment left open by a crash has no length in the log: read it from the file,
                // so "delete this lecture?" does not claim there is no audio.
                audioMs = state.segments.sumOf { seg ->
                    if (seg.isOpen) OggInfo.read(File(dir, seg.file))?.durationMs ?: 0L else seg.lengthMs()
                },
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

    /**
     * Erases a lecture folder, only on explicit confirmation in the UI: an unfinished one the
     * user started by mistake, or (with [finished] = true, after a double confirmation) a
     * finished one. The only places besides discarded frames where the app deletes data.
     */
    fun delete(dir: File, finished: Boolean = false) {
        requireLectureDir(dir)
        val state = LectureState.replay(EventReader.read(File(dir, Naming.EVENTS)).records)
        require(state.isEnded == finished) {
            if (finished) "${dir.name} is not finished" else "${dir.name} is finished; delete it from the archive"
        }
        check(dir.deleteRecursively()) { "could not delete everything in ${dir.name}" }
    }

    /**
     * Later edit of a finished lecture's names and kind; see [Lecture.amend]. Returns the
     * folder, renamed if the names changed.
     */
    fun amend(dir: File, subject: String? = null, title: String? = null, kind: LectureKind? = null): File {
        requireLectureDir(dir)
        return open(dir).amend(subject, title, kind)
    }

    /** Marks a photo of a finished lecture obsolete or relevant again. */
    fun setObsolete(dir: File, file: String, obsolete: Boolean) {
        requireLectureDir(dir)
        open(dir).use { lecture ->
            check(lecture.state.isEnded) { "${dir.name} is not finished; edit it through the open session" }
            val photo = lecture.state.photos.firstOrNull { it.file == file } ?: error("unknown photo $file")
            if (photo.obsolete != obsolete) lecture.append(if (obsolete) PhotoObsolete(file) else PhotoRestored(file))
        }
    }

    private fun requireLectureDir(dir: File) {
        require(dir.parentFile?.canonicalFile == root.canonicalFile) { "$dir is not a lecture folder" }
        require(File(dir, Naming.EVENTS).isFile) { "$dir has no ${Naming.EVENTS}" }
    }

    /**
     * Creates the root with a .nomedia marker (lecture photos stay out of the gallery) and a
     * Syncthing .stignore for files that are not data yet. Existing files are left as they are,
     * so the user can edit .stignore.
     */
    fun ensureRoot() {
        if (!root.isDirectory) check(root.mkdirs()) { "cannot create $root" }
        File(root, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        File(root, ".stignore").let { if (!it.exists()) it.writeText(STIGNORE) }
    }

    companion object {
        val STIGNORE = """
            // Lecture Capture: unsorted frames and files still being written
            .pending
            *.part
            *.tmp
        """.trimIndent() + "\n"
    }
}
