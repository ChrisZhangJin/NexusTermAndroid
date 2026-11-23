package com.nexusterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val StripHeight = 40.dp
private val StripGap = 4.dp

/**
 * 补全建议条：用户在提示符后敲了几个字时，浮在输入行**正上方**（输入行贴着顶部时放到
 * 下方），横向一排历史命令，点一下把没敲的部分补进去（走粘贴，不执行，回车还是用户按）。
 *
 * 浮在输入行旁边而不是固定在键栏上方：键栏上方紧挨着的往往正是那一行输入，盖住它
 * 等于让用户看不见自己在敲什么；而常驻一条占位又要让终端少几行、每次出现都触发一次
 * 远端 resize。
 *
 * [onBounds] 报告建议条在父 Box 里的位置（隐藏时 null）：终端手势识别器在 Initial 阶段
 * 就把触摸吃掉，得让它放过落在建议条上的点按。
 */
@Composable
internal fun CompletionStrip(handle: TerminalHandle, onBounds: (Rect?) -> Unit) {
    val context = LocalContext.current
    val line = handle.inputLine
    // 用户点了 ✕：这一行剩下的输入都不再打扰，直到行被清空（回车 / Ctrl+C 之后）。
    var dismissed by remember(handle) { mutableStateOf(false) }
    LaunchedEffect(line?.text?.isEmpty()) { if (line == null || line.text.isEmpty()) dismissed = false }

    val local = remember(handle, handle.historyVersion) { CommandHistory.list(context, handle.historyKey) }
    val items = remember(line?.text, local, handle.remoteHistory) {
        line?.let { Completion.suggest(it.text, local, handle.remoteHistory) }.orEmpty()
    }
    val show = line != null && !dismissed && items.isNotEmpty()
    LaunchedEffect(show) { if (!show) onBounds(null) }
    if (!show) return

    val density = LocalDensity.current.density
    val stripPx = StripHeight.value * density
    val gapPx = StripGap.value * density
    val above = line!!.top * density - stripPx - gapPx
    val y = if (above >= 0f) above else line.bottom * density + gapPx
    val typed = line.text.trimStart()

    Box(
        modifier = Modifier
            .offset { IntOffset(0, y.roundToInt()) }
            .fillMaxWidth()
            .height(StripHeight)
            .onGloballyPositioned { onBounds(it.boundsInParent()) },
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(StripHeight),
        ) {
            items(items) { cmd ->
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF9E9E9E))) { append(typed) }
                        withStyle(SpanStyle(color = Color.White)) { append(cmd.substring(typed.length)) }
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xF0263238))
                        .clickable { handle.paste(Completion.remainder(line.text, cmd)) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            item {
                Text(
                    "✕",
                    color = Color(0xFFBDBDBD),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xF0263238))
                        .clickable { dismissed = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}
