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
