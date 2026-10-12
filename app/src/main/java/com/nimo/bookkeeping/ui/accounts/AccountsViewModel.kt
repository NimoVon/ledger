package com.nimo.bookkeeping.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.AccountType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 账户页状态（计划 4.4：至少覆盖加载、内容、空状态和可理解的错误） */
data class AccountsUiState(
    val isLoading: Boolean = true,
    val accounts: List<AccountEntity> = emptyList(),
    val errorMessage: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && errorMessage == null && accounts.isEmpty()
}

/**
 * 账户管理页（F01）。UI 不直接访问 DAO，只经由 Repository（计划 4.3）。
 */
class AccountsViewModel(private val repository: LedgerRepository) : ViewModel() {

    val uiState: StateFlow<AccountsUiState> = repository.observeAccounts()
        .map<List<AccountEntity>, AccountsUiState> { AccountsUiState(isLoading = false, accounts = it) }
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
        fun factory(repository: LedgerRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountsViewModel(repository) }
        }
    }
}
