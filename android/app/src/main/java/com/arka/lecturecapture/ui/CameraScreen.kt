package com.arka.lecturecapture.ui

import android.annotation.SuppressLint
import android.util.Log
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arka.lecturecapture.LectureSession
import com.arka.lecturecapture.TAG
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Full-screen camera. Each frame goes straight to `.pending/` under a name that carries the
 * shutter moment. The UI stays portrait, but the device rotation is passed to ImageCapture,
 * so a board shot holding the phone sideways is saved landscape.
 *
 * Zoom: pinch or slider. If the phone exposes a logical multi-camera, ratios below 1× and the
 * switch between lenses are handled by the system; otherwise it is digital zoom on the main
 * camera. Tap: focus and exposure on that point, then a brightness slider appears.
 * Zoom and brightness start from default every time the camera is opened.
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
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setJpegQuality(95)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var inFlight by remember { mutableIntStateOf(0) }
    var flash by remember { mutableIntStateOf(0) }
    var confirmCancel by remember { mutableStateOf(false) }
    val frames = savedFrames + inFlight

    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var exposureRange by remember { mutableStateOf(0..0) }
    var exposureStep by remember { mutableFloatStateOf(0f) }
    var exposure by remember { mutableIntStateOf(0) }
    var showExposure by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }

    fun applyZoom(ratio: Float) {
        zoom = ratio.coerceIn(minZoom, maxZoom)
        camera?.cameraControl?.setZoomRatio(zoom)
    }

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
                val bound = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                camera = bound
                bound.cameraInfo.zoomState.value?.let {
                    minZoom = it.minZoomRatio
                    maxZoom = it.maxZoomRatio
                }
                val exposureState = bound.cameraInfo.exposureState
                if (exposureState.isExposureCompensationSupported) {
                    exposureRange = exposureState.exposureCompensationRange.let { it.lower..it.upper }
                    exposureStep = exposureState.exposureCompensationStep.toFloat()
                }
                // CameraX may skip the sensor's full size on some devices (quirks), so log what we got.
                Log.i(TAG, "photo resolution ${imageCapture.resolutionInfo?.resolution}, zoom $minZoom..$maxZoom")
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

    // Pinch to zoom; a tap that was not part of a pinch focuses and meters at that point.
    val currentZoom by rememberUpdatedState(zoom)
    LaunchedEffect(Unit) {
        var pinched = false
        val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinched = true
                applyZoom(currentZoom * detector.scaleFactor)
                return true
            }
        })
        previewView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pinched = false
                MotionEvent.ACTION_UP -> if (!pinched) {
                    val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                    camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    focusPoint = Offset(event.x, event.y)
                    focusTick++
                    if (exposureRange.first != exposureRange.last) showExposure = true
                    view.performClick()
                }
            }
            true
        }
    }

    fun shoot() {
        val part = session.newFramePart() ?: return
        inFlight++
        flash++
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(part.file).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    inFlight--
                    session.onFrameWritten(part)
                }

                override fun onError(exception: ImageCaptureException) {
                    inFlight--
                    part.file.delete()
                    session.reportError("Кадр не сохранён: ${exception.message}")
                }
            },
        )
    }

    fun close() = if (frames > 0) confirmCancel = true else onClose()
    BackHandler { close() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize().testTag("camera_preview"))

        // Focus ring at the tapped point, fades after a moment.
        focusPoint?.let { point ->
            var visible by remember(focusTick) { mutableStateOf(true) }
            LaunchedEffect(focusTick) { delay(1200); visible = false }
            if (visible) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White, radius = 36.dp.toPx(), center = point, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }

        // Short white blink as shutter feedback.
        if (flash > 0) {
            var visible by remember(flash) { mutableStateOf(true) }
            LaunchedEffect(flash) { delay(80); visible = false }
            if (visible) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.5f)))
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
            if (showExposure) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("☀", color = Color.White)
                    Slider(
                        value = exposure.toFloat(),
                        onValueChange = {
                            exposure = it.roundToInt()
                            camera?.cameraControl?.setExposureCompensationIndex(exposure)
                        },
                        valueRange = exposureRange.first.toFloat()..exposureRange.last.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("exposure_slider"),
                    )
                    Text(
                        "%+.1f".format(exposure * exposureStep),
                        color = Color.White,
                        modifier = Modifier.width(44.dp).testTag("exposure_value"),
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (maxZoom > minZoom) ZoomControls(zoom, minZoom, maxZoom, ::applyZoom)
            Row(verticalAlignment = Alignment.CenterVertically) {
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

/**
 * Preset buttons (0.5× only when the phone offers a wider lens) and a slider on a log scale,
 * so 1×→2× takes as much travel as 4×→8×.
 */
@Composable
private fun ZoomControls(zoom: Float, min: Float, max: Float, onZoom: (Float) -> Unit) {
    val presets = listOf(0.5f, 1f, 2f, 4f).filter { it in min..max }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            presets.forEach { p ->
                val active = kotlin.math.abs(zoom - p) < 0.05f
                Box(
                    Modifier.padding(horizontal = 6.dp).size(44.dp).clip(CircleShape)
                        .background(if (active) Color.White else Color.Black.copy(alpha = 0.45f))
                        .clickable { onZoom(p) }
                        .testTag("zoom_preset_$p"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(formatRatio(p) + "×", color = if (active) Color.Black else Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = (ln(zoom / min) / ln(max / min)).coerceIn(0f, 1f),
                onValueChange = { onZoom(min * (max / min).pow(it)) },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                modifier = Modifier.weight(1f).testTag("zoom_slider"),
            )
            Text(
                formatRatio(zoom) + "×",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.width(52.dp).padding(start = 8.dp).testTag("zoom_value"),
            )
        }
    }
}

private fun formatRatio(r: Float): String = if (r == r.roundToInt().toFloat()) "${r.roundToInt()}" else "%.1f".format(r)
