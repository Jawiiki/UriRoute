package com.uriroute.model

data class JsScript(
    val group: String,
    val name: String,
    val fileName: String = "$name.js"
)

data class ExecResult(
    val data: Map<String, String>,
    val logs: List<String>,
    val error: String? = null
)

data class CacheConfig(
    val enabled: Boolean = false,
    val durationSeconds: Int = 30
)

data class ImportedPackage(
    val name: String,
    val filePath: String,
    val sourceType: SourceType = SourceType.LOCAL
)

enum class SourceType {
    LOCAL, NETWORK
}

enum class ShellPermission {
    ROOT, SHIZUKU
}

enum class InstallStatus {
    INSTALLING, SUCCESS, FAILED, TIMEOUT
}

data class InstallTask(
    val group: String,
    val name: String,
    val url: String,
    val status: InstallStatus,
    val error: String? = null,
    val version: String = "",
    val cache: String? = null,
    val extraParams: Map<String, String> = emptyMap()
) {
    fun toRequest() = InstallRequest(group, name, version, url, cache, extraParams)
}

data class InstallRequest(
    val group: String,
    val name: String,
    val version: String,
    val url: String,
    val cache: String?,
    val extraParams: Map<String, String>
)
