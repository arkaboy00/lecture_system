package com.arka.lecturecapture

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.arka.lecturecapture.core.Clock
import java.time.ZoneId

class AndroidClock(private val context: Context) : Clock {
    // BOOT_COUNT cannot change while this process is alive.
    private val bootCount: Int? by lazy {
        try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (e: Settings.SettingNotFoundException) {
            null
        }
    }

    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun mono(): Long = SystemClock.elapsedRealtime()
    override fun zone(): ZoneId = ZoneId.systemDefault()
    override fun boot(): Int? = bootCount
}
