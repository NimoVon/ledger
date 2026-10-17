package com.nimo.ledger.ui.budgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.stats.BudgetCalculator
import com.nimo.ledger.domain.stats.BudgetStatus
import com.nimo.ledger.ui.LedgerIcons
import com.nimo.ledger.ui.components.EmptyState
import com.nimo.ledger.ui.components.EmptyTone
import com.nimo.ledger.ui.components.LedgerProgressBar
import com.nimo.ledger.ui.components.LedgerSectionHeader
import com.nimo.ledger.ui.components.MonthSwitcher
import com.nimo.ledger.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 对话框要编辑的目标：总预算（categoryId = null）或某个分类的预算 */
private data class BudgetDialogTarget(
    val categoryId: Long?,
    val title: String,
    val subtitle: String,
    val currentAmountCents: Long?,
    val budgetId: Long?,
)

/**
 * 预算管理页（F09）：按月设总预算 + 分类预算，80% 提示、100% 超支。
 *
 * 口径全部来自 domain 层的 BudgetCalculator / StatsCalculator，这个页面只负责把状态画出来、
 * 把用户输入交给 ViewModel——UI 不碰 DAO，也不自己算钱。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsScreen(
    repository: LedgerRepository,
    pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
    onBack: () -> Unit = {},
) {
    val viewModel: BudgetsViewModel = viewModel(
        factory = BudgetsViewModel.factory(repository, pendingDeletionIds),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<BudgetDialogTarget?>(null) }
    var saving by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    fun openTotal() {
        dialogError = null
        target = BudgetDialogTarget(
            categoryId = null,
            title = "本月总预算",
            subtitle = "本月已支出 " + Money.formatCurrency(state.totalSpentCents) + "。总预算管的是当月全部支出（含未分类）。",
            currentAmountCents = state.totalBudgetCents,
            budgetId = state.totalBudgetId,
        )
    }

    fun openCategory(row: BudgetCategoryRow) {
        dialogError = null
        target = BudgetDialogTarget(
            categoryId = row.categoryId,
            title = row.name,
            subtitle = "本月已支出 " + Money.formatCurrency(row.spentCents) + "。",
            currentAmountCents = row.budgetCents,
            budgetId = row.budgetId,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("预算管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LedgerIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            MonthSwitcher(
                label = state.monthLabel,
                onPrevious = viewModel::showPreviousMonth,
                onNext = viewModel::showNextMonth,
            )
            HorizontalDivider()
            when {
                state.errorMessage != null -> EmptyState(
                    title = "加载失败",
                    message = state.errorMessage.orEmpty(),
                    modifier = Modifier.weight(1f),
                    tone = EmptyTone.ERROR,
                )

                state.isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.isEmpty -> EmptyState(
                    title = "还没有支出分类",
                    message = "先去「设置 → 分类管理」建好支出分类，再回来给它们设预算。",
                    modifier = Modifier.weight(1f),
                )

                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    item(key = "total") {
                        TotalBudgetCard(state = state, onClick = { openTotal() })
                    }
                    item(key = "alert") { BudgetAlertLine(state = state) }
                    item(key = "section") {
                        LedgerSectionHeader(title = "分类预算")
                    }
                    items(state.rows, key = { it.categoryId }) { row ->
                        CategoryBudgetRowItem(row = row, onClick = { openCategory(row) })
                        HorizontalDivider()
                    }
                    item(key = "footer") {
                        Text(
                            text = "预算只看支出：转账与余额调整不算支出（与统计页同一口径）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    val dialogTarget = target
    if (dialogTarget != null) {
        BudgetEditDialog(
            title = dialogTarget.title,
            subtitle = dialogTarget.subtitle,
            currentAmountCents = dialogTarget.currentAmountCents,
            saving = saving,
            errorMessage = dialogError,
            onDismiss = { if (!saving) target = null },
            onSave = { cents ->
                scope.launch {
                    saving = true
                    val result = viewModel.setBudget(dialogTarget.categoryId, cents)
                    saving = false
                    result
                        .onSuccess {
                            dialogError = null
                            target = null
                        }
                        .onFailure { dialogError = it.toBudgetMessage() }
                }
            },
            onDelete = dialogTarget.budgetId?.let { id ->
                {
                    scope.launch {
                        saving = true
                        val result = viewModel.removeBudget(id)
                        saving = false
                        result
                            .onSuccess {
                                dialogError = null
                                target = null
                            }
                            .onFailure { dialogError = it.toBudgetMessage() }
                    }
                }
            },
        )
    }
}

@Composable
private fun TotalBudgetCard(state: BudgetsUiState, onClick: () -> Unit) {
    val budget = state.totalBudgetCents
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("本月总预算", style = MaterialTheme.typography.titleMedium)
            Text(
                text = budget?.let { Money.formatCurrency(it) } ?: "未设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (budget == null) MaterialTheme.colorScheme.onSurfaceVariant else statusColor(state.totalStatus),
            )
        }
        if (budget == null) {
            Text(
                text = "本月已支出 " + Money.formatCurrency(state.totalSpentCents) + " · 点这里设置本月总预算",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BudgetProgressBar(
                spentCents = state.totalSpentCents,
                budgetCents = budget,
                status = state.totalStatus,
            )
        }
    }
}

@Composable
private fun BudgetAlertLine(state: BudgetsUiState) {
    if (state.overCount == 0 && state.warningCount == 0) return
    val parts = buildList {
        if (state.overCount > 0) add(state.overCount.toString() + " 个分类已超支")
        if (state.warningCount > 0) add(state.warningCount.toString() + " 个分类接近上限")
    }
    val color = if (state.overCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Text(
        text = parts.joinToString("，"),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun CategoryBudgetRowItem(row: BudgetCategoryRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.depth > 0) Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = row.name +
                        (if (row.isArchived) "（已停用）" else "") +
                        (if (row.isNonExpense) "（非支出）" else ""),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                text = row.budgetCents?.let { Money.formatCurrency(it) } ?: "未设置",
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.budgetCents == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    statusColor(row.status)
                },
            )
        }
        val budget = row.budgetCents
        if (budget == null) {
            Text(
                text = "本月已支出 " + Money.formatCurrency(row.spentCents) + " · 点这里设预算",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BudgetProgressBar(spentCents = row.spentCents, budgetCents = budget, status = row.status)
        }
    }
}

/**
 * 预算进度条：条形本体统一走 [LedgerProgressBar]（设计规范 §6.4），这里只补下面那行文字。
 * overMarker = true：超支时 100% 处留 2dp 刻度、填充压到 60% 高，一眼看出「越过线多少」。
 */
