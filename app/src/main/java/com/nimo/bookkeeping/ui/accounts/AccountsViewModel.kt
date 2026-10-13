package com.nimo.bookkeeping.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.toBalanceInput
import com.nimo.bookkeeping.data.local.toRecordOrNull
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.stats.BalanceCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** 账户页状态（计划 4.4：至少覆盖加载、内容、空状态和可理解的错误） */
data class AccountsUiState(
    val isLoading: Boolean = true,
    val accounts: List<AccountEntity> = emptyList(),
    /**
     * 账户 id → 当前余额（分），由 [BalanceCalculator] 从初始余额与全部交易推导（铁律 2）。
     * 撤销窗口内那一笔已经剔除，因此与流水页的合计口径一致。
     */
    val balances: Map<Long, Long> = emptyMap(),
    /** 净资产 = Σ(计入净资产账户的当前余额)，信用卡欠款为负（计划 3.4） */
    val netWorthCents: Long = 0L,
    val errorMessage: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && errorMessage == null && accounts.isEmpty()

    /**
     * 列表展示用。拿不到推导值（理论不可能：账户只归档不删除）时回落到初始余额，
     * 宁可显示一个保守数字，也不让金额位置空着。
     */
    fun balanceOf(account: AccountEntity): Long = balances[account.id] ?: account.initialBalance
}

/**
 * 账户管理页（F01）。UI 不直接访问 DAO，只经由 Repository（计划 4.3）。
 *
 * 余额不落库：这里把账户与全量流水喂给 [BalanceCalculator]，页面只显示推导结果，
 * 不得自己累加、也不得把初始余额当余额（铁律 2）。
 */
class AccountsViewModel(
    private val repository: LedgerRepository,
    /**
     * 撤销窗口内那一笔（延迟物理删除，计划 9.2）的 id：数据库里它还在，
     * 但界面必须当它不存在，否则会显示一个"用户已经删掉"的余额。
     */
    private val pendingDeletionId: Flow<Long?> = flowOf(null),
) : ViewModel() {

    val uiState: StateFlow<AccountsUiState> = combine(
        repository.observeAccounts(),
        repository.observeAllTransactions(),
        pendingDeletionId,
    ) { accounts, transactions, pendingId ->
        // 非法 occurredOn（绕过校验器的直插，例如备份导入）由 toRecordOrNull 丢弃：
        // 不能让一行脏数据把 stateIn 拖死，页面永远停在加载态。
        val records = transactions
            .filter { it.id != pendingId }
            .mapNotNull { it.toRecordOrNull() }
        val inputs = accounts.map { it.toBalanceInput() }
        val balances = BalanceCalculator.computeBalances(inputs, records)
        AccountsUiState(
            isLoading = false,
            accounts = accounts,
            balances = balances,
            netWorthCents = BalanceCalculator.netWorthCents(balances, inputs),
        )
    }
        .catch { emit(AccountsUiState(isLoading = false, errorMessage = "读取账户失败，请返回重试")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    suspend fun createAccount(
        name: String,
        type: AccountType,
        initialBalanceCents: Long,
        includeInTotal: Boolean,
    ): Result<Long> = repository.createAccount(name, type, initialBalanceCents, includeInTotal)

    suspend fun updateAccount(
        id: Long,
        name: String,
        type: AccountType,
        initialBalanceCents: Long,
        includeInTotal: Boolean,
    ): Result<Unit> = repository.updateAccount(id, name, type, initialBalanceCents, includeInTotal)

    suspend fun toggleArchive(id: Long, archived: Boolean): Result<Unit> =
        repository.setAccountArchived(id, archived)

    companion object {
        fun factory(
            repository: LedgerRepository,
            pendingDeletionId: Flow<Long?> = flowOf(null),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountsViewModel(repository, pendingDeletionId) }
        }
    }
}
