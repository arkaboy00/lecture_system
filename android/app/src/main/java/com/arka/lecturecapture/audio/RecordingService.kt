package com.arka.lecturecapture.audio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.arka.lecturecapture.Notifications
import com.arka.lecturecapture.TAG
import com.arka.lecturecapture.session

/**
 * Foreground "microphone" service: keeps the process alive and allowed to record while the
 * app is in the background, the screen is locked or the camera is open. The recording itself
 * is owned by [com.arka.lecturecapture.LectureSession]; this class is only the shell.
 */
class RecordingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LectureCapture:recording")
            .apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foreground = try {
            startForeground(
                Notifications.RECORDING_ID,
                Notifications.recording(this, session.current.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
        if (intent == null) {
            // START_STICKY restart after the process was killed while recording.
            Log.w(TAG, "service restarted by the system, foreground=$foreground")
            session.recoverKilledRecording(foreground)
        } else if (intent.action == ACTION_START) {
            if (foreground) session.beginRecording() else session.abortRecordingStart("startForeground failed")
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed from recents while the service runs")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "recording service destroyed")
        session.onServiceDestroyed()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_START = "com.arka.lecturecapture.START_RECORDING"

        /** Must be called while the app is visible: Android 12 forbids starting it from the background. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