@Composable
private fun BudgetProgressBar(spentCents: Long, budgetCents: Long, status: BudgetStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LedgerProgressBar(
            fraction = BudgetCalculator.percent(spentCents, budgetCents).toFloat(),
            color = statusColor(status),
            height = 8.dp,
            shape = RoundedCornerShape(4.dp),
            overMarker = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "已用 " + Money.formatCurrency(spentCents) + " / " + Money.formatCurrency(budgetCents),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = statusLabel(status, spentCents, budgetCents),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(status),
            )
        }
    }
}

private fun statusLabel(status: BudgetStatus, spentCents: Long, budgetCents: Long): String = when (status) {
    BudgetStatus.NONE -> "未设置"
    BudgetStatus.OK -> "还剩 " + Money.formatCurrency(budgetCents - spentCents)
    BudgetStatus.WARNING -> "已用 " + percentText(spentCents, budgetCents) + "，接近上限"
    BudgetStatus.OVER -> "已超支 " + Money.formatCurrency(spentCents - budgetCents)
}

private fun percentText(spentCents: Long, budgetCents: Long): String {
    if (budgetCents <= 0L) return "0%"
    return (BudgetCalculator.percent(spentCents, budgetCents) * 100).roundToInt().toString() + "%"
}

@Composable
private fun statusColor(status: BudgetStatus): Color = when (status) {
    BudgetStatus.NONE -> MaterialTheme.colorScheme.outline
    BudgetStatus.OK -> MaterialTheme.colorScheme.primary
    BudgetStatus.WARNING -> MaterialTheme.colorScheme.tertiary
    BudgetStatus.OVER -> MaterialTheme.colorScheme.error
}
