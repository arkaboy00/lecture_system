package com.arka.lecturecapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arka.lecturecapture.LectureSession
import com.arka.lecturecapture.core.LectureSummary
import com.arka.lecturecapture.core.Naming
import com.arka.lecturecapture.core.Stamps
import java.time.format.DateTimeFormatter

private val DEFAULT_SUBJECTS = listOf("Сопромат", "Термех", "Кратные интегралы и ряды", "Материаловедение")
private const val TITLE_PREFIX = "Лекция"
private val START_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(session: LectureSession) {
    val lectures by session.lectures.collectAsStateWithLifecycle()
    val error by session.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { session.refresh() }

    val unfinished = lectures.filter { !it.ended }
    val subjects = (lectures.map { it.subject } + DEFAULT_SUBJECTS).filter { it.isNotBlank() }.distinct()
    var subject by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var titleEdited by rememberSaveable { mutableStateOf(false) }

    fun suggestTitle(forSubject: String) = Naming.nextNumberedTitle(
        TITLE_PREFIX,
        lectures.filter { it.subject.equals(forSubject.trim(), ignoreCase = true) }.map { it.title },
    )
    fun pickSubject(value: String) {
        subject = value
        if (!titleEdited) title = if (value.isBlank()) "" else suggestTitle(value)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Конспект лекций") }) },
        // Pinned: a long list of unfinished lectures must not push it off the screen.
        bottomBar = {
            Button(
                onClick = { session.start(subject.trim(), title.trim().ifEmpty { suggestTitle(subject) }) },
                enabled = subject.isNotBlank(),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)
                    .height(64.dp).testTag("start_button"),
            ) { Text("Начать", style = MaterialTheme.typography.titleMedium) }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { ErrorBanner(it, session::clearError) }

            if (unfinished.isNotEmpty()) {
                Text("Незавершённые", style = MaterialTheme.typography.titleMedium)
                unfinished.forEachIndexed { i, lecture -> UnfinishedCard(lecture, i) { session.resume(lecture.dir) } }
            }

            Text("Новая лекция", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = subject,
                onValueChange = ::pickSubject,
                label = { Text("Предмет") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("subject_input"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subjects.forEachIndexed { i, s ->
                    FilterChip(
                        selected = s == subject,
                        onClick = { pickSubject(s) },
                        label = { Text(s) },
                        modifier = Modifier.testTag("subject_chip_$i"),
                    )
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; titleEdited = true },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("title_input"),
            )
        }
    }
}

@Composable
private fun UnfinishedCard(lecture: LectureSummary, index: Int, onResume: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("unfinished_$index")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${lecture.subject} — ${lecture.title}", style = MaterialTheme.typography.bodyLarge)
                val started = lecture.started?.let { Stamps.zoned(it.ts).format(START_FORMAT) } ?: "?"
                Text(
                    "$started · аудио: ${lecture.segments} · фото: ${lecture.photos}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledTonalButton(onClick = onResume, modifier = Modifier.testTag("resume_$index")) { Text("Продолжить") }
        }
    }
}
