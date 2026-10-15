package com.arka.lecturecapture.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arka.lecturecapture.LectureSession
import com.arka.lecturecapture.Permissions
import com.arka.lecturecapture.R
import com.arka.lecturecapture.core.Archive
import com.arka.lecturecapture.core.ArchiveSort
import com.arka.lecturecapture.core.LectureKind
import com.arka.lecturecapture.core.LectureSummary
import com.arka.lecturecapture.core.Stamps
import com.arka.lecturecapture.core.sync.SyncMark
import java.io.File
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/** How the list is shown; remembered between visits (per-device convenience, nothing else). */
private class ArchivePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("archive", Context.MODE_PRIVATE)
    var sort: ArchiveSort
        get() = runCatching { ArchiveSort.valueOf(prefs.getString("sort", null)!!) }.getOrDefault(ArchiveSort.NEWEST)
        set(value) { prefs.edit().putString("sort", value.name).apply() }
    var grouped: Boolean
        get() = prefs.getBoolean("grouped", false)
        set(value) { prefs.edit().putBoolean("grouped", value).apply() }
    var subject: String?
        get() = prefs.getString("subject", null)
        set(value) { prefs.edit().putString("subject", value).apply() }
    var collapsed: Set<String>
        get() = prefs.getStringSet("collapsed", emptySet())!!
        set(value) { prefs.edit().putStringSet("collapsed", value).apply() }
}

/** All lecture folders on the phone: sort by time, group by subject, filter to one subject. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(session: LectureSession, onOpen: (File) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val prefs = remember { ArchivePrefs(context) }
    val lectures by session.lectures.collectAsStateWithLifecycle()
    val error by session.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { session.refresh() }

    var sort by remember { mutableStateOf(prefs.sort) }
    var grouped by remember { mutableStateOf(prefs.grouped) }
    var subject by remember { mutableStateOf(prefs.subject) }
    var collapsed by remember { mutableStateOf(prefs.collapsed) }
    var marks by remember { mutableStateOf<Map<String, SyncMark>>(emptyMap()) }
    var showFolder by remember { mutableStateOf(false) }
    LaunchedEffect(lectures) { marks = session.syncMarks(lectures.map { it.dir }) }

    val subjects = Archive.subjects(lectures)
    // A filter left over from a subject that no longer exists would show an empty list.
    val filter = subject?.takeIf { s -> subjects.any { it.equals(s, ignoreCase = true) } }
    val sections = Archive.arrange(lectures, sort, grouped, filter)
    val listState = rememberLazyListState()
    // A new order is read from the top; otherwise the list keeps the old item in view.
    LaunchedEffect(sort, grouped, filter) { listState.scrollToItem(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Лекции") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("archive_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showFolder = true }, modifier = Modifier.testTag("archive_folder")) {
                        Icon(painterResource(R.drawable.ic_folder), contentDescription = "Папка с лекциями")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Box(Modifier.padding(8.dp)) { ErrorBanner(it, session::clearError) } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        sort = if (sort == ArchiveSort.NEWEST) ArchiveSort.OLDEST else ArchiveSort.NEWEST
                        prefs.sort = sort
                    },
                    modifier = Modifier.testTag("archive_sort"),
                ) {
                    Icon(
                        painterResource(if (sort == ArchiveSort.NEWEST) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up),
                        contentDescription = null,
                    )
                    Text(if (sort == ArchiveSort.NEWEST) " Сначала новые" else " Сначала старые")
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text("По предметам", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = grouped,
                        onCheckedChange = { grouped = it; prefs.grouped = it },
                        modifier = Modifier.padding(start = 8.dp).testTag("archive_group"),
                    )
                }
            }
            if (subjects.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = filter == null,
                        onClick = { subject = null; prefs.subject = null },
                        label = { Text("Все") },
                        modifier = Modifier.testTag("archive_filter_all"),
                    )
                    subjects.forEachIndexed { i, s ->
                        FilterChip(
                            selected = filter.equals(s, ignoreCase = true),
                            onClick = { subject = s; prefs.subject = s },
                            label = { Text(s) },
                            modifier = Modifier.testTag("archive_filter_$i"),
                        )
                    }
                }
            }
            if (sections.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Лекций пока нет", modifier = Modifier.testTag("archive_empty"))
                }
                return@Column
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().testTag("archive_list"),
            ) {
                sections.forEach { section ->
                    val name = section.subject
                    val folded = name != null && name.lowercase() in collapsed
                    if (name != null) {
                        item(key = "h:$name") {
                            SectionHeader(name, section.lectures.size, folded) {
                                collapsed = if (folded) collapsed - name.lowercase() else collapsed + name.lowercase()
                                prefs.collapsed = collapsed
                            }
                        }
                    }
                    if (!folded) {
                        items(section.lectures, key = { it.dir.name }) { lecture ->
                            LectureCard(lecture, marks[lecture.dir.name], showSubject = name == null) { onOpen(lecture.dir) }
                        }
                    }
                }
            }
        }
    }
    if (showFolder) FolderDialog(Permissions.lecturesRoot, onDismiss = { showFolder = false })
}

@Composable
private fun SectionHeader(subject: String, count: Int, folded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp).testTag("archive_section_$subject"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(subject, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("$count  ${if (folded) "▼" else "▲"}", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LectureCard(lecture: LectureSummary, mark: SyncMark?, showSubject: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = if (lecture.ended) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("archive_item_${lecture.dir.name}"),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (showSubject) "${lecture.subject} — ${lecture.title}" else lecture.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(lecture.started?.let { Stamps.zoned(it.ts).format(DATE) } ?: "?")
                    if (lecture.ended) append(if (lecture.kind == LectureKind.SEMINAR) " · семинар" else " · лекция")
                    append(" · аудио ${formatClock(lecture.audioMs)} · фото ${lecture.photos}")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!lecture.ended) Badge("не завершена", MaterialTheme.colorScheme.tertiary)
                SyncBadge(mark)
                if (lecture.problems > 0) Badge("⚠ ${lecture.problems}", MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun SyncBadge(mark: SyncMark?, modifier: Modifier = Modifier) {
    when (mark) {
        SyncMark.SYNCED -> Badge("✓ на ноутбуке", MaterialTheme.colorScheme.primary, modifier)
        SyncMark.CHANGED -> Badge("изменена после синхр.", MaterialTheme.colorScheme.error, modifier)
        SyncMark.NEVER -> Badge("не на ноутбуке", MaterialTheme.colorScheme.error, modifier)
        null -> {}
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.background(color.copy(alpha = 0.12f), MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
