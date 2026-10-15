package com.arka.lecturecapture.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.platform.LocalContext
import com.arka.lecturecapture.LectureSession
import com.arka.lecturecapture.NoteDraft
import com.arka.lecturecapture.OpenLecture
import com.arka.lecturecapture.core.LectureKind
import com.arka.lecturecapture.core.LectureState
import com.arka.lecturecapture.core.NoteRemoved
import com.arka.lecturecapture.core.Photo
import com.arka.lecturecapture.core.PhotoRedraw
import com.arka.lecturecapture.core.PhotoRetyped
import com.arka.lecturecapture.core.Stamps
import com.arka.lecturecapture.core.Unmarked
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Main lecture screen: header (names, settings, menu), recording status and the audio
 * segment strip, the photo grid, and the two big buttons at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LectureScreen(
    open: OpenLecture,
    session: LectureSession,
    pendingFrames: Int,
    onPhoto: () -> Unit,
    onSortPending: () -> Unit,
    onSettings: () -> Unit,
) {
    val state = open.state
    val error by session.error.collectAsStateWithLifecycle()
    val level by session.level.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    var noteSeq by rememberSaveable { mutableStateOf<Long?>(null) }
    var markSeq by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val fileOf = { name: String -> File(open.dir, name) }
    val context = LocalContext.current
    // The note being typed lives on disk (survives a crash or rotation); reopened on return.
    var draft by remember(open.dir.name) { mutableStateOf(NoteDraft.load(context, open.dir.name)) }

    fun openNote() {
        val stamp = session.noteStamp() ?: return
        draft = NoteDraft(open.dir.name, stamp, "").also { NoteDraft.save(context, it) }
    }

    fun mark() {
        session.mark { seq ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar("★ Отмечено", actionLabel = "Отменить", duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) session.edit(Unmarked(seq))
            }
        }
    }

    /** Toggle with an undo snackbar; both directions are plain events in the log. */
    fun toggleObsolete(photo: Photo) {
        val marking = !photo.obsolete
        session.setObsolete(photo.file, marking)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = if (marking) "Фото помечено неактуальным" else "Пометка снята",
                actionLabel = "Отменить",
                // Long (~10 s): during a lecture there is little time to notice a mis-tap.
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) session.setObsolete(photo.file, !marking)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.subject, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("lecture_subject"))
                        Text(
                            state.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            modifier = Modifier.testTag("lecture_title"),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("settings_button")) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("menu_button")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Меню")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            modifier = Modifier.exposeTestTags(),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Предмет и название") },
                                onClick = { menuOpen = false; renaming = true },
                                modifier = Modifier.testTag("menu_rename"),
                            )
                            DropdownMenuItem(
                                text = { Text("Сохранить и выйти") },
                                onClick = { menuOpen = false; confirmFinish = true },
                                modifier = Modifier.testTag("menu_save_exit"),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomButtons(
                recording = state.recording != null,
                onAudio = { if (state.recording != null) confirmStop = true else session.startRecording() },
                onPhoto = onPhoto,
                onMark = ::mark,
                onNote = ::openNote,
            )
        },
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    action = {
                        TextButton(onClick = { data.performAction() }, modifier = Modifier.testTag("snackbar_undo")) {
                            Text(data.visuals.actionLabel ?: "")
                        }
                    },
                    modifier = Modifier.padding(8.dp).testTag("snackbar"),
                ) { Text(data.visuals.message) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Box(Modifier.padding(8.dp)) { ErrorBanner(it, session::clearError) } }
            RecordingStatus(state, level)
            SegmentStrip(state)
            if (pendingFrames > 0) PendingBanner(pendingFrames, onSortPending)
            PhotoGrid(
                items = gridItems(state),
                fileOf = fileOf,
                onToggleObsolete = ::toggleObsolete,
                onOpen = { viewing = it.file },
                onNote = { noteSeq = it.seq },
                onMark = { markSeq = it.seq },
                modifier = Modifier.weight(1f),
            )
        }
    }

    state.photos.firstOrNull { it.file == viewing }?.let { photo ->
        PhotoViewer(
            photo,
            fileOf(photo.file),
            onToggleObsolete = { toggleObsolete(photo) },
            onClose = { viewing = null },
            onType = { session.edit(PhotoRetyped(photo.file, it)) },
            onRedraw = { session.edit(PhotoRedraw(photo.file, it)) },
        )
    }
    draft?.let { d ->
        NoteDialog(
            text = d.text,
            openedAt = Stamps.zoned(d.opened.ts).format(NOTE_CLOCK),
            onText = { text -> draft = d.copy(text = text).also { NoteDraft.save(context, it) } },
            onSave = {
                session.addNote(d.text, d.opened)
                NoteDraft.clear(context)
                draft = null
            },
            onCancel = { NoteDraft.clear(context); draft = null },
        )
    }
    state.notes.firstOrNull { it.seq == noteSeq && !it.removed }?.let { note ->
        NoteDetailsDialog(note, onRemove = { noteSeq = null; session.edit(NoteRemoved(note.seq)) }, onDismiss = { noteSeq = null })
    }
    state.marks.firstOrNull { it.seq == markSeq && !it.removed }?.let { mark ->
        MarkDetailsDialog(mark, onRemove = { markSeq = null; session.edit(Unmarked(mark.seq)) }, onDismiss = { markSeq = null })
    }
    if (confirmStop) {
        StopRecordingDialog(
            onConfirm = { confirmStop = false; session.stopRecording() },
            onDismiss = { confirmStop = false },
        )
    }
    if (confirmFinish) {
        FinishDialog(
            initialKind = state.kind ?: open.meta.kind,
            onConfirm = { kind -> confirmFinish = false; session.finish(kind) },
            onDismiss = { confirmFinish = false },
        )
    }
    if (renaming) {
        RenameDialog(
            subject = state.subject,
            title = state.title,
            onConfirm = { s, t -> renaming = false; session.rename(s, t) },
            onDismiss = { renaming = false },
        )
    }
}

