package com.arka.lecturecapture.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Shows where a folder is and offers the apps that can open it. Android has no single
 * "open this folder" intent: the system Files app (and many file managers) take a
 * DocumentsContract directory, Samsung "My Files" takes its own start-path extra.
 */
@Composable
fun FolderDialog(dir: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text("Папка на телефоне") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(dir.absolutePath, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("folder_path"))
                }
                Text(
                    "В файловом менеджере: Внутренняя память → ${dir.relativeToOrNull(Environment.getExternalStorageDirectory())?.path ?: dir.path}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); FolderLinks.open(context, dir) }, modifier = Modifier.testTag("folder_open")) {
                Text("Открыть в…")
            }
        },
        dismissButton = {
            TextButton(onClick = { FolderLinks.copyPath(context, dir); onDismiss() }, modifier = Modifier.testTag("folder_copy")) {
                Text("Копировать путь")
            }
        },
    )
}

object FolderLinks {
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val MY_FILES = "com.sec.android.app.myfiles"

    /** content:// directory document of [dir] in the primary storage, or null outside it. */
    fun documentUri(dir: File): Uri? {
        val relative = dir.relativeToOrNull(Environment.getExternalStorageDirectory())?.invariantSeparatorsPath ?: return null
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:$relative")
    }

    fun open(context: Context, dir: File) {
        val targets = buildList {
            documentUri(dir)?.let { add(Intent(Intent.ACTION_VIEW).setDataAndType(it, DocumentsContract.Document.MIME_TYPE_DIR)) }
            if (context.packageManager.getLaunchIntentForPackage(MY_FILES) != null) {
                add(
                    Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES")
                        .setPackage(MY_FILES)
                        .putExtra("samsung.myfiles.intent.extra.START_PATH", dir.absolutePath),
                )
            }
        }
        if (targets.isEmpty()) {
            copyPath(context, dir)
            return
        }
        val chooser = Intent.createChooser(targets.first(), "Открыть папку")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            copyPath(context, dir)
        }
    }

    fun copyPath(context: Context, dir: File) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("path", dir.absolutePath))
        Toast.makeText(context, "Путь скопирован", Toast.LENGTH_SHORT).show()
    }
}
