package com.arka.lecturecapture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Vendor shells kill background apps (MIUI especially, One UI too). Only the standard battery
 * optimization exemption can be checked from an app; vendor switches (MIUI autostart, Samsung
 * "never sleeping apps") have no public API, so we open their screens and explain what to set.
 */
object Battery {
    enum class Vendor { XIAOMI, SAMSUNG, OTHER }

    data class Shortcut(val label: String, val tag: String, val intents: List<Intent>)

    val vendor: Vendor
        get() = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> Vendor.XIAOMI
            "samsung" -> Vendor.SAMSUNG
            else -> Vendor.OTHER
        }

    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** System dialog "Allow the app to always run in the background?". */
    fun requestUnrestricted(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Steps the user has to do by hand, in order. */
    fun manualSteps(): List<String> = when (vendor) {
        Vendor.XIAOMI -> listOf(
            "«Автозапуск»: включить для «Конспект».",
            "«Контроль активности» (батарея приложения): выбрать «Нет ограничений».",
            "По желанию: в недавних приложениях закрепить «Конспект» замком.",
        )
        Vendor.SAMSUNG -> listOf(
            "Настройки → Батарея → Ограничения фоновой работы → «Приложения, которые никогда не переходят в спящий режим» → добавить «Конспект».",
            "Там же выключить «Перевод неиспользуемых приложений в спящий режим» или убедиться, что «Конспект» не в списке спящих.",
        )
        Vendor.OTHER -> listOf("В настройках батареи разрешить приложению работать в фоне без ограничений.")
    }

    /** Buttons that open the vendor screens; each falls back to the app's details page. */
    fun shortcuts(context: Context): List<Shortcut> {
        val pkg = context.packageName
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        return when (vendor) {
            Vendor.XIAOMI -> listOf(
                Shortcut(
                    "Открыть «Автозапуск»", "battery_autostart",
                    listOf(
                        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                        details,
                    ),
                ),
                Shortcut(
                    "Открыть «Контроль активности»", "battery_vendor",
                    listOf(
                        Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
                            .putExtra("package_name", pkg)
                            .putExtra("package_label", context.getString(R.string.app_name)),
                        details,
                    ),
                ),
            )
            Vendor.SAMSUNG -> listOf(
                Shortcut(
                    "Открыть настройки батареи", "battery_vendor",
                    listOf(
                        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        details,
                    ),
                ),
            )
            Vendor.OTHER -> listOf(Shortcut("Открыть настройки приложения", "battery_vendor", listOf(details)))
        }
    }

    /** Starts the first intent the system can resolve. */
    fun open(context: Context, shortcut: Shortcut) {
        for (intent in shortcut.intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Log.w(TAG, "cannot open ${intent.component ?: intent.action}: ${e.message}")
            }
        }
    }
}
