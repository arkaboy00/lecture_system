package com.arka.lecturecapture.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Version of the lecture folder format (events.jsonl + lecture.json). */
const val FORMAT_VERSION = 1

/**
 * When an event happened.
 *
 * [ts] is wall-clock ISO-8601 with offset, [mono] is SystemClock.elapsedRealtime() in ms,
 * [boot] is Settings.Global.BOOT_COUNT. Two [mono] values are comparable only when their
 * [boot] values are equal; otherwise fall back to [ts].
 */
data class Stamp(val ts: String, val mono: Long, val boot: Int?)

/** One line of events.jsonl: common envelope fields plus the event-specific body. */
data class Record(val seq: Long, val stamp: Stamp, val body: EventBody)

@Serializable
enum class PhotoType {
    @SerialName("board") BOARD,
    @SerialName("notebook") NOTEBOOK,
}

@Serializable
enum class StopReason {
    @SerialName("user") USER,
    @SerialName("error") ERROR,
    @SerialName("recovered") RECOVERED,
}

@Serializable
enum class LectureKind {
    @SerialName("lecture") LECTURE,
    @SerialName("seminar") SEMINAR,
}

/** Event-specific payload. The serial name becomes the "e" field of the line. */
@Serializable
sealed interface EventBody

@Serializable
@SerialName("lecture_start")
data class LectureStart(
    val v: Int = FORMAT_VERSION,
    val subject: String,
    val title: String,
    val device: String? = null,
    val app: String? = null,
) : EventBody

/** Subject/title/kind changed during the lecture. Null fields are unchanged. */
@Serializable
@SerialName("meta")
data class MetaChanged(
    val subject: String? = null,
    val title: String? = null,
    val kind: LectureKind? = null,
) : EventBody

@Serializable
@SerialName("audio_start")
data class AudioStart(val file: String) : EventBody

@Serializable
@SerialName("audio_stop")
data class AudioStop(
    val file: String,
    val reason: StopReason,
    /** Duration measured from the file itself, when known (always set for recovered segments). */
    @SerialName("dur_ms") val durMs: Long? = null,
) : EventBody

@Serializable
@SerialName("audio_silenced")
data class AudioSilenced(val file: String) : EventBody

@Serializable
@SerialName("audio_unsilenced")
data class AudioUnsilenced(val file: String) : EventBody

/** Stamp of a photo event is the shutter moment, not the moment the batch was saved. */
@Serializable
@SerialName("photo")
data class PhotoTaken(val file: String, val type: PhotoType, val batch: Int) : EventBody

@Serializable
@SerialName("obsolete")
data class PhotoObsolete(val file: String) : EventBody

@Serializable
@SerialName("restored")
data class PhotoRestored(val file: String) : EventBody

@Serializable
@SerialName("recovered")
data class Recovered(val note: String? = null) : EventBody

@Serializable
@SerialName("lecture_end")
data class LectureEnd(val kind: LectureKind? = null) : EventBody
