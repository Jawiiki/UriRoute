package com.uriroute.ui.screen

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import com.uriroute.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uriroute.data.JsRepository
import com.uriroute.engine.ShellManager
import com.uriroute.engine.ShizukuShell
import com.uriroute.model.ImportedPackage
import com.uriroute.model.ShellPermission
import com.uriroute.ui.component.JsCodeEditor
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Settings tab — manage imported JS packages, acknowledgements.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(repository: JsRepository) {
    var showImportPage by remember { mutableStateOf(false) }
    var showAcknowledgements by remember { mutableStateOf(false) }

    if (showImportPage) {
        ImportManagementPage(
            repository = repository,
            onBack = { showImportPage = false }
        )
    } else {
        SettingsMenu(
            repository = repository,
            onImportClick = { showImportPage = true },
            onAcknowledgementsClick = { showAcknowledgements = true }
        )
    }

    if (showAcknowledgements) {
        AcknowledgementsDialog(onDismiss = { showAcknowledgements = false })
    }
}

@Composable
private fun SettingsMenu(
    repository: JsRepository,
    onImportClick: () -> Unit,
    onAcknowledgementsClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateState?>(null) }
    var showShellDialog by remember { mutableStateOf(false) }
    var shellPermission by remember { mutableStateOf(repository.getShellPermission()) }
    var shizukuStatusMsg by remember { mutableStateOf<String?>(null) }

    // Listen for Shizuku permission result
    val shizukuPermissionListener = remember {
        ShizukuShell.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHELL_PERMISSION_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                ShellManager.setShizukuState(
                    available = ShizukuShell.pingBinder(),
                    granted = granted
                )
                shizukuStatusMsg = if (granted) "Shizuku 权限已授予" else "Shizuku 权限被拒绝"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            shizukuPermissionListener.let { ShizukuShell.removeRequestPermissionResultListener(it) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp)
        )

        Card(
            onClick = onImportClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "导入的JS包",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = ">",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Shell 权限来源 ──────────────────────────────
        Card(
            onClick = { showShellDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Shell权限来源",
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (shellPermission == ShellPermission.ROOT) "Root" else "Shizuku",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = ">",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // ── 致谢 ───────────────────────────────────────────
        Card(
            onClick = onAcknowledgementsClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "致谢",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = ">",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── CoolAPK ─────────────────────────────────────
        Card(
            onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("coolapk://u/1849451"))
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.coolapk.com/u/1849451"))
                    context.startActivity(intent)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "酷安主页",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = ">",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── GitHub 仓库 ────────────────────────────────
        Card(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Jawiiki/UriRoute"))
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GitHub 仓库",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = ">",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 检查更新 ──────────────────────────────────
        Card(
            onClick = {
                if (isCheckingUpdate) return@Card
                isCheckingUpdate = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val url = URL("https://api.github.com/repos/Jawiiki/UriRoute/releases/latest")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "GET"
                            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                            conn.connectTimeout = 10000
                            conn.readTimeout = 10000
                            if (conn.responseCode == 200) {
                                val json = conn.inputStream.bufferedReader().readText()
                                val obj = JSONObject(json)
                                UpdateResult(
                                    tagName = obj.getString("tag_name"),
                                    releaseUrl = obj.getString("html_url"),
                                    body = obj.optString("body", "")
                                )
                            } else null
                        } catch (_: Exception) { null }
                    }
                    updateState = if (result != null) {
                        val currentVer = BuildConfig.VERSION_NAME
                        val latestVer = result.tagName.removePrefix("v")
                        if (currentVer == latestVer) UpdateState.Current
                        else UpdateState.Available(result)
                    } else {
                        UpdateState.Failed
                    }
                    isCheckingUpdate = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            enabled = !isCheckingUpdate
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isCheckingUpdate) "检查中..." else "检查更新",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    // ── Shell 权限来源对话框 ──────────────────────────
    if (showShellDialog) {
        ShellPermissionDialog(
            current = shellPermission,
            shizukuStatusMsg = shizukuStatusMsg,
            onSelect = { selected ->
                shellPermission = selected
                repository.setShellPermission(selected)
                ShellManager.setPermission(selected)

                if (selected == ShellPermission.SHIZUKU) {
                    if (!ShizukuShell.pingBinder()) {
                        shizukuStatusMsg = "Shizuku 服务未运行，请先启动 Shizuku"
                    } else if (!ShizukuShell.isPermissionGranted()) {
                        try {
                            ShizukuShell.requestPermission(SHELL_PERMISSION_REQUEST_CODE)
                            shizukuStatusMsg = "正在请求 Shizuku 权限..."
                        } catch (e: Exception) {
                            shizukuStatusMsg = "请求 Shizuku 权限失败: ${e.message}"
                        }
                    } else {
                        shizukuStatusMsg = "Shizuku 权限已就绪"
                    }
                } else {
                    shizukuStatusMsg = null
                }
            },
            onDismiss = { showShellDialog = false }
        )
    }

    // ── Update dialog ─────────────────────────────────
    if (updateState != null) {
        val state = updateState!!
        AlertDialog(
            onDismissRequest = { updateState = null },
            title = {
                Text(
                    text = when (state) {
                        is UpdateState.Available -> "发现新版本"
                        is UpdateState.Current -> "已是最新版本"
                        is UpdateState.Failed -> "检查失败"
                    }
                )
            },
            text = {
                when (state) {
                    is UpdateState.Available -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("最新版本：${state.result.tagName}")
                            Text("当前版本：v${BuildConfig.VERSION_NAME}")
                            HorizontalDivider()
                            Text(
                                text = state.result.body.ifBlank { "暂无更新说明" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is UpdateState.Current -> {
                        Text("当前已是最新版本（v${BuildConfig.VERSION_NAME}）")
                    }
                    is UpdateState.Failed -> {
                        Text("检查更新失败，请检查网络连接后重试。")
                    }
                }
            },
            confirmButton = {
                when (state) {
                    is UpdateState.Available -> {
                        Button(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.result.releaseUrl))
                            context.startActivity(intent)
                            updateState = null
                        }) { Text("下载") }
                        TextButton(onClick = { updateState = null }) { Text("取消") }
                    }
                    else -> {
                        TextButton(onClick = { updateState = null }) { Text("关闭") }
                    }
                }
            }
        )
    }
}

private const val SHELL_PERMISSION_REQUEST_CODE = 1001

@Composable
private fun ShellPermissionDialog(
    current: ShellPermission,
    shizukuStatusMsg: String?,
    onSelect: (ShellPermission) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shell权限来源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "选择 uriRoute.shell 使用的权限来源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.selectableGroup()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = current == ShellPermission.ROOT,
                                onClick = { onSelect(ShellPermission.ROOT); onDismiss() },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = current == ShellPermission.ROOT,
                            onClick = null
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Root", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "通过 su 命令获取 root 权限执行 shell",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = current == ShellPermission.SHIZUKU,
                                onClick = { onSelect(ShellPermission.SHIZUKU); onDismiss() },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = current == ShellPermission.SHIZUKU,
                            onClick = null
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Shizuku", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "通过 Shizuku 服务获取权限执行 shell",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (shizukuStatusMsg != null) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Text(
                        text = shizukuStatusMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImportManagementPage(
    repository: JsRepository,
    onBack: () -> Unit
) {
    var imports by remember { mutableStateOf(repository.listImports()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var selectedImport by remember { mutableStateOf<ImportedPackage?>(null) }
    var pendingImportName by remember { mutableStateOf("") }
    var pendingImportContent by remember { mutableStateOf("") }
    var showImportConflictDialog by remember { mutableStateOf(false) }
    var pendingAddName by remember { mutableStateOf("") }
    var editingImportName by remember { mutableStateOf<String?>(null) }
    var editingImportContent by remember { mutableStateOf(TextFieldValue("")) }

    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val name = pendingAddName
        if (uri == null || name.isBlank()) return@rememberLauncherForActivityResult
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()

            if (content.isBlank()) return@rememberLauncherForActivityResult

            if (repository.importExists(name)) {
                pendingImportName = name
                pendingImportContent = content
                showImportConflictDialog = true
            } else {
                repository.saveImport(name, content)
                imports = repository.listImports()
            }
        } catch (_: Exception) { }
    }

    LaunchedEffect(Unit) {
        imports = repository.listImports()
    }

    // If editing an import, show the code editor
    if (editingImportName != null) {
        ImportCodeEditor(
            importName = editingImportName!!,
            content = editingImportContent,
            onContentChange = { editingImportContent = it },
            onBack = {
                editingImportName?.let { name ->
                    repository.saveImport(name, editingImportContent.text)
                }
                editingImportName = null
                imports = repository.listImports()
            },
            repository = repository
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with back button and add button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onBack) {
                    Text("← 返回")
                }
                Text(
                    text = "导入的JS包",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            FilledTonalButton(onClick = { showAddDialog = true }) {
                Text("新增")
            }
        }

        if (imports.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无导入的JS包",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(imports) { pkg ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    editingImportName = pkg.name
                                    editingImportContent = TextFieldValue(repository.getImportContent(pkg.name))
                                },
                                onLongClick = {
                                    selectedImport = pkg
                                    showRenameDialog = true
                                }
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pkg.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (pkg.sourceType == com.uriroute.model.SourceType.LOCAL) "本地" else "网络",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Dialogs ─────────────────────────────────────────

    if (showAddDialog) {
        AddImportDialog(
            onDismiss = { showAddDialog = false },
            validate = { repository.validateName(it) },
            onConfirm = { name, isLocal ->
                if (isLocal) {
                    pendingAddName = name.trim()
                    showAddDialog = false
                    importLauncher.launch("*/*")
                } else {
                    repository.saveImport(name.trim(), "")
                    imports = repository.listImports()
                    showAddDialog = false
                }
            }
        )
    }

    if (showRenameDialog && selectedImport != null) {
        var newName by remember { mutableStateOf(selectedImport!!.name) }
        var renameError by remember { mutableStateOf<String?>(null) }
        val isRenameValid by remember {
            derivedStateOf {
                if (newName.isBlank() || newName == selectedImport?.name) false
                else {
                    val err = repository.validateName(newName)
                    renameError = err
                    err == null
                }
            }
        }
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                selectedImport = null
            },
            title = { Text("重命名 / 删除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = it
                            renameError = null
                        },
                        label = { Text("新名称") },
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
                    Button(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("删除此包")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    selectedImport = null
                }) { Text("取消") }
                Button(
                    onClick = {
                        repository.renameImport(selectedImport!!.name, newName)
                        imports = repository.listImports()
                        showRenameDialog = false
                        selectedImport = null
                    },
                    enabled = isRenameValid
                ) { Text("重命名") }
            }
        )
    }

    if (showDeleteConfirm && selectedImport != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除导入包「${selectedImport!!.name}」吗？\n这可能影响使用此包的脚本。") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                Button(
                    onClick = {
                        repository.deleteImport(selectedImport!!.name)
                        imports = repository.listImports()
                        showDeleteConfirm = false
                        selectedImport = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            }
        )
    }

    if (showImportConflictDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportConflictDialog = false
                pendingImportName = ""
                pendingImportContent = ""
            },
            title = { Text("导入冲突") },
            text = { Text("导入包「$pendingImportName」已存在。是否覆盖？") },
            confirmButton = {
                TextButton(onClick = {
                    showImportConflictDialog = false
                    pendingImportName = ""
                    pendingImportContent = ""
                }) { Text("取消") }
                Button(onClick = {
                    repository.saveImport(pendingImportName, pendingImportContent)
                    imports = repository.listImports()
                    showImportConflictDialog = false
                    pendingImportName = ""
                    pendingImportContent = ""
                }) { Text("覆盖") }
            }
        )
    }

    // ── Rename dialog ────────────────────────
}

