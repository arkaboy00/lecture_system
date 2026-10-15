package com.arka.lecturecapture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arka.lecturecapture.ArchivedLecture
import com.arka.lecturecapture.LectureSession
import com.arka.lecturecapture.R
import com.arka.lecturecapture.core.EventBody
import com.arka.lecturecapture.core.LectureKind
import com.arka.lecturecapture.core.NoteRemoved
import com.arka.lecturecapture.core.PhotoRedraw
import com.arka.lecturecapture.core.PhotoRetyped
import com.arka.lecturecapture.core.Unmarked
import com.arka.lecturecapture.core.Photo
import com.arka.lecturecapture.core.Stamps
import com.arka.lecturecapture.core.sync.SyncMark
import kotlinx.coroutines.launch
import java.io.File
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/**
 * One lecture from the archive: photos, audio segments, names and kind. A finished
 * lecture can be edited (the changes are events appended to its log, like during the
 * lecture); an unfinished one can only be viewed or continued. Deleting asks twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedLectureScreen(session: LectureSession, dir: File, onMoved: (File) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val error by session.error.collectAsStateWithLifecycle()
    var version by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<ArchivedLecture?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(dir, version) {
        val read = session.readArchived(dir)
        if (read == null) missing = true else data = read
    }
    if (missing) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleteStep by rememberSaveable { mutableIntStateOf(0) }
    var showFolder by remember { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    var noteSeq by rememberSaveable { mutableStateOf<Long?>(null) }
    var markSeq by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val reload: () -> Unit = { version++ }
    val fileOf = { name: String -> File(dir, name) }

    val lecture = data
    val state = lecture?.state
    val ended = state?.isEnded == true

    fun toggleObsolete(photo: Photo) {
        if (!ended) {
            scope.launch { snackbar.showSnackbar("Отметки меняются в незавершённой лекции после «Продолжить запись»") }
            return
        }
        session.setArchivedObsolete(dir, photo.file, !photo.obsolete, onDone = reload)
    }

    /** Later edits are possible only once the lecture is finished (else: "Продолжить запись"). */
    val edit: ((EventBody) -> Unit)? = if (ended) { body -> session.editArchived(dir, body, onDone = reload) } else null

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("lecture_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Column {
                        Text(state?.subject ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("lecture_subject"))
                        Text(state?.title ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.testTag("lecture_title"))
                    }
                },
                actions = {
                    IconButton(onClick = { showFolder = true }, modifier = Modifier.testTag("lecture_folder")) {
                        Icon(painterResource(R.drawable.ic_folder), contentDescription = "Папка лекции")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("menu_button")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Меню")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.exposeTestTags()) {
                            if (ended) {
                                DropdownMenuItem(
                                    text = { Text("Предмет и название") },
                                    onClick = { menuOpen = false; renaming = true },
                                    modifier = Modifier.testTag("menu_rename"),
                                )
                                DropdownMenuItem(
                                    text = { Text("Начать следующую часть") },
                                    onClick = { menuOpen = false; session.startNextPart(dir) },
                                    modifier = Modifier.testTag("menu_next_part"),
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Удалить лекцию", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; deleteStep = 1 },
                                modifier = Modifier.testTag("menu_delete"),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, modifier = Modifier.padding(8.dp).testTag("snackbar")) } },
    ) { padding ->
        if (lecture == null || state == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Box(Modifier.padding(8.dp)) { ErrorBanner(it, session::clearError) } }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.started?.let { Stamps.zoned(it.ts).format(DATE) } ?: "?",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    SyncBadge(lecture.sync, Modifier.testTag("lecture_sync"))
                }
                if (ended) {
                    val kind = state.kind ?: lecture.meta?.kind ?: LectureKind.LECTURE
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        LectureKind.entries.forEachIndexed { i, k ->
                            SegmentedButton(
                                selected = kind == k,
                                onClick = { if (k != kind) session.amendArchived(dir, state.subject, state.title, k, onDone = { onMoved(it); reload() }) },
                                shape = SegmentedButtonDefaults.itemShape(i, LectureKind.entries.size),
                                modifier = Modifier.testTag("kind_${k.name.lowercase()}"),
                            ) { Text(if (k == LectureKind.LECTURE) "Лекция" else "Семинар") }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Лекция не завершена",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { session.resume(dir) }, modifier = Modifier.testTag("lecture_resume")) { Text("Продолжить запись") }
                    }
                }
                val warnings = lecture.badLines + state.warnings.size
                if (warnings > 0) {
                    Text("⚠ Неполадок в журнале: $warnings", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.segments.isNotEmpty()) {
                HorizontalDivider()
                // Times and breaks only: the audio is listened to and processed on the laptop.
                SegmentStrip(state)
            } else {
                HorizontalDivider()
            }
            PhotoGrid(
                items = gridItems(state),
                fileOf = fileOf,
                onToggleObsolete = ::toggleObsolete,
                onOpen = { viewing = it.file },
                onNote = { noteSeq = it.seq },
                onMark = { markSeq = it.seq },
                followNewest = false,
                modifier = Modifier.weight(1f),
            )
        }
    }

    state?.photos?.firstOrNull { it.file == viewing }?.let { photo ->
        PhotoViewer(
            photo,
            fileOf(photo.file),
            onToggleObsolete = { toggleObsolete(photo) },
            onClose = { viewing = null },
            onType = edit?.let { e -> { t -> e(PhotoRetyped(photo.file, t)) } },
            onRedraw = edit?.let { e -> { on -> e(PhotoRedraw(photo.file, on)) } },
        )
    }
    state?.notes?.firstOrNull { it.seq == noteSeq && !it.removed }?.let { note ->
        NoteDetailsDialog(note, onRemove = edit?.let { e -> { noteSeq = null; e(NoteRemoved(note.seq)) } }, onDismiss = { noteSeq = null })
    }
    state?.marks?.firstOrNull { it.seq == markSeq && !it.removed }?.let { mark ->
        MarkDetailsDialog(mark, onRemove = edit?.let { e -> { markSeq = null; e(Unmarked(mark.seq)) } }, onDismiss = { markSeq = null })
    }
    if (showFolder) FolderDialog(dir, onDismiss = { showFolder = false })
    if (renaming && state != null) {
        RenameDialog(
            subject = state.subject,
            title = state.title,
            onConfirm = { s, t ->
                renaming = false
                session.amendArchived(dir, s, t, state.kind ?: lecture.meta?.kind ?: LectureKind.LECTURE, onDone = { onMoved(it); reload() })
            },
            onDismiss = { renaming = false },
        )
    }
    if (deleteStep == 1 && lecture != null) {
        DeleteFirstDialog(lecture, onNext = { deleteStep = 2 }, onDismiss = { deleteStep = 0 })
    }
    if (deleteStep == 2 && lecture != null) {
        DeleteSecondDialog(
            lecture.sync,
            onConfirm = { deleteStep = 0; session.deleteArchived(dir, onDone = onBack) },
            onDismiss = { deleteStep = 0 },
        )
    }
}

