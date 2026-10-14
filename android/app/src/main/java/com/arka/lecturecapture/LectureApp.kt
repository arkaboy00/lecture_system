package com.arka.lecturecapture

import android.app.Application
import android.content.Context

const val TAG = "LectureCapture"

class LectureApp : Application() {
    /** One per process: the single owner of the open lecture and its event log. */
    val session: LectureSession by lazy { LectureSession(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

val Context.session: LectureSession get() = (applicationContext as LectureApp).session
