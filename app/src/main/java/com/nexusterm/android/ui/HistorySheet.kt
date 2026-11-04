package com.nexusterm.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 命令历史面板：当前主机上执行过的命令，最新的在最前，可搜索。
 * 点一条 = 放到命令行上（不执行，可以先改）；右边的「执行」= 放上去并回车。
 * 数据来源见 index.html 的 captureCommand；按主机分开存（[TerminalHandle.historyKey]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistorySheet(handle: TerminalHandle, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var all by remember { mutableStateOf(CommandHistory.list(context, handle.historyKey)) }
    var query by remember { mutableStateOf("") }
    val shown = remember(all, query) { HistoryLog.search(all, query) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "命令历史 · ${all.size} 条",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = all.isNotEmpty(),
                    onClick = {
                        CommandHistory.clear(context, handle.historyKey)
                        all = emptyList()
                    },
                ) { Text("清空") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索，多个词用空格分开") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (shown.isEmpty()) {
                Text(
                    text = if (all.isEmpty()) "还没有记录。在终端里执行命令后会出现在这里。" else "没有匹配的命令。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(shown, key = { it }) { cmd ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = cmd,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        handle.insertCommand(cmd, run = false)
                                        onDismiss()
                                    }
                                    .padding(vertical = 14.dp),
                            )
                            TextButton(onClick = {
                                handle.insertCommand(cmd, run = true)
                                onDismiss()
                            }) { Text("执行") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
