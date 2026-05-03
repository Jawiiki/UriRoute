package com.uriroute.engine

import android.util.Log
import com.uriroute.model.ShellPermission
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Manages shell command execution via Root (su) or Shizuku.
 */
object ShellManager {

    private var currentPermission: ShellPermission = ShellPermission.ROOT
    private var shizukuAvailable: Boolean = false
    private var shizukuGranted: Boolean = false

    fun setPermission(permission: ShellPermission) {
        currentPermission = permission
    }

    fun getPermission(): ShellPermission = currentPermission

    fun setShizukuState(available: Boolean, granted: Boolean) {
        shizukuAvailable = available
        shizukuGranted = granted
    }

    fun isShizukuReady(): Boolean = shizukuAvailable && shizukuGranted

    /**
     * Execute a shell command using the currently selected permission source.
     */
    fun execute(command: String): String {
        return when (currentPermission) {
            ShellPermission.ROOT -> executeRoot(command)
            ShellPermission.SHIZUKU -> executeShizuku(command)
        }
    }

    private fun executeRoot(command: String): String {
        return try {
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            writer.write("$command\n")
            writer.write("exit\n")
            writer.flush()
            writer.close()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (output.isBlank()) "执行完成" else output.trim()
        } catch (e: Exception) {
            "未获取到Root权限"
        }
    }

    private fun executeShizuku(command: String): String {
        return try {
            if (!shizukuAvailable) return "Shizuku 服务未运行"
            if (!shizukuGranted) return "未授予 Shizuku 权限"

            val process = ShizukuShell.newProcess(arrayOf("sh"))
            if (process == null) return "Shizuku 进程创建失败"

            val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            writer.write("$command\n")
            writer.write("exit\n")
            writer.flush()
            writer.close()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (output.isBlank()) "执行完成" else output.trim()
        } catch (e: Exception) {
            Log.e("ShellManager", "Shizuku exec failed", e)
            "Shizuku 执行失败: ${e.message}"
        }
    }
}
