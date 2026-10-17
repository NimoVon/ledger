package com.nimo.ledger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import com.nimo.ledger.R

/** 空态插图节点（测试与无障碍核对用） */
const val EMPTY_STATE_ILLUSTRATION_TEST_TAG: String = "empty-state-illustration"

/** 插图边长：图标里那只狗本身带透明留白，120dp 里可见的圆约 80dp */
private const val ILLUSTRATION_SIZE_DP = 120

/** FILTERED / ERROR 用更小的插图：这两种空态是「暂时的」或「出错的」，别占满一屏 */
private const val COMPACT_ILLUSTRATION_SIZE_DP = 96

/**
 * 空态的三种语气（设计规范 §6.7）。
 *
 * - [NEUTRAL]：真的没有数据（「本月还没有记录」）。120dp 插图 + 品牌色淡底，是唯一值得占满一屏的语气。
 * - [FILTERED]：数据在、筛选条件没命中（「没有符合条件的记录」）。96dp + surfaceContainerHigh——
 *   用户下一步是改筛选条件，不是「去记一笔」，插图不该那么大声。
 * - [ERROR]：加载失败。96dp + errorContainer，和普通空态在颜色上区分开，避免把故障看成「没数据」。
 */
enum class EmptyTone { NEUTRAL, FILTERED, ERROR }

/**
 * 统一空状态：小狗插图 + 标题 + 说明（+ 可选操作按钮），居中显示（计划 4.4：空状态不能被空白页掩盖）。
 *
 * 插图复用应用图标里那只白狗（`res/drawable-nodpi/ic_launcher_foreground.png`，R4-L1
 * 「空态小狗插图（复用现有 PNG 资产）」），是**纯装饰**：contentDescription = null，
 * 读屏器只念标题与说明，不会念一张图。挤在窄条里、不适合放图的场合传 illustration = false。
 *
 * 插图写死 120dp 时，从插图底边到标题只有 0dp（标题直接贴着图），读起来像「一行说明挂在图上」；
 * 这里改成 插图 → 16dp → 标题 → 8dp → 说明，与规范 §6.7 的层级一致。
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    illustration: Boolean = true,
    tone: EmptyTone = EmptyTone.NEUTRAL,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val illustrationSize = when (tone) {
        EmptyTone.NEUTRAL -> ILLUSTRATION_SIZE_DP.dp
        EmptyTone.FILTERED, EmptyTone.ERROR -> COMPACT_ILLUSTRATION_SIZE_DP.dp
    }
    // 深色主题下 primaryContainer 偏亮，直接把插图底压成 surfaceContainerHigh，别让空态比正文还亮
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val plate: Color = when (tone) {
        EmptyTone.NEUTRAL ->
            if (darkTheme) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        EmptyTone.FILTERED -> MaterialTheme.colorScheme.surfaceContainerHigh
        EmptyTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (illustration) {
            Box(
                modifier = Modifier
                    .size(illustrationSize)
                    .clip(CircleShape)
                    .background(plate),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(EMPTY_STATE_ILLUSTRATION_TEST_TAG),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
