package com.nimo.ledger.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nimo.ledger.data.local.entity.AccountEntity
import com.nimo.ledger.data.local.entity.TransactionEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.ui.LedgerIcons
import com.nimo.ledger.ui.components.EmptyState
import com.nimo.ledger.ui.components.EmptyTone
import com.nimo.ledger.util.Dates
import com.nimo.ledger.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 金额输入框的测试锚点 */
internal const val AMOUNT_FIELD_TAG = "transfer-amount"

/** 余额不足提示的测试锚点（文案本身会随金额变化，用 tag 更稳） */
internal const val INSUFFICIENT_BALANCE_TAG = "transfer-insufficient-balance"
/** 转入后预估余额：只有「选了转入账户 + 金额可解析」时才给数，缺一不可（纯显示，不进统计） */
internal fun transferAfterBalance(balanceCents: Long?, amountCents: Long?): Long? =
    if (balanceCents == null || amountCents == null) null else balanceCents + amountCents

/**
 * 账户 chip 的测试锚点。两个选择器里的账户名会重复出现（"CMB" 在转出和转入里各一份），
 * 所以按 id 打 tag，测试不用靠"第几个同名节点"这种脆弱的写法。
 */
internal const val FROM_ACCOUNT_TAG_PREFIX = "transfer-from-"
internal const val TO_ACCOUNT_TAG_PREFIX = "transfer-to-"

