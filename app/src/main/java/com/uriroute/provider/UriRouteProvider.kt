package com.uriroute.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.uriroute.data.JsRepository
import com.uriroute.engine.InstallManager
import com.uriroute.engine.JsEngine
import com.uriroute.model.InstallRequest
import com.uriroute.model.JsScript
import org.json.JSONObject

/**
 * ContentProvider that exposes JavaScript execution via URI:
 *   content://uriroute/data?group=xxx&name=xxx&custom=value
 *   content://uriroute/update?group=xxx&name=xxx
 *   content://uriroute/install?group=xxx&name=xxx&version=1.0&url=https://...&cache=60&key1=value1
 *
 * /data — execute script (or return cached result)
 * /update — clear the script's cache (no execution)
 * /install — download and install a script asynchronously
 *
 * Returns JSON result from the executed JS run() function.
 *
 * Supported paths: /data, /update, /install
 * Required params: group, name
 * Custom params: any additional query parameters (for /data)
 */
class UriRouteProvider : ContentProvider() {

    private lateinit var repository: JsRepository
    private val engine = JsEngine()
    private val scriptLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    companion object {
        private const val AUTHORITY = "uriroute"
        private val SUPPORTED_PATHS = setOf("data", "update", "install")
    }

    override fun onCreate(): Boolean {
        repository = JsRepository(context!!)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        // Validate authority
        if (uri.authority != AUTHORITY) {
            return errorCursor("Invalid authority: ${uri.authority}")
        }

        // Validate path
        val path = uri.path?.trim('/')?.lowercase() ?: ""
        if (path.isBlank()) {
            return errorCursor("Path is required: use /data, /update, or /install")
        }
        if (path !in SUPPORTED_PATHS) {
            return errorCursor("Unsupported path: /$path (use /data, /update, or /install)")
        }

        val group = uri.getQueryParameter("group") ?: ""
        val name = uri.getQueryParameter("name") ?: ""

        if (group.isBlank()) {
            return errorCursor("Parameter 'group' is required")
        }
        if (name.isBlank()) {
            return errorCursor("Parameter 'name' is required")
        }

        if (path == "install") {
            return handleInstall(uri, group, name)
        }

        if (path == "update") {
            // /update — clear cache only
            repository.clearCache(group, name)
            return resultCursor("""{"success":"缓存已清除"}""")
        }

        // /data — execute script (or return cached result)
        // Build custom params from URI (exclude reserved params)
        val reservedParams = setOf("group", "name")
        val customParams = mutableMapOf<String, String>()
        for (param in uri.queryParameterNames) {
            if (param !in reservedParams) {
                uri.getQueryParameter(param)?.let { value ->
                    if (value.isNotBlank()) {
                        customParams[param] = value
                    }
                }
            }
        }

        val script = JsScript(group, name)
        val scriptContent = repository.getScriptContent(script)

        if (scriptContent.isBlank()) {
            return errorCursor("Script not found: group='$group', name='$name'")
        }

        // Check cache for data requests
        val cached = repository.getCachedData(group, name, customParams)
        if (cached != null) {
            return resultCursor(cached)
        }

        // Per-script lock: serialize concurrent requests for the same script
        val lock = scriptLocks.getOrPut("$group:$name") { Any() }
        synchronized(lock) {
            // Double-check cache after acquiring lock
            val cachedAgain = repository.getCachedData(group, name, customParams)
            if (cachedAgain != null) {
                return resultCursor(cachedAgain)
            }

            // Load env vars
            val envVars = repository.getEnvVars(group, name)

            // Load imports
            val imports = repository.listImports().map { pkg ->
                repository.getImportContent(pkg.name)
            }.filter { it.isNotBlank() }

            // Execute
            val result = engine.execute(
                JsEngine.ExecuteRequest(
                    scriptContent = scriptContent,
                    imports = imports,
                    envVars = envVars,
                    customParams = customParams,
                    group = group,
                    name = name
                )
            )

            // Build result JSON safely
            val resultJson = if (result.error != null) {
                jsonEscape(mapOf("error" to result.error))
            } else {
                JSONObject(result.data).toString()
            }

            // Update cache on success
            if (result.error == null) {
                repository.saveCachedData(group, name, resultJson, customParams)
            }

            return resultCursor(resultJson)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun getType(uri: Uri): String? = "text/plain"

    /**
     * Handle /install path:
     *   content://uriroute/install?group=xxx&name=xxx&version=1.0&url=https://...&cache=60&key1=value1
     *   content://uriroute/install?group=xxx&name=xxx&version=1.0&reareyeUri={"mode":"","id":"","entry":""}&cache=60
     *
     * Required: group, name, version, and either url or reareyeUri
     * Optional: cache (seconds, must be integer)
     * Extra: any other params become environment variables
     */
    private fun handleInstall(uri: Uri, group: String, name: String): Cursor {
        val version = uri.getQueryParameter("version") ?: ""
        val url = uri.getQueryParameter("url") ?: ""
        val cache = uri.getQueryParameter("cache")
        val reareyeUri = uri.getQueryParameter("reareyeUri")

        if (version.isBlank()) {
            return errorCursor("Parameter 'version' is required for install")
        }
        if (url.isBlank() && reareyeUri.isNullOrBlank()) {
            return errorCursor("Parameter 'url' or 'reareyeUri' is required for install")
        }

        // Collect extra params (exclude reserved install params)
        val reserved = setOf("group", "name", "version", "url", "cache", "reareyeUri")
        val extraParams = mutableMapOf<String, String>()
        for (param in uri.queryParameterNames) {
            if (param !in reserved) {
                uri.getQueryParameter(param)?.let { value ->
                    if (value.isNotBlank()) {
                        extraParams[param] = value
                    }
                }
            }
        }

        val request = InstallRequest(
            group = group,
            name = name,
            version = version,
            url = url,
            cache = cache,
            extraParams = extraParams,
            reareyeUri = reareyeUri
        )

        val result = InstallManager.submitInstall(request)
        return resultCursor(result)
    }

    private fun errorCursor(message: String): Cursor {
        return resultCursor(jsonEscape(mapOf("error" to message)))
    }

    private fun resultCursor(json: String): Cursor {
        val cursor = MatrixCursor(arrayOf("result"))
        cursor.addRow(arrayOf(json))
        return cursor
    }

    private fun jsonEscape(data: Map<String, String>): String {
        return JSONObject(data).toString()
    }
}
