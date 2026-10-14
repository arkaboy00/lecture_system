package com.arka.lecturecapture.ui

import android.annotation.SuppressLint
import android.util.Log
import com.arka.lecturecapture.TAG
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arka.lecturecapture.LectureSession
import kotlinx.coroutines.delay

/**
 * Full-screen camera. Each frame goes straight to `.pending/` under a name that carries the
 * shutter moment. The UI stays portrait, but the device rotation is passed to ImageCapture,
 * so a board shot holding the phone sideways is saved landscape.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraScreen(session: LectureSession, savedFrames: Int, onContinue: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val fourByThree = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setJpegQuality(95)
            .setResolutionSelector(fourByThree)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var inFlight by remember { mutableIntStateOf(0) }
    var flash by remember { mutableIntStateOf(0) }
    var confirmCancel by remember { mutableStateOf(false) }
    val frames = savedFrames + inFlight

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build(),
                ).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                // CameraX may skip the sensor's full size on some devices (quirks), so log what we got.
                Log.i(TAG, "photo resolution ${imageCapture.resolutionInfo?.resolution}")
            } catch (e: Exception) {
                session.reportError("Камера не запустилась: ${e.message}")
            }
        }, mainExecutor)
        onDispose { runCatching { future.get().unbindAll() } }
    }

    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (degrees) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(Unit) {
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                view.performClick()
            }
            true
        }
    }

    fun shoot() {
        val part = session.newFramePart() ?: return
        inFlight++
        flash++
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(part).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    inFlight--
                    session.onFrameWritten(part)
                }

                override fun onError(exception: ImageCaptureException) {
                    inFlight--
                    part.delete()
                    session.reportError("Кадр не сохранён: ${exception.message}")
                }
            },
        )
    }

    fun close() = if (frames > 0) confirmCancel = true else onClose()
    BackHandler { close() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize().testTag("camera_preview"))

        // Short white blink as shutter feedback.
        if (flash > 0) {
            var visible by remember(flash) { mutableStateOf(true) }
            LaunchedEffect(flash) { delay(80); visible = false }
            if (visible) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.5f)))
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = ::close, modifier = Modifier.testTag("cancel_camera")) {
                Text("Отмена", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Кадров: $frames",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 12.dp).testTag("frame_count"),
            )
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(84.dp).clip(CircleShape).clickable(onClick = ::shoot)
                    .border(4.dp, Color.White, CircleShape).padding(8.dp)
                    .clip(CircleShape).background(Color.White)
                    .semantics { contentDescription = "Снять" }
                    .testTag("shutter_button"),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Button(
                    onClick = onContinue,
                    enabled = savedFrames > 0 && inFlight == 0,
                    modifier = Modifier.height(56.dp).testTag("continue_button"),
                ) { Text("Далее ($savedFrames)") }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            modifier = Modifier.exposeTestTags(),
            title = { Text("Удалить снятые кадры?") },
            text = { Text("Кадров в этой пачке: $frames. Они будут удалены.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmCancel = false; session.cancelBatch(); onClose() },
                    modifier = Modifier.testTag("confirm_cancel_batch"),
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }, modifier = Modifier.testTag("keep_batch")) { Text("Оставить") }
            },
        )
    }
}
