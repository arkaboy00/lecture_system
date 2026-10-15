package com.arka.lecturecapture.core

data class Silence(val start: Stamp, val end: Stamp? = null)

data class AudioSegment(
    val file: String,
    val start: Stamp,
    val stop: Stamp? = null,
    val stopReason: StopReason? = null,
    val durMs: Long? = null,
    val silences: List<Silence> = emptyList(),
) {
    val isOpen: Boolean get() = stop == null

    /** Length from the file when known, else from the log; an open segment counts as 0. */
    fun lengthMs(): Long = durMs ?: stop?.let { Stamps.between(start, it) } ?: 0L
}

data class Photo(
    val file: String,
    val type: PhotoType,
    val batch: Int,
    val taken: Stamp,
    val obsolete: Boolean = false,
    /** The user asked for the drawings on this photo to be redrawn properly (laptop side). */
    val redraw: Boolean = false,
)

/** A text note; [seq] identifies it for [NoteRemoved]. */
data class Note(val seq: Long, val at: Stamp, val text: String, val removed: Boolean = false)

/** A press of the "important" button; [seq] identifies it for [Unmarked]. */
data class Mark(val seq: Long, val at: Stamp, val removed: Boolean = false)

sealed interface TimelineItem {
    val at: Stamp

    data class Segment(val segment: AudioSegment) : TimelineItem {
        override val at get() = segment.start
    }

    /** Pause between two audio segments. */
    data class Gap(override val at: Stamp, val until: Stamp) : TimelineItem {
        val durationMs: Long get() = Stamps.between(at, until)
    }

    data class Shot(val photo: Photo) : TimelineItem {
        override val at get() = photo.taken
    }

    data class Noted(val note: Note) : TimelineItem {
        override val at get() = note.at
    }

    data class Starred(val mark: Mark) : TimelineItem {
        override val at get() = mark.at
    }
}

/**
 * Screen state of one lecture, built only by folding events.jsonl through [reduce].
 * The same code runs live (after each append) and on recovery (replaying the whole file).
 */
data class LectureState(
    val started: Stamp? = null,
    val subject: String = "",
    val title: String = "",
    val kind: LectureKind? = null,
    /** Part of a lecture split into several recordings (1 for the first or only one). */
    val part: Int = 1,
    val segments: List<AudioSegment> = emptyList(),
    /** Chronological (see [chronoKey]), not in file order. */
    val photos: List<Photo> = emptyList(),
    /** In log order (stamps are the moments the note field was opened). Removed ones stay, flagged. */
    val notes: List<Note> = emptyList(),
    val marks: List<Mark> = emptyList(),
    val recoveries: List<Stamp> = emptyList(),
    val ended: Stamp? = null,
    val lastSeq: Long = 0,
    /** Boot counts in order of first appearance; used to order stamps across reboots. */
    val boots: List<Int?> = emptyList(),
    /** Inconsistencies found while replaying. They never stop the replay. */
    val warnings: List<String> = emptyList(),
) {
    val recording: AudioSegment? get() = segments.lastOrNull()?.takeIf { it.isOpen }
    /** The open segment is currently silenced by the system. */
    val isSilenced: Boolean get() = recording?.silences?.lastOrNull()?.let { it.end == null } == true
    val isEnded: Boolean get() = ended != null
    val nextBatch: Int get() = (photos.maxOfOrNull { it.batch } ?: 0) + 1
    val lastPhotoType: PhotoType? get() = photos.maxByOrNull { it.batch }?.type
    val nextAudioIndex: Int get() = segments.size + 1

    /** Total order: boot (by first appearance in the log), then monotonic time. */
    fun chronoKey(stamp: Stamp): Pair<Int, Long> {
        val index = boots.indexOf(stamp.boot)
        return (if (index >= 0) index else boots.size) to stamp.mono
    }

    fun timeline(): List<TimelineItem> {
        val items = ArrayList<TimelineItem>()
        segments.forEachIndexed { i, seg ->
            items += TimelineItem.Segment(seg)
            val next = segments.getOrNull(i + 1)
            if (seg.stop != null && next != null) items += TimelineItem.Gap(seg.stop, next.start)
        }
        photos.forEach { items += TimelineItem.Shot(it) }
        notes.filterNot { it.removed }.forEach { items += TimelineItem.Noted(it) }
        marks.filterNot { it.removed }.forEach { items += TimelineItem.Starred(it) }
        val order = compareBy<TimelineItem>({ chronoKey(it.at).first }, { chronoKey(it.at).second })
        return items.sortedWith(order)
    }

    companion object {
        fun replay(records: Iterable<Record>): LectureState =
            records.fold(LectureState()) { state, record -> state.reduce(record) }
    }
}

