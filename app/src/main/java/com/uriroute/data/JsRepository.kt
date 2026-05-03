package com.uriroute.data

import android.content.Context
import com.uriroute.model.CacheConfig
import com.uriroute.model.ImportedPackage
import com.uriroute.model.JsScript
import com.uriroute.model.ShellPermission
import com.uriroute.model.SourceType
import org.json.JSONObject
import java.io.File

/**
 * Repository managing JS files, environment variables, cache, and imports.
 *
 * Directory structure:
 *   js/<group>/<name>/<name>.js    — script file
 *   js/<group>/<name>/env.conf    — environment variables (JSON)
 *   js/<group>/<name>/cache       — cached result (text)
 *   js/<group>/<name>/cache.conf  — cache configuration (JSON)
 *   import/<name>/<name>.js      — imported JS packages
 */
class JsRepository(private val context: Context) {

    private val baseDir: File get() = File(context.filesDir, "js")
    private val importDir: File get() = File(context.filesDir, "import")

    companion object {
        // Characters not allowed in file/directory names on Android (Linux FS)
                // Illegal file name characters on Windows
        val ILLEGAL_CHARS = listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        private fun hasIllegalChar(name: String): Boolean = name.any { it in ILLEGAL_CHARS || it.code == 0 }
        private const val MAX_NAME_LENGTH = 100
    }

    init {
        val bundledFlag = File(importDir, ".bundled_initialized")
        if (!bundledFlag.exists()) {
            initBundledImports()
            initBundledScripts()
            bundledFlag.parentFile?.mkdirs()
            bundledFlag.createNewFile()
        }
    }

    // ── Validation ─────────────────────────────────────

    /**
     * Validates that a group or script name is safe for filesystem use.
     * Returns an error message string, or null if valid.
     */
    fun validateName(name: String): String? {
        if (name.isBlank()) return "名称不能为空"
        if (name.length > MAX_NAME_LENGTH) return "名称过长（最长${MAX_NAME_LENGTH}字符）"
        if (name.startsWith(".")) return "名称不能以点号开头"
        val illegalChar = name.firstOrNull { it.code == 0 || it in ILLEGAL_CHARS }
        if (illegalChar != null) return "名称包含非法字符「$illegalChar」"
        if (name.contains("..")) return "名称不能包含连续的点号"
        return null
    }

    // ── Group & Script listing ──────────────────────────

