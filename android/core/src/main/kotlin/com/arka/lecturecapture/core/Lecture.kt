package com.arka.lecturecapture.core

import java.io.Closeable
import java.io.File
import java.time.ZoneId

/**
 * One lecture folder opened for writing. All writes go through [append], which persists the
 * event (fsync) before updating [state]. Thread-safe.
 */
class Lecture private constructor(
    dir: File,
    private val clock: Clock,
    private var log: EventLog?,
    state: LectureState,
    meta: LectureMeta,
    /** Lines of events.jsonl that failed to decode when the lecture was opened. */
    val readProblems: List<LineProblem>,
) : Closeable {

    var dir: File = dir
        private set
    @Volatile var state: LectureState = state
        private set
    @Volatile var meta: LectureMeta = meta
        private set

    val isOpen: Boolean @Synchronized get() = log != null

    fun file(relative: String): File = File(dir, relative)

    fun zone(): ZoneId = clock.zone()

    /** Current stamp, e.g. the moment a note field opens (the note is written later with it). */
    fun now(): Stamp = clock.now()

    /** The shutter moment, taken when the button is pressed (not when the JPEG is written). */
    fun shutterNow(): PendingShot = PendingShot(clock.boot(), clock.mono(), clock.wallMillis())

    @Synchronized
    fun append(body: EventBody, stamp: Stamp = clock.now()): LectureState {
        val log = checkNotNull(log) { "lecture ${dir.name} is closed" }
        state = state.reduce(log.append(body, stamp))
        return state
    }

    /**
     * Path for the next audio segment. Skips names already on disk, in case the event of an
     * earlier segment was lost in a torn line.
     */
    @Synchronized
    fun nextAudioFile(extension: String = "ogg"): String {
        var index = state.nextAudioIndex
        while (file(Naming.audioFile(index, extension)).exists()) index++
        return Naming.audioFile(index, extension)
    }

    /**
     * After the recording process died: closes the segment that has no audio_stop.
     * The event is stamped at the real end of the audio (start + duration found in the file),
     * so pauses on the timeline stay correct; without a readable file it is stamped now.
     */
    @Synchronized
    fun closeDanglingAudio(durationOf: (File) -> Long? = { OggInfo.read(it)?.durationMs }): AudioStop? {
        val open = state.recording ?: return null
        val duration = durationOf(file(open.file))
        val stop = AudioStop(open.file, StopReason.RECOVERED, durMs = duration)
        append(stop, if (duration != null) Stamps.plus(open.start, duration) else clock.now())
        return stop
    }

    /** Changes names mid-lecture: a `meta` event for history plus a rewritten lecture.json. */
    @Synchronized
    fun rename(subject: String? = null, title: String? = null): LectureState {
        append(MetaChanged(subject = subject, title = title))
        writeMeta(meta.copy(subject = state.subject, title = state.title))
        return state
    }

    /**
     * Writes lecture_end, marks lecture.json as ended, closes the log and, if the names
     * changed since the folder was created, renames the folder (nothing is open any more).
     * Returns the final folder.
     */
    @Synchronized
    fun finish(kind: LectureKind): File {
        append(LectureEnd(kind))
        writeMeta(meta.copy(subject = state.subject, title = state.title, kind = kind, ended = state.ended?.ts))
        close()
        renameFolderToNames()
        return dir
    }

    /**
     * Later edit of a finished lecture (archive screen): only the changed fields go into a
     * `meta` event, lecture.json is rewritten, the log is closed and the folder renamed to
     * match. Returns the final folder.
     */
    @Synchronized
    fun amend(subject: String? = null, title: String? = null, kind: LectureKind? = null): File {
        check(state.isEnded) { "${dir.name} is not finished; edit it through the open session" }
        val change = MetaChanged(
            subject = subject?.takeIf { it != state.subject },
            title = title?.takeIf { it != state.title },
            kind = kind?.takeIf { it != (state.kind ?: meta.kind) },
        )
        if (change != MetaChanged()) {
            append(change)
            writeMeta(meta.copy(subject = state.subject, title = state.title, kind = state.kind ?: meta.kind))
        }
        close()
        renameFolderToNames()
        return dir
    }

    private fun renameFolderToNames() {
        val wanted = Naming.folderName(Stamps.zoned(meta.created), meta.subject, meta.title)
        if (dir.name != wanted && !dir.name.matches(Regex(Regex.escape(wanted) + "_\\d+"))) {
            val target = uniqueDir(dir.parentFile, wanted)
            if (dir.renameTo(target)) dir = target
        }
    }

    @Synchronized
    override fun close() {
        log?.close()
        log = null
    }

    private fun writeMeta(newMeta: LectureMeta) {
        MetaFile.write(dir, newMeta)
        meta = newMeta
    }

    companion object {
        fun create(
            root: File,
            subject: String,
            title: String,
            clock: Clock,
            device: String? = null,
            app: String? = null,
            part: Int? = null,
        ): Lecture {
            val now = clock.now()
            val dir = uniqueDir(root, Naming.folderName(Stamps.zoned(now.ts), subject, title))
            for (sub in listOf(Naming.AUDIO_DIR, Naming.PHOTOS_DIR, Naming.PENDING_DIR)) {
                check(File(dir, sub).mkdirs()) { "cannot create ${File(dir, sub)}" }
            }
            // Log first: a folder with events.jsonl but no lecture.json is repaired on open,
            // the other way round it would be an empty shell.
            val log = EventLog.open(File(dir, Naming.EVENTS), lastSeq = 0)
            val state = LectureState().reduce(log.append(LectureStart(subject = subject, title = title, device = device, app = app, part = part), now))
            val meta = LectureMeta(subject = subject, title = title, created = now.ts, device = device, app = app, part = part)
            MetaFile.write(dir, meta)
            return Lecture(dir, clock, log, state, meta, emptyList())
        }

        /** Replays events.jsonl and opens it for appending. */
        fun open(dir: File, clock: Clock): Lecture {
            val read = EventReader.read(File(dir, Naming.EVENTS))
            val state = LectureState.replay(read.records)
            val meta = MetaFile.read(dir) ?: MetaFile.fromState(state, clock.now().ts).also { MetaFile.write(dir, it) }
            for (sub in listOf(Naming.AUDIO_DIR, Naming.PHOTOS_DIR, Naming.PENDING_DIR)) File(dir, sub).mkdirs()
            val log = EventLog.open(File(dir, Naming.EVENTS), state.lastSeq)
            return Lecture(dir, clock, log, state, meta, read.problems)
        }

        internal fun uniqueDir(parent: File, name: String): File {
            var candidate = File(parent, name)
            var n = 2
            while (candidate.exists()) candidate = File(parent, "${name}_${n++}")
            return candidate
        }
    }
}
