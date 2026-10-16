package com.nimo.ledger.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.ui.transactions.TransactionRow
import com.nimo.ledger.ui.transactions.TransactionTimeline
import com.nimo.ledger.util.Money

internal const val DAY_DETAIL_DIALOG_TEST_TAG = "report-day-detail"

/** 弹层里的日期文案："今天" / "昨天" / "10月12日 周一"，与明细页的日段头同一套规则 */
internal fun dayDetailHead(state: DayDetailUiState): String = state.label

/** 当日合计一句："支出 ¥12.00 · 收入 ¥0.00"（转账/余额调整永不进入） */
internal fun dayDetailSummaryText(state: DayDetailUiState): String =
    "支出 ${Money.formatCurrency(state.summary.expenseCents)} · " +
        "收入 ${Money.formatCurrency(state.summary.incomeCents)}"

/**
 * 当日流水明细弹层（计划附.1 R2：趋势区点某一根柱子 → 看那一天的流水）。
 *
 * 用 AlertDialog 而不是 ModalBottomSheet：后者是实验 API，本项目按"不引实验特性"处理。
 * 弹层只负责渲染 [DayDetailUiState]：分类名、账户标签、金额符号全部由 TransactionTimeline
 * 拼好（与明细页逐字一致），这里不重算任何东西。
 */
@Composable
internal fun DayDetailDialog(state: DayDetailUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(DAY_DETAIL_DIALOG_TEST_TAG),
        title = { Text(dayDetailHead(state)) },
        text = {
            when {
                state.isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }

                state.errorMessage != null -> Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                state.rows.isEmpty() -> Text(
                    text = "这一天没有流水。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> Column(
                    modifier = Modifier
                        .heightIn(max = DIALOG_LIST_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = dayDetailSummaryText(state),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    state.rows.forEachIndexed { index, row ->
                        DayDetailRow(row)
                        if (index != state.rows.lastIndex) HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 一天最多占弹层多高：行多了就滚，而不是把弹层撑满整屏 */
private val DIALOG_LIST_MAX_HEIGHT = 360.dp

@Composable
private fun DayDetailRow(row: TransactionRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = row.categoryIcon, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.categoryName, style = MaterialTheme.typography.bodyLarge)
            Text(
                // 备注可能为空：空的时候只显示账户，不留一个孤零零的分隔点
                text = listOfNotNull(row.note?.takeIf { it.isNotBlank() }, row.accountLabel)
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = TransactionTimeline.amountText(row),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = when (row.type) {
                TransactionType.EXPENSE -> MaterialTheme.colorScheme.error
                TransactionType.INCOME -> MaterialTheme.colorScheme.primary
                TransactionType.TRANSFER, TransactionType.BALANCE_ADJUST ->
                    MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