fun LectureState.reduce(record: Record): LectureState {
    val stamp = record.stamp
    var s = copy(
        lastSeq = maxOf(lastSeq, record.seq),
        boots = if (stamp.boot in boots) boots else boots + stamp.boot,
    )
    // Collected separately: warn() is called from inside lambdas whose result replaces `s`.
    val warnings = ArrayList<String>()
    fun warn(msg: String) { warnings += "seq ${record.seq}: $msg" }
    if (s.isEnded && record.body !is LectureEnd && !record.body.isLaterEdit) warn("event after lecture_end")

    when (val body = record.body) {
        is LectureStart -> {
            if (s.started != null) warn("repeated lecture_start")
            s = s.copy(started = s.started ?: stamp, subject = body.subject, title = body.title, part = body.part ?: 1)
        }
        is MetaChanged -> s = s.copy(
            subject = body.subject ?: s.subject,
            title = body.title ?: s.title,
            kind = body.kind ?: s.kind,
        )
        is AudioStart -> {
            var segments = s.segments
            val open = s.recording
            if (open != null) {
                warn("audio_start while ${open.file} is open; closing it")
                segments = segments.dropLast(1) + open.copy(stop = stamp)
            }
            if (segments.any { it.file == body.file }) warn("audio file ${body.file} reused")
            s = s.copy(segments = segments + AudioSegment(body.file, stamp))
        }
        is AudioStop -> s = s.updateSegment(body.file, ::warn) { seg ->
            if (!seg.isOpen) {
                warn("audio_stop for already stopped ${body.file}")
                seg
            } else {
                seg.copy(
                    stop = stamp,
                    stopReason = body.reason,
                    durMs = body.durMs,
                    silences = seg.silences.map { if (it.end == null) it.copy(end = stamp) else it },
                )
            }
        }
        is AudioSilenced -> s = s.updateSegment(body.file, ::warn) { seg ->
            val last = seg.silences.lastOrNull()
            if (last != null && last.end == null) seg // already silenced
            else seg.copy(silences = seg.silences + Silence(stamp))
        }
        is AudioUnsilenced -> s = s.updateSegment(body.file, ::warn) { seg ->
            val last = seg.silences.lastOrNull()
            if (last == null || last.end != null) {
                warn("audio_unsilenced without audio_silenced")
                seg
            } else {
                seg.copy(silences = seg.silences.dropLast(1) + last.copy(end = stamp))
            }
        }
        is PhotoTaken -> {
            if (s.photos.any { it.file == body.file }) {
                warn("duplicate photo ${body.file}")
            } else {
                val photos = s.photos + Photo(body.file, body.type, body.batch, stamp)
                s = s.copy(photos = photos.sortedWith(compareBy({ s.chronoKey(it.taken).first }, { s.chronoKey(it.taken).second })))
            }
        }
        is PhotoObsolete -> s = s.updatePhoto(body.file, ::warn) { it.copy(obsolete = true) }
        is PhotoRestored -> s = s.updatePhoto(body.file, ::warn) { it.copy(obsolete = false) }
        is PhotoRetyped -> s = s.updatePhoto(body.file, ::warn) { it.copy(type = body.type) }
        is PhotoRedraw -> s = s.updatePhoto(body.file, ::warn) { it.copy(redraw = body.on) }
        is NoteAdded -> s = s.copy(notes = s.notes + Note(record.seq, stamp, body.text))
        is NoteRemoved -> {
            val i = s.notes.indexOfFirst { it.seq == body.note }
            if (i < 0) warn("unknown note ${body.note}")
            else s = s.copy(notes = s.notes.toMutableList().also { it[i] = it[i].copy(removed = true) })
        }
        is Marked -> s = s.copy(marks = s.marks + Mark(record.seq, stamp))
        is Unmarked -> {
            val i = s.marks.indexOfFirst { it.seq == body.mark }
            if (i < 0) warn("unknown mark ${body.mark}")
            else s = s.copy(marks = s.marks.toMutableList().also { it[i] = it[i].copy(removed = true) })
        }
        is Recovered -> s = s.copy(recoveries = s.recoveries + stamp)
        is LectureEnd -> {
            if (s.isEnded) warn("repeated lecture_end")
            s = s.copy(ended = s.ended ?: stamp, kind = body.kind ?: s.kind)
        }
    }
    return if (warnings.isEmpty()) s else s.copy(warnings = s.warnings + warnings)
}

/**
 * Whether [body] changes anything (an edit to an unknown photo, note or mark is an error).
 * Lets the UI append photo flags and withdrawals without writing no-op events.
 */
fun LectureState.changedBy(body: EventBody): Boolean {
    fun photo(file: String) = photos.firstOrNull { it.file == file } ?: error("unknown photo $file")
    return when (body) {
        is PhotoObsolete -> !photo(body.file).obsolete
        is PhotoRestored -> photo(body.file).obsolete
        is PhotoRetyped -> photo(body.file).type != body.type
        is PhotoRedraw -> photo(body.file).redraw != body.on
        is NoteRemoved -> !(notes.firstOrNull { it.seq == body.note } ?: error("unknown note ${body.note}")).removed
        is Unmarked -> !(marks.firstOrNull { it.seq == body.mark } ?: error("unknown mark ${body.mark}")).removed
        else -> true
    }
}

private inline fun LectureState.updateSegment(
    file: String,
    warn: (String) -> Unit,
    change: (AudioSegment) -> AudioSegment,
): LectureState {
    val index = segments.indexOfLast { it.file == file }
    if (index < 0) {
        warn("unknown audio file $file")
        return this
    }
    return copy(segments = segments.toMutableList().also { it[index] = change(it[index]) })
}

private inline fun LectureState.updatePhoto(
    file: String,
    warn: (String) -> Unit,
    change: (Photo) -> Photo,
): LectureState {
    val index = photos.indexOfFirst { it.file == file }
    if (index < 0) {
        warn("unknown photo $file")
        return this
    }
    return copy(photos = photos.toMutableList().also { it[index] = change(it[index]) })
}

/**
 * Edits the archive screen may append to a finished lecture: names, kind, photo marks and
 * flags, withdrawing notes and marks. Recording events (audio, photos, new notes and marks)
 * after lecture_end are still an inconsistency.
 */
val EventBody.isLaterEdit: Boolean
    get() = this is MetaChanged || this is PhotoObsolete || this is PhotoRestored || this is PhotoRetyped ||
        this is PhotoRedraw || this is NoteRemoved || this is Unmarked
