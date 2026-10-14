package com.arka.lecturecapture.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes [file] so that its longer side is at most [maxSide] (power-of-two subsampling),
 * applying the EXIF rotation CameraX writes instead of rotating pixels.
 */
fun decodeSampled(file: File, maxSide: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bitmap = BitmapFactory.decodeFile(
        file.path,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
        },
    ) ?: return null
    val degrees = try {
        ExifInterface(file.path).rotationDegrees
    } catch (e: Exception) {
        0
    }
    if (degrees == 0) return bitmap
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

@Composable
fun rememberImage(file: File, maxSide: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, file.path, maxSide) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, maxSide, config)?.asImageBitmap() }
    }
    return image
}

/**
 * Full-screen photo with pinch zoom, pan and double-tap zoom, to judge whether handwriting
 * is sharp. Decoded at up to 4096 px in RGB_565: enough detail for a 48 MP frame without
 * running out of memory.
 */
@Composable
fun ZoomableImage(file: File, modifier: Modifier = Modifier) {
    val image = rememberImage(file, maxSide = 4096, config = Bitmap.Config.RGB_565)
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (image == null) {
            CircularProgressIndicator()
            return@Box
        }
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(file) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 10f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(file) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                        }
                    })
                }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}
