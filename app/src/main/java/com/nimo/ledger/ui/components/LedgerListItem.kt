package com.nimo.ledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nimo.ledger.ui.LedgerIcons

/** 左侧图标区的固定宽度：有没有图标，标题都从同一条竖线开始 */
private val LEADING_SLOT = 40.dp

/** 有图标时分隔线的起点：图标区 40dp + 行的左内边距 16dp */
internal val LEDGER_LIST_DIVIDER_INSET = 56.dp

/** 没有图标时分隔线退回文字左缘 */
internal val LEDGER_LIST_DIVIDER_FLUSH = 16.dp

private val SINGLE_LINE_MIN_HEIGHT = 56.dp
private val TWO_LINE_MIN_HEIGHT = 72.dp
private val SINGLE_LINE_VERTICAL = 14.dp
private val TWO_LINE_VERTICAL = 12.dp
private const val LEADING_EMOJI_SP = 20
private val CHEVRON_SIZE = 20.dp

/**
 * 全应用统一列表行（UI-UX 规范 §6.1，批 D · D-1）。
 *
 * 改这个文件之前，账户 / 分类 / 预算 / 设置四处各写了一套几乎一样的 [Row]：
 * 内边距各差几 dp、金额字号不统一、返回箭头用「›」字符（读屏器会念成「大于号」）。
 * 现在只有这一份实现，**新页面要列表行就复用它，不要再手搓**。
 *
 * 规格要点：
 * - 单行最小高 56dp、双行 72dp；竖内边距 14 / 12dp，横向通栏 16dp（[@param contentPadding] 可覆盖）
 * - 点击涟漪通栏（[fillMaxWidth] + [clickable]，不内缩），整行是一个无障碍节点
 * - [chevron] 用 [LedgerIcons.ChevronRight] 图标替代字符箭头；图标 [contentDescription] = null，
 *   因为整行本身已经可点，读屏器念出标题即可，不该再念一个「右箭头」
 * - [trailingText] 统一 bodyLarge + Medium 字重（金额列因此左右对齐）
 *
 * @param leadingEmoji 分类图标那样的 emoji（20sp），与 [leadingIcon] 二选一
 * @param leadingIcon 需要矢量图标时用（如 [LedgerIcons.Scale]），与 [leadingEmoji] 二选一
 * @param trailingText 行尾文字（余额 / 金额），颜色默认跟随内容色，超支等场合传 [Color]
 * @param trailingContent 行尾自定义区域（如「校准余额」「上移 / 下移」这类按钮）
 * @param chevron 是否画右侧箭头：**只给"点进去还有一页"的行**，动作型行不要画
 * @param danger 标题用 error 色（仅用于「删除这一笔」这类破坏性入口）
 * @param onClick 传 null 表示这一行不可点（只读展示，不画涟漪）
 * @param contentPadding 行内水平留白：默认 16dp；已经在 24dp 页边距里的页面传 0.dp，避免双重缩进
 */
@Composable
fun LedgerListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingEmoji: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    trailingTextColor: Color = LocalContentColor.current,
    trailingContent: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val hasLeading = leadingEmoji != null || leadingIcon != null
    val twoLine = subtitle != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = if (twoLine) TWO_LINE_MIN_HEIGHT else SINGLE_LINE_MIN_HEIGHT)
            .padding(contentPadding)
            .padding(vertical = if (twoLine) TWO_LINE_VERTICAL else SINGLE_LINE_VERTICAL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasLeading) {
            Box(
                modifier = Modifier.size(LEADING_SLOT),
                contentAlignment = Alignment.Center,
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                } else {
                    Text(
                        text = leadingEmoji.orEmpty(),
                        fontSize = LEADING_EMOJI_SP.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(8.dp))
            trailingContent()
        }
        if (trailingText != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyLarge,
                color = trailingTextColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = LedgerIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(CHEVRON_SIZE),
            )
        }
    }
}

/**
 * 列表行之间的分隔线：**末行不画**（画了列表底部会多出一条悬空细线）。
 *
 * @param hasLeading 这一组行有没有左侧图标：有就从图标列之后起画（56dp），否则从文字左缘起（16dp）
 */
@Composable
fun LedgerListItemDivider(hasLeading: Boolean, modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(
            start = if (hasLeading) LEDGER_LIST_DIVIDER_INSET else LEDGER_LIST_DIVIDER_FLUSH,
        ),
    )
}
