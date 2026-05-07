package com.uriroute.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uriroute.data.JsRepository
import com.uriroute.engine.InstallManager
import com.uriroute.engine.JsEngine
import com.uriroute.model.ExecResult
import com.uriroute.model.InstallStatus
import com.uriroute.model.JsScript
import com.uriroute.ui.component.ExecuteResultDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Overview tab — browse groups and scripts, execute scripts with custom params.
 *
 * @param onNavigateToEditor called when user clicks "转到编辑器" for a script
 */
@Composable
fun OverviewScreen(
    repository: JsRepository,
    onNavigateToEditor: (JsScript) -> Unit = {}
) {
    var groups by remember { mutableStateOf(repository.listGroups()) }
    var expandedGroup by remember { mutableStateOf<String?>(null) }
    var scripts by remember { mutableStateOf(emptyList<JsScript>()) }
    var selectedScript by remember { mutableStateOf<JsScript?>(null) }
    var showExecuteDialog by remember { mutableStateOf(false) }
    var executeParams by remember { mutableStateOf(emptyMap<String, String>()) }
    var executeResult by remember { mutableStateOf<ExecResult?>(null) }
    var executedUri by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }

    val installTasks by InstallManager.tasks.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Refresh groups
    LaunchedEffect(Unit) {
        groups = repository.listGroups()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Text(
            text = "概览",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Installed scripts section ──
            item {
                Text(
                    text = "已安装脚本",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (groups.isEmpty()) {
                item {
                    Text(
                        "暂无已安装的脚本",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(groups) { group ->
                    InstalledGroupCard(
                        group = group,
                        isExpanded = expandedGroup == group,
                        scripts = if (expandedGroup == group) scripts else emptyList(),
                        onClick = {
                            if (expandedGroup == group) {
                                expandedGroup = null
                            } else {
                                expandedGroup = group
                                scripts = repository.listScripts(group)
                            }
                        },
                        onExecute = { script ->
                            selectedScript = script
                            executeParams = repository.getEnvVars(script.group, script.name)
                            showExecuteDialog = true
                        },
                        onNavigateToEditor = onNavigateToEditor
                    )
                }
            }

            // ── Downloading scripts section ──
            if (installTasks.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "下载中的脚本",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(installTasks, key = { "${it.group}:${it.name}" }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onRetry = { InstallManager.retryInstall(task.group, task.name) },
                        onDelete = { InstallManager.removeTask(task.group, task.name) }
                    )
                }
            }
        }
    }

    // Execute with params dialog
    if (showExecuteDialog && selectedScript != null) {
        EnvParamsDialog(
            title = "执行 - ${selectedScript!!.name}",
            initialVars = executeParams,
            onDismiss = { showExecuteDialog = false },
            onExecute = { params ->
                val script = selectedScript!!
                val uri = buildExecuteUri(script, params)
                isExecuting = true

                coroutineScope.launch {
                    val content = repository.getScriptContent(script)
                    val imports = repository.listImports().map {
                        repository.getImportContent(it.name)
                    }.filter { it.isNotBlank() }
                    val envVars = withContext(Dispatchers.IO) {
                        repository.getEnvVars(script.group, script.name)
                    }

                    repository.clearCache(script.group, script.name)

                    val result = JsEngine().executeAsync(
                        JsEngine.ExecuteRequest(
                            scriptContent = content,
                            imports = imports,
                            envVars = envVars,
                            customParams = params,
                            group = script.group,
                            name = script.name
                        )
                    )

                    executedUri = uri
                    executeResult = result
                    showExecuteDialog = false
                    isExecuting = false
                }
            }
        )
    }

    // Loading indicator while JS is executing
    if (isExecuting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("执行中") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {}
        )
    }

    // Result dialog
    if (executeResult != null) {
        ExecuteResultDialog(
            uri = executedUri,
            result = executeResult!!,
            onDismiss = {
                executeResult = null
                executeParams = emptyMap()
            }
        )
    }
}

@Composable
private fun InstalledGroupCard(
    group: String,
    isExpanded: Boolean,
    scripts: List<JsScript>,
    onClick: () -> Unit,
    onExecute: (JsScript) -> Unit,
    onNavigateToEditor: (JsScript) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = group,
                style = MaterialTheme.typography.titleMedium
            )

            if (isExpanded) {
                Spacer(Modifier.height(8.dp))
                scripts.forEach { script ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = script.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { onExecute(script) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("执行", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onNavigateToEditor(script) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("转到编辑器", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: com.uriroute.model.InstallTask,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Open download URL in browser (only for URL mode)
                if (task.url.isNotBlank()) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(task.url)))
                    } catch (_: Exception) { }
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = if (task.status == InstallStatus.TIMEOUT)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${task.group} / ${task.name}",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (task.status == InstallStatus.INSTALLING) {
                    Text(
                        text = "下载中...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (task.error != null) {
                    Text(
                        text = task.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (task.status == InstallStatus.TIMEOUT || task.status == InstallStatus.FAILED) {
                    FilledTonalButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("重试", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun buildExecuteUri(script: JsScript, params: Map<String, String>): String {
    return buildString {
        append("content://uriroute/data?group=${script.group}&name=${script.name}")
        for ((k, v) in params) append("&$k=$v")
    }
}

@Composable
private fun EnvParamsDialog(
    title: String,
    initialVars: Map<String, String> = emptyMap(),
    onDismiss: () -> Unit,
    onExecute: (Map<String, String>) -> Unit
) {
    val entries = remember(initialVars) {
        if (initialVars.isEmpty()) mutableStateListOf(Pair("", ""))
        else mutableStateListOf<Pair<String, String>>().also { list ->
            initialVars.entries.forEach { (k, v) -> list.add(k to v) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 400.dp)
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(entries) { index, entry ->
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = entry.first,
                            onValueChange = { newKey ->
                                entries[index] = newKey to entry.second
                            },
                            label = { Text("Key") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = entry.second,
                            onValueChange = { newValue ->
                                entries[index] = entry.first to newValue
                            },
                            label = { Text("Value") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                entries.removeAt(index)
                                if (entries.isEmpty()) entries.add(Pair("", ""))
                            }
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                item {
                    TextButton(onClick = {
                        entries.add(Pair("", ""))
                    }) {
                        Text("+ 添加新行")
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    onClick = {
                        val valid = entries.filter { it.first.isNotBlank() }
                        onExecute(valid.toMap())
                    }
                ) { Text("立刻执行") }
            }
        }
    )
}