@Composable
private fun AcknowledgementsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("致谢") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("UriRoute 使用了以下开源库：")
                Text(
                    text = "• Mozilla Rhino",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "  JavaScript 引擎 (MPL 2.0)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• Jetpack Compose",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "  Android UI 工具包 (Apache 2.0)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• Kotlin",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "  编程语言 (Apache 2.0)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun AddImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
    validate: ((String) -> String?)? = null
) {
    var name by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var isLocalImport by remember { mutableStateOf(true) }
    val isNameValid by remember {
        derivedStateOf {
            if (name.isBlank()) false
            else {
                val err = validate?.invoke(name)
                nameError = err
                err == null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增导入包") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                    },
                    label = { Text("名称") },
                    singleLine = true,
                    isError = nameError != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (nameError != null) {
                    Text(
                        text = nameError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                HorizontalDivider()

                Text(
                    "来源",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { isLocalImport = false }
                    ) {
                        RadioButton(
                            selected = !isLocalImport,
                            onClick = { isLocalImport = false }
                        )
                        Text("空文件", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { isLocalImport = true }
                    ) {
                        RadioButton(
                            selected = isLocalImport,
                            onClick = { isLocalImport = true }
                        )
                        Text("本地导入", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(
                onClick = { onConfirm(name.trim(), isLocalImport) },
                enabled = isNameValid
            ) { Text("确定") }
        }
    )
}

/**
 * Code editor for imported JS packages.
 * Simplifies the JS editor — no run button, no undo/redo.
 */
@Composable
private fun ImportCodeEditor(
    importName: String,
    content: TextFieldValue,
    onContentChange: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
    repository: JsRepository
) {
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
            onContentChange(newValue)
            return
        }
        onContentChange(newValue)
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
                Text(
                    text = importName,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton(
                            onClick = {
                                repository.saveImport(importName, content.text)
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
                }
            }
        }

        // Code editor
        JsCodeEditor(
            value = content,
            onValueChange = { handleContentChange(it) },
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        )
    }

    // Auto-save
    LaunchedEffect(content.text) {
        kotlinx.coroutines.delay(2000)
        repository.saveImport(importName, content.text)
    }
}

private data class UpdateResult(
    val tagName: String,
    val releaseUrl: String,
    val body: String
)

private sealed interface UpdateState {
    data class Available(val result: UpdateResult) : UpdateState
    data object Current : UpdateState
    data object Failed : UpdateState
}
