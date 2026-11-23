package com.nexusterm.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.nexus_core.AuthPrompt

/**
 * 认证过程中服务器 / 私钥要问用户的问题（二次验证码、私钥口令）的排队器。
 *
 * 终端拨号、自动重连、SFTP 独立拨号都从 Rust 的 `on_auth_prompt` 回调进来（阻塞在 JNI
 * 线程上，见 SessionListenerImpl），统一调 [ask] 挂起等答案；界面最上层的 [AuthPromptHost]
 * 显示队首那个。几个标签同时重连时问题排队，一次只弹一个。
 *
 * 等待被取消（SessionListenerImpl 的 180 秒超时、连接被关）时 [ask] 的 finally 把问题出队，
 * 对话框随之消失，不会留下一个答了也没人收的框。
 */
object AuthPrompts {
    class Pending(val prompt: AuthPrompt) {
        internal val answer = CompletableDeferred<String>()
    }

    /** 主线程读写（[ask] 切到 Main 再动它）。 */
    val queue = mutableStateListOf<Pending>()

    /**
     * 返回各字段答案用 `\n` 拼起来的串（nexus-core prompt.rs 的约定）；空串 = 用户取消。
     */
    suspend fun ask(prompt: AuthPrompt): String = withContext(Dispatchers.Main) {
        val p = Pending(prompt)
        queue.add(p)
        try {
            p.answer.await()
        } finally {
            queue.remove(p)
        }
    }
}

/**
 * 服务器的提示语是不是在要一次性验证码 —— 这类输入框用数字键盘、明文显示（验证码不是
 * 长期秘密，看得见才好核对）。
 *
 * 关键词表与 Rust 侧 `asks_for_otp`（nexus-core ssh/session.rs）一致，改要一起改：
 * 自动填码判成验证码的题，弹框里也得长成验证码的样子。没照搬桌面前端的
 * `isOneTimeCode()`，它比 Rust 窄，会把 PAM 常见的 "One-time password:" 当成密码框。
 * 另加了 code / token：有的服务器问 "Enter code:"，按验证码显示更顺手。
 */
private val OTP_KEYWORDS = listOf(
    "otp", "mfa", "2fa", "one-time", "onetime", "one time", "verif", "passcode",
    "authenticator", "google auth", "totp", "dynamic code", "code", "token",
)

internal fun isOneTimeCode(prompt: String): Boolean {
    val t = prompt.lowercase()
    return OTP_KEYWORDS.any { it in t } ||
        listOf("验证码", "动态口令", "动态码", "一次性").any { it in prompt }
}

/**
 * 把用户填的各字段拼成回给 Rust 的答案串。验证码常连空格一起从短信 / 验证器里复制过来，
 * 去掉空白；任何字段里的换行都去掉（换行是字段分隔符）。全部为空时返回一个空格 ——
 * 空串在协议里表示「取消」，而用户点「确定」就不是取消（有的服务器确实接受空回答）。
 */
internal fun joinAuthAnswers(prompts: List<String>, values: List<String>): String {
    val parts = values.mapIndexed { i, v ->
        val clean = v.replace("\r", "").replace("\n", "")
        if (isOneTimeCode(prompts.getOrElse(i) { "" })) clean.filterNot(Char::isWhitespace) else clean
    }
    return parts.joinToString("\n").ifEmpty { " " }
}

/** 放在界面最上层（NavHost 之上），有待答的问题就弹出队首那个。 */
@Composable
fun AuthPromptHost() {
    val head = AuthPrompts.queue.firstOrNull() ?: return
    // key：换了一个问题就是一个全新的对话框（输入框状态不能串到下一个问题上）。
    androidx.compose.runtime.key(head) {
        AuthPromptDialog(
            prompt = head.prompt,
            onSubmit = { head.answer.complete(it) },
            onCancel = { head.answer.complete("") },
        )
    }
}

@Composable
private fun AuthPromptDialog(prompt: AuthPrompt, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    val passphrase = prompt.kind == "passphrase"
    val labels = prompt.fields.map { it.prompt.trim().trimEnd(':', '：').trim() }
    val values = remember { mutableStateListOf(*prompt.fields.map { it.prefill.orEmpty() }.toTypedArray()) }
    // 焦点给第一个还空着的字段（密码已预填时直接落在验证码上）。
    val focusIndex = values.indexOfFirst { it.isEmpty() }.coerceAtLeast(0)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() = onSubmit(joinAuthAnswers(prompt.fields.map { it.prompt }, values))

    AlertDialog(
        // 点框外不关：手一滑把正在输的验证码丢了，还得等下一个 30 秒窗口。返回键 = 取消。
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                when {
                    passphrase -> "私钥口令"
                    prompt.round > 1u -> "二次验证（第 ${prompt.round} 步）"
                    else -> "二次验证"
                },
            )
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val who = if (prompt.username.isNotEmpty()) "${prompt.username}@${prompt.host}" else prompt.host
                Text(who, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                // 服务器的说明（堡垒机常把 MFA 菜单放在这里），没有就退回标题。
                val note = prompt.instructions.trim().ifEmpty { prompt.name.trim() }
                if (note.isNotEmpty()) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                prompt.fields.forEachIndexed { i, f ->
                    val otp = !passphrase && isOneTimeCode(f.prompt)
                    val last = i == prompt.fields.lastIndex
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { values[i] = it },
                        label = { Text(labels[i].ifEmpty { if (passphrase) "口令" else "回答" }) },
                        placeholder = if (otp) ({ Text("输入验证码") }) else null,
                        supportingText = if (otp) ({ Text("一次性，约 30 秒过期") }) else null,
                        singleLine = true,
                        visualTransformation = if (otp || f.echo) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when {
                                otp -> KeyboardType.Number
                                f.echo -> KeyboardType.Text
                                else -> KeyboardType.Password
                            },
                            imeAction = if (last) ImeAction.Done else ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .let { if (i == focusIndex) it.focusRequester(focus) else it },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}
