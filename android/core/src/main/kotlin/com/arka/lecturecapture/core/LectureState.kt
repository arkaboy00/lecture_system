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
}

data class Photo(
    val file: String,
    val type: PhotoType,
    val batch: Int,
    val taken: Stamp,
    val obsolete: Boolean = false,
)

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
    val segments: List<AudioSegment> = emptyList(),
    /** Chronological (see [chronoKey]), not in file order. */
    val photos: List<Photo> = emptyList(),
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
    if (s.isEnded && record.body !is LectureEnd) warn("event after lecture_end")

    when (val body = record.body) {
        is LectureStart -> {
            if (s.started != null) warn("repeated lecture_start")
            s = s.copy(started = s.started ?: stamp, subject = body.subject, title = body.title)
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
        is Recovered -> s = s.copy(recoveries = s.recoveries + stamp)
        is LectureEnd -> {
            if (s.isEnded) warn("repeated lecture_end")
            s = s.copy(ended = s.ended ?: stamp, kind = body.kind ?: s.kind)
        }
    }
    return if (warnings.isEmpty()) s else s.copy(warnings = s.warnings + warnings)
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
