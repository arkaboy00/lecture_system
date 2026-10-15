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
import com.arka.lecturecapture.core.EventReader
import com.arka.lecturecapture.core.MetaFile
import com.arka.lecturecapture.core.Naming
import com.arka.lecturecapture.core.sync.SyncMark
import com.arka.lecturecapture.sync.PrefsSyncStore
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
import com.arka.lecturecapture.core.Marked
import com.arka.lecturecapture.core.NoteAdded
import com.arka.lecturecapture.core.Recovered
import com.arka.lecturecapture.core.changedBy
import com.arka.lecturecapture.core.cleanupLeftovers
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Snapshot of the open lecture for the UI. */
data class OpenLecture(val dir: File, val meta: LectureMeta, val state: LectureState)

/** A lecture that is not open, read for the archive screen. */
data class ArchivedLecture(val dir: File, val meta: LectureMeta?, val state: LectureState, val badLines: Int, val sync: SyncMark)

/** A frame CameraX is writing; [generation] ties it to the batch it was shot for. */
data class FramePart(val file: File, val generation: Int)

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
    private val syncStore by lazy { PrefsSyncStore(app) }

    // Written on the I/O thread, read on the main thread at the shutter press.
    @Volatile private var lecture: Lecture? = null
    private val audio = AudioController { OpusRecorder(app) }

    private val _pending = MutableStateFlow<List<PendingFrame>>(emptyList())
    /** Frames of the current, not yet sorted batch (on disk in `.pending/`). */
    val pending: StateFlow<List<PendingFrame>> = _pending.asStateFlow()

    /** Bumped when a batch is cancelled, so frames still being written for it are dropped. */
    @Volatile private var batchGeneration = 0
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
        store.ensureRoot()
        _lectures.value = store.list()
    }

    /** Starts the next part of a finished lecture (same subject, "ч.N" title); see [LectureStore.createNextPart]. */
    fun startNextPart(previous: File) = io("next part") {
        check(lecture == null) { "another lecture is open" }
        val created = store.createNextPart(previous, device = Build.MODEL, app = BuildConfig.VERSION_NAME)
        Log.i(TAG, "lecture created (part ${created.state.part}): ${created.dir}")
        publish(created)
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
        syncStore.onFolderDeleted(dir.name)
        Log.i(TAG, "lecture deleted: $dir")
        _lectures.value = store.list()
    }

    // --- Archive (lectures that are not open) ------------------------------------------------

    /** Reads a lecture that is not open, on the I/O thread so it never races an edit. */
    suspend fun readArchived(dir: File): ArchivedLecture? = withContext(io) {
        if (!File(dir, Naming.EVENTS).isFile) return@withContext null
        val read = EventReader.read(File(dir, Naming.EVENTS))
        ArchivedLecture(dir, MetaFile.read(dir), LectureState.replay(read.records), read.problems.size, syncStore.mark(dir))
    }

    /** Sync marks of the listed lectures (walks the folders, so not on the main thread). */
    suspend fun syncMarks(dirs: List<File>): Map<String, SyncMark> = withContext(io) {
        dirs.associate { it.name to syncStore.mark(it) }
    }

    /** Names and kind of a finished lecture; [onDone] gets the folder, renamed if names changed. */
    fun amendArchived(dir: File, subject: String, title: String, kind: LectureKind, onDone: (File) -> Unit) = io("edit") {
        check(lecture?.dir != dir) { "the lecture is open" }
        val moved = store.amend(dir, subject.trim().ifEmpty { null }, title.trim().ifEmpty { null }, kind)
        syncStore.onFolderRenamed(dir.name, moved.name)
        Log.i(TAG, "lecture edited: ${moved.name}")
        _lectures.value = store.list()
        mainHandler.post { onDone(moved) }
    }

    fun setArchivedObsolete(dir: File, file: String, obsolete: Boolean, onDone: () -> Unit) = io("obsolete") {
        check(lecture?.dir != dir) { "the lecture is open" }
        store.setObsolete(dir, file, obsolete)
        mainHandler.post(onDone)
    }

    /** A later edit of a finished lecture (photo type, redraw flag, withdrawn note or mark). */
    fun editArchived(dir: File, body: EventBody, onDone: () -> Unit) = io("edit") {
        check(lecture?.dir != dir) { "the lecture is open" }
        store.edit(dir, body)
        mainHandler.post(onDone)
    }

    /** Erases a lecture from the archive (after the double confirmation in the UI). */
    fun deleteArchived(dir: File, onDone: () -> Unit) = io("delete") {
        check(lecture?.dir != dir) { "the lecture is open" }
        val finished = LectureState.replay(EventReader.read(File(dir, Naming.EVENTS)).records).isEnded
        store.delete(dir, finished = finished)
        syncStore.onFolderDeleted(dir.name)
        Log.i(TAG, "lecture deleted from the archive: $dir")
        _lectures.value = store.list()
        mainHandler.post(onDone)
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

    /** Photo type, redraw flag, withdrawn note or mark of the open lecture; no-op edits are not written. */
    fun edit(body: EventBody) = io("edit") {
        val open = checkNotNull(lecture) { "no open lecture" }
        if (!open.state.changedBy(body)) return@io
        open.append(body)
        publish(open)
    }

    /** The "important" button. [onMarked] (main thread) gets the mark's seq, for undo. */
    fun mark(onMarked: (Long) -> Unit) = io("mark") {
        val open = checkNotNull(lecture) { "no open lecture" }
        open.append(Marked)
        val seq = open.state.marks.last().seq
        Log.i(TAG, "mark $seq")
        publish(open)
        mainHandler.post { onMarked(seq) }
    }

    /** Stamp for a note whose field is opening now (read on the main thread). */
    fun noteStamp(): Stamp? = lecture?.now()

    /** Writes a note with the stamp taken when its field was opened. */
    fun addNote(text: String, opened: Stamp) = io("note") {
        val open = checkNotNull(lecture) { "no open lecture" }
        open.append(NoteAdded(text.trim()), opened)
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
        val before = open.dir.name
        val dir = open.finish(kind)
        syncStore.onFolderRenamed(before, dir.name)
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
    fun newFramePart(): FramePart? {
        val open = lecture ?: return null
        val file = open.pendingPartFile(open.shutterNow()).also { it.parentFile?.mkdirs() }
        return FramePart(file, batchGeneration)
    }

    fun onFrameWritten(part: FramePart) = io("frame") {
        // The batch was cancelled while CameraX was still writing this frame: drop it.
        if (part.generation != batchGeneration) {
            part.file.delete()
            return@io
        }
        check(part.file.renameTo(pendingFile(part.file))) { "cannot rename ${part.file.name}" }
        refreshPendingNow()
    }

    fun refreshPending() = io("pending") { refreshPendingNow() }

    /** [typeOf]: per-frame type changed in the full-screen view; [redraw]: frames with the redraw flag. */
    fun savePhotos(
        keepNames: Set<String>,
        type: PhotoType,
        typeOf: Map<String, PhotoType> = emptyMap(),
        redraw: Set<String> = emptySet(),
    ) = io("save photos") {
        val open = checkNotNull(lecture) { "no open lecture" }
        val frames = open.pendingFrames()
        val saved = open.savePhotos(
            keep = frames.filter { it.name in keepNames },
            type = type,
            discard = frames.filterNot { it.name in keepNames },
            typeOf = typeOf,
            redraw = redraw,
        )
        Log.i(TAG, "saved batch ${open.state.photos.lastOrNull()?.batch}: $saved")
        publish(open)
    }

    fun cancelBatch() = io("cancel batch") {
        val open = checkNotNull(lecture) { "no open lecture" }
        batchGeneration++
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
        opened.cleanupLeftovers().takeIf { it.isNotEmpty() }?.let { Log.i(TAG, "removed leftovers: $it") }
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
