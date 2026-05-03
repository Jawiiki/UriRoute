package com.uriroute.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Dialog for editing environment variables (key-value pairs).
 * Used in both Overview (execute) and Editor (settings) flows.
 *
 * @param hideSave when true, the save button is hidden and changes auto-save
 */
@Composable
fun EnvVarDialog(
    title: String = "环境变量设置",
    initialVars: Map<String, String> = emptyMap(),
    showExecuteButton: Boolean = false,
    hideSave: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
    onExecute: ((Map<String, String>) -> Unit)? = null
) {
    val entries = remember(if (hideSave) Unit else initialVars) {
        if (initialVars.isEmpty()) mutableStateListOf(Pair("", ""))
        else mutableStateListOf<Pair<String, String>>().also { list ->
            initialVars.entries.forEach { (k, v) -> list.add(k to v) }
        }
    }
    var savedSnapshot by remember { mutableStateOf(entries.toList()) }
    val hasChanges by remember {
        derivedStateOf { entries.toList() != savedSnapshot }
    }

    // Auto-save on change when save button is hidden
    var autoSaveVersion by remember { mutableIntStateOf(0) }
    if (hideSave) {
        LaunchedEffect(autoSaveVersion) {
            if (autoSaveVersion > 0) {
                delay(300)
                val validEntries = entries.filter { it.first.isNotBlank() || it.second.isNotBlank() }
                onSave(validEntries.toMap())
            }
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
                itemsIndexed(entries, key = { index, _ -> index }) { index, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = entry.first,
                            onValueChange = { newKey ->
                                entries[index] = newKey to entry.second
                                if (hideSave) autoSaveVersion++
                            },
                            label = { Text("Key") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = entry.second,
                            onValueChange = { newValue ->
                                entries[index] = entry.first to newValue
                                if (hideSave) autoSaveVersion++
                            },
                            label = { Text("Value") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                entries.removeAt(index)
                                if (entries.isEmpty()) entries.add(Pair("", ""))
                                if (hideSave) autoSaveVersion++
                            }
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            entries.add(Pair("", ""))
                            if (hideSave) autoSaveVersion++
                        }
                    ) {
                        Text("+ 添加新行")
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
                if (!hideSave) {
                    Button(
                        onClick = {
                            val validEntries = entries.filter { it.first.isNotBlank() || it.second.isNotBlank() }
                            onSave(validEntries.toMap())
                            savedSnapshot = entries.toList()
                        },
                        enabled = hasChanges
                    ) {
                        Text("保存")
                    }
                }
                if (showExecuteButton && onExecute != null) {
                    Button(
                        onClick = {
                            val validEntries = entries.filter { it.first.isNotBlank() && it.second.isNotBlank() }
                            onExecute(validEntries.toMap())
                        }
                    ) {
                        Text("立刻执行")
                    }
                }
            }
        }
    )
}
