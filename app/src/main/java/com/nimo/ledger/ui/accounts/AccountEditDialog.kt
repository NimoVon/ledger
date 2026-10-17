package com.nimo.ledger.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.nimo.ledger.data.local.entity.AccountEntity
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.validate.AccountValidationError
import com.nimo.ledger.domain.validate.AccountValidator
import com.nimo.ledger.domain.validate.TxValidationException
import com.nimo.ledger.ui.record.toMessage
import com.nimo.ledger.util.Money

/** 校验错误 → 中文提示 */
internal fun AccountValidationError.toMessage(): String = when (this) {
    AccountValidationError.NameBlank -> "账户名称不能为空"
    AccountValidationError.NameTooLong -> "账户名称最多 ${AccountValidator.MAX_NAME_LENGTH} 个字"
    AccountValidationError.InitialBalanceOutOfRange -> "初始余额超出允许范围"
}

/** 保存失败 → 中文提示 */
internal fun Throwable.toAccountMessage(): String = when (this) {
    is com.nimo.ledger.domain.validate.AccountValidationException ->
        errors.joinToString("；") { it.toMessage() }

    // 余额校准（F11）走的是交易校验：文案必须与记账页同一套，不能让"交易校验失败: ..."漏到界面
    is TxValidationException -> errors.joinToString("；") { it.toMessage() }

    else -> message ?: "保存失败"
}

/**
 * 账户对话框标题（AA-1）：不能与页内 FAB 的「新建账户」同串，否则读屏器把两个可操作项念成同一个名字。
 * 新建与编辑共用——标题只说明「这是什么」，动词留给按钮。
 */
internal const val ACCOUNT_DIALOG_TITLE: String = "账户信息"

/**
 * 新建/编辑账户表单。金额在 UI 侧先解析成"分"，非法时不发起写入。
 * @param account null 表示新建
 */
@Composable
fun AccountEditDialog(
    account: AccountEntity?,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, type: AccountType, initialBalanceCents: Long, includeInTotal: Boolean) -> Unit,
    onToggleArchive: (() -> Unit)?,
) {
    var name by remember(account) { mutableStateOf(account?.name.orEmpty()) }
    var type by remember(account) { mutableStateOf(account?.type ?: AccountType.CASH) }
    var amountText by remember(account) {
        mutableStateOf(account?.let { Money.format(it.initialBalance) }.orEmpty())
    }
    var includeInTotal by remember(account) { mutableStateOf(account?.includeInTotal ?: true) }
    var typeExpanded by remember { mutableStateOf(false) }
    var localError by remember(account) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ACCOUNT_DIALOG_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; localError = null },
                    label = { Text("账户名称") },
                    singleLine = true,
                )
                Column {
                    OutlinedButton(onClick = { typeExpanded = true }) {
                        Text("类型：${type.label}")
                    }
                    DropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false },
                    ) {
                        AccountType.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.label) },
                                onClick = { type = candidate; typeExpanded = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = Money.normalizeAmountInput(it); localError = null },
                    label = { Text("初始余额（分计入，可留空=0）") },
                    prefix = { Text("¥") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = includeInTotal, onCheckedChange = { includeInTotal = it })
                    Text(text = "计入净资产", modifier = Modifier.padding(start = 8.dp))
                }
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
                    val cents = if (amountText.isBlank()) 0L else Money.parseCents(amountText)
                    if (cents == null) {
                        localError = if (Money.exceedsMaxAmount(amountText)) {
                            "初始余额超出上限（最多 ${Money.formatCurrency(Money.MAX_AMOUNT_CENTS)}）"
                        } else {
                            "初始余额格式不正确（例：1000 或 1000.50）"
                        }
                        return@TextButton
                    }
                    val errors = AccountValidator.validate(name, cents)
                    if (errors.isNotEmpty()) {
                        localError = errors.joinToString("；") { it.toMessage() }
                        return@TextButton
                    }
                    onSave(name.trim(), type, cents, includeInTotal)
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            Row {
                if (onToggleArchive != null) {
                    TextButton(onClick = onToggleArchive, enabled = !saving) {
                        Text(if (account?.isArchived == true) "恢复" else "归档")
                    }
                }
                TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
            }
        },
    )
}
