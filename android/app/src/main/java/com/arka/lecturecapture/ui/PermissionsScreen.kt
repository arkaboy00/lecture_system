package com.arka.lecturecapture.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.arka.lecturecapture.PermissionStatus
import com.arka.lecturecapture.Permissions

@Composable
fun PermissionsScreen(status: PermissionStatus, onChanged: () -> Unit) {
    val context = LocalContext.current
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Нужны разрешения", style = MaterialTheme.typography.headlineSmall)
            PermissionRow("Микрофон", status.microphone, "perm_microphone")
            PermissionRow("Камера", status.camera, "perm_camera")
            PermissionRow("Уведомления", status.notifications, "perm_notifications")
            PermissionRow("Доступ к файлам (папка Lectures)", status.storage, "perm_storage")
            Spacer(Modifier.height(12.dp))

            val missing = Permissions.missingRuntime(context)
            if (missing.isNotEmpty()) {
                Button(
                    onClick = { runtimeLauncher.launch(missing) },
                    modifier = Modifier.fillMaxWidth().testTag("grant_runtime"),
                ) { Text("Выдать разрешения") }
            }
            if (!status.storage && Permissions.usesAllFilesAccess) {
                Button(
                    onClick = { settingsLauncher.launch(Permissions.allFilesAccessIntent(context)) },
                    modifier = Modifier.fillMaxWidth().testTag("grant_storage"),
                ) { Text("Разрешить доступ ко всем файлам") }
            }
            // If a permission was denied twice, Android stops showing the dialog.
            OutlinedButton(
                onClick = { settingsLauncher.launch(Permissions.appSettingsIntent(context)) },
                modifier = Modifier.fillMaxWidth().testTag("open_app_settings"),
            ) { Text("Настройки приложения") }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, tag: String) {
    Row(Modifier.fillMaxWidth().testTag(tag), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (granted) "есть" else "нет",
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