private fun syncWarning(mark: SyncMark): String = when (mark) {
    SyncMark.SYNCED -> "✓ Лекция целиком есть на ноутбуке."
    SyncMark.CHANGED -> "⚠ Лекция менялась после синхронизации: последние изменения есть только на телефоне."
    SyncMark.NEVER -> "⚠ Лекция НЕ синхронизирована: её файлы есть только на телефоне."
}

/** Step 1: what will be lost and whether the laptop has it. Confirm is on the right, in the middle of the screen. */
@Composable
private fun DeleteFirstDialog(lecture: ArchivedLecture, onNext: () -> Unit, onDismiss: () -> Unit) {
    val state = lecture.state
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text("Удалить лекцию?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("«${state.subject} — ${state.title}»")
                Text("Аудио: ${formatClock(state.segments.sumOf { it.lengthMs() })}, фото: ${state.photos.size}.")
                Text(
                    syncWarning(lecture.sync),
                    color = if (lecture.sync == SyncMark.SYNCED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("delete_sync_status"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onNext, modifier = Modifier.testTag("confirm_delete_1")) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_delete_1")) { Text("Отмена") }
        },
    )
}

/**
 * Step 2: the buttons swap places, so a double tap on "Удалить" in step 1 lands on
 * "Отмена" here.
 */
@Composable
private fun DeleteSecondDialog(mark: SyncMark, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text("Точно удалить?") },
        text = {
            Text(
                "Все файлы лекции будут стёрты с телефона. Отменить это нельзя." +
                    if (mark != SyncMark.SYNCED) "\n\nНа ноутбуке полной копии нет." else "",
            )
        },
        // AlertDialog puts dismissButton on the left: here it carries the destructive action.
        dismissButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm_delete_2")) {
                Text("Да, удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_delete_2")) { Text("Отмена") }
        },
    )
}
