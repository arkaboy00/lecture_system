package com.arka.lecturecapture

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.arka.lecturecapture.audio.AudioController
import com.arka.lecturecapture.audio.OpusRecorder
import com.arka.lecturecapture.audio.RecordingService
import com.arka.lecturecapture.core.EventBody
import com.arka.lecturecapture.core.Lecture
import com.arka.lecturecapture.core.LectureKind
import com.arka.lecturecapture.core.LectureMeta
import com.arka.lecturecapture.core.LectureState
import com.arka.lecturecapture.core.LectureStore
import com.arka.lecturecapture.core.LectureSummary
import com.arka.lecturecapture.core.PendingFrame
import com.arka.lecturecapture.core.PhotoObsolete
import com.arka.lecturecapture.core.PhotoRestored
import com.arka.lecturecapture.core.PhotoType
import com.arka.lecturecapture.core.Recovered
import com.arka.lecturecapture.core.completePendingMoves
import com.arka.lecturecapture.core.discardPending
import com.arka.lecturecapture.core.pendingFile
import com.arka.lecturecapture.core.pendingFrames
import com.arka.lecturecapture.core.pendingPartFile
import com.arka.lecturecapture.core.savePhotos
import com.arka.lecturecapture.core.Stamp
import com.arka.lecturecapture.core.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/** Snapshot of the open lecture for the UI. */
data class OpenLecture(val dir: File, val meta: LectureMeta, val state: LectureState)

/**
 * Process-wide owner of the open [Lecture] and of the audio recorder. Everything that writes
 * events (UI actions, the recording service, system callbacks) runs on one background thread,
 * so the log is appended in order and the recorder is never touched concurrently.
 */
