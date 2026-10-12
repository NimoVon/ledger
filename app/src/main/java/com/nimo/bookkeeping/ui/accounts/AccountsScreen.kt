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
import kotlinx.coroutines.launch

/**
 * 账户管理（F01）：列表 / 新建 / 编辑 / 归档（归档不删除历史数据）。
 * 余额不在此列显示推导值——账户列表展示"初始余额"，推导余额随流水页一起做。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(repository: LedgerRepository, onBack: () -> Unit) {
    val viewModel: AccountsViewModel = viewModel(factory = AccountsViewModel.factory(repository))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var dialogAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var dialogVisible by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }

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
                items(state.accounts, key = { it.id }) { account ->
                    AccountRow(account = account, onClick = { openDialog(account) })
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
}

@Composable
private fun AccountRow(account: AccountEntity, onClick: () -> Unit) {
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
        Text(
            text = Money.formatCurrency(account.initialBalance),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
