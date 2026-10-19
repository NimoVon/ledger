package com.nimo.ledger.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nimo.ledger.util.Money

/**
 * 余额校准对话框（F11）：填实际余额，预览要追加的调整金额，确认后由
 * [com.nimo.ledger.data.repository.LedgerRepository.adjustBalanceTo] 写一笔 BALANCE_ADJUST。
 *
 * 解析与判定全在 [balanceAdjustOutcome]（纯函数，有 JVM 单测），这里只做渲染：
 * 输入非法或"无需校准"时按钮直接禁用，不让用户点出一个必然失败的写入。
 */
@Composable
fun BalanceAdjustDialog(
    accountName: String,
    currentBalanceCents: Long,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (targetCents: Long) -> Unit,
) {
    var targetText by remember(accountName) { mutableStateOf("") }
    val outcome = balanceAdjustOutcome(targetText, currentBalanceCents)
    val typed = targetText.isNotBlank()
    val invalid = outcome is BalanceAdjustOutcome.Invalid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("校准余额") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "账户：$accountName")
                Text(text = "当前账面余额：${Money.formatCurrency(currentBalanceCents)}")
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = Money.normalizeAmountInput(it) },
                    label = { Text("实际余额") },
                    prefix = { Text("¥") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    text = if (typed) {
                        balanceAdjustHintText(outcome)
                    } else {
                        "把银行 / 钱包里的真实数字填进来，差额会记成一笔余额调整。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (typed && invalid) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                val shown = errorMessage
                if (shown != null) {
                    Text(
                        text = shown,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "只追加一笔调整交易，历史流水不会被删除；余额调整不计入收入 / 支出 / 预算。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && outcome is BalanceAdjustOutcome.Valid,
                onClick = {
                    (outcome as? BalanceAdjustOutcome.Valid)?.let { onConfirm(it.targetCents) }
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}
