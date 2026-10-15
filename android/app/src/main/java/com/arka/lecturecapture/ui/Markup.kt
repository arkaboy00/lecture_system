package com.arka.lecturecapture.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arka.lecturecapture.core.Mark
import com.arka.lecturecapture.core.Note
import com.arka.lecturecapture.core.PhotoType
import com.arka.lecturecapture.core.Stamps
import java.time.format.DateTimeFormatter

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** Frame colours by type (the user's palette): board green, slide blue, notebook orange. */
val PhotoType.color: Color
    get() = when (this) {
        PhotoType.BOARD -> Color(0xFF2E9E44)
        PhotoType.SLIDE -> Color(0xFF1E6FE0)
        PhotoType.NOTEBOOK -> Color(0xFFF08A00)
    }

val PhotoType.label: String
    get() = when (this) {
        PhotoType.BOARD -> "доска"
        PhotoType.SLIDE -> "слайд"
        PhotoType.NOTEBOOK -> "тетрадь"
    }

/** Order on screen: board, slide, notebook. */
val PHOTO_TYPES = listOf(PhotoType.BOARD, PhotoType.SLIDE, PhotoType.NOTEBOOK)

/** The red corners of the redraw flag: a different colour and shape than the type frame. */
val REDRAW_COLOR = Color(0xFFE53935)

/** Coloured frame of the photo type; kept on obsolete (grey) photos so the type stays visible. */
fun Modifier.typeFrame(type: PhotoType, shape: Shape, width: Dp = 3.dp): Modifier = border(width, type.color, shape)

/** Four L-shaped corner marks over the thumbnail, for photos flagged "redraw". */
@Composable
fun BoxScope.RedrawCorners(inset: Dp = 6.dp, arm: Dp = 16.dp, stroke: Dp = 4.dp) {
    Canvas(Modifier.matchParentSize().semantics { contentDescription = "перерисовать" }) {
        val i = inset.toPx()
        val a = arm.toPx()
        val w = stroke.toPx()
        val (r, b) = size.width - i to size.height - i
        fun l(corner: Offset, dx: Float, dy: Float) {
            drawLine(REDRAW_COLOR, corner, corner + Offset(dx, 0f), w, StrokeCap.Square)
            drawLine(REDRAW_COLOR, corner, corner + Offset(0f, dy), w, StrokeCap.Square)
        }
        l(Offset(i, i), a, a)
        l(Offset(r, i), -a, a)
        l(Offset(i, b), a, -a)
        l(Offset(r, b), -a, -a)
    }
}

/** Board / slide / notebook; the selected segment is filled with the type colour. */
@Composable
fun TypeSelector(type: PhotoType, onType: (PhotoType) -> Unit, modifier: Modifier = Modifier, tagPrefix: String = "type") {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        PHOTO_TYPES.forEachIndexed { i, t ->
            SegmentedButton(
                selected = type == t,
                onClick = { onType(t) },
                shape = SegmentedButtonDefaults.itemShape(i, PHOTO_TYPES.size),
                colors = SegmentedButtonDefaults.colors(activeContainerColor = t.color, activeContentColor = Color.White),
                modifier = Modifier.testTag("${tagPrefix}_${t.name.lowercase()}"),
            ) { Text(t.label.replaceFirstChar { it.uppercase() }) }
        }
    }
}

/**
 * Bottom panel of a full-screen photo: type and the redraw flag. [onType]/[onRedraw] null =
 * read-only (an unfinished lecture in the archive), then only the current values are shown.
 */
@Composable
fun BoxScope.PhotoMarkupPanel(
    type: PhotoType,
    redraw: Boolean,
    onType: ((PhotoType) -> Unit)?,
    onRedraw: ((Boolean) -> Unit)?,
) {
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))
            .navigationBarsPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onType != null) {
            TypeSelector(type, onType, tagPrefix = "viewer_type")
        } else {
            Text(type.label + if (redraw) " · перерисовать" else "", color = type.color, style = MaterialTheme.typography.titleMedium)
        }
        if (onRedraw != null) {
            FilterChip(
                selected = redraw,
                onClick = { onRedraw(!redraw) },
                label = { Text("Перерисовать рисунки нейросетью") },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = Color.White,
                    selectedContainerColor = REDRAW_COLOR,
                    selectedLabelColor = Color.White,
                ),
                modifier = Modifier.testTag("viewer_redraw"),
            )
        }
    }
}

/** A note in the chronological grid (full width). */
@Composable
fun NoteRow(note: Note, index: Int, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("note_$index"),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(
                "${Stamps.zoned(note.at.ts).format(CLOCK)}  ${note.text}",
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A press of the "important" button in the chronological grid (full width, one line). */
@Composable
fun MarkRow(mark: Mark, index: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(onClick = onClick).padding(horizontal = 8.dp).testTag("mark_$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = MARK_COLOR)
        Box(Modifier.width(8.dp))
        Text("${Stamps.zoned(mark.at.ts).format(CLOCK)}  важный момент", style = MaterialTheme.typography.bodyMedium)
    }
}

val MARK_COLOR = Color(0xFFFFB300)

/**
 * Typing a note. The note's time is when this dialog opened (kept by the caller), so a slow
 * typist still lands at the right place in the speech. The draft is saved by [onText].
 */
@Composable
fun NoteDialog(text: String, openedAt: String, onText: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    // The keyboard opens at once: during a lecture every tap counts.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = {}, // only explicit buttons: a stray tap must not lose the text
        modifier = Modifier.exposeTestTags(),
        title = { Text("Заметка · $openedAt") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                minLines = 3,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("note_input"),
            )
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = text.isNotBlank(), modifier = Modifier.testTag("note_save")) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("note_cancel")) { Text("Отмена") }
        },
    )
}

/** Full text of a note; [onRemove] null = read-only. */
@Composable
fun NoteDetailsDialog(note: Note, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text("Заметка · ${Stamps.zoned(note.at.ts).format(CLOCK)}") },
        text = { Text(note.text, modifier = Modifier.testTag("note_text")) },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("note_close")) { Text("Закрыть") } },
        dismissButton = onRemove?.let {
            { TextButton(onClick = it, modifier = Modifier.testTag("note_remove")) { Text("Удалить", color = MaterialTheme.colorScheme.error) } }
        },
    )
}

/** Withdrawing a mark; [onRemove] null = read-only. */
@Composable
fun MarkDetailsDialog(mark: Mark, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text("Важный момент · ${Stamps.zoned(mark.at.ts).format(CLOCK)}") },
        text = { Text("Дома по записи речи перед этим моментом решается, к какому контролю он относится.") },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("mark_close")) { Text("Закрыть") } },
        dismissButton = onRemove?.let {
            { TextButton(onClick = it, modifier = Modifier.testTag("mark_remove")) { Text("Снять отметку", color = MaterialTheme.colorScheme.error) } }
        },
    )
}
