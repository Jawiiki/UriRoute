package com.uriroute

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.uriroute.data.JsRepository
import com.uriroute.engine.ShellManager
import com.uriroute.model.ShellPermission
import com.uriroute.ui.theme.UriRouteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = JsRepository(applicationContext)

        // Initialize shell permission from saved preference
        ShellManager.setPermission(repository.getShellPermission())

        // Request notification permission on Android 13+
        requestNotificationPermission()

        setContent {
            UriRouteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(repository)
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) return

        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* granted or denied — proceed either way */ }

        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
