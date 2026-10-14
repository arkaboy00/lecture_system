package com.arka.lecturecapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

/**
 * Debug builds only: SIGKILLs our own process, the way the low-memory killer or MIUI does.
 * Unlike `am force-stop`, the system then restarts a START_STICKY service.
 * adb shell am broadcast -n com.arka.lecturecapture/.DebugKillReceiver
 */
class DebugKillReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "debug kill requested")
        Process.killProcess(Process.myPid())
    }
}
