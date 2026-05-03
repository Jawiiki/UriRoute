package com.uriroute

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
}
