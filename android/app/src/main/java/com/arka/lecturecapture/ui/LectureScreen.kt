package com.arka.lecturecapture.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arka.lecturecapture.LectureSession
import com.arka.lecturecapture.OpenLecture
import com.arka.lecturecapture.core.LectureKind
import com.arka.lecturecapture.core.LectureState

/**
 * Main lecture screen: header with settings and menu, photo grid in the middle,
 * two big buttons at the bottom. Stage 0: layout only, the buttons are not wired yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LectureScreen(open: OpenLecture, session: LectureSession) {
    val state = open.state
    val error by session.error.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    val level by session.level.collectAsStateWithLifecycle()

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
                    // Settings arrive in a later stage.
                    IconButton(onClick = {}, enabled = false, modifier = Modifier.testTag("settings_button")) {
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
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Box(Modifier.padding(8.dp)) { ErrorBanner(it, session::clearError) } }
            RecordingStatus(state, level)
            PhotoGrid(state, Modifier.weight(1f))
        }
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
                    append("сегм. ${state.segments.size} · фото ${state.photos.size}")
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

/** Placeholder grid; thumbnails and obsolete check marks come with stage 2. */
@Composable
private fun PhotoGrid(state: LectureState, modifier: Modifier) {
    if (state.photos.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Фото пока нет", modifier = Modifier.testTag("photos_empty"))
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(state.photos) { i, photo ->
            Card(Modifier.height(96.dp).testTag("photo_$i")) {
                Text(photo.file.substringAfter('/'), Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BottomButtons(recording: Boolean, onAudio: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(
            onClick = onAudio,
            colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors(),
            modifier = Modifier.weight(1f).height(72.dp).testTag("audio_button"),
        ) {
            Text(if (recording) "Стоп" else "Запись", style = MaterialTheme.typography.titleMedium)
        }
        Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f).height(72.dp).testTag("photo_button")) {
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
