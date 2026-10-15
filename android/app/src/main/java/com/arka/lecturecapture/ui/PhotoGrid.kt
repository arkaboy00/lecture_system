package com.arka.lecturecapture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.arka.lecturecapture.core.LectureState
import com.arka.lecturecapture.core.Mark
import com.arka.lecturecapture.core.Note
import com.arka.lecturecapture.core.Photo
import com.arka.lecturecapture.core.PhotoType
import com.arka.lecturecapture.core.Stamps
import com.arka.lecturecapture.core.TimelineItem
import java.io.File
import java.time.format.DateTimeFormatter

private val SHOT_TIME = DateTimeFormatter.ofPattern("HH:mm")
private val GREY = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** Photos, notes and marks of [state], oldest first (the content of [PhotoGrid]). */
fun gridItems(state: LectureState): List<TimelineItem> =
    state.timeline().filter { it is TimelineItem.Shot || it is TimelineItem.Noted || it is TimelineItem.Starred }

/**
 * Photos of the lecture, oldest first, with notes and marks as full-width rows at their
 * moments. The corner check means "relevant"; unchecking marks the photo obsolete (grey),
 * like selection in Telegram. Tapping the photo itself opens it. The frame colour is the
 * photo type, red corners are the redraw flag.
 */
@Composable
fun PhotoGrid(
    items: List<TimelineItem>,
    fileOf: (String) -> File,
    onToggleObsolete: (Photo) -> Unit,
    onOpen: (Photo) -> Unit,
    modifier: Modifier = Modifier,
    onNote: (Note) -> Unit = {},
    onMark: (Mark) -> Unit = {},
    /** During a lecture new photos appear at the end: keep them in view. */
    followNewest: Boolean = true,
) {
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Фото пока нет", modifier = Modifier.testTag("photos_empty"))
        }
        return
    }
    // Test tags number photos, notes and marks separately, in time order.
    val indexed = remember(items) {
        var photo = 0
        var note = 0
        var mark = 0
        items.map {
            when (it) {
                is TimelineItem.Shot -> photo++
                is TimelineItem.Noted -> note++
                else -> mark++
            }
        }
    }
    val gridState = rememberLazyGridState()
    if (followNewest) LaunchedEffect(items.size) { gridState.animateScrollToItem(items.lastIndex) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = modifier.fillMaxSize().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(
            items.indices.toList(),
            key = { i ->
                when (val it = items[i]) {
                    is TimelineItem.Shot -> it.photo.file
                    is TimelineItem.Noted -> "note:${it.note.seq}"
                    is TimelineItem.Starred -> "mark:${it.mark.seq}"
                    else -> "item:$i"
                }
            },
            span = { i -> if (items[i] is TimelineItem.Shot) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
        ) { i ->
            when (val item = items[i]) {
                is TimelineItem.Shot -> PhotoCell(item.photo, fileOf(item.photo.file), indexed[i], { onToggleObsolete(item.photo) }, { onOpen(item.photo) })
                is TimelineItem.Noted -> NoteRow(item.note, indexed[i]) { onNote(item.note) }
                is TimelineItem.Starred -> MarkRow(item.mark, indexed[i]) { onMark(item.mark) }
                else -> {}
            }
        }
    }
}

@Composable
private fun PhotoCell(photo: Photo, file: File, index: Int, onToggle: () -> Unit, onOpen: () -> Unit) {
    val thumb = rememberImage(file, maxSide = 400)
    Box(
        Modifier.aspectRatio(3f / 4f).clip(MaterialTheme.shapes.small).background(Color.DarkGray)
            .typeFrame(photo.type, MaterialTheme.shapes.small)
            .clickable(onClick = onOpen).testTag("photo_$index")
            .semantics { stateDescription = photo.type.label + if (photo.redraw) ", перерисовать" else "" },
    ) {
        if (thumb != null) {
            Image(
                thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (photo.obsolete) GREY else null,
                alpha = if (photo.obsolete) 0.45f else 1f,
                modifier = Modifier.fillMaxSize().padding(3.dp),
            )
        }
        // Checked = the photo is relevant (same meaning as "keep" in the batch catalog).
        CheckMark(
            checked = !photo.obsolete,
            onToggle = onToggle,
            size = 28,
            modifier = Modifier.align(Alignment.TopEnd).testTag("photo_check_$index"),
        )
        Text(
            buildString {
                append(Stamps.zoned(photo.taken.ts).format(SHOT_TIME))
                append(" · ${photo.type.label}")
                if (photo.obsolete) append(" · неакт.")
            },
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
        // Over the caption: all four corners stay visible.
        if (photo.redraw) RedrawCorners()
    }
}

/**
 * Full-screen view of a saved photo, with the same obsolete mark as in the grid, and its type
 * and redraw flag at the bottom ([onType]/[onRedraw] null = read-only).
 */
@Composable
fun PhotoViewer(
    photo: Photo,
    file: File,
    onToggleObsolete: () -> Unit,
    onClose: () -> Unit,
    onType: ((PhotoType) -> Unit)? = null,
    onRedraw: ((Boolean) -> Unit)? = null,
) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("photo_viewer")) {
        ZoomableImage(file)
        PhotoMarkupPanel(photo.type, photo.redraw, onType, onRedraw)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, modifier = Modifier.testTag("photo_viewer_close")) {
                Text("Назад", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (photo.obsolete) "неактуально" else "",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            CheckMark(
                checked = !photo.obsolete,
                onToggle = onToggleObsolete,
                size = 40,
                modifier = Modifier.padding(end = 8.dp).testTag("photo_viewer_check"),
            )
        }
    }
}
