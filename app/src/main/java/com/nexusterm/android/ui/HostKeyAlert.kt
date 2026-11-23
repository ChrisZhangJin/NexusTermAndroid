package com.nexusterm.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import uniffi.nexus_core.hostKeyConflict
import uniffi.nexus_core.trustHostKey

/**
 * 主机指纹冲突的确认框。指纹表落盘之后（nexus-core `KnownHosts::persistent`），服务器正常
 * 重装 / 换密钥也会被拒，所以必须给用户一条「核对后信任新指纹」的路；但这一步也正是中间人
 * 希望用户点的，所以文案把两种可能都说清楚，按钮用警示色，默认动作是取消。
 */
object HostKeyAlerts {
    class Pending(
        val host: String,
        val port: Int,
        val known: String,
        val presented: String,
        /** 信任新指纹之后做什么（通常是重新连接）。 */
        val onTrusted: () -> Unit,
    )

    var pending by mutableStateOf<Pending?>(null)
        private set

    /**
     * 连接因 `HostKeyMismatch` 失败后调用：从 nexus-core 取出冲突两边，弹框。
     * 取不到（理论上不会）返回 false，调用方退回原来的错误提示。
     */
    fun show(host: String, port: Int, onTrusted: () -> Unit): Boolean {
        val c = runCatching { hostKeyConflict(host, port.toUShort()) }.getOrNull() ?: return false
        pending = Pending(host, port, c.known, c.presented, onTrusted)
        return true
    }

    internal fun dismiss() {
        pending = null
    }
}

/** 放在界面最上层，和 [AuthPromptHost] 并列。 */
@Composable
fun HostKeyAlertHost() {
    val p = HostKeyAlerts.pending ?: return
    var error by androidx.compose.runtime.remember(p) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { HostKeyAlerts.dismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("主机密钥已变化") },
        text = {
            Column {
                Text("${p.host}:${p.port} 这次出示的公钥指纹和之前记录的不一致。")
                Text(
                    "可能是服务器重装过或更换了密钥，也可能是有人在冒充它（中间人攻击）。" +
                        "不确定时请先向服务器管理员核对新指纹，核对一致再信任。",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text("之前记录", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                Text(p.known, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("这次出示", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Text(p.presented, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { trustHostKey(p.host, p.port.toUShort(), p.presented) }
                    .onSuccess {
                        HostKeyAlerts.dismiss()
                        p.onTrusted()
                    }
                    .onFailure { error = "保存失败：${it.message}" }
            }) { Text("信任新指纹并连接", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { HostKeyAlerts.dismiss() }) { Text("取消") } },
    )
}
