package com.arka.lecturecapture.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arka.lecturecapture.LectureSession
import com.arka.lecturecapture.OpenLecture
import com.arka.lecturecapture.core.PhotoType

private enum class LectureView { MAIN, CAMERA, CATALOG }

/** Navigation inside an open lecture: main screen ⇄ camera ⇄ catalog of the current batch. */
@Composable
fun LectureFlow(open: OpenLecture, session: LectureSession) {
    var view by rememberSaveable { mutableStateOf(LectureView.MAIN) }
    val pending by session.pending.collectAsStateWithLifecycle()

    when (view) {
        LectureView.MAIN -> LectureScreen(
            open = open,
            session = session,
            pendingFrames = pending.size,
            onPhoto = { view = LectureView.CAMERA },
            onSortPending = { view = LectureView.CATALOG },
        )
        LectureView.CAMERA -> CameraScreen(
            session = session,
            savedFrames = pending.size,
            onContinue = { view = LectureView.CATALOG },
            onClose = { view = LectureView.MAIN },
        )
        LectureView.CATALOG -> {
            LaunchedEffect(Unit) { session.refreshPending() }
            if (pending.isEmpty()) {
                LaunchedEffect(Unit) { view = LectureView.MAIN }
            } else {
                CatalogScreen(
                    session = session,
                    frames = pending,
                    defaultType = open.state.lastPhotoType ?: PhotoType.BOARD,
                    onMore = { view = LectureView.CAMERA },
                    onDone = { view = LectureView.MAIN },
                )
            }
        }
    }
}
