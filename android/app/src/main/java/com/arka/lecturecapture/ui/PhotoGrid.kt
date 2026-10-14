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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.arka.lecturecapture.core.Photo
import com.arka.lecturecapture.core.PhotoType
import com.arka.lecturecapture.core.Stamps
import java.io.File
import java.time.format.DateTimeFormatter

private val SHOT_TIME = DateTimeFormatter.ofPattern("HH:mm")
private val GREY = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Photos of the lecture, oldest first. The corner check means "relevant"; unchecking marks
 * the photo obsolete (grey), like selection in Telegram. Tapping the photo itself opens it.
 */
@Composable
fun PhotoGrid(
    photos: List<Photo>,
    fileOf: (String) -> File,
    onToggleObsolete: (Photo) -> Unit,
    onOpen: (Photo) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (photos.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Фото пока нет", modifier = Modifier.testTag("photos_empty"))
        }
        return
    }
    val gridState = rememberLazyGridState()
    // New photos appear at the end: keep them in view.
    LaunchedEffect(photos.size) { gridState.animateScrollToItem(photos.lastIndex) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = modifier.fillMaxSize().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(photos, key = { _, p -> p.file }) { i, photo ->
            PhotoCell(photo, fileOf(photo.file), i, { onToggleObsolete(photo) }, { onOpen(photo) })
        }
    }
}

@Composable
private fun PhotoCell(photo: Photo, file: File, index: Int, onToggle: () -> Unit, onOpen: () -> Unit) {
    val thumb = rememberImage(file, maxSide = 400)
    Box(
        Modifier.aspectRatio(3f / 4f).clip(MaterialTheme.shapes.small).background(Color.DarkGray)
            .clickable(onClick = onOpen).testTag("photo_$index"),
    ) {
        if (thumb != null) {
            Image(
                thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (photo.obsolete) GREY else null,
                alpha = if (photo.obsolete) 0.45f else 1f,
                modifier = Modifier.fillMaxSize(),
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
                append(if (photo.type == PhotoType.BOARD) " · доска" else " · тетрадь")
                if (photo.obsolete) append(" · неакт.")
            },
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/** Full-screen view of a saved photo, with the same obsolete mark as in the grid. */
@Composable
fun PhotoViewer(photo: Photo, file: File, onToggleObsolete: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("photo_viewer")) {
        ZoomableImage(file)
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
