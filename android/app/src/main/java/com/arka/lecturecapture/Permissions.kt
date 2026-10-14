package com.arka.lecturecapture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

data class PermissionStatus(
    val microphone: Boolean,
    val camera: Boolean,
    /** Always true below Android 13, where no runtime permission exists. */
    val notifications: Boolean,
    val storage: Boolean,
) {
    val allGranted: Boolean get() = microphone && camera && notifications && storage
}

object Permissions {
    /** Lecture folders live in shared storage so Syncthing can read them. */
    val lecturesRoot: File get() = File(Environment.getExternalStorageDirectory(), "Lectures")

    /** All-files access exists from Android 11; Android 10 uses legacy WRITE_EXTERNAL_STORAGE. */
    val usesAllFilesAccess: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun status(context: Context) = PermissionStatus(
        microphone = granted(context, Manifest.permission.RECORD_AUDIO),
        camera = granted(context, Manifest.permission.CAMERA),
        notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS),
        storage = if (usesAllFilesAccess) Environment.isExternalStorageManager()
        else granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE),
    )

    /** Runtime permissions still missing (all-files access is granted in system settings instead). */
    fun missingRuntime(context: Context): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (!usesAllFilesAccess) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.filterNot { granted(context, it) }.toTypedArray()

    fun allFilesAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
