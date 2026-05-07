package com.uriroute.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.uriroute.MainActivity
import com.uriroute.data.JsRepository
import com.uriroute.model.CacheConfig
import com.uriroute.model.InstallRequest
import com.uriroute.model.InstallStatus
import com.uriroute.model.InstallTask
import com.uriroute.model.JsScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object InstallManager {

    private const val CHANNEL_ID = "uriroute_install"
    private const val CHANNEL_NAME = "脚本安装"
    private const val NOTIFY_ID_BASE = 1000
    private const val DOWNLOAD_TIMEOUT_MS = 15_000

    private val installLocks = ConcurrentHashMap<String, Any>()
    private val _tasks = MutableStateFlow<List<InstallTask>>(emptyList())
    val tasks: StateFlow<List<InstallTask>> = _tasks

    private var appContext: Context? = null
    private var notificationManager: NotificationManager? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var nextNotifyId = NOTIFY_ID_BASE

    private var tasksFile: File? = null

    fun init(context: Context) {
        appContext = context
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            notificationManager?.createNotificationChannel(channel)
        }
        tasksFile = File(context.filesDir, "install_tasks.json")
        loadTasks()
    }

    private fun saveTasks() {
        val file = tasksFile ?: return
        try {
            val arr = JSONArray()
            for (t in _tasks.value) {
                arr.put(JSONObject().apply {
                    put("group", t.group)
                    put("name", t.name)
                    put("url", t.url)
                    put("status", t.status.name)
                    t.error?.let { put("error", it) }
                    put("version", t.version)
                    t.cache?.let { put("cache", it) }
                    val extra = JSONObject(t.extraParams)
                    put("extraParams", extra)
                    t.reareyeUri?.let { put("reareyeUri", it) }
                })
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    private fun loadTasks() {
        val file = tasksFile ?: return
        if (!file.exists()) return
        try {
            val json = file.readText()
            val arr = JSONArray(json)
            val list = mutableListOf<InstallTask>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val status = try { InstallStatus.valueOf(obj.getString("status")) } catch (_: Exception) { null }
                // Only restore FAILED and TIMEOUT — INSTALLING is stale after crash
                if (status != InstallStatus.FAILED && status != InstallStatus.TIMEOUT) continue
                val extraJson = obj.optJSONObject("extraParams")
                val extra = if (extraJson != null) {
                    extraJson.keys().asSequence().associateWith { extraJson.optString(it, "") }
                } else emptyMap()
                list.add(InstallTask(
                    group = obj.getString("group"),
                    name = obj.getString("name"),
                    url = obj.optString("url", ""),
                    status = status,
                    error = obj.optString("error", null),
                    version = obj.optString("version", ""),
                    cache = obj.optString("cache", null),
                    extraParams = extra,
                    reareyeUri = obj.optString("reareyeUri", null)
                ))
            }
            _tasks.value = list
        } catch (_: Exception) { }
    }

    /**
     * Submit an install request. Returns JSON for the ContentProvider.
     */
    fun submitInstall(request: InstallRequest): String {
        val lockKey = "install:${request.group}:${request.name}"
        if (installLocks.putIfAbsent(lockKey, Any()) != null) {
            return """{"status":"installing"}"""
        }

        try {
            val ctx = appContext ?: run {
                installLocks.remove(lockKey)
                return """{"error":"InstallManager not initialized"}"""
            }
            val repo = JsRepository(ctx)

            // Version check: always performed, regardless of script file existence.
            // URIROUTE_VERSION is written to env.conf BEFORE async download starts,
            // so it persists even if the download fails.
            val versionNum = request.version.toFloatOrNull()
            if (versionNum == null) {
                installLocks.remove(lockKey)
                return """{"status":"skipped","reason":"version must be numeric"}"""
            }

            val envVars = repo.getEnvVars(request.group, request.name)
            val currentVerStr = envVars["URIROUTE_VERSION"]
            val currentVer = currentVerStr?.toFloatOrNull()

            if (currentVer != null && currentVer >= versionNum) {
                installLocks.remove(lockKey)
                return """{"status":"current"}"""
            }

            // ── Download flow ──
            startDownloadFlow(request, lockKey)
            return """{"status":"installing"}"""
        } catch (e: Exception) {
            installLocks.remove(lockKey)
            return """{"error":"${e.message?.replace("\"", "\\\"") ?: "unknown error"}"}"""
        }
    }

    /**
     * Download flow: synchronous setup then enqueue async download.
     * Does NOT check locks or versions — called directly by both submitInstall and retryInstall.
     */
    private fun startDownloadFlow(request: InstallRequest, lockKey: String) {
        val ctx = appContext ?: return
        val repo = JsRepository(ctx)

        // 1. Delete script directory & all contents
        repo.deleteScript(request.group, request.name)

        // 2. Configure cache
        val cacheSeconds = request.cache?.toIntOrNull()
        if (cacheSeconds != null && cacheSeconds > 0) {
            repo.saveCacheConfig(request.group, request.name, CacheConfig(enabled = true, durationSeconds = cacheSeconds))
        } else {
            repo.saveCacheConfig(request.group, request.name, CacheConfig(enabled = false))
        }

        // 3. Save extra params as env vars
        repo.saveEnvVars(request.group, request.name, request.extraParams)

        // 4. Set URIROUTE_VERSION into env vars
        val newEnvVars = LinkedHashMap(request.extraParams)
        newEnvVars["URIROUTE_VERSION"] = request.version
        repo.saveEnvVars(request.group, request.name, newEnvVars)

        // 5. Create temporary script (so /data queries show "installing" during download)
        repo.saveScriptContent(
            JsScript(request.group, request.name),
            """function run(){
    console.log("脚本下载中")
    uriRoute.add("status","installing")
}"""
        )

        // 6. Enqueue async downloader
        enqueueDownload(request, lockKey)
    }

    private fun enqueueDownload(request: InstallRequest, lockKey: String) {
        val task = InstallTask(
            group = request.group,
            name = request.name,
            url = request.url,
            status = InstallStatus.INSTALLING,
            version = request.version,
            cache = request.cache,
            extraParams = request.extraParams,
            reareyeUri = request.reareyeUri
        )
        _tasks.value = _tasks.value.filter { it.group != request.group || it.name != request.name } + task
        saveTasks()

        val notifyIdStart = nextNotifyId++
        val notifyIdDone = nextNotifyId++

        // Show "download started" notification
        showNotification(request.group, request.name, "开始下载", notifyIdStart)

        scope.launch {
            // Cancel the "started" notification
            notificationManager?.cancel(notifyIdStart)

            try {
                val (status, error) = performDownload(request)
                installLocks.remove(lockKey)
                if (status == InstallStatus.SUCCESS) {
                    // Remove from task list on success (概览中不再显示)
                    _tasks.value = _tasks.value.filter {
                        it.group != request.group || it.name != request.name
                    }
                } else {
                    _tasks.value = _tasks.value.map {
                        if (it.group == request.group && it.name == request.name)
                            it.copy(status = status, error = error)
                        else it
                    }
                }
                saveTasks()
                val title = when (status) {
                    InstallStatus.SUCCESS -> "安装成功"
                    InstallStatus.FAILED -> "下载失败"
                    InstallStatus.TIMEOUT -> "下载超时"
                    InstallStatus.INSTALLING -> "下载中"
                }
                showNotification(request.group, request.name, title, notifyIdDone)
            } catch (e: Exception) {
                installLocks.remove(lockKey)
                _tasks.value = _tasks.value.map {
                    if (it.group == request.group && it.name == request.name)
                        it.copy(status = InstallStatus.FAILED, error = e.message)
                    else it
                }
                saveTasks()
                showNotification(request.group, request.name, "下载失败", notifyIdDone)
            }
        }
    }

    private suspend fun performDownload(
        request: InstallRequest
    ): Pair<InstallStatus, String?> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext InstallStatus.FAILED to "未初始化"
        val repo = JsRepository(ctx)

        try {
            val content: String
            if (!request.reareyeUri.isNullOrBlank()) {
                // ── RearEye mode ────────────────────────────
                val reareyeJson = JSONObject(request.reareyeUri)
                val queryUri = Uri.parse("content://hk.uwu.reareye.archive.read").buildUpon()
                for (key in reareyeJson.keys()) {
                    queryUri.appendQueryParameter(key, reareyeJson.optString(key, ""))
                }
                val finalUri = queryUri.build()

                val cursor = ctx.contentResolver.query(finalUri, null, null, null, null)
                    ?: return@withContext InstallStatus.FAILED to "无法访问 reareye 服务"
                content = cursor.use { c ->
                    if (!c.moveToFirst()) {
                        return@withContext InstallStatus.FAILED to "reareye 服务返回为空"
                    }
                    val resultJson = c.getString(c.getColumnIndexOrThrow("json"))
                    val resultObj = JSONObject(resultJson)

                    if (!resultObj.optBoolean("success", false)) {
                        return@withContext InstallStatus.FAILED to
                                resultObj.optString("error", "reareye 返回错误")
                    }

                    val base64Content = resultObj.optString("contentBase64", "")
                    if (base64Content.isBlank()) {
                        return@withContext InstallStatus.FAILED to "reareye 返回的 contentBase64 为空"
                    }

                    String(Base64.decode(base64Content, Base64.DEFAULT), Charsets.UTF_8)
                }
            } else {
                // ── URL mode ────────────────────────────────
                val url = URL(request.url)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = DOWNLOAD_TIMEOUT_MS
                conn.readTimeout = DOWNLOAD_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.requestMethod = "GET"

                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext InstallStatus.FAILED to "HTTP $responseCode"
                }

                content = conn.inputStream.bufferedReader().use { it.readText() }
            }

            if (content.isBlank()) {
                return@withContext InstallStatus.FAILED to "下载内容为空"
            }

            repo.saveScriptContent(JsScript(request.group, request.name), content)
            InstallStatus.SUCCESS to null
        } catch (e: java.net.SocketTimeoutException) {
            InstallStatus.TIMEOUT to "下载超时"
        } catch (e: Exception) {
            InstallStatus.FAILED to (e.message ?: "未知错误")
        }
    }

    fun retryInstall(group: String, name: String) {
        val task = _tasks.value.find { it.group == group && it.name == name } ?: return
        val lockKey = "retry:${group}:${name}:${System.nanoTime()}"
        // Remove old task entry from UI
        _tasks.value = _tasks.value.filter { it.group != group || it.name != name }
        saveTasks()
        // Start download flow directly on IO dispatcher — no lock or version checks
        scope.launch {
            startDownloadFlow(task.toRequest(), lockKey)
        }
    }

    fun removeTask(group: String, name: String) {
        _tasks.value = _tasks.value.filter { it.group != group || it.name != name }
        saveTasks()
    }

    private fun showNotification(group: String, name: String, title: String, notifyId: Int) {
        val ctx = appContext ?: return
        val nm = notificationManager ?: return

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, notifyId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("$group / $name")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(notifyId, notification)
    }


    fun shutdown() {
        scope.cancel()
    }
}
