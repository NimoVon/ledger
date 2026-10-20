package com.nimo.ledger.ui.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nimo.ledger.ui.components.fieldHasError
import com.nimo.ledger.ui.LedgerIcons
import com.nimo.ledger.domain.validate.BudgetValidationError
import com.nimo.ledger.domain.validate.BudgetValidationException
import com.nimo.ledger.util.Money

/** 预算校验错误 → 中文。唯一的收口，页面不许另写一份文案。 */
internal fun BudgetValidationError.toMessage(): String = when (this) {
    BudgetValidationError.MonthFormat -> "月份格式不对，应该是 2026-10 这样"
    BudgetValidationError.AmountNotPositive -> "预算金额要大于 0"
    BudgetValidationError.AmountTooLarge -> "预算金额超过上限"
}

/** 写预算失败 → 给用户看的一句话。校验异常展开成多条；其它异常退回 message。 */
internal fun Throwable.toBudgetMessage(): String = when (this) {
    is BudgetValidationException -> errors.joinToString("；") { it.toMessage() }
    else -> message ?: "保存失败"
}

/**
 * 预算编辑对话框（总预算与分类预算共用）。
 *
 * 金额用 OutlinedTextField（键盘类型 Decimal，和记账/转账页一致），解析走 Money.parseCents——
 * 金额单位永远是 Long 分，输入框里是给人看的字符串。
 * 校验在对话框内先做一遍（空/0/上限），业务校验（月份、金额合法）由 Repository 兜底，失败经 errorMessage 回显。
 */
@Composable
fun BudgetEditDialog(
    title: String,
    subtitle: String,
    currentAmountCents: Long?,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var amount by remember(currentAmountCents) {
        mutableStateOf(currentAmountCents?.let { Money.format(it) } ?: "")
    }
    var localError by remember { mutableStateOf<String?>(null) }
    // 删除预算不可撤销、也不进撤销队列，所以要求二次确认：点「删除」先切到确认态，确认态再点「删除」才真删。
    var confirmingDelete by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        BudgetDeleteConfirmDialog(
            saving = saving,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                onDelete?.invoke()
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = { if (!saving) onDismiss() },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = amount,
                        onValueChange = {
                            amount = Money.normalizeAmountInput(it)
                            localError = null
                        },
                        label = { Text("预算金额") },
                    isError = fieldHasError(localError ?: errorMessage, "金额"),
                        singleLine = true,
                        enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    val shown = localError ?: errorMessage
                    if (shown != null) {
                        Text(
                            text = shown,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        val cents = Money.parseCents(amount)
                        if (cents == null) {
                            localError = if (Money.exceedsMaxAmount(amount)) "预算金额超过上限" else "请输入大于 0 的金额"
                            return@TextButton
                        }
                        onSave(cents)
                    },
                ) {
                    Text(if (saving) "保存中…" else "保存")
                }
            },
            dismissButton = {
                Row {
                    if (onDelete != null) {
                        TextButton(onClick = { confirmingDelete = true }, enabled = !saving) { Text("删除") }
                    }
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                }
            },
        )
    }
}

/**
 * 删除预算的二次确认（F15）。
 *
 * 单独抽出来是为了能在 Robolectric 里测：AlertDialog 只要套着 OutlinedTextField 就永远不 idle
 * （TransactionFilterDialogUiTest 的注释记着这条），编辑态渲染不了；确认态没有输入框，渲染得了。
 * 编辑态点「删除」进确认态这一步由真机流程验证覆盖。
 */
@Composable
internal fun BudgetDeleteConfirmDialog(
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("删除这条预算？") },
        text = {
            Text(
                text = "删除后这个月不再有预算上限，超支提示也会一起消失。" +
                    "已经记下的流水不受影响，之后可以重新设置。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = onConfirm) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}