    fun listGroups(): List<String> {
        val dir = baseDir
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isDirectory }
            .map { it.name }
            .filter { !it.startsWith(".") }  // Skip hidden dirs
            .sorted()
    }

    fun listScripts(group: String): List<JsScript> {
        if (group.isBlank()) return emptyList()
        val dir = File(baseDir, group)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isDirectory }
            .map { JsScript(group, it.name) }
            .filter { !it.name.startsWith(".") }
            .sortedBy { it.name }
    }

    fun getScriptContent(script: JsScript): String {
        val file = getScriptFile(script)
        return try {
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun saveScriptContent(script: JsScript, content: String) {
        try {
            getScriptFile(script).apply {
                parentFile?.mkdirs()
                writeText(content)
            }
        } catch (e: Exception) {
            // File write failure — caller can check via getScriptContent
        }
    }

    fun getScriptFile(script: JsScript): File {
        return File(File(baseDir, script.group), "${script.name}/${script.fileName}")
    }

    // ── Group management ────────────────────────────────

    fun createGroup(group: String): Boolean {
        if (validateName(group) != null) return false
        val dir = File(baseDir, group)
        return !dir.exists() && dir.mkdirs()
    }

    fun renameGroup(oldName: String, newName: String): Boolean {
        if (validateName(newName) != null) return false
        val oldDir = File(baseDir, oldName)
        val newDir = File(baseDir, newName)
        if (!oldDir.exists()) return false
        if (newDir.exists()) return false  // prevent overwrite
        return oldDir.renameTo(newDir)
    }

    fun deleteGroup(group: String): Boolean {
        val dir = File(baseDir, group)
        return dir.exists() && dir.deleteRecursively()
    }

    // ── Script management ───────────────────────────────

    fun createScript(group: String, name: String): Boolean {
        if (validateName(group) != null) return false
        if (validateName(name) != null) return false
        val file = getScriptFile(JsScript(group, name))
        if (file.exists()) return false  // prevent overwrite
        file.parentFile?.mkdirs()
        return try {
            file.writeText("function run() {\n    \n}")
            true
        } catch (e: Exception) {
            false
        }
    }

    fun renameScript(group: String, oldName: String, newName: String): Boolean {
        if (validateName(newName) != null) return false
        val oldDir = File(File(baseDir, group), oldName)
        val newDir = File(File(baseDir, group), newName)
        if (!oldDir.exists()) return false
        if (newDir.exists()) return false  // prevent overwrite
        if (!oldDir.renameTo(newDir)) return false
        // Rename the JS file inside to match the new name
        val oldJsFile = File(newDir, "$oldName.js")
        val newJsFile = File(newDir, "$newName.js")
        if (oldJsFile.exists() && !newJsFile.exists()) {
            oldJsFile.renameTo(newJsFile)
        }
        return true
    }

    fun moveScript(group: String, name: String, newGroup: String): Boolean {
        if (validateName(newGroup) != null) return false
        val oldDir = File(File(baseDir, group), name)
        val newDir = File(File(baseDir, newGroup), name)
        if (!oldDir.exists()) return false
        if (newDir.exists()) return false  // prevent overwrite
        newDir.parentFile?.mkdirs()
        return oldDir.renameTo(newDir)
    }

    fun deleteScript(group: String, name: String): Boolean {
        val dir = File(File(baseDir, group), name)
        return dir.exists() && dir.deleteRecursively()
    }

    // ── Environment Variables ──────────────────────────

    fun getEnvVars(group: String, name: String): Map<String, String> {
        val file = getEnvFile(group, name)
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            json.keys().asSequence().associateWith { json.optString(it, "") }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveEnvVars(group: String, name: String, vars: Map<String, String>) {
        try {
            val file = getEnvFile(group, name)
            file.parentFile?.mkdirs()
            val json = JSONObject(vars.filter { (k, _) -> k.isNotBlank() })
            file.writeText(json.toString())
        } catch (e: Exception) {
            // Silently fail — env vars are non-critical
        }
    }

    private fun getEnvFile(group: String, name: String): File {
        return File(File(baseDir, "$group/$name"), "env.conf")
    }

    // ── Cache System ────────────────────────────────────

    /**
     * Compute a fingerprint string from custom params for use in cache keys.
     * Params are sorted by key to ensure deterministic output regardless of order.
     * Returns empty string if no params, so the base cache file is used.
     */
    private fun paramsFingerprint(customParams: Map<String, String>): String {
        if (customParams.isEmpty()) return ""
        val sorted = customParams.entries.sortedBy { it.key }
        val raw = sorted.joinToString("&") { "${it.key}=${it.value}" }
        return Integer.toHexString(raw.hashCode())
    }

    fun getCacheConfig(group: String, name: String): CacheConfig {
        val file = getCacheConfigFile(group, name)
        if (!file.exists()) return CacheConfig()
        return try {
            val json = JSONObject(file.readText())
            CacheConfig(
                enabled = json.optBoolean("enabled", false),
                durationSeconds = json.optInt("durationSeconds", 30)
            )
        } catch (e: Exception) {
            CacheConfig()
        }
    }

    fun saveCacheConfig(group: String, name: String, config: CacheConfig) {
        try {
            val file = getCacheConfigFile(group, name)
            file.parentFile?.mkdirs()
            val json = JSONObject().apply {
                put("enabled", config.enabled)
                put("durationSeconds", if (config.enabled) config.durationSeconds.coerceAtLeast(1) else 0)
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            // Silently fail
        }
    }

    fun getCachedData(group: String, name: String, customParams: Map<String, String> = emptyMap()): String? {
        val config = getCacheConfig(group, name)
        if (!config.enabled) return null

        val file = getCacheFile(group, name, customParams)
        if (!file.exists()) return null

        val lastModified = file.lastModified()
        if (lastModified <= 0) return null  // File timestamp issue

        val elapsed = System.currentTimeMillis() - lastModified
        val cacheDurationMs = config.durationSeconds.toLong() * 1_000L

        if (elapsed > cacheDurationMs) {
            file.delete()
            return null
        }
        return try {
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun saveCachedData(group: String, name: String, data: String, customParams: Map<String, String> = emptyMap()) {
        try {
            val file = getCacheFile(group, name, customParams)
            file.parentFile?.mkdirs()
            file.writeText(data)
            // Clean up expired cache variants after a successful save
            cleanupExpiredCache(group, name, file.name)
        } catch (e: Exception) {
            // Cache save failure is non-critical
        }
    }

    fun clearCache(group: String, name: String) {
        // Delete all cache variants for this script (but not cache.conf)
        val dir = File(File(baseDir, "$group/$name"), "cache")
        dir.parentFile?.listFiles()?.filter {
            it.name.startsWith("cache") && it.name != "cache.conf"
        }?.forEach { it.delete() }
    }

    /**
     * Remove expired cache_* files (excluding the current one).
     * This prevents orphaned cache entries from accumulating.
     */
    private fun cleanupExpiredCache(group: String, name: String, excludeName: String) {
        val config = getCacheConfig(group, name)
        if (!config.enabled) return
        val cacheDurationMs = config.durationSeconds.toLong() * 1_000L
        val now = System.currentTimeMillis()

        val dir = File(File(baseDir, "$group/$name"), "cache").parentFile ?: return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.name == excludeName || !file.name.startsWith("cache_")) continue
            val lastModified = file.lastModified()
            if (lastModified > 0 && (now - lastModified) > cacheDurationMs) {
                file.delete()
            }
        }
    }

    private fun getCacheConfigFile(group: String, name: String): File {
        return File(File(baseDir, "$group/$name"), "cache.conf")
    }

    private fun getCacheFile(group: String, name: String, customParams: Map<String, String> = emptyMap()): File {
        val fp = paramsFingerprint(customParams)
        val cacheName = if (fp.isEmpty()) "cache" else "cache_$fp"
        return File(File(baseDir, "$group/$name"), cacheName)
    }

    // ── Import System ───────────────────────────────────

    /**
     * Copy pre-bundled JS packages from assets/imports/ to the import directory
     * on first launch.
     */
    private fun initBundledImports() {
        try {
            val assets = context.assets
            val bundled = assets.list("imports") ?: return
            for (fileName in bundled) {
                if (!fileName.endsWith(".js")) continue
                val name = fileName.removeSuffix(".js")
                if (validateName(name) != null) continue
                val content = try {
                    assets.open("imports/$fileName").bufferedReader().readText()
                } catch (_: Exception) { continue }
                if (content.isBlank()) continue
                saveImport(name, content)
            }
        } catch (_: Exception) { }
    }

    /**
     * Copy pre-bundled scripts from assets/scripts/<group>/ to js/<group>/<name>/
     * on first launch.
     * If a .env file exists alongside the .js file, it's copied as env.conf.
     */
    private fun initBundledScripts() {
        try {
            val assets = context.assets
            val groups = assets.list("scripts") ?: return
            for (group in groups) {
                if (validateName(group) != null) continue
                val files = assets.list("scripts/$group") ?: continue
                for (fileName in files) {
                    if (!fileName.endsWith(".js")) continue
                    val name = fileName.removeSuffix(".js")
                    if (validateName(name) != null) continue
                    val content = try {
                        assets.open("scripts/$group/$fileName").bufferedReader().readText()
                    } catch (_: Exception) { continue }
                    if (content.isBlank()) continue
                    val script = JsScript(group, name)
                    saveScriptContent(script, content)

                    // Copy env vars if a matching .env file exists
                    val envName = "$name.env"
                    if (envName in files) {
                        val envContent = try {
                            assets.open("scripts/$group/$envName").bufferedReader().readText()
                        } catch (_: Exception) { continue }
                        if (envContent.isNotBlank()) {
                            getEnvFile(group, name).apply {
                                parentFile?.mkdirs()
                                writeText(envContent)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    fun listImports(): List<ImportedPackage> {
        val dir = importDir
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isDirectory }
            .mapNotNull { file ->
                val jsFile = File(file, "${file.name}.js")
                if (jsFile.exists()) {
                    ImportedPackage(
                        name = file.name,
                        filePath = jsFile.absolutePath,
                        sourceType = SourceType.LOCAL
                    )
                } else {
                    null  // Only list valid imports with actual JS files
                }
            }
            .sortedBy { it.name }
    }

    /** Check if an import with the given name already exists. */
    fun importExists(name: String): Boolean {
        val file = File(File(importDir, name), "$name.js")
        return file.exists()
    }

    fun getImportContent(name: String): String {
        val file = File(File(importDir, name), "$name.js")
        return try {
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun saveImport(name: String, content: String): Boolean {
        if (validateName(name) != null) return false
        return try {
            val file = File(File(importDir, name), "$name.js")
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun renameImport(oldName: String, newName: String): Boolean {
        if (validateName(newName) != null) return false
        val oldDir = File(importDir, oldName)
        val newDir = File(importDir, newName)
        if (!oldDir.exists()) return false
        if (newDir.exists()) return false
        return oldDir.renameTo(newDir)
    }

    fun deleteImport(name: String): Boolean {
        val dir = File(importDir, name)
        return dir.exists() && dir.deleteRecursively()
    }

    fun getScriptsUsingImport(importName: String): List<JsScript> {
        val results = mutableListOf<JsScript>()
        for (group in listGroups()) {
            for (script in listScripts(group)) {
                val content = getScriptContent(script)
                if (content.contains(importName)) {
                    results.add(script)
                }
            }
        }
        return results
    }

    // ── Shell Permission ────────────────────────────────

    private val prefs: android.content.SharedPreferences
        get() = context.getSharedPreferences("uriroute_settings", android.content.Context.MODE_PRIVATE)

    fun getShellPermission(): ShellPermission {
        val name = prefs.getString("shell_permission", ShellPermission.ROOT.name) ?: ShellPermission.ROOT.name
        return try { ShellPermission.valueOf(name) } catch (_: Exception) { ShellPermission.ROOT }
    }

    fun setShellPermission(permission: ShellPermission) {
        prefs.edit().putString("shell_permission", permission.name).apply()
    }
}
