package com.arka.lecturecapture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.arka.lecturecapture.LectureSession
import com.arka.lecturecapture.core.PendingFrame
import com.arka.lecturecapture.core.PhotoType

/**
 * Review of the frames just shot. The last frame is selected by default; tapping a frame opens
 * it full screen to judge sharpness. The type selected at the bottom applies to the whole
 * batch; a frame can get its own type and the redraw flag in the full-screen view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    session: LectureSession,
    frames: List<PendingFrame>,
    defaultType: PhotoType,
    onMore: () -> Unit,
    onDone: () -> Unit,
) {
    // null = the user has not touched the selection yet: follow "last frame selected".
    var explicit by remember { mutableStateOf<Set<String>?>(null) }
    val selected = explicit ?: setOfNotNull(frames.lastOrNull()?.name)
    var type by remember { mutableStateOf(defaultType) }
    // Per-frame choices from the full-screen view, by pending file name.
    var typeOf by remember { mutableStateOf(emptyMap<String, PhotoType>()) }
    var redraw by remember { mutableStateOf(emptySet<String>()) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }

    fun toggle(name: String) {
        explicit = if (name in selected) selected - name else selected + name
    }

    BackHandler(enabled = viewing == null) { confirmCancel = true }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Кадры: выбрано ${selected.size} из ${frames.size}") }) },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TypeSelector(type, { type = it })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { confirmCancel = true },
                        modifier = Modifier.weight(1f).height(56.dp).testTag("cancel_batch"),
                    ) { Text("Отмена") }
                    OutlinedButton(
                        onClick = onMore,
                        modifier = Modifier.weight(1f).height(56.dp).testTag("more_frames"),
                    ) { Text("Ещё кадр") }
                    Button(
                        onClick = {
                            session.savePhotos(selected, type, typeOf.filterKeys { it in selected }, redraw intersect selected)
                            onDone()
                        },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1.3f).height(56.dp).testTag("save_photos"),
                    ) { Text("Сохранить") }
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(padding).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(frames, key = { _, f -> f.name }) { i, frame ->
                FrameCell(
                    frame = frame,
                    index = i,
                    type = typeOf[frame.name] ?: type,
                    redraw = frame.name in redraw,
                    checked = frame.name in selected,
                    onOpen = { viewing = frame.name },
                    onToggle = { toggle(frame.name) },
                )
            }
        }
    }

    val open = frames.firstOrNull { it.name == viewing }
    if (open != null) {
        BackHandler { viewing = null }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("viewer")) {
            ZoomableImage(open.file)
            PhotoMarkupPanel(
                type = typeOf[open.name] ?: type,
                redraw = open.name in redraw,
                onType = { typeOf = typeOf + (open.name to it) },
                onRedraw = { on -> redraw = if (on) redraw + open.name else redraw - open.name },
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { viewing = null }, modifier = Modifier.testTag("viewer_close")) {
                    Text("Назад", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.weight(1f))
                CheckMark(
                    checked = open.name in selected,
                    onToggle = { toggle(open.name) },
                    size = 48,
                    modifier = Modifier.padding(end = 8.dp).testTag("viewer_check"),
                )
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            modifier = Modifier.exposeTestTags(),
            title = { Text("Удалить всю пачку?") },
            text = { Text("Все ${frames.size} кадров будут удалены, ничего не сохранится.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmCancel = false; session.cancelBatch(); onDone() },
                    modifier = Modifier.testTag("confirm_cancel_batch"),
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }, modifier = Modifier.testTag("keep_batch")) { Text("Оставить") }
            },
        )
    }
}

@Composable
private fun FrameCell(
    frame: PendingFrame,
    index: Int,
    type: PhotoType,
    redraw: Boolean,
    checked: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val thumb = rememberImage(frame.file, maxSide = 400)
    Box(
        Modifier.aspectRatio(3f / 4f).clip(MaterialTheme.shapes.small).background(Color.DarkGray)
            .typeFrame(type, MaterialTheme.shapes.small)
            .clickable(onClick = onOpen).testTag("frame_$index")
            .semantics { stateDescription = type.label + if (redraw) ", перерисовать" else "" },
    ) {
        if (thumb != null) {
            Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(3.dp))
        }
        if (redraw) RedrawCorners()
        CheckMark(
            checked = checked,
            onToggle = onToggle,
            size = 32,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).testTag("frame_check_$index"),
        )
    }
}

/** Telegram-style round check mark; the touch target is larger than the circle. */
@Composable
fun CheckMark(checked: Boolean, onToggle: () -> Unit, size: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size((size + 16).dp).clip(CircleShape).clickable(onClick = onToggle)
            .semantics { contentDescription = if (checked) "Выбран" else "Не выбран" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size.dp).clip(CircleShape)
                .background(if (checked) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.3f))
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.7).dp))
        }
    }
}