/** Under the header: recording state, segment timer, microphone level, silencing. */
@Composable
private fun RecordingStatus(state: LectureState, level: Float) {
    val segment = state.recording
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (segment != null) {
        LaunchedEffect(segment.file) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(500)
            }
        }
    }
    // The ticking timer and level meter have fixed semantics: changing semantics every second
    // floods accessibility events and `uiautomator dump` never sees an idle screen.
    val recordingDescription = if (segment != null) "Идёт запись ${segment.file}" else "Запись не идёт"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (segment != null) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (segment != null) "Запись ${formatDuration(now - segment.start.mono)}" else "Запись не идёт",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clearAndSetSemantics {
                    testTag = "recording_timer"
                    contentDescription = recordingDescription
                },
            )
            Spacer(Modifier.weight(1f))
            Text(
                buildString {
                    append("фото ${state.photos.size}")
                    val obsolete = state.photos.count { it.obsolete }
                    if (obsolete > 0) append(" (неакт. $obsolete)")
                    if (state.warnings.isNotEmpty()) append(" · ⚠${state.warnings.size}")
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("status_line"),
            )
        }
        if (segment != null) {
            LinearProgressIndicator(
                progress = { level },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp)
                    .clearAndSetSemantics { testTag = "level_meter" },
                drawStopIndicator = {},
            )
        }
        if (state.isSilenced) {
            Text(
                "Микрофон заглушён системой (звонок или другое приложение)",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp).testTag("silenced_warning"),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun PendingBanner(count: Int, onSort: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("pending_banner")) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Неразобранные кадры: $count", Modifier.weight(1f))
            TextButton(onClick = onSort, modifier = Modifier.testTag("pending_sort")) { Text("Разобрать") }
        }
    }
}

private val NOTE_CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * Audio and photo stay the two big buttons; between them two small ones: the "important"
 * star (one tap, undo in the snackbar) and a text note.
 */
@Composable
private fun BottomButtons(recording: Boolean, onAudio: () -> Unit, onPhoto: () -> Unit, onMark: () -> Unit, onNote: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onAudio,
            colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors(),
            modifier = Modifier.weight(1f).height(72.dp).testTag("audio_button"),
        ) {
            Text(if (recording) "Стоп" else "Запись", style = MaterialTheme.typography.titleMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(onClick = onMark, modifier = Modifier.size(48.dp, 34.dp).testTag("mark_button")) {
                Icon(Icons.Filled.Star, contentDescription = "Важный момент", tint = MARK_COLOR)
            }
            FilledTonalIconButton(onClick = onNote, modifier = Modifier.size(48.dp, 34.dp).testTag("note_button")) {
                Icon(Icons.Filled.Edit, contentDescription = "Заметка")
            }
        }
        Button(onClick = onPhoto, modifier = Modifier.weight(1f).height(72.dp).testTag("photo_button")) {
            Text("Фото", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Opened from the bottom-left button; its confirm button is in the middle of the screen,
 * so a double tap on "Стоп" only opens the dialog.
 */
@Composable
private fun StopRecordingDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text("Остановить запись?") },
        text = { Text("Текущий сегмент закроется. Следующая запись пойдёт новым файлом.") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm_stop")) { Text("Остановить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_stop")) { Text("Отмена") }
        },
    )
}

/**
 * Opened from the top-right menu; its confirm button sits in the middle of the screen,
 * so a double tap on the menu cannot finish the lecture by accident.
 */
@Composable
private fun FinishDialog(initialKind: LectureKind, onConfirm: (LectureKind) -> Unit, onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text("Сохранить и выйти?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Запись остановится, лекция будет завершена.")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LectureKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = kind == k,
                            onClick = { kind = k },
                            shape = SegmentedButtonDefaults.itemShape(i, LectureKind.entries.size),
                            modifier = Modifier.testTag("kind_${k.name.lowercase()}"),
                        ) { Text(if (k == LectureKind.LECTURE) "Лекция" else "Семинар") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(kind) }, modifier = Modifier.testTag("confirm_finish")) { Text("Сохранить и выйти") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_finish")) { Text("Отмена") }
        },
    )
}

/**
 * Names can change mid-lecture; the folder itself is renamed only at "Save and exit".
 * The archive uses the same dialog for finished lectures.
 */
@Composable
internal fun RenameDialog(subject: String, title: String, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var newSubject by rememberSaveable { mutableStateOf(subject) }
    var newTitle by rememberSaveable { mutableStateOf(title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text("Предмет и название") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newSubject,
                    onValueChange = { newSubject = it },
                    label = { Text("Предмет") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_subject"),
                )
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_title"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newSubject, newTitle) },
                enabled = newSubject.isNotBlank() && newTitle.isNotBlank(),
                modifier = Modifier.testTag("confirm_rename"),
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_rename")) { Text("Отмена") }
        },
    )
}