/**
 * 转账 / 改转账（F04）。
 *
 * 转账和记一笔是两种东西：记一笔是"一个账户 + 一个分类"，转账是"两个账户 + 没有分类"，
 * 所以这里自成一套表单，而不是往记一笔里塞一个模式开关。
 *
 * 新建的入口在记一笔页的「转账」分段（F04）；点流水里的转账行进来则是编辑模式（[editingId] 非空）。
 *
 * @param onDeleted 用户要求删除这一笔。**本页不删数据**：整笔交给延迟物理删除策略
 *   （[com.nimo.ledger.ui.transactions.PendingTransactionDeletion]），页面随即返回，
 *   撤销交给流水页的 Snackbar（计划 9.2）——和记一笔完全同款。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    repository: LedgerRepository,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    editingId: Long? = null,
    pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
    onDeleted: suspend (TransactionEntity) -> Unit = {},
) {
    val viewModel: TransferViewModel = viewModel(
        factory = TransferViewModel.factory(repository, editingId, pendingDeletionIds),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedTransactionId) {
        val id = state.savedTransactionId ?: return@LaunchedEffect
        viewModel.consumeSaved()
        onSaved(id)
    }
    LaunchedEffect(state.deleteRequested) {
        val entity = state.deleteRequested ?: return@LaunchedEffect
        viewModel.consumeDeleteRequest()
        onDeleted(entity)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "编辑转账" else "转账") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = LedgerIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.isFormVisible) {
                        TextButton(onClick = viewModel::save, enabled = !state.isSaving) {
                            Text(if (state.isSaving) "保存中…" else "保存")
                        }
                    }
                },
            )
        },
    ) { padding ->
        TransferFormSection(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }

}

/**
 * 转账表单的整块内容：加载中 / 加载失败 / 账户不够 / 表单，外加日期选择弹窗。
 *
 * 宿主只管 Scaffold 与顶栏的「保存」按钮：转账页用它，记一笔页的「转账」分段也用它——
 * 转账表单因此只有一份实现，两处渲染的是同一块内容，不存在第二个真相。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransferFormSection(
    state: TransferUiState,
    viewModel: TransferViewModel,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    when {
        state.isLoading -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        state.loadErrorMessage != null -> EmptyState(
            title = "加载失败",
            message = state.loadErrorMessage.orEmpty(),
            modifier = modifier,
            tone = EmptyTone.ERROR,
        )

        state.hasNotEnoughAccounts -> EmptyState(
            title = "还不能转账",
            message = "转账要在两个账户之间移动钱。先到「设置 → 账户管理」再建一个账户。",
            modifier = modifier,
        )

        else -> TransferFormBody(
            state = state,
            viewModel = viewModel,
            onPickDate = { showDatePicker = true },
            modifier = modifier,
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = Dates.utcMillisFromIsoDate(state.form.occurredOn),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            viewModel.setDate(Dates.isoDateFromUtcMillis(it))
                        }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransferFormBody(
    state: TransferUiState,
    viewModel: TransferViewModel,
    onPickDate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!state.isEditing) amountFocus.requestFocus()
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val message = state.saveErrorMessage
            ?: state.formErrors.takeIf { it.isNotEmpty() }?.joinToString("；") { it.toMessage() }
        if (message != null) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // 原来挂着的账户被归档了：账户只归档不删（历史数据还在），恢复即可继续编辑
        if (state.originalFromAccountUnavailable) {
            ArchivedAccountNotice(
                role = "转出",
                name = state.originalFromAccountName,
                isRestoring = state.isRestoringAccount,
                onRestore = { state.form.fromAccountId?.let(viewModel::restoreOriginalAccount) },
            )
        }
        if (state.originalToAccountUnavailable) {
            ArchivedAccountNotice(
                role = "转入",
                name = state.originalToAccountName,
                isRestoring = state.isRestoringAccount,
                onRestore = { state.form.toAccountId?.let(viewModel::restoreOriginalAccount) },
            )
        }

        FieldSection(title = "转出账户") {
            AccountChips(
                accounts = state.accounts,
                selectedId = state.effectiveFromAccountId,
                tagPrefix = FROM_ACCOUNT_TAG_PREFIX,
                onSelect = viewModel::setFromAccount,
            )
            state.balanceBasisCents?.let { basis ->
                Text(
                    text = "当前余额 " + Money.formatCurrency(basis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FieldSection(title = "转入账户") {
            AccountChips(
                accounts = state.accounts,
                selectedId = state.form.toAccountId,
                tagPrefix = TO_ACCOUNT_TAG_PREFIX,
                onSelect = viewModel::setToAccount,
            )
            transferAfterBalance(
                balanceCents = state.form.toAccountId?.let { state.balances[it] },
                amountCents = state.amountCents,
            )?.let { after ->
                Text(
                    text = "转入后 " + Money.formatCurrency(after),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = state.form.amountText,
                onValueChange = viewModel::setAmount,
                label = { Text("金额") },
                prefix = { Text("¥") },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(amountFocus)
                    .testTag(AMOUNT_FIELD_TAG),
            )
            if (state.isFromBalanceInsufficient) {
                val fromName = state.accounts
                    .firstOrNull { it.id == state.effectiveFromAccountId }
                    ?.name
                    .orEmpty()
                Text(
                    text = "转出账户「" + fromName + "」余额 " +
                        Money.formatCurrency(state.balanceBasisCents ?: 0L) +
                        "，不够这笔 " + Money.formatCurrency(state.amountCents ?: 0L) +
                        "。仍要转账可以直接保存。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(INSUFFICIENT_BALANCE_TAG),
                )
            }
        }

        FieldSection(title = "日期") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPickDate) { Text(state.form.occurredOn) }
                if (state.form.occurredOn != Dates.today()) {
                    TextButton(onClick = { viewModel.setDate(Dates.today()) }) { Text("今天") }
                } else {
                    Text(
                        text = "今天",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.form.note,
            onValueChange = viewModel::setNote,
            label = { Text("备注") },
            singleLine = false,
            maxLines = 3,
            supportingText = { Text(state.form.note.length.toString() + "/200") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.isEditing) {
            HorizontalDivider()
            TextButton(
                onClick = viewModel::requestDelete,
                enabled = !state.isSaving,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("删除这一笔") }
            Text(
                text = "删除后它会立刻从流水里消失，底部提示条里可以撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountChips(
    accounts: List<AccountEntity>,
    selectedId: Long?,
    tagPrefix: String,
    onSelect: (Long) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        accounts.forEach { account ->
            FilterChip(
                selected = account.id == selectedId,
                onClick = { onSelect(account.id) },
                label = { Text(account.name) },
                modifier = Modifier.testTag(tagPrefix + account.id),
            )
        }
    }
}

@Composable
private fun ArchivedAccountNotice(
    role: String,
    name: String?,
    isRestoring: Boolean,
    onRestore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = role + "账户「" + name.orEmpty() + "」已归档，不能用于新的转账。",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onRestore, enabled = !isRestoring) {
            Text(if (isRestoring) "恢复中…" else "恢复账户并编辑")
        }
    }
}

@Composable
private fun FieldSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}
