package com.arka.lecturecapture.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.arka.lecturecapture.MainActivity
import com.arka.lecturecapture.Permissions
import com.arka.lecturecapture.R
import com.arka.lecturecapture.TAG
import com.arka.lecturecapture.core.sync.Ack
import com.arka.lecturecapture.core.sync.DiscoveryResponder
import com.arka.lecturecapture.core.sync.SyncListener
import com.arka.lecturecapture.core.sync.SyncServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface

data class SyncStatus(
    val running: Boolean = false,
    val code: String = "",
    val address: String = "",
    val files: Int = 0,
    val bytes: Long = 0,
    val paired: Boolean = false,
    /** Why the last session ended, shown after it stops. */
    val result: String? = null,
)

/**
 * Serves the Lectures folder to the laptop while sync is on. Started by a button; keeps
 * running if the app is closed; stops by itself when the laptop reports it has everything,
 * after [IDLE_LIMIT_MS] without requests, or from the Stop button (app or notification).
 */
class SyncService : Service() {
    private var server: SyncServer? = null
    private var discovery: DiscoveryResponder? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastActivity = SystemClock.elapsedRealtime()
    private var lastNotified = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish("Остановлено вручную")
            return START_NOT_STICKY
        }
        if (server != null) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification("Ждём ноутбук…", ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        try {
            begin()
        } catch (e: Exception) {
            Log.e(TAG, "sync start failed", e)
            finish("Не удалось запустить: ${e.message}")
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val store = PrefsSyncStore(this)
        val listener = object : SyncListener {
            override fun onActivity() { lastActivity = SystemClock.elapsedRealtime() }
            override fun onPaired(client: String) {
                Log.i(TAG, "sync paired with $client")
                update { it.copy(paired = true) }
            }
            override fun onFileSent(path: String, bytes: Long) {
                update { it.copy(files = it.files + 1, bytes = server?.bytesSent?.get() ?: it.bytes) }
            }
            override fun onAck(ack: Ack) { Log.i(TAG, "laptop confirmed ${ack.folder}: ${ack.files.size} files") }
            override fun onDone() {
                Log.i(TAG, "laptop finished sync")
                handler.postDelayed({ finish("Готово: ноутбук получил всё") }, 1000)
            }
        }
        val s = SyncServer(Permissions.lecturesRoot, store, listener, device = Build.MODEL).start()
        server = s
        discovery = DiscoveryResponder({ Json.encodeToString(com.arka.lecturecapture.core.sync.Hello.serializer(), s.hello()) }).start()

        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LectureCapture:sync").apply { acquire() }
        // Some phones drop broadcast packets with the screen off unless this is held.
        multicastLock = wifi.createMulticastLock("LectureCapture:discovery").apply { setReferenceCounted(false); acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LectureCapture:sync").apply { setReferenceCounted(false); acquire() }

        lastActivity = SystemClock.elapsedRealtime()
        _status.value = SyncStatus(running = true, code = s.pairingCode, address = "${wifiAddress() ?: "нет Wi-Fi"}:${s.port}", paired = store.token() != null)
        Log.i(TAG, "sync server on ${_status.value.address}")
        refreshNotification(force = true)
        handler.postDelayed(::watchdog, WATCHDOG_MS)
    }

    private fun watchdog() {
        if (server == null) return
        if (SystemClock.elapsedRealtime() - lastActivity > IDLE_LIMIT_MS) {
            finish("Остановлено: ноутбук не обращался ${IDLE_LIMIT_MS / 60_000} мин")
            return
        }
        handler.postDelayed(::watchdog, WATCHDOG_MS)
    }

    private fun update(change: (SyncStatus) -> SyncStatus) {
        _status.value = change(_status.value)
        handler.post { refreshNotification(force = false) }
    }

    private fun refreshNotification(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotified < 1000) return
        lastNotified = now
        val st = _status.value
        val title = if (st.files == 0) "Синхронизация: ждём ноутбук" else "Синхронизация: передано ${st.files} файлов"
        val text = buildString {
            if (!st.paired) append("Код для ноутбука: ${st.code} · ")
            append("%.1f МБ".format(st.bytes / 1_048_576.0))
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
    }

    private fun finish(result: String) {
        Log.i(TAG, "sync finished: $result")
        handler.removeCallbacksAndMessages(null)
        discovery?.close()
        discovery = null
        server?.close()
        server = null
        wifiLock?.takeIf { it.isHeld }?.release()
        multicastLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        _status.value = _status.value.copy(running = false, result = result)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (server != null) finish("Служба остановлена системой")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(title: String, text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Синхронизация", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
    }

    private fun wifiAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress

    companion object {
        private const val ACTION_STOP = "com.arka.lecturecapture.STOP_SYNC"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 3
        private const val IDLE_LIMIT_MS = 5 * 60_000L
        private const val WATCHDOG_MS = 15_000L

        private val _status = MutableStateFlow(SyncStatus())
        val status: StateFlow<SyncStatus> = _status.asStateFlow()

        /** From a visible screen only (foreground service start rules). */
        fun start(context: Context) {
            _status.value = SyncStatus(running = true, code = "…")
            context.startForegroundService(Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SyncService::class.java).setAction(ACTION_STOP))
        }
    }
}
