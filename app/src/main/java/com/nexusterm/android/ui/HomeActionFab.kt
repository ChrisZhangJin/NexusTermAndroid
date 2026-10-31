package com.nexusterm.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 一个按钮从把手滑出所用的时长，以及相邻两个按钮之间的错位。 */
private const val SLIDE_MILLIS = 200
private const val STAGGER_MILLIS = 50

/**
 * 首页右下角的「设置 / 新增 / 帮助」按钮组。
 *
 * 为什么合成一组：原先「?」「+」是两个常驻 FAB，设置还是顶栏上的文字按钮，
 * 三个入口散在两处，常驻 FAB 还会压住列表最后一行的按钮。现在平时只剩一个
 * 小把手，点一下或在上面划一下才把三个按钮滑出来。
 *
 * 必须放在一个铺满的 [Box] 里（调用方的内容区），因为展开时要在整块内容区上
 * 盖一层 scrim 来接「点空白处收起」；这也是它不再走 Scaffold 的
 * `floatingActionButton` 槽的原因（那个槽只给 FAB 自己那一小块位置）。
 *
 * 展开状态由调用方持有：[expanded] / [onExpandedChange]。
 */
@Composable
fun HomeActionFab(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onAddNew: () -> Unit,
    onOpenHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 系统返回键：只在展开时拦截，收起时让返回键照常走原来的逻辑（退出 / 回上一页）。
    BackHandler(enabled = expanded) { onExpandedChange(false) }

    Box(modifier = modifier.fillMaxSize()) {
        // scrim：展开时盖住整块内容区，点哪里都只是收起。它同时挡住了下层列表的
        // 触摸，展开期间列表不可滚动 —— 这是有意的：展开态是个小模态，用户
        // 要么选一个按钮、要么点空白收起。收起后 scrim 随动画退出而离开组合，
        // 不会留下一层看不见却吃触摸的东西。
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(SLIDE_MILLIS)),
            exit = fadeOut(tween(SLIDE_MILLIS)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        // 不要涟漪：整屏闪一下水波纹很怪。
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "收起",
                        onClick = { onExpandedChange(false) },
                    ),
            )
        }

        // 固定宽度 56dp = 最宽的那个按钮（「+」FAB）。别的按钮和把手在这个宽度里
        // 居中，这样展开/收起时把手不会因为 Column 变宽而横向挪位。
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
                .width(56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 从上到下：帮助、设置、新增、把手。离把手最近的「新增」是首页真正的
            // 主操作，拇指最好够；它最先滑出（index 0），最远的「帮助」最后滑出，
            // 收起时反过来，所以每个按钮的延迟 = 距把手的位次 * 错位。
            ActionSlot(visible = expanded, order = 2) {
                SmallFloatingActionButton(
                    onClick = {
                        onExpandedChange(false)
                        onOpenHelp()
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    // Text 子节点的语义清掉，只留下面这条 contentDescription，
                    // 否则读屏会念成「帮助 问号」。
                    modifier = Modifier.semantics { contentDescription = "帮助" },
                ) {
                    Text("?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics {})
                }
            }
            ActionSlot(visible = expanded, order = 1) {
                SmallFloatingActionButton(
                    onClick = {
                        onExpandedChange(false)
                        onOpenSettings()
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        imageVector = GearIcon,
                        contentDescription = "设置",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            ActionSlot(visible = expanded, order = 0) {
                FloatingActionButton(
                    onClick = {
                        onExpandedChange(false)
                        onAddNew()
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.semantics { contentDescription = "新增会话" },
                ) {
                    Text("+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
                }
            }

            ActionHandle(expanded = expanded, onExpandedChange = onExpandedChange)
        }
    }
}

/**
 * 一个按钮的进出场：沿竖直方向从把手那边滑出并淡入，收起时滑回去。
 *
 * 只用稳定的 AnimatedVisibility + slide/fade，不用 expandVertically：布局空间
 * 一次到位，把手始终钉在 Column 最底部不动，按钮是「从把手下面钻出来」，
 * 把手在组里最后绘制所以盖在按钮上，视觉上就是从把手里长出来的。
 *
 * [order] 是离把手的位次（0 最近）。进场时近的先出；退场时远的先走，延迟取反。
 */
@Composable
private fun ActionSlot(
    visible: Boolean,
    order: Int,
    content: @Composable () -> Unit,
) {
    val enterDelay = order * STAGGER_MILLIS
    val exitDelay = (2 - order) * STAGGER_MILLIS
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(SLIDE_MILLIS, delayMillis = enterDelay)) +
            slideInVertically(tween(SLIDE_MILLIS, delayMillis = enterDelay)) { fullHeight -> fullHeight },
        exit = fadeOut(tween(SLIDE_MILLIS, delayMillis = exitDelay)) +
            slideOutVertically(tween(SLIDE_MILLIS, delayMillis = exitDelay)) { fullHeight -> fullHeight },
    ) {
        content()
    }
}

/**
 * 收起态唯一露出来的小把手：40dp 的圆，外面套 48dp 的命中区。
 *
 * 触摸全部自己处理（不用 clickable），因为要区分两种手势又不能让它们打架：
 * - **点按**（落下、抬起，期间没超过 touch slop）→ 切换展开/收起；
 * - **划过 / 按住拖动**（移动超过 touch slop）→ 展开，且这一次手势里的后续
 *   移动被消费，抬起时**不再**触发「点按=切换」，否则刚展开就被抬手收回去。
 *   划过只会展开、不会收起：从展开态划一下不该把它合上。
 *
 * 为什么不会吞掉列表滚动、也不会让屏幕右侧变成触发区：pointerInput 挂在这个
 * 48dp 的 Box 上，只有落点（ACTION_DOWN）在它范围内才会进入 awaitEachGesture；
 * 落在别处的手势根本到不了这里。而它在 Box 里叠在列表之上，落在它身上的触摸
 * 本来就不会传给列表。
 */
@Composable
private fun ActionHandle(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    // pointerInput 的 key 是 Unit，block 只会捕获第一次组合时的 lambda / 状态，
    // 所以这里用 rememberUpdatedState 的等价写法：每次读取都经过最新的引用。
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnChange by rememberUpdatedState(onExpandedChange)

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(SLIDE_MILLIS),
        label = "handleChevron",
    )
    val chevronColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .size(48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var travelled = Offset.Zero
                    var swiped = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // 抬起：没划过才算点按。
                            if (!swiped) currentOnChange(!currentExpanded)
                            break
                        }
                        if (!swiped) {
                            travelled += change.positionChange()
                            if (travelled.getDistance() > viewConfiguration.touchSlop) {
                                swiped = true
                                if (!currentExpanded) currentOnChange(true)
                            }
                        }
                        // 划过后消费后续移动：手指拖出把手范围也只属于这次手势，
                        // 不会被别的节点当成别的东西。
                        if (swiped) change.consume()
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = if (expanded) "收起操作菜单" else "展开操作菜单"
                // 读屏用户靠双击激活，不会产生上面的触摸事件，要单独给一个 onClick。
                onClick {
                    currentOnChange(!currentExpanded)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            // 中性的灰绿，和三个绿色操作按钮区分开：它是「开关」不是「操作」。
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = chevronColor,
            shadowElevation = 4.dp,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                // 手绘一个朝上的「^」，展开时转 180° 变成「v」，暗示「再点就收」。
                Canvas(
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                ) {
                    val stroke = 2.5.dp.toPx()
                    val a = Offset(size.width * 0.2f, size.height * 0.65f)
                    val b = Offset(size.width * 0.5f, size.height * 0.35f)
                    val c = Offset(size.width * 0.8f, size.height * 0.65f)
                    drawLine(chevronColor, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
                    drawLine(chevronColor, b, c, strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
        }
    }
}

/**
 * 手绘的齿轮图标（24×24 视口）。
 *
 * 为什么手绘：核心 material-icons 里没有 Settings，要用得引入
 * material-icons-extended（体积大、还要改 build 文件），只为一个图标不值。
 *
 * 形状：8 个梯形齿的外轮廓 + 中间一个圆孔，用 EvenOdd 填充让圆孔镂空。
 * 颜色填黑只是占位，实际由 [Icon] 按主题的 contentColor 重新着色。
 */
private val GearIcon: ImageVector by lazy {
    val cx = 12f
    val cy = 12f
    val tipR = 10.5f // 齿顶半径
    val rootR = 7.8f // 齿根半径
    val teeth = 8
    val step = 2.0 * PI / teeth
    // 以齿中心为 0，齿顶半宽 / 齿根半宽（弧度）：根比顶宽，才是梯形齿。
    val tipHalf = step * 0.17
    val rootHalf = step * 0.30

    fun pt(angle: Double, r: Float): Pair<Float, Float> =
        (cx + r * cos(angle).toFloat()) to (cy + r * sin(angle).toFloat())

    ImageVector.Builder(
        name = "Gear",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.EvenOdd,
    ) {
        for (i in 0 until teeth) {
            val a = i * step
            val p1 = pt(a - rootHalf, rootR)
            val p2 = pt(a - tipHalf, tipR)
            val p3 = pt(a + tipHalf, tipR)
            val p4 = pt(a + rootHalf, rootR)
            if (i == 0) moveTo(p1.first, p1.second) else lineTo(p1.first, p1.second)
            lineTo(p2.first, p2.second)
            lineTo(p3.first, p3.second)
            lineTo(p4.first, p4.second)
        }
        close()
        // 中心圆孔：两段半圆弧拼成整圆。
        val holeR = 3.6f
        moveTo(cx + holeR, cy)
        arcTo(holeR, holeR, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = cx - holeR, y1 = cy)
        arcTo(holeR, holeR, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = cx + holeR, y1 = cy)
        close()
    }.build()
}
