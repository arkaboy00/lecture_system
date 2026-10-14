package com.arka.lecturecapture.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.arka.lecturecapture.TAG
import java.io.File

/** Writes one audio segment to one file. Kept behind an interface so the format can change. */
interface SegmentRecorder {
    val extension: String

    /** Starts writing [file]; throws if the microphone or encoder cannot start. */
    fun start(file: File, onError: (String) -> Unit)

    /** Finalizes the file. Never throws. */
    fun stop()

    /** Peak amplitude 0..32767 since the previous call. */
    fun level(): Int
}

/** Ogg/Opus through MediaRecorder: mono 16 kHz, 32 kbit/s — enough for speech. */
class OpusRecorder(private val context: Context) : SegmentRecorder {
    override val extension = "ogg"
    private var recorder: MediaRecorder? = null

    override fun start(file: File, onError: (String) -> Unit) {
        check(recorder == null) { "already recording" }
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(file)
            r.setOnErrorListener { _, what, extra -> onError("MediaRecorder error what=$what extra=$extra") }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
    }

    override fun stop() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (e: RuntimeException) {
            // Thrown when nothing was recorded; the file is still closed by release().
            Log.w(TAG, "MediaRecorder.stop failed", e)
        } finally {
            r.release()
        }
    }

    override fun level(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (e: IllegalStateException) {
        0
    }
}
