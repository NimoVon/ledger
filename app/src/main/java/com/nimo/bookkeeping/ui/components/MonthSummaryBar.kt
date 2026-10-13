package com.nimo.bookkeeping.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nimo.bookkeeping.domain.stats.PeriodSummary
import com.nimo.bookkeeping.util.Money

/**
 * 当月合计：支出 / 收入 / 结余。数字全部来自 StatsCalculator 的 [PeriodSummary]，
 * 页面不得自行累加（铁律 3：统计口径唯一实现）。
 *
 * 加载中显示 "—" 而不是 0.00，避免闪一下"这个月一分钱没花"的错觉。
 * 明细页与统计页共用，两页的合计口径与文案因此不可能分叉。
 */
@Composable
fun MonthSummaryBar(summary: PeriodSummary, loading: Boolean) {
    fun text(cents: Long): String = if (loading) "—" else Money.formatCurrency(cents)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SummaryCell(
            label = "支出",
            amountText = text(summary.expenseCents),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        SummaryCell(
            label = "收入",
            amountText = text(summary.incomeCents),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        SummaryCell(
            label = "结余",
            amountText = text(summary.netCents),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryCell(
    label: String,
    amountText: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = amountText,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}
