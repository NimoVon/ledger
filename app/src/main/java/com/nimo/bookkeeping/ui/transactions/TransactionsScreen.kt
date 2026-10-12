package com.nimo.bookkeeping.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.stats.PeriodSummary
import com.nimo.bookkeeping.ui.LedgerIcons
import com.nimo.bookkeeping.ui.components.EmptyState
import com.nimo.bookkeeping.util.Money

/**
 * 明细（流水时间线，F05）。
 *
 * 按日分组倒序展示，每行给出分类图标与名称、账户、备注、金额；
 * 顶部按月切换（月份边界来自 [com.nimo.bookkeeping.util.Dates.monthBounds]），
 * 合计来自 [com.nimo.bookkeeping.domain.stats.StatsCalculator]（转账/余额调整不计入）。
 *
 * 行点击是给后续「编辑 / 删除 + 撤销」预留的入口，本轮只留结构、不做编辑删除。
 */
@Composable
fun TransactionsScreen(
    repository: LedgerRepository,
    onTransactionClick: (Long) -> Unit = {},
) {
    val viewModel: TransactionsViewModel =
        viewModel(factory = TransactionsViewModel.factory(repository))
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
            state.isLoading -> Box(contentModifier, Alignment.Center) { CircularProgressIndicator() }

            state.errorMessage != null -> EmptyState(
                title = "加载失败",
                message = state.errorMessage.orEmpty(),
                modifier = contentModifier,
            )

            // 空状态按"本月"表述：切到没有记录的月份不该看起来像坏了
            state.isEmpty -> EmptyState(
                title = "本月还没有记录",
                message = "点右下角「记一笔」，3 秒记下第一笔。",
                modifier = contentModifier,
            )

            else -> LazyColumn(modifier = contentModifier) {
                state.groups.forEach { group ->
                    item(key = "day-" + group.date) { DayHeader(group) }
                    items(items = group.rows, key = { it.id }) { row ->
                        TransactionRowItem(
                            row = row,
                            onClick = { onTransactionClick(row.id) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
                    }
                }
            }
        }
    }
}

/** 月份切换：< 2026年10月 > */
@Composable
private fun MonthSwitcher(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(imageVector = LedgerIcons.ChevronLeft, contentDescription = "上个月")
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext) {
            Icon(imageVector = LedgerIcons.ChevronRight, contentDescription = "下个月")
        }
    }
}

/**
 * 当月合计：支出 / 收入 / 结余。数字全部来自 StatsCalculator 的 PeriodSummary。
 * 加载中显示 "—" 而不是 0.00，避免闪一下"这个月一分钱没花"的错觉。
 */
@Composable
private fun MonthSummaryBar(summary: PeriodSummary, loading: Boolean) {
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

/** 日段头：日期 + 当日合计 */
@Composable
private fun DayHeader(group: TransactionDayGroup) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = group.label, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.weight(1f))
            if (group.expenseCents > 0L) {
                Text(
                    text = "支出 " + Money.formatCurrency(group.expenseCents),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (group.incomeCents > 0L) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "收入 " + Money.formatCurrency(group.incomeCents),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一行流水：分类图标 + 名称 / 账户 · 备注 / 金额 */
@Composable
private fun TransactionRowItem(row: TransactionRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 后续「编辑 / 删除 + 撤销」的入口（本轮只留结构）
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.categoryIcon,
            fontSize = 20.sp,
            modifier = Modifier.width(36.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.categoryName, style = MaterialTheme.typography.bodyLarge)
            val secondary = listOfNotNull(
                row.accountLabel,
                row.note?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (secondary.isNotEmpty()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = TransactionTimeline.amountText(row),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = amountColor(row.type),
        )
    }
}

/** 支出红、收入主色，转账与余额调整用中性色（它们不参与收支，不该抢眼） */
@Composable
private fun amountColor(type: TransactionType): Color = when (type) {
    TransactionType.EXPENSE -> MaterialTheme.colorScheme.error
    TransactionType.INCOME -> MaterialTheme.colorScheme.primary
    TransactionType.TRANSFER, TransactionType.BALANCE_ADJUST ->
        MaterialTheme.colorScheme.onSurfaceVariant
}
