package com.uriroute.engine

import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Thin wrapper around rikka.shizuku.Shizuku for shell command execution.
 * The Rikka Shizuku library provides the dev.rikka.shizuku:api dependency.
 */
object ShizukuShell {

    fun pingBinder(): Boolean = Shizuku.pingBinder()

    fun isPermissionGranted(): Boolean =
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener): Shizuku.OnRequestPermissionResultListener {
        Shizuku.addRequestPermissionResultListener(listener)
        return listener
    }

    fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener): Shizuku.OnBinderReceivedListener {
        Shizuku.addBinderReceivedListener(listener)
        return listener
    }

    fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.removeBinderReceivedListener(listener)
    }

    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener): Shizuku.OnBinderDeadListener {
        Shizuku.addBinderDeadListener(listener)
        return listener
    }

    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.removeBinderDeadListener(listener)
    }

    /**
     * Execute a shell command through Shizuku by creating a new process.
     * Uses reflection since Shizuku.newProcess() is private in the Rikka API.
     * Returns null if Shizuku is unavailable or the call fails.
     */
    fun newProcess(cmd: Array<String>): Process? {
        // Attempt 1: reflection on rikka.shizuku.Shizuku.newProcess
        try {
            val method = Class.forName("rikka.shizuku.Shizuku")
                .getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            method.isAccessible = true
            val result = method.invoke(null, cmd, null, null)
            if (result is Process) return result
        } catch (e: Exception) {
            Log.w("ShizukuShell", "reflection newProcess failed", e)
        }
        // Attempt 2: use "shizuku" CLI binary
        return try {
            Runtime.getRuntime().exec(cmd)
        } catch (e: Exception) {
            Log.w("ShizukuShell", "shizuku CLI exec failed", e)
            null
        }
    }
}
