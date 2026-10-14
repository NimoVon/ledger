package com.nimo.bookkeeping.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.ui.LedgerIcons
import com.nimo.bookkeeping.ui.components.EmptyState
import com.nimo.bookkeeping.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 账户管理（F01 + F11）：列表 / 新建 / 编辑 / 归档（归档不删除历史数据）+ 余额校准。
 *
 * 余额校准（F11）在行尾的「校准」按钮里：填一个实际余额，追加一笔余额调整把账面补齐，
 * 历史一行都不动（差额由 [LedgerRepository.adjustBalanceTo] 在事务内推导，UI 不自己算）。
 *
 * 列表展示的是**当前余额**：初始余额 + 全部交易变动，由 [AccountsViewModel] 交给
 * BalanceCalculator 推导（铁律 2）。初始余额只在编辑对话框里出现——它是建账当时的快照，
 * 直接当余额显示会让用户看到一个"没有跟着记账变化"的数字。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    repository: LedgerRepository,
    onBack: () -> Unit,
    /** 撤销窗口内那几笔的 id：余额要把它们剔除，否则显示的是用户已经删掉的钱（计划 9.2） */
    pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
) {
    val viewModel: AccountsViewModel = viewModel(
        factory = AccountsViewModel.factory(repository, pendingDeletionIds),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var dialogAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var dialogVisible by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    // 余额校准（F11）的对话框状态：独立于编辑对话框，两者不会同时打开
    var adjustAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var adjusting by remember { mutableStateOf(false) }
    var adjustError by remember { mutableStateOf<String?>(null) }

    fun openDialog(account: AccountEntity?) {
        dialogAccount = account
        dialogError = null
        dialogVisible = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账户管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = LedgerIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openDialog(null) },
                icon = { Icon(imageVector = LedgerIcons.Add, contentDescription = null) },
                text = { Text("新建账户") },
            )
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when {
            state.isLoading -> Box(contentModifier, Alignment.Center) { CircularProgressIndicator() }

            state.errorMessage != null -> EmptyState(
                title = "加载失败",
                message = state.errorMessage.orEmpty(),
                modifier = contentModifier,
            )

            state.accounts.isEmpty() -> EmptyState(
                title = "还没有账户",
                message = "先建一个账户（现金 / 储蓄卡 / 信用卡 / 微信 / 支付宝 / 其他），\n才能开始记账。",
                modifier = contentModifier,
            )

            else -> LazyColumn(modifier = contentModifier) {
                item(key = "netWorth") {
                    NetWorthRow(netWorthCents = state.netWorthCents)
                    HorizontalDivider()
                }
                items(state.accounts, key = { it.id }) { account ->
                    AccountRow(
                        account = account,
                        balanceCents = state.balanceOf(account),
                        onClick = { openDialog(account) },
                        // 归档账户不能再写新交易（校验器会拒），所以不给校准入口
                        onAdjust = if (account.isArchived) {
                            null
                        } else {
                            { adjustAccount = account; adjustError = null }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (dialogVisible) {
        AccountEditDialog(
            account = dialogAccount,
            saving = saving,
            errorMessage = dialogError,
            onDismiss = { if (!saving) dialogVisible = false },
            onSave = { name, type, cents, includeInTotal ->
                scope.launch {
                    saving = true
                    val editing = dialogAccount
                    val result = if (editing == null) {
                        viewModel.createAccount(name, type, cents, includeInTotal).map { }
                    } else {
                        viewModel.updateAccount(editing.id, name, type, cents, includeInTotal)
                    }
                    saving = false
                    result
                        .onSuccess { dialogVisible = false; dialogError = null }
                        .onFailure { dialogError = it.toAccountMessage() }
                }
            },
            onToggleArchive = dialogAccount?.let { editing ->
                {
                    scope.launch {
                        saving = true
                        viewModel.toggleArchive(editing.id, !editing.isArchived)
                        saving = false
                        dialogVisible = false
                    }
                }
            },
        )
    }

    adjustAccount?.let { target ->
        BalanceAdjustDialog(
            accountName = target.name,
            currentBalanceCents = state.balanceOf(target),
            saving = adjusting,
            errorMessage = adjustError,
            onDismiss = { if (!adjusting) adjustAccount = null },
            onConfirm = { targetCents ->
                scope.launch {
                    adjusting = true
                    viewModel.adjustBalance(target.id, targetCents)
                        .onSuccess { adjustAccount = null; adjustError = null }
                        .onFailure { adjustError = it.toAccountMessage() }
                    adjusting = false
                }
            },
        )
    }
}

/** 净资产合计：只统计"未归档且计入净资产"的账户（信用卡欠款以负余额体现，计划 3.4） */
@Composable
private fun NetWorthRow(netWorthCents: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "净资产", style = MaterialTheme.typography.bodySmall)
        Text(text = Money.formatCurrency(netWorthCents), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun AccountRow(
    account: AccountEntity,
    balanceCents: Long,
    onClick: () -> Unit,
    /** 余额校准入口（F11）；归档账户传 null，整个按钮不显示 */
    onAdjust: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = account.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = account.type.label + if (account.isArchived) " · 已归档" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (onAdjust != null) {
            IconButton(onClick = onAdjust) {
                Icon(
                    imageVector = LedgerIcons.Scale,
                    contentDescription = "校准 ${account.name} 的余额",
                )
            }
        }
        // 当前余额（推导值），不是建账时的初始余额
        Text(
            text = Money.formatCurrency(balanceCents),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
