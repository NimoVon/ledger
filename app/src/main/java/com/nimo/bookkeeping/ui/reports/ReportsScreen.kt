package com.nimo.bookkeeping.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.stats.CategoryBreakdownRow
import com.nimo.bookkeeping.ui.components.EmptyState
import com.nimo.bookkeeping.ui.components.MonthSummaryBar
import com.nimo.bookkeeping.ui.components.MonthSwitcher
import com.nimo.bookkeeping.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 统计 / 月报（F07，阶段 3 · 报表）。
 *
 * 一屏回答三件事：本月花了多少（收入/支出/结余）、比上月多还是少（环比）、
 * 钱花在哪（分类支出排行）。所有数字来自 [ReportsViewModel]，页面一个数都不自己算
 * （铁律 3：统计口径唯一实现在 StatsCalculator）。
 *
 * 顶部按月切换，月份边界与标签与明细页同一套（共用 [MonthSwitcher] / [MonthSummaryBar]）。
 *
 * @param pendingDeletionIds 正被暂存待删除的那几笔 id：倒计时内它们不进报表
 */
@Composable
fun ReportsScreen(
    repository: LedgerRepository,
    pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
) {
    val viewModel: ReportsViewModel =
        viewModel(factory = ReportsViewModel.factory(repository, pendingDeletionIds))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        MonthSwitcher(
            label = state.monthLabel,
            onPrevious = viewModel::showPreviousMonth,
            onNext = viewModel::showNextMonth,
        )
        MonthSummaryBar(summary = state.summary, loading = state.isLoading)
        HorizontalDivider()

        val contentModifier = Modifier.weight(1f)
        when {
            // 错误优先于加载：读取失败时两条流都会 emit，别把用户永远按在转圈上
            state.errorMessage != null -> EmptyState(
                title = "加载失败",
                message = state.errorMessage.orEmpty(),
                modifier = contentModifier,
            )

            state.isLoading -> Box(contentModifier, Alignment.Center) { CircularProgressIndicator() }

            // 空状态按"本月"表述：切到没有记录的月份不该看起来像坏了
            state.isEmpty -> EmptyState(
                title = "本月还没有记录",
                message = "记一笔之后，这里会给出收入、支出、结余与环比。",
                modifier = contentModifier,
            )

            else -> LazyColumn(modifier = contentModifier) {
                item(key = "change") { ChangeSection(state) }
                item(key = "breakdown-header") { SectionHeader("支出排行") }
                if (state.breakdown.isEmpty()) {
                    item(key = "breakdown-empty") {
                        HintRow("本月没有支出。转账与余额调整不算收支，所以不会出现在这里。")
                    }
                } else {
                    items(items = state.breakdown, key = { it.categoryId }) { row ->
                        BreakdownRow(row)
                    }
                }
            }
        }
    }
}

/** 环比：支出与收入各一格。上月为 0 时显示 "—"（计划 3.4） */
@Composable
private fun ChangeSection(state: ReportsUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ChangeCell(
            label = "支出环比",
            ratio = state.expenseChangeRatio,
            // 支出涨是坏消息、收入涨是好消息，箭头方向一样，颜色不一样
            growthIsBad = true,
            modifier = Modifier.weight(1f),
        )
        ChangeCell(
            label = "收入环比",
            ratio = state.incomeChangeRatio,
            growthIsBad = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChangeCell(
    label: String,
    ratio: Double?,
    growthIsBad: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = when {
        ratio == null || ratio == 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
        growthIsBad == (ratio > 0) -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = ReportFormatting.change(ratio),
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 段头：与明细页的日段头同款 */
@Composable
private fun SectionHeader(title: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** 排行一行：分类名 + 占比 + 金额 + 占比条 */
@Composable
private fun BreakdownRow(row: CategoryBreakdownRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = ReportFormatting.percent(row.percent),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = Money.formatCurrency(row.amountCents),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (row.percent != null) {
            Spacer(modifier = Modifier.height(6.dp))
            BreakdownBar(fraction = row.percent)
        }
    }
}

/** 占比条：用两层 Box 画，宽度就是占比 */
@Composable
private fun BreakdownBar(fraction: Double) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat())
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary, shape),
        )
    }
}
