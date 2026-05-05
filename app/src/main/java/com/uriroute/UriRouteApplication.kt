package com.uriroute

import android.app.Application
import android.util.Log
import com.uriroute.data.JsRepository
import com.uriroute.engine.InstallManager
import com.uriroute.engine.JsEngine
import com.uriroute.engine.ShellManager
import com.uriroute.engine.ShizukuShell
import rikka.shizuku.Shizuku
import java.io.File

class UriRouteApplication : Application() {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d("UriRouteApp", "Shizuku binder received")
        updateShizukuState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d("UriRouteApp", "Shizuku binder dead")
        ShellManager.setShizukuState(available = false, granted = false)
    }

    override fun onCreate() {
        super.onCreate()

        // Load saved shell permission from SharedPreferences
        // (needed here because ContentProvider may be accessed without Activity)
        val repository = JsRepository(this)
        JsEngine.initEnvBase(File(filesDir, "js").absolutePath)
        ShellManager.setPermission(repository.getShellPermission())

        // Initialize install manager for async script downloads
        InstallManager.init(this)

        // Initial Shizuku state check
        updateShizukuState()

        // Listen for Shizuku binder connection changes
        ShizukuShell.addBinderReceivedListener(binderReceivedListener)
        ShizukuShell.addBinderDeadListener(binderDeadListener)
    }

    override fun onTerminate() {
        super.onTerminate()
        ShizukuShell.removeBinderReceivedListener(binderReceivedListener)
        ShizukuShell.removeBinderDeadListener(binderDeadListener)
    }

    private fun updateShizukuState() {
        val available = ShizukuShell.pingBinder()
        val granted = available && ShizukuShell.isPermissionGranted()
        ShellManager.setShizukuState(available, granted)
    }
}