class LectureSession(context: Context) {
    private val app = context.applicationContext
    private val clock = AndroidClock(app)
    private val store by lazy { LectureStore(Permissions.lecturesRoot, clock) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lecture-io") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Written on the I/O thread, read on the main thread at the shutter press.
    @Volatile private var lecture: Lecture? = null
    private val audio = AudioController { OpusRecorder(app) }

    private val _pending = MutableStateFlow<List<PendingFrame>>(emptyList())
    /** Frames of the current, not yet sorted batch (on disk in `.pending/`). */
    val pending: StateFlow<List<PendingFrame>> = _pending.asStateFlow()
    private var levelJob: Job? = null

    private val _current = MutableStateFlow<OpenLecture?>(null)
    val current: StateFlow<OpenLecture?> = _current.asStateFlow()

    private val _lectures = MutableStateFlow<List<LectureSummary>>(emptyList())
    val lectures: StateFlow<List<LectureSummary>> = _lectures.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Microphone level 0..1 while recording. */
    val level: StateFlow<Float> = audio.level

    /** Reports silencing of our own recording (calls, other apps). Since Android 10 only our configs are visible. */
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val silenced = configs.any { it.isClientSilenced }
            io("silence") { updateSilenced(silenced) }
        }
    }

    fun clearError() { _error.value = null }

    fun refresh() = io("scan") {
        _lectures.value = store.list()
    }

    fun start(subject: String, title: String) = io("start") {
        check(lecture == null) { "another lecture is open" }
        val created = store.create(subject, title, device = Build.MODEL, app = BuildConfig.VERSION_NAME)
        Log.i(TAG, "lecture created: ${created.dir}")
        publish(created)
    }

    /** Reopens an unfinished lecture after the process that wrote it has died. */
    fun resume(dir: File) = io("resume") {
        check(lecture == null) { "another lecture is open" }
        openForRecovery(dir, "reopened after the app process ended")
        Notifications.clearInterrupted(app)
    }

    /** Erases an unfinished lecture the user started by mistake (after confirmation in the UI). */
    fun deleteLecture(dir: File) = io("delete") {
        check(lecture?.dir != dir) { "the lecture is open" }
        store.delete(dir)
        Log.i(TAG, "lecture deleted: $dir")
        _lectures.value = store.list()
    }

    /** Only changed names are written (a `meta` event and lecture.json). */
    fun rename(subject: String, title: String) = io("rename") {
        val open = checkNotNull(lecture) { "no open lecture" }
        val newSubject = subject.trim().takeIf { it.isNotEmpty() && it != open.state.subject }
        val newTitle = title.trim().takeIf { it.isNotEmpty() && it != open.state.title }
        if (newSubject == null && newTitle == null) return@io
        open.rename(newSubject, newTitle)
        publish(open)
    }

    /** Marks a photo obsolete or restores it; the file itself is never touched. */
    fun setObsolete(file: String, obsolete: Boolean) = io("obsolete") {
        val open = checkNotNull(lecture) { "no open lecture" }
        val photo = open.state.photos.firstOrNull { it.file == file } ?: error("unknown photo $file")
        if (photo.obsolete == obsolete) return@io
        open.append(if (obsolete) PhotoObsolete(file) else PhotoRestored(file))
        publish(open)
    }

    fun append(body: EventBody, stamp: Stamp? = null) = io("append") {
        val open = checkNotNull(lecture) { "no open lecture" }
        if (stamp == null) open.append(body) else open.append(body, stamp)
        publish(open)
    }

    fun finish(kind: LectureKind) = io("finish") {
        val open = checkNotNull(lecture) { "no open lecture" }
        stopRecordingNow(StopReason.USER)
        val dir = open.finish(kind)
        Log.i(TAG, "lecture finished: $dir")
        lecture = null
        _current.value = null
        _pending.value = emptyList()
        _lectures.value = store.list()
    }

    // --- Photos ------------------------------------------------------------------------------

    /**
     * Called on the main thread at the shutter press: the returned `.part` file name already
     * carries the shutter moment. Rename it with [onFrameWritten] when CameraX is done.
     */
    fun newFramePart(): File? {
        val open = lecture ?: return null
        return open.pendingPartFile(open.shutterNow()).also { it.parentFile?.mkdirs() }
    }

    fun onFrameWritten(part: File) = io("frame") {
        check(part.renameTo(pendingFile(part))) { "cannot rename ${part.name}" }
        refreshPendingNow()
    }

    fun refreshPending() = io("pending") { refreshPendingNow() }

    fun savePhotos(keepNames: Set<String>, type: PhotoType) = io("save photos") {
        val open = checkNotNull(lecture) { "no open lecture" }
        val frames = open.pendingFrames()
        val saved = open.savePhotos(
            keep = frames.filter { it.name in keepNames },
            type = type,
            discard = frames.filterNot { it.name in keepNames },
        )
        Log.i(TAG, "saved batch ${open.state.photos.lastOrNull()?.batch}: $saved")
        publish(open)
    }

    fun cancelBatch() = io("cancel batch") {
        val open = checkNotNull(lecture) { "no open lecture" }
        open.discardPending()
        Log.i(TAG, "batch cancelled")
        refreshPendingNow()
    }

    fun reportError(message: String) {
        Log.e(TAG, message)
        _error.value = message
    }

    private fun refreshPendingNow() {
        _pending.value = lecture?.pendingFrames() ?: emptyList()
    }

    // --- Audio -------------------------------------------------------------------------------

    /** UI entry point: the service must be started while the activity is visible. */
    fun startRecording() {
        if (lecture == null) return
        RecordingService.start(app)
    }

    fun stopRecording() = io("audio stop") { stopRecordingNow(StopReason.USER) }

    /** Called by the service once it is in the foreground. */
    internal fun beginRecording() = io("audio start") {
        val open = lecture
        if (open == null) {
            RecordingService.stop(app)
            error("no open lecture")
        }
        beginRecordingNow(open)
    }

    internal fun abortRecordingStart(reason: String) = io("audio start") {
        RecordingService.stop(app)
        error(reason)
    }

    /**
     * START_STICKY restart: the process was killed while recording. Reopen the lecture whose
     * last segment has no audio_stop, close that segment and try to continue. Whether the
     * microphone works for a service restarted from the background is exactly what stage 1
     * checks; if it does not, the user gets a loud notification instead.
     */
    internal fun recoverKilledRecording(foreground: Boolean) = io("sticky recovery") {
        if (lecture != null) return@io
        val candidate = store.list().firstOrNull { !it.ended && it.recording }
        if (candidate == null) {
            Log.i(TAG, "sticky restart: nothing was recording")
            RecordingService.stop(app)
            return@io
        }
        val open = openForRecovery(candidate.dir, "process killed while recording; service restarted by the system")
        if (!foreground) {
            interrupted("Система не разрешила продолжить запись в фоне")
            RecordingService.stop(app)
            return@io
        }
        try {
            beginRecordingNow(open)
        } catch (e: Exception) {
            interrupted("Не удалось снова включить микрофон: ${e.message}")
            RecordingService.stop(app)
            throw e
        }
        // A background-restarted service may get a silenced microphone; give the callback time.
        delay(3_000)
        if (audio.isActive && open.state.isSilenced) {
            Log.w(TAG, "sticky recovery: microphone is silenced, giving up")
            stopRecordingNow(StopReason.ERROR)
            interrupted("Система заглушила микрофон после перезапуска")
        } else {
            Log.i(TAG, "sticky recovery: recording continues")
        }
    }

    /** The service is going away; if we are still recording, the system stopped it. */
    internal fun onServiceDestroyed() = io("service destroyed") {
        if (audio.isActive) {
            Log.w(TAG, "service destroyed while recording")
            stopRecordingNow(StopReason.ERROR)
            interrupted("Служба записи была остановлена системой")
        }
    }

    private fun beginRecordingNow(open: Lecture) {
        if (audio.isActive) return
        try {
            audio.start(open) { message -> io("recorder error") { onRecorderError(message) } }
        } catch (e: Exception) {
            publish(open)
            RecordingService.stop(app)
            throw e
        }
        audioManager.registerAudioRecordingCallback(recordingCallback, mainHandler)
        updateSilenced(audioManager.activeRecordingConfigurations.any { it.isClientSilenced })
        levelJob = scope.launch {
            while (isActive) {
                audio.pollLevel()
                delay(150)
            }
        }
        Log.i(TAG, "recording ${open.state.recording?.file}")
        publish(open)
    }

    private fun stopRecordingNow(reason: StopReason) {
        val open = lecture ?: return
        if (!audio.isActive) return
        levelJob?.cancel()
        levelJob = null
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        val file = open.state.recording?.file
        audio.stop(open, reason)
        Log.i(TAG, "stopped $file reason=$reason dur=${open.state.segments.lastOrNull()?.durMs}")
        publish(open)
        RecordingService.stop(app)
    }

    private fun updateSilenced(silenced: Boolean) {
        val open = lecture ?: return
        if (!audio.isActive) return
        val before = open.state.lastSeq
        audio.setSilenced(open, silenced)
        if (open.state.lastSeq != before) {
            Log.i(TAG, "microphone silenced=$silenced")
            publish(open)
        }
    }

    private fun onRecorderError(message: String) {
        Log.e(TAG, message)
        stopRecordingNow(StopReason.ERROR)
        interrupted(message)
        _error.value = "Запись остановлена: $message"
    }

    private fun openForRecovery(dir: File, note: String): Lecture {
        val opened = store.open(dir)
        if (opened.readProblems.isNotEmpty()) Log.w(TAG, "bad lines in ${dir.name}: ${opened.readProblems}")
        lecture = opened
        opened.append(Recovered(note))
        opened.closeDanglingAudio()?.let { Log.i(TAG, "closed dangling ${it.file}, dur=${it.durMs}") }
        opened.completePendingMoves().takeIf { it.isNotEmpty() }?.let { Log.i(TAG, "completed photo moves: $it") }
        Log.i(TAG, "lecture reopened: ${opened.dir}")
        publish(opened)
        return opened
    }

    private fun interrupted(reason: String) {
        Log.w(TAG, "recording interrupted: $reason")
        Notifications.interrupted(app, reason)
    }

    private fun publish(open: Lecture) {
        lecture = open
        _current.value = OpenLecture(open.dir, open.meta, open.state)
        refreshPendingNow()
    }

    private fun io(what: String, block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "$what failed", e)
            _error.value = "$what: ${e.message ?: e::class.simpleName}"
        }
    }
}
