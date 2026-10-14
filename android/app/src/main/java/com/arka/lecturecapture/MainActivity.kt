package com.arka.lecturecapture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arka.lecturecapture.ui.HomeScreen
import com.arka.lecturecapture.ui.LectureScreen
import com.arka.lecturecapture.ui.PermissionsScreen
import com.arka.lecturecapture.ui.exposeTestTags

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                AppRoot(session)
            }
        }
    }
}

@Composable
private fun AppRoot(session: LectureSession) {
    val context = LocalContext.current
    var permissions by remember { mutableStateOf(Permissions.status(context)) }
    // Permissions may be granted in system settings or over adb while we are paused.
    LifecycleResumeEffect(Unit) {
        permissions = Permissions.status(context)
        onPauseOrDispose {}
    }
    val current by session.current.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().exposeTestTags()) {
        val open = current
        when {
            !permissions.allGranted -> PermissionsScreen(permissions) { permissions = Permissions.status(context) }
            open != null -> LectureScreen(open, session)
            else -> HomeScreen(session)
        }
    }
}
