package com.nexusterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 编辑框：用一个普通的原生输入框编辑整条命令，再一次性放到终端里。
 *
 * 直接往终端里打字时，输入法是对着 xterm 的隐藏 textarea 工作的：中文 / 语音输入、联想、
 * 长按移动光标、改中间某个字都不顺手（改字只能靠 ← → 一格格挪）。这里是标准的 Android
 * 输入框，输入法的能力全都能用。
 *
 * - 打开时带上命令行上已经敲了的半句（认得出提示符时），接着改；
 * - 「放到命令行」= 替换当前这行、不执行（和命令历史面板点一条一样）；「执行」= 再按回车；
 * - 认不出提示符（vim 插入模式、REPL、密码提示……）时不去清行（Ctrl+A Ctrl+K 在那里另有
 *   含义），只在光标处插入；
 * - 输入框下面一排是以当前内容开头的历史命令，点一下换成那条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposeSheet(handle: TerminalHandle, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 打开那一刻的输入行：决定预填什么、以及提交时要不要先清行。之后不再跟着变。
    val atPrompt = remember { handle.inputLine }
    val start = atPrompt?.text?.trimStart().orEmpty()
    var value by remember { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val local = remember { CommandHistory.list(context, handle.historyKey) }
    val suggestions = remember(value.text) {
        Completion.suggest(value.text, local, handle.remoteHistory, limit = 12)
    }

    fun submit(run: Boolean) {
        val text = value.text
        if (text.isEmpty() && !run) {
            onDismiss()
            return
        }
        if (atPrompt != null) {
            handle.insertCommand(text, run)
        } else {
            if (text.isNotEmpty()) handle.paste(text)
            if (run) handle.sendEnter()
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            Text(
                if (atPrompt != null) "编辑命令" else "输入文字（当前不在 shell 提示符，只在光标处插入）",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text("可用输入法、语音输入，长按移动光标") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submit(run = true) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .focusRequester(focus),
            )
            if (suggestions.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    items(suggestions) { cmd ->
                        Text(
                            cmd,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 260.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { value = TextFieldValue(cmd, TextRange(cmd.length)) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                TextButton(onClick = { value = TextFieldValue("") }, enabled = value.text.isNotEmpty()) { Text("清空") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { submit(run = false) }) {
                    Text(if (atPrompt != null) "放到命令行" else "插入")
                }
                Button(onClick = { submit(run = true) }) { Text("执行") }
            }
        }
    }
}
