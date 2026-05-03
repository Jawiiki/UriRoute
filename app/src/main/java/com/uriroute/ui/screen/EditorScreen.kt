package com.uriroute.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.uriroute.data.JsRepository
import com.uriroute.engine.JsEngine
import com.uriroute.model.CacheConfig
import com.uriroute.model.ExecResult
import com.uriroute.model.JsScript
import com.uriroute.ui.component.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * JS Editor tab — manage groups, scripts, edit code with syntax highlighting.
 *
 * @param initialScript if non-null, immediately opens the editor for this script
 * @param onEditorFullscreenChanged called when the code editor view opens/closes
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    repository: JsRepository,
    initialScript: JsScript? = null,
    onEditorFullscreenChanged: (Boolean) -> Unit = {}
) {
    var groups by remember { mutableStateOf(repository.listGroups()) }
    var expandedGroup by remember { mutableStateOf<String?>(null) }
    var scripts by remember { mutableStateOf(emptyList<JsScript>()) }
    var currentScript by remember { mutableStateOf<JsScript?>(null) }
    var scriptContent by remember { mutableStateOf(TextFieldValue("")) }
    var focusedScript by remember { mutableStateOf<JsScript?>(null) }
    val listState = rememberLazyListState()

    // Dialogs
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var showNewScriptDialog by remember { mutableStateOf(false) }
    var showRenameGroupDialog by remember { mutableStateOf(false) }
    var showDeleteGroupConfirm by remember { mutableStateOf(false) }
    var showRenameScriptDialog by remember { mutableStateOf(false) }
    var showMoveScriptDialog by remember { mutableStateOf(false) }
    var showDeleteScriptConfirm by remember { mutableStateOf(false) }
    var showEnvDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }
    var showExecuteDialog by remember { mutableStateOf(false) }
    var showExecuteResult by remember { mutableStateOf<ExecResult?>(null) }
    var executedUri by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // State holders
    var groupToRename by remember { mutableStateOf("") }
    var scriptToRename by remember { mutableStateOf<JsScript?>(null) }
    var scriptToMove by remember { mutableStateOf<JsScript?>(null) }
    var envVars by remember { mutableStateOf(emptyMap<String, String>()) }
    var cacheConfig by remember { mutableStateOf(CacheConfig()) }
    var editingEnvVars by remember { mutableStateOf(emptyMap<String, String>()) }
    var envScriptTarget by remember { mutableStateOf<JsScript?>(null) }
    var cacheScriptTarget by remember { mutableStateOf<JsScript?>(null) }
    var executeScriptTarget by remember { mutableStateOf<JsScript?>(null) }

    var selectedForLongPress by remember { mutableStateOf<Any?>(null) }
    var importConflictName by remember { mutableStateOf("") }
    var importConflictContent by remember { mutableStateOf("") }
    var showImportConflictDialog by remember { mutableStateOf(false) }
    var pendingConflicts by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var pendingConflictIndex by remember { mutableIntStateOf(0) }

    var scriptToSave by remember { mutableStateOf<JsScript?>(null) }
    var groupToSave by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    // Launcher: save a single script file to user-chosen location
    val saveScriptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/javascript")
    ) { uri ->
        val script = scriptToSave
        if (uri != null && script != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(repository.getScriptContent(script).toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) { }
        }
        scriptToSave = null
    }

    // Launcher: save all scripts in a group to user-chosen directory
    val saveGroupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val group = groupToSave
        if (uri != null && group != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val parentDoc = DocumentFile.fromTreeUri(context, uri)
            if (parentDoc != null) {
                for (script in repository.listScripts(group)) {
                    val content = repository.getScriptContent(script)
                    try {
                        val file = parentDoc.createFile("text/javascript", "${script.name}.js")
                        file?.let {
                            context.contentResolver.openOutputStream(it.uri)?.use { out ->
                                out.write(content.toByteArray(Charsets.UTF_8))
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        }
        groupToSave = null
    }

    // Handle initialScript: expand group and highlight script without opening editor
    LaunchedEffect(initialScript) {
        if (initialScript != null) {
            expandedGroup = initialScript.group
            scripts = repository.listScripts(initialScript.group)
            focusedScript = initialScript
            // Scroll to the group containing the script
            val groupIndex = groups.indexOf(initialScript.group)
            if (groupIndex >= 0) {
                listState.animateScrollToItem(groupIndex)
            }
        }
    }
    // Clear focus highlight after 2 seconds
    LaunchedEffect(focusedScript) {
        if (focusedScript != null) {
            kotlinx.coroutines.delay(2000)
            focusedScript = null
        }
    }

    // Notify parent when editor fullscreen state changes
    LaunchedEffect(currentScript) {
        onEditorFullscreenChanged(currentScript != null)
    }

    /** Perform the actual import of a JS file into the given group. */
    fun doImport(name: String, content: String, repo: JsRepository, group: String = "default") {
        val script = JsScript(group, name)
        repo.saveScriptContent(script, content)
        scripts = repo.listScripts(expandedGroup ?: "")
        groups = repo.listGroups()
    }

    /** Check if a script with the given name already exists in the target group. */
    fun scriptConflict(group: String, name: String, repo: JsRepository): Boolean {
        return repo.getScriptFile(JsScript(group, name)).exists()
    }

    /** Resolve the display name of a content URI. */
    fun getFileName(context: android.content.Context, uri: android.net.Uri): String {
        var name = "imported.js"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: "imported.js"
            }
        }
        return name
    }

    /** Process the next pending conflict, or finish. */
    fun processNextConflict() {
        pendingConflictIndex++
        if (pendingConflictIndex < pendingConflicts.size) {
            importConflictName = pendingConflicts[pendingConflictIndex].first
            importConflictContent = pendingConflicts[pendingConflictIndex].second
            showImportConflictDialog = true
        } else {
            showImportConflictDialog = false
            pendingConflicts = emptyList()
        }
    }

    // File picker for import (multi-file)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val group = expandedGroup ?: "default"
        val noConflict = mutableListOf<Pair<String, String>>()
        val conflictList = mutableListOf<Pair<String, String>>()

        for (uri in uris) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: continue
                val reader = BufferedReader(InputStreamReader(inputStream))
                val content = reader.readText()
                reader.close()

                val fileName = getFileName(context, uri)
                val name = fileName.removeSuffix(".js")

                if (scriptConflict(group, name, repository)) {
                    conflictList.add(Pair(name, content))
                } else {
                    noConflict.add(Pair(name, content))
                }
            } catch (_: Exception) { }
        }

        // Import non-conflicting files immediately
        for ((name, content) in noConflict) {
            doImport(name, content, repository, group)
        }

        // Queue conflicts for sequential dialog
        if (conflictList.isNotEmpty()) {
            pendingConflicts = conflictList
            pendingConflictIndex = 0
            importConflictName = conflictList[0].first
            importConflictContent = conflictList[0].second
            showImportConflictDialog = true
        }
    }

    LaunchedEffect(Unit) {
        groups = repository.listGroups()
    }

    // System back button: in code editor → exit editor; otherwise let parent handle
    BackHandler(enabled = currentScript != null) {
        currentScript?.let { repository.saveScriptContent(it, scriptContent.text) }
        currentScript = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with actions — only show when NOT in code editor
        if (currentScript == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "JS编辑器",
                    style = MaterialTheme.typography.headlineMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        if (expandedGroup != null) {
                            showNewScriptDialog = true
                        } else {
                            showNewGroupDialog = true
                        }
                    }) {
                        Text("新增")
                    }
                    FilledTonalButton(onClick = {
                        importLauncher.launch("*/*")
                    }) {
                        Text("导入")
                    }
                }
            }
        }

        if (currentScript == null) {
            // Group/Script listing
            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "暂无数据",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showNewGroupDialog = true }) {
                            Text("新建分组")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(groups) { group ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (expandedGroup == group) {
                                            expandedGroup = null
                                        } else {
                                            expandedGroup = group
                                            scripts = repository.listScripts(group)
                                        }
                                    },
                                    onLongClick = {
                                        groupToRename = group
                                        selectedForLongPress = group
                                        showRenameGroupDialog = true
                                    }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "📁 $group",
                                    style = MaterialTheme.typography.titleMedium
                                )

                                if (expandedGroup == group) {
                                    Spacer(Modifier.height(8.dp))
                                    if (scripts.isEmpty()) {
                                        Text(
                                            "暂无脚本",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        scripts.forEach { script ->
                                            ScriptItem(
                                                script = script,
                                                isFocused = focusedScript == script,
                                                onExecute = {
                                                    executeScriptTarget = script
                                                    envVars = repository.getEnvVars(script.group, script.name)
                                                    showExecuteDialog = true
                                                },
                                                onEdit = {
                                                    currentScript = script
                                                    scriptContent = TextFieldValue(repository.getScriptContent(script))
                                                },
                                                onCache = {
                                                    cacheScriptTarget = script
                                                    cacheConfig = repository.getCacheConfig(script.group, script.name)
                                                    showCacheDialog = true
                                                },
                                                onEnv = {
                                                    envScriptTarget = script
                                                    editingEnvVars = repository.getEnvVars(script.group, script.name)
                                                    showEnvDialog = true
                                                },
                                                onLongClick = {
                                                    scriptToRename = script
                                                    selectedForLongPress = script
                                                    showRenameScriptDialog = true
                                                }
                                            )
                                            Spacer(Modifier.height(4.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Code editor view
            CodeEditorView(
                script = currentScript!!,
                content = scriptContent,
                onContentChange = { scriptContent = it },
                onBack = {
                    currentScript?.let { repository.saveScriptContent(it, scriptContent.text) }
                    currentScript = null
                },
                repository = repository
            )
        }
    }

    // ── Dialogs ─────────────────────────────────────────

    if (showNewGroupDialog) {
        NewNameDialog(
            title = "新建分组",
            hint = "输入分组名称",
            onDismiss = { showNewGroupDialog = false },
            validate = { name ->
                val formatError = repository.validateName(name)
                if (formatError != null) return@NewNameDialog formatError
                if (repository.listGroups().contains(name)) return@NewNameDialog "分组已存在"
                null
            },
            onConfirm = { name ->
                if (repository.createGroup(name)) {
                    groups = repository.listGroups()
                }
                showNewGroupDialog = false
            }
        )
    }

    if (showNewScriptDialog) {
        NewNameDialog(
            title = "新建脚本",
            hint = "输入脚本Name名称",
            onDismiss = { showNewScriptDialog = false },
            validate = { name ->
                val formatError = repository.validateName(name)
                if (formatError != null) return@NewNameDialog formatError
                val group = expandedGroup ?: "default"
                if (repository.listScripts(group).any { it.name == name }) return@NewNameDialog "脚本已存在"
                null
            },
            onConfirm = { name ->
                val group = expandedGroup ?: "default"
                if (repository.createScript(group, name)) {
                    scripts = repository.listScripts(group)
                }
                showNewScriptDialog = false
            }
        )
    }

    if (showRenameGroupDialog) {
        GroupActionDialog(
            groupName = groupToRename,
            onDismiss = {
                showRenameGroupDialog = false
                selectedForLongPress = null
            },
            validate = { repository.validateName(it) },
            onRename = { newName ->
                if (repository.renameGroup(groupToRename, newName)) {
                    groups = repository.listGroups()
                    if (expandedGroup == groupToRename) expandedGroup = newName
                }
                showRenameGroupDialog = false
            },
            onDelete = {
                showRenameGroupDialog = false
                groupToRename = groupToRename
                showDeleteGroupConfirm = true
            },
            onSaveToLocal = {
                groupToSave = groupToRename
                showRenameGroupDialog = false
                saveGroupLauncher.launch(null)
            }
        )
    }

    if (showDeleteGroupConfirm) {
        DeleteConfirmDialog(
            title = "删除分组",
            message = "确定要删除分组「$groupToRename」及其所有脚本吗？",
            onDismiss = { showDeleteGroupConfirm = false },
            onConfirm = {
                repository.deleteGroup(groupToRename)
                groups = repository.listGroups()
                if (expandedGroup == groupToRename) expandedGroup = null
                showDeleteGroupConfirm = false
            }
        )
    }

    if (showRenameScriptDialog && scriptToRename != null) {
        ScriptActionDialog(
            script = scriptToRename!!,
            groups = groups,
            onDismiss = {
                showRenameScriptDialog = false
                scriptToRename = null
            },
            validate = { repository.validateName(it) },
            onRename = { newName ->
                if (repository.renameScript(scriptToRename!!.group, scriptToRename!!.name, newName)) {
                    scripts = repository.listScripts(expandedGroup ?: "")
                }
                showRenameScriptDialog = false
                scriptToRename = null
            },
            onMoveGroup = { newGroup ->
                if (repository.moveScript(scriptToRename!!.group, scriptToRename!!.name, newGroup)) {
                    scripts = repository.listScripts(expandedGroup ?: "")
                }
                showRenameScriptDialog = false
                scriptToRename = null
            },
            onDelete = {
                showRenameScriptDialog = false
                showDeleteScriptConfirm = true
            },
            onSaveToLocal = {
                scriptToSave = scriptToRename
                showRenameScriptDialog = false
                saveScriptLauncher.launch("${scriptToRename!!.name}.js")
            }
        )
    }

    if (showMoveScriptDialog && scriptToMove != null) {
        MoveScriptDialog(
            script = scriptToMove!!,
            groups = groups,
            onDismiss = { showMoveScriptDialog = false },
            onConfirm = { newGroup ->
                repository.moveScript(scriptToMove!!.group, scriptToMove!!.name, newGroup)
                scripts = repository.listScripts(expandedGroup ?: "")
                showMoveScriptDialog = false
                scriptToMove = null
            }
        )
    }

    if (showDeleteScriptConfirm && scriptToRename != null) {
        DeleteConfirmDialog(
            title = "删除脚本",
            message = "确定要删除脚本「${scriptToRename!!.name}」吗？",
            onDismiss = { showDeleteScriptConfirm = false },
            onConfirm = {
                repository.deleteScript(scriptToRename!!.group, scriptToRename!!.name)
                scripts = repository.listScripts(expandedGroup ?: "")
                showDeleteScriptConfirm = false
                scriptToRename = null
            }
        )
    }

    if (showEnvDialog && envScriptTarget != null) {
        EnvVarDialog(
            title = "环境变量 - ${envScriptTarget!!.name}",
            initialVars = editingEnvVars,
            onDismiss = { showEnvDialog = false },
            onSave = { vars ->
                repository.saveEnvVars(envScriptTarget!!.group, envScriptTarget!!.name, vars)
                showEnvDialog = false
            }
        )
    }

    if (showCacheDialog && cacheScriptTarget != null) {
        CacheDialog(
            initialConfig = cacheConfig,
            onDismiss = { showCacheDialog = false },
            onSave = { config ->
                repository.saveCacheConfig(cacheScriptTarget!!.group, cacheScriptTarget!!.name, config)
                showCacheDialog = false
            }
        )
    }

    if (showExecuteDialog && executeScriptTarget != null) {
        val script = executeScriptTarget!!
        EnvVarDialog(
            title = "执行 - ${script.name}",
            initialVars = envVars,
            showExecuteButton = true,
            onDismiss = { showExecuteDialog = false },
            onSave = { /* env saving is handled via env button */ },
            onExecute = { params ->
                isExecuting = true
                coroutineScope.launch {
                    val content = repository.getScriptContent(script)
                    val imports = repository.listImports().map {
                        repository.getImportContent(it.name)
                    }.filter { it.isNotBlank() }
                    val envVarsData = withContext(Dispatchers.IO) {
                        repository.getEnvVars(script.group, script.name)
                    }

                    // Clear cache then execute
                    repository.clearCache(script.group, script.name)
                    val result = JsEngine().executeAsync(
                        JsEngine.ExecuteRequest(
                            scriptContent = content,
                            imports = imports,
                            envVars = envVarsData,
                            customParams = params,
                            group = script.group,
                            name = script.name
                        )
                    )

                    val uri = buildString {
                        append("content://uriroute/data?group=${script.group}&name=${script.name}")
                        for ((k, v) in params) append("&$k=$v")
                    }

                    executedUri = uri
                    showExecuteResult = result
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

    if (showExecuteResult != null) {
        ExecuteResultDialog(
            uri = executedUri,
            result = showExecuteResult!!,
            onDismiss = {
                showExecuteResult = null
                showExecuteDialog = false
            }
        )
    }

    // Import conflict dialog: ask user whether to overwrite existing import
    if (showImportConflictDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportConflictDialog = false
                pendingConflicts = emptyList()
                importConflictName = ""
                importConflictContent = ""
            },
            title = { Text("导入冲突 (${pendingConflictIndex + 1}/${pendingConflicts.size})") },
            text = {
                Text("导入包「$importConflictName」已存在。是否覆盖？")
            },
            confirmButton = {
                TextButton(onClick = {
                    // Skip this file, process next conflict
                    processNextConflict()
                }) { Text("跳过") }
                Button(onClick = {
                    val group = expandedGroup ?: "default"
                    doImport(importConflictName, importConflictContent, repository, group)
                    processNextConflict()
                }) { Text("覆盖") }
            }
        )
    }
}

// ── Sub-components ──────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptItem(
    script: JsScript,
    isFocused: Boolean = false,
    onExecute: () -> Unit,
    onEdit: () -> Unit,
    onCache: () -> Unit,
    onEnv: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
        color = if (isFocused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = script.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SmallButton("执行", onExecute)
                SmallButton("编辑器", onEdit)
                SmallButton("缓存", onCache)
                SmallButton("环境变量", onEnv)
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

// ── Code Editor View with Undo/Redo ─────────────────────

@Composable
private fun CodeEditorView(
    script: JsScript,
    content: TextFieldValue,
    onContentChange: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
    repository: JsRepository
) {
    var showRunParams by remember { mutableStateOf(false) }
    var runParams by remember { mutableStateOf(repository.getEnvVars(script.group, script.name)) }
    var showResult by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ExecResult?>(null) }
    var resultUri by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // ── Undo/Redo history ─────────────────────────────────
    var undoHistory by remember { mutableStateOf(listOf(content)) }
    var undoIndex by remember { mutableIntStateOf(0) }
    var isUndoRedo by remember { mutableStateOf(false) }
    var lastSnapshotText by remember { mutableStateOf(content.text) }

    val canUndo = undoIndex > 0
    val canRedo = undoIndex < undoHistory.size - 1

    fun pushHistory(snapshot: TextFieldValue) {
        val newHistory = undoHistory.take(undoIndex + 1) + snapshot
        undoHistory = if (newHistory.size > 50) newHistory.drop(newHistory.size - 50) else newHistory
        undoIndex = undoHistory.size - 1
    }

    fun handleContentChange(newValue: TextFieldValue) {
        if (isUndoRedo) {
            // When undoing/redoing, just update without pushing to history
            onContentChange(newValue)
            return
        }
        onContentChange(newValue)
        // Push to history when text actually changes
        if (newValue.text != lastSnapshotText) {
            lastSnapshotText = newValue.text
            pushHistory(newValue)
        }
    }

    fun undo() {
        if (!canUndo) return
        isUndoRedo = true
        undoIndex--
        val restored = undoHistory[undoIndex]
        onContentChange(restored)
        lastSnapshotText = restored.text
        isUndoRedo = false
    }

    fun redo() {
        if (!canRedo) return
        isUndoRedo = true
        undoIndex++
        val restored = undoHistory[undoIndex]
        onContentChange(restored)
        lastSnapshotText = restored.text
        isUndoRedo = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                // Row 1: script name (larger font)
                Text(
                    text = "${script.group}/${script.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                // Row 2: buttons (smaller)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton(
                            onClick = {
                                repository.saveScriptContent(script, content.text)
                                onBack()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("← 返回", style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(
                            onClick = { undo() },
                            enabled = canUndo,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text("↩", style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(
                            onClick = { redo() },
                            enabled = canRedo,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text("↪", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Button(
                        onClick = {
                            showRunParams = true
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "运行",
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        // Editor
        JsCodeEditor(
            value = content,
            onValueChange = { handleContentChange(it) },
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        )
    }

    // Auto-save timer
    LaunchedEffect(content.text) {
        kotlinx.coroutines.delay(2000)
        repository.saveScriptContent(script, content.text)
    }

    if (showRunParams) {
        EnvVarDialog(
            title = "运行参数 - ${script.name}",
            initialVars = runParams,
            showExecuteButton = true,
            hideSave = true,
            onDismiss = { showRunParams = false },
            onSave = { params ->
                runParams = params
            },
            onExecute = { params ->
                isExecuting = true
                coroutineScope.launch {
                    val imports = repository.listImports().map {
                        repository.getImportContent(it.name)
                    }.filter { it.isNotBlank() }

                    repository.clearCache(script.group, script.name)

                    val execResult = JsEngine().executeAsync(
                        JsEngine.ExecuteRequest(
                            scriptContent = content.text,
                            imports = imports,
                            envVars = params,
                            customParams = params,
                            group = script.group,
                            name = script.name
                        )
                    )

                    val uri = buildString {
                        append("content://uriroute/data?group=${script.group}&name=${script.name}")
                        for ((k, v) in params) append("&$k=$v")
                    }

                    resultUri = uri
                    result = execResult
                    showRunParams = false
                    showResult = true
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

    if (showResult && result != null) {
        ExecuteResultDialog(
            uri = resultUri,
            result = result!!,
            onDismiss = {
                showResult = false
                result = null
            }
        )
    }
}

// ── Reusable Dialogs ────────────────────────────────────

@Composable
private fun NewNameDialog(
    title: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    validate: ((String) -> String?)? = null
) {
    var name by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val isNameValid by remember {
        derivedStateOf {
            if (name.isBlank()) false
            else {
                val error = validate?.invoke(name)
                errorMsg = error
                error == null
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        errorMsg = null
                    },
                    label = { Text(hint) },
                    singleLine = true,
                    isError = errorMsg != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(
                onClick = { onConfirm(name) },
                enabled = isNameValid
            ) { Text("确定") }
        }
    )
}

@Composable
private fun DeleteConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) { Text("删除") }
        }
    )
}

@Composable
private fun MoveScriptDialog(
    script: JsScript,
    groups: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selectedGroup by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到分组") },
        text = {
            Column {
                groups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedGroup == group,
                            onClick = { selectedGroup = group }
                        )
                        Text(group, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(
                onClick = { onConfirm(selectedGroup) },
                enabled = selectedGroup.isNotBlank() && selectedGroup != script.group
            ) { Text("确定") }
        }
    )
}

@Composable
private fun GroupActionDialog(
    groupName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onSaveToLocal: () -> Unit,
    validate: ((String) -> String?)? = null
) {
    var newName by remember { mutableStateOf(groupName) }
    var renameError by remember { mutableStateOf<String?>(null) }
    val isRenameValid by remember {
        derivedStateOf {
            if (newName.isBlank() || newName == groupName) false
            else {
                val err = validate?.invoke(newName)
                renameError = err
                err == null
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分组操作 - $groupName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        renameError = null
                    },
                    label = { Text("重命名") },
                    singleLine = true,
                    isError = renameError != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (renameError != null) {
                    Text(
                        text = renameError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSaveToLocal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存至本地")
                }
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除分组（含所有脚本）")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(
                onClick = { onRename(newName) },
                enabled = isRenameValid
            ) { Text("重命名") }
        }
    )
}

@Composable
private fun ScriptActionDialog(
    script: JsScript,
    groups: List<String>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onMoveGroup: (String) -> Unit,
    onDelete: () -> Unit,
    onSaveToLocal: () -> Unit,
    validate: ((String) -> String?)? = null
) {
    var newName by remember { mutableStateOf(script.name) }
    var renameError by remember { mutableStateOf<String?>(null) }
    val isRenameValid by remember {
        derivedStateOf {
            if (newName.isBlank() || newName == script.name) false
            else {
                val err = validate?.invoke(newName)
                renameError = err
                err == null
            }
        }
    }
    var selectedGroup by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("脚本操作 - ${script.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        renameError = null
                    },
                    label = { Text("重命名") },
                    singleLine = true,
                    isError = renameError != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (renameError != null) {
                    Text(
                        text = renameError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text("移动到分组:", style = MaterialTheme.typography.labelMedium)
                groups.filter { it != script.group }.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedGroup == group,
                            onClick = { selectedGroup = group }
                        )
                        Text(group, modifier = Modifier.padding(start = 8.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSaveToLocal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存至本地")
                }
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除脚本")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
            if (selectedGroup.isNotBlank()) {
                Button(onClick = { onMoveGroup(selectedGroup) }) {
                    Text("移动到「$selectedGroup」")
                }
            }
            Button(
                onClick = { onRename(newName) },
                enabled = isRenameValid
            ) { Text("重命名") }
        }
    )
}
