package com.arka.lecturecapture.audio

import com.arka.lecturecapture.core.AudioSilenced
import com.arka.lecturecapture.core.AudioStart
import com.arka.lecturecapture.core.AudioStop
import com.arka.lecturecapture.core.AudioUnsilenced
import com.arka.lecturecapture.core.Lecture
import com.arka.lecturecapture.core.OggInfo
import com.arka.lecturecapture.core.StopReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Segment lifecycle: events and the recorder are changed together. Not thread-safe by design:
 * every call comes from the session's single I/O thread, the same one that writes the log.
 */
class AudioController(private val newRecorder: () -> SegmentRecorder) {
    private var recorder: SegmentRecorder? = null
    private var file: String? = null
    private var silenced = false

    private val _level = MutableStateFlow(0f)
    /** 0..1, square root of the peak so quiet speech is still visible. */
    val level: StateFlow<Float> = _level.asStateFlow()

    val isActive: Boolean get() = recorder != null

    /**
     * audio_start is written before the recorder starts: if the start fails or the process
     * dies right after, the log still knows about the file.
     */
    fun start(lecture: Lecture, onError: (String) -> Unit) {
        if (recorder != null) return
        val rec = newRecorder()
        val name = lecture.nextAudioFile(rec.extension)
        lecture.append(AudioStart(name))
        try {
            rec.start(lecture.file(name), onError)
        } catch (e: Exception) {
            lecture.append(AudioStop(name, StopReason.ERROR, durMs = 0))
            throw e
        }
        recorder = rec
        file = name
        silenced = false
    }

    fun stop(lecture: Lecture, reason: StopReason) {
        val rec = recorder ?: return
        val name = checkNotNull(file)
        recorder = null
        file = null
        _level.value = 0f
        rec.stop()
        lecture.append(AudioStop(name, reason, durMs = OggInfo.read(lecture.file(name))?.durationMs))
    }

    fun setSilenced(lecture: Lecture, value: Boolean) {
        val name = file ?: return
        if (value == silenced) return
        silenced = value
        lecture.append(if (value) AudioSilenced(name) else AudioUnsilenced(name))
    }

    fun pollLevel() {
        val peak = recorder?.level() ?: 0
        _level.value = sqrt(peak / 32767f).coerceIn(0f, 1f)
    }
}
