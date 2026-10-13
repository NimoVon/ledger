package com.nimo.bookkeeping.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.ui.LedgerIcons
import com.nimo.bookkeeping.ui.components.EmptyState
import com.nimo.bookkeeping.ui.DrillDownRequests
import com.nimo.bookkeeping.ui.components.MonthSummaryBar
import com.nimo.bookkeeping.ui.components.MonthSwitcher
import com.nimo.bookkeeping.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 明细（流水时间线，F05）+ 筛选与搜索（F06）。
 *
 * 按日分组倒序展示，每行给出分类图标与名称、账户、备注、金额；
 * 顶部按月切换（月份边界来自 [com.nimo.bookkeeping.util.Dates.monthBounds]），
 * 合计来自 [com.nimo.bookkeeping.domain.stats.StatsCalculator]（转账/余额调整不计入）。
 *
 * 筛选条件由 [TransactionFilterDialog] 收集、[TransactionsViewModel] 执行；
 * 开了日期区间就不再显示月份控件（区间优先），条件摘要贴在「筛选」按钮右侧。
 *
 * 点某一行进入「记一笔」的编辑模式（F03 修改）；删除入口也在那一页，
 * 删除后这一行会立刻消失、合计立刻回退，底部提示条里可撤销（计划 9.2 延迟物理删除）。
 *
 * @param pendingDeletionIds 正被暂存待删除的那几笔 id：倒计时内它们不在列表也不在合计里
 */
@Composable
fun TransactionsScreen(
    repository: LedgerRepository,
    onTransactionClick: (Long) -> Unit = {},
    /** 点转账行：转账走自己那套表单（F04），落到记一笔会变成"这一笔不能在这里改" */
    onTransferClick: (Long) -> Unit = {},
    pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
    drillDown: DrillDownRequests? = null,
) {
    val viewModel: TransactionsViewModel =
        viewModel(factory = TransactionsViewModel.factory(repository, pendingDeletionIds, drillDown))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showFilterDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.showsMonthSwitcher) {
            MonthSwitcher(
                label = state.monthLabel,
                onPrevious = viewModel::showPreviousMonth,
                onNext = viewModel::showNextMonth,
            )
        }
        MonthSummaryBar(summary = state.summary, loading = state.isLoading)
        FilterBar(
            chips = state.filterChips,
            onOpen = { showFilterDialog = true },
            onClear = viewModel::clearFilter,
        )
        HorizontalDivider()

        val contentModifier = Modifier.weight(1f)
        when {
            state.isLoading -> Box(contentModifier, Alignment.Center) { CircularProgressIndicator() }

            state.errorMessage != null -> EmptyState(
                title = "加载失败",
                message = state.errorMessage.orEmpty(),
                modifier = contentModifier,
            )

            // 筛选态下"没有记录"是条件的问题，不是这个月没记账——措辞要分开，否则用户以为数据丢了
            state.isEmpty && state.hasActiveFilter -> EmptyState(
                title = "没有符合条件的记录",
                message = "试试放宽条件，或点上面的「清空」。",
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
                            // 转账和收支是两种表单：按类型分流，各进各的编辑页
                            onClick = {
                                if (row.type == TransactionType.TRANSFER) {
                                    onTransferClick(row.id)
                                } else {
                                    onTransactionClick(row.id)
                                }
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
                    }
                }
            }
        }
    }

    if (showFilterDialog) {
        TransactionFilterDialog(
            initial = state.filter,
            options = state.filterOptions,
            onDismiss = { showFilterDialog = false },
            onApply = { filter ->
                viewModel.applyFilter(filter)
                showFilterDialog = false
            },
            onReset = {
                viewModel.clearFilter()
                showFilterDialog = false
            },
        )
    }
}

/**
 * 筛选入口 + 当前条件摘要。
 * 条件文字由 [TransactionFilterSummary] 生成（页面不自己拼字符串），开条件后才出现「清空」。
 */
@Composable
private fun FilterBar(chips: List<String>, onOpen: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onOpen) {
            Icon(
                imageVector = LedgerIcons.Filter,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("筛选")
        }
        if (chips.isEmpty()) {
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                chips.forEach { chip ->
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onClear) { Text("清空") }
        }
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
            // 编辑 / 删除 + 撤销的入口（F03 修改）
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
