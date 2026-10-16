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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.stats.PeriodSummary
import com.nimo.ledger.util.Money

/**
 * 一行三格放不下、改成竖排的字体倍数门槛。
 *
 * Android 的字体档位是 1.0 / 1.15 / 1.3 / 1.5 / 2.0：1.3 实测三格仍在同一行，
 * 2.0（特大）实测「结余」的金额被折行断成 "-¥600.0" 与 "0"（F15 真机取证），门槛取两者之间的 1.5。
 */
private const val STACK_FONT_SCALE = 1.5f

/** 字体够大就竖排三格，免得每格只有屏幕三分之一宽、金额被折断（纯函数，便于单测） */
internal fun stackSummaryCells(fontScale: Float): Boolean = fontScale >= STACK_FONT_SCALE

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

    val cells = listOf(
        SummaryCellData("支出", text(summary.expenseCents), MaterialTheme.colorScheme.error),
        SummaryCellData("收入", text(summary.incomeCents), MaterialTheme.colorScheme.primary),
        SummaryCellData("结余", text(summary.netCents), MaterialTheme.colorScheme.onSurface),
    )
    val barModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp)

    if (stackSummaryCells(LocalDensity.current.fontScale)) {
        Column(modifier = barModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cells.forEach { SummaryCell(it, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(modifier = barModifier) {
            cells.forEach { SummaryCell(it, Modifier.weight(1f)) }
        }
    }
}

/** 一格的数据：标签、给人看的金额、颜色 */
private data class SummaryCellData(val label: String, val amountText: String, val color: Color)

@Composable
private fun SummaryCell(cell: SummaryCellData, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = cell.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = cell.amountText,
            style = MaterialTheme.typography.titleMedium,
            color = cell.color,
            fontWeight = FontWeight.Medium,
        )
    }
}
