package com.uriroute.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uriroute.model.CacheConfig

/**
 * Dialog for configuring cache settings for a JS script.
 */
@Composable
fun CacheDialog(
    initialConfig: CacheConfig = CacheConfig(),
    onDismiss: () -> Unit,
    onSave: (CacheConfig) -> Unit
) {
    var enabled by remember { mutableStateOf(initialConfig.enabled) }
    var durationText by remember { mutableStateOf(initialConfig.durationSeconds.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("缓存设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("开启缓存")
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            if (it && (durationText.isBlank() || durationText == "0")) {
                                durationText = "30"
                            }
                            enabled = it
                        }
                    )
                }

                if (enabled) {
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it.filter { c -> c.isDigit() } },
                        label = { Text("缓存时间（秒）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (durationText.isBlank()) {
                        Text(
                            "缓存时间不可为空",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val duration = durationText.toIntOrNull() ?: 0
                        onSave(CacheConfig(enabled, duration))
                    },
                    enabled = !enabled || durationText.isNotBlank()
                ) {
                    Text("保存")
                }
            }
        }
    )
}
