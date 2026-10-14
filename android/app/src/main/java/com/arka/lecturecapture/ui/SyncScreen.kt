package com.arka.lecturecapture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arka.lecturecapture.LectureSession
import com.arka.lecturecapture.sync.PrefsSyncStore
import com.arka.lecturecapture.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts / stops the sync service and shows what the laptop already has. The service keeps
 * running after leaving this screen or closing the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(session: LectureSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val status by SyncService.status.collectAsStateWithLifecycle()
    val lectures by session.lectures.collectAsStateWithLifecycle()
    var onLaptop by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(Unit) { session.refresh() }
    // Re-check the marks whenever a session changes state or more files went out.
    LaunchedEffect(lectures, status.running, status.files) {
        val store = PrefsSyncStore(context)
        onLaptop = withContext(Dispatchers.IO) { lectures.associate { it.dir.name to store.isOnLaptop(it.dir) } }
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Синхронизация с ноутбуком") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("sync_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!status.running) {
                Text(
                    "Телефон и ноутбук должны быть в одной домашней сети Wi-Fi. Нажми «Начать», " +
                        "затем «Синхронизировать» в программе на ноутбуке. Приложение можно закрыть — " +
                        "синхронизация продолжится и сама выключится, когда ноутбук всё получит.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                status.result?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("sync_result")) }
                Button(
                    onClick = { SyncService.start(context) },
                    modifier = Modifier.fillMaxWidth().height(64.dp).testTag("sync_start"),
                ) { Text("Начать синхронизацию", style = MaterialTheme.typography.titleMedium) }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Синхронизация включена", style = MaterialTheme.typography.titleMedium)
                        if (!status.paired) {
                            Text("Код для ноутбука (нужен один раз):", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                status.code,
                                fontSize = 40.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.testTag("sync_code"),
                            )
                        }
                        Text("Адрес: ${status.address}", modifier = Modifier.testTag("sync_address"))
                        Text(
                            "Передано: ${status.files} файлов, %.1f МБ".format(status.bytes / 1_048_576.0),
                            modifier = Modifier.testTag("sync_progress"),
                        )
                    }
                }
                Button(
                    onClick = { SyncService.stop(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("sync_stop"),
                ) { Text("Остановить") }
            }

            if (lectures.isNotEmpty()) {
                HorizontalDivider()
                Text("Лекции на телефоне", style = MaterialTheme.typography.titleSmall)
                lectures.forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth().testTag("sync_lecture_$i"), verticalAlignment = Alignment.CenterVertically) {
                        Text("${l.subject} — ${l.title}${if (l.ended) "" else " (идёт)"}", Modifier.weight(1f))
                        Text(
                            if (onLaptop[l.dir.name] == true) "на ноутбуке ✓" else "ещё нет",
                            color = if (onLaptop[l.dir.name] == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
