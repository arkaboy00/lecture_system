package com.arka.lecturecapture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.arka.lecturecapture.Battery

/** Settings: for now, keeping the recording alive in the background on MIUI / One UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(Battery.isUnrestricted(context)) }
    // The user comes back from system screens: re-check.
    LifecycleResumeEffect(Unit) {
        unrestricted = Battery.isUnrestricted(context)
        onPauseOrDispose {}
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
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
            Text("Работа в фоне", style = MaterialTheme.typography.titleMedium)
            Text(
                "Оболочка телефона может усыпить или закрыть приложение, и запись прервётся. " +
                    "Сделай один раз всё, что ниже.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Оптимизация батареи", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (unrestricted) "Отключена для приложения — хорошо." else "Включена: система может усыпить запись.",
                        color = if (unrestricted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("battery_status"),
                    )
                    if (!unrestricted) {
                        Button(
                            onClick = { context.startActivity(Battery.requestUnrestricted(context)) },
                            modifier = Modifier.testTag("battery_request"),
                        ) { Text("Разрешить работу в фоне") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (Battery.vendor) {
                            Battery.Vendor.XIAOMI -> "2. Настройки MIUI"
                            Battery.Vendor.SAMSUNG -> "2. Настройки One UI"
                            Battery.Vendor.OTHER -> "2. Настройки производителя"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Их нельзя проверить из приложения — включи вручную:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Battery.manualSteps().forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Battery.shortcuts(context).forEach { shortcut ->
                        OutlinedButton(
                            onClick = { Battery.open(context, shortcut) },
                            modifier = Modifier.fillMaxWidth().testTag(shortcut.tag),
                        ) { Text(shortcut.label) }
                    }
                }
            }
        }
    }
}
