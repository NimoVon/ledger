package com.nimo.ledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.stats.PeriodSummary
import com.nimo.ledger.util.Money

/** 字体够大就竖排三格，免得每格只有屏幕三分之一宽、金额被折断（门槛与全应用共用 [STACK_FONT_SCALE]） */
internal fun stackSummaryCells(fontScale: Float): Boolean = stackAtLargeFont(fontScale)

/**
 * 当月合计：支出 / 收入 / 结余。数字全部来自 StatsCalculator 的 [PeriodSummary]，
 * 页面不得自行累加（铁律 3：统计口径唯一实现）。
 *
 * 加载中显示 "—" 而不是 0.00，避免闪一下"这个月一分钱没花"的错觉。
 * 明细页与统计页共用，两页的合计口径与文案因此不可能分叉。
 * 字体倍数达到 [STACK_FONT_SCALE] 时三格改为竖排：横排时每格只有三分之一宽，金额会被折行折断。
 */
@Composable
fun MonthSummaryBar(summary: PeriodSummary, loading: Boolean) {
    fun text(cents: Long): String = if (loading) "—" else Money.formatCurrency(cents)

    // AA-5：读屏念「支出 2566 元」，不念「¥ 二 逗号 五六六 点 零 零」；加载中也要有话说
    fun speech(cents: Long): String = if (loading) "加载中" else Money.spokenAmount(cents)

    val cells = listOf(
        SummaryCellData("支出", text(summary.expenseCents), speech(summary.expenseCents), MaterialTheme.colorScheme.error),
        SummaryCellData("收入", text(summary.incomeCents), speech(summary.incomeCents), MaterialTheme.colorScheme.primary),
        SummaryCellData("结余", text(summary.netCents), speech(summary.netCents), MaterialTheme.colorScheme.onSurface),
    )
    val barModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp)

    if (stackSummaryCells(LocalDensity.current.fontScale)) {
        Column(modifier = barModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cells.forEach { SummaryCell(it, loading = loading, modifier = Modifier.fillMaxWidth()) }
        }
    } else {
        Row(modifier = barModifier) {
            cells.forEach { SummaryCell(it, loading = loading, modifier = Modifier.weight(1f)) }
        }
    }
}

/** 一格的数据：标签、给人看的金额、读屏口语、颜色 */
private data class SummaryCellData(
    val label: String,
    val amountText: String,
    val speech: String,
    val color: Color,
)

@Composable
private fun SummaryCell(cell: SummaryCellData, loading: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "${cell.label} ${cell.speech}"
        },
    ) {
        Text(
            text = cell.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading) {
            // 加载中给占位灰块而不是 "—"：先占住金额的位置，数据到达时行高不跳（G-9）
            SkeletonBlock(height = 20.dp, width = 72.dp)
        } else {
            Text(
                text = cell.amountText,
                style = MaterialTheme.typography.titleMedium,
                color = cell.color,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
