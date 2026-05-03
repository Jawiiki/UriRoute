package com.uriroute.ui.component

import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.uriroute.model.ExecResult

/**
 * Dialog showing the result of JS execution.
 * Displays the executed URI, logs, and returned data.
 * Long-press any content block to copy it to the clipboard.
 */
@Composable
fun ExecuteResultDialog(
    uri: String,
    result: ExecResult,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val copyToClipboard: (String) -> Unit = { text ->
        clipboardManager.setText(AnnotatedString(text))
        Toast.makeText(context, "复制成功", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("执行结果") },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(min = 300.dp, max = 450.dp)
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── URI ─────────────────────────────────────
                Text(
                    "执行指令",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    modifier = Modifier.pointerInput(uri) {
                        detectTapGestures(onLongPress = { copyToClipboard(uri) })
                    },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = uri,
                        modifier = Modifier
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // ── Console ────────────────────────────────────
                val consoleLines = buildList {
                    addAll(result.logs)
                    if (result.error != null) {
                        add("[ERROR] ${result.error}")
                    }
                }
                if (consoleLines.isNotEmpty()) {
                    val fullConsole = consoleLines.joinToString("\n")
                    Text(
                        "控制台信息",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        modifier = Modifier.pointerInput(fullConsole) {
                            detectTapGestures(onLongPress = { copyToClipboard(fullConsole) })
                        },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            consoleLines.forEach { line ->
                                val isError = line.startsWith("[ERROR]")
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // ── Result JSON ──────────────────────────────
                val resultText = if (result.error != null) {
                    val escaped = result.error
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                    "{\n  \"error\": \"$escaped\"\n}"
                } else {
                    result.data.entries.joinToString(
                        separator = ",\n  ",
                        prefix = "{\n  ",
                        postfix = "\n}"
                    ) { (k, v) -> "\"$k\": \"$v\"" }
                }

                Text(
                    "返回内容",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    modifier = Modifier.pointerInput(resultText) {
                        detectTapGestures(onLongPress = { copyToClipboard(resultText) })
                    },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = resultText,
                        modifier = Modifier
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
