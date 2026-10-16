package com.nimo.ledger.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.ledger.data.local.entity.AccountEntity
import com.nimo.ledger.data.local.entity.TransactionEntity
import com.nimo.ledger.data.local.toBalanceInput
import com.nimo.ledger.data.local.toRecordOrNull
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.stats.BalanceCalculator
import com.nimo.ledger.domain.stats.TransactionVisibility
import com.nimo.ledger.ui.record.toRecordMessage
import com.nimo.ledger.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 转账页（F04）的状态。
 *
 * @param effectiveFromAccountId 转出账户的"实际生效值"：新建时如果用户还没选，就是第一个可用账户
 *   （和记一笔一样，保住"少点几下"），这样界面上被选中的 chip 和保存时用的账户是同一个
 * @param amountCents 金额（分），空或格式不对时为 null；余额不足警示用它，避免 UI 再解析一遍
 * @param balances 由 [BalanceCalculator] 推导的账户余额（余额不落库，铁律 2）
 */
data class TransferUiState(
    val isLoading: Boolean = true,
    val loadErrorMessage: String? = null,
    val accounts: List<AccountEntity> = emptyList(),
    val balances: Map<Long, Long> = emptyMap(),
    val form: TransferForm = TransferForm(),
    val effectiveFromAccountId: Long? = null,
    val amountCents: Long? = null,
    val formErrors: List<TransferFormError> = emptyList(),
    val isSaving: Boolean = false,
    val saveErrorMessage: String? = null,
    val savedTransactionId: Long? = null,
    val isEditing: Boolean = false,
    val editingTransaction: TransactionEntity? = null,
    val originalFromAccountUnavailable: Boolean = false,
    val originalFromAccountName: String? = null,
    val originalToAccountUnavailable: Boolean = false,
    val originalToAccountName: String? = null,
    val isRestoringAccount: Boolean = false,
    val deleteRequested: TransactionEntity? = null,
) {

    /**
     * 判断"余额够不够"用的基准余额：**这一笔转账还没有生效时**转出账户的余额。
     * 新建时就是当前余额；编辑时本笔已经算进余额里了，要把它加回去，
     * 否则把金额改大会拿"已经被扣过"的余额去比，警告会误报。
     */
    val balanceBasisCents: Long?
        get() {
            val accountId = effectiveFromAccountId ?: return null
            val current = balances[accountId] ?: return null
            val editing = editingTransaction
            return if (editing != null && editing.accountId == accountId) {
                current + editing.amount
            } else {
                current
            }
        }

    /**
     * 转出账户余额不足。计划 F04 只要求**警示**，用户可以继续（"可选择继续"），
     * 所以它只是一个提示，不参与保存拦截。
     */
    val isFromBalanceInsufficient: Boolean
        get() {
            val amount = amountCents ?: return false
            val basis = balanceBasisCents ?: return false
            return amount > 0L && amount > basis
        }

    /** 可用账户不到两个：转不动，给一条明确的出路而不是让用户对着空表单发呆 */
    val hasNotEnoughAccounts: Boolean
        get() = !isLoading && loadErrorMessage == null && accounts.size < 2

    /** 该显示表单（而不是加载中/错误/账户不够）：顶栏的「保存」也按它决定露不露面 */
    val isFormVisible: Boolean
        get() = !isLoading && loadErrorMessage == null && !hasNotEnoughAccounts
}

/** 提交态（与记一笔同构：错误/保存中/保存成功/恢复账户防连点） */
private data class TransferSubmitState(
    val isSaving: Boolean = false,
    val errors: List<TransferFormError> = emptyList(),
    val saveErrorMessage: String? = null,
    val savedTransactionId: Long? = null,
    val isRestoringAccount: Boolean = false,
)

/** 账户 / 编辑对象 / 余额的加载态 */
private data class TransferLoadState(
    val accounts: List<AccountEntity>? = null,
    val editingTransaction: TransactionEntity? = null,
    val editingLoaded: Boolean = false,
    val errorMessage: String? = null,
    val balances: Map<Long, Long> = emptyMap(),
)

/** 编辑时那笔转账原来挂着的两个账户（用于"已被归档"的提示与恢复） */
private data class OriginalAccounts(
    val from: AccountEntity? = null,
    val to: AccountEntity? = null,
)

/**
 * 转账页的 ViewModel（F04）。
 *
 * 写库一律走 [LedgerRepository.addTransaction] / [LedgerRepository.updateTransaction]：
 * 转账就是**一行** transactions（转出账户 + 转入账户 + 金额 + 无分类）。
 * 余额是推导值（[BalanceCalculator]），不是另一条要同步更新的记录，
 * 所以"同一事务内两端一起变""编辑时先撤销旧变动再应用新变动"由数据结构本身保证，
 * 不需要额外的事务代码（计划 4.4 / 6.3 的要求，测试里会钉住）。
 */
class TransferViewModel(
    private val repository: LedgerRepository,
    private val editingId: Long? = null,
    private val pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
) : ViewModel() {

    private val form = MutableStateFlow(TransferForm())
    private val submit = MutableStateFlow(TransferSubmitState())
    private val accounts = MutableStateFlow<List<AccountEntity>?>(null)
    private val balances = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val loadError = MutableStateFlow<String?>(null)
    private val editingTransaction = MutableStateFlow<TransactionEntity?>(null)
    private val editingOriginals = MutableStateFlow(OriginalAccounts())
    private val editingLoaded = MutableStateFlow(editingId == null)
    private val deleteRequest = MutableStateFlow<TransactionEntity?>(null)

    init {
        // 1) 编辑模式：先把这一笔和它原来的两个账户读出来
        viewModelScope.launch {
            val id = editingId
            if (id != null) {
                val entity = runCatching { repository.getTransaction(id) }.getOrNull()
                when {
                    entity == null -> loadError.value = "这笔记录不存在，可能已被删除"

                    entity.type != TransactionType.TRANSFER ->
                        loadError.value = "这一笔不是转账，请到「记一笔」里编辑"

                    else -> {
                        editingTransaction.value = entity
                        form.value = entity.toTransferForm()
                        editingOriginals.value = OriginalAccounts(
                            from = entity.accountId?.let {
                                runCatching { repository.getAccount(it) }.getOrNull()
                            },
                            to = entity.toAccountId?.let {
                                runCatching { repository.getAccount(it) }.getOrNull()
                            },
                        )
                    }
                }
            }
            editingLoaded.value = true
        }

        // 2) 可选账户：只列未归档的（M2：归档账户不能用于新交易）
        viewModelScope.launch {
            repository.observeActiveAccounts()
                .catch {
                    loadError.value = "读取账户失败，请返回重试"
                    emit(emptyList())
                }
                .collect { accounts.value = it }
        }

        // 3) 余额（余额不足警示的输入）：全部账户 + 全部流水推导
        viewModelScope.launch {
            combine(
                repository.observeAccounts(),
                repository.observeAllTransactions(),
                pendingDeletionIds,
            ) { allAccounts, transactions, pending -> Triple(allAccounts, transactions, pending) }
                .catch {
                    loadError.value = "读取账户余额失败，请返回重试"
                    emit(Triple(emptyList(), emptyList(), emptySet<Long>()))
                }
                .collect { (allAccounts, transactions, pending) ->
                    val records = TransactionVisibility.visible(transactions, pending)
                        .mapNotNull { it.toRecordOrNull() }
                    balances.value = BalanceCalculator.computeBalances(
                        allAccounts.map { it.toBalanceInput() },
                        records,
                    )
                }
        }
    }

    private val load = combine(
        accounts,
        editingTransaction,
        editingLoaded,
        loadError,
        balances,
    ) { accounts, editing, loaded, error, balances ->
        TransferLoadState(accounts, editing, loaded, error, balances)
    }

    val uiState = combine(
        load,
        form,
        submit,
        deleteRequest,
        editingOriginals,
    ) { load, form, submit, deleteRequest, originals ->
        // 新建时默认选中第一个可用账户：和记一笔一样，不让用户为了"随手转一笔"多点一步
        val effectiveFrom = form.fromAccountId
            ?: if (load.editingTransaction == null) load.accounts.orEmpty().firstOrNull()?.id else null

        TransferUiState(
            isLoading = load.accounts == null || !load.editingLoaded,
            loadErrorMessage = load.errorMessage,
            accounts = load.accounts.orEmpty(),
            balances = load.balances,
            form = form,
            effectiveFromAccountId = effectiveFrom,
            amountCents = if (form.amountText.isBlank()) null else Money.parseCents(form.amountText),
            formErrors = submit.errors,
            isSaving = submit.isSaving,
            saveErrorMessage = submit.saveErrorMessage,
            savedTransactionId = submit.savedTransactionId,
            isEditing = load.editingTransaction != null,
            editingTransaction = load.editingTransaction,
            originalFromAccountUnavailable = isArchived(originals.from, load.accounts),
            originalFromAccountName = originals.from?.name,
            originalToAccountUnavailable = isArchived(originals.to, load.accounts),
            originalToAccountName = originals.to?.name,
            isRestoringAccount = submit.isRestoringAccount,
            deleteRequested = deleteRequest,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransferUiState())

    /**
     * 编辑时原来那个账户还能不能用：**不看** init 时读出来的那份快照的 isArchived，
     * 而是看它是否还在"可用账户"里——用户点了「恢复账户并编辑」之后快照不会自己刷新，
     * 只看快照的话提示会一直挂着（测试 anArchivedSourceAccount_... 钉住这条）。
     */
    private fun isArchived(account: AccountEntity?, activeAccounts: List<AccountEntity>?): Boolean {
        if (account == null || activeAccounts == null) return false
        return activeAccounts.none { it.id == account.id }
    }

    fun setFromAccount(accountId: Long) = updateForm { it.copy(fromAccountId = accountId) }

    fun setToAccount(accountId: Long) = updateForm { it.copy(toAccountId = accountId) }

    fun setAmount(text: String) = updateForm { it.copy(amountText = text) }

    fun setDate(isoDate: String) = updateForm { it.copy(occurredOn = isoDate) }

    fun setNote(text: String) = updateForm { it.withNote(text) }

    private fun updateForm(transform: (TransferForm) -> TransferForm) {
        form.update(transform)
        submit.update { it.copy(errors = emptyList(), saveErrorMessage = null) }
    }

    /**
     * 保存。新建走 addTransaction、编辑走 updateTransaction（整行替换）。
     * 余额不足**不在这里拦**：计划 F04 要求的是"警示（可选择继续）"。
     */
    fun save() {
        if (submit.value.isSaving) return
        val current = form.value
        val toSubmit = if (editingTransaction.value == null) {
            // 用户没手动选过转出账户时，落成界面上显示的那个默认账户
            current.copy(
                fromAccountId = current.fromAccountId
                    ?: accounts.value.orEmpty().firstOrNull()?.id,
            )
        } else {
            current
        }

        when (val outcome = toSubmit.toOutcome()) {
            is TransferFormOutcome.Invalid ->
                submit.update { it.copy(errors = outcome.errors, saveErrorMessage = null) }

            is TransferFormOutcome.Valid -> {
                submit.update {
                    it.copy(isSaving = true, errors = emptyList(), saveErrorMessage = null)
                }
                val editing = editingTransaction.value
                viewModelScope.launch {
                    val result = if (editing == null) {
                        repository.addTransaction(outcome.input)
                    } else {
                        repository.updateTransaction(editing.id, outcome.input).map { editing.id }
                    }
                    submit.update { state ->
                        result.fold(
                            onSuccess = { id -> state.copy(isSaving = false, savedTransactionId = id) },
                            onFailure = { error ->
                                state.copy(isSaving = false, saveErrorMessage = error.toRecordMessage())
                            },
                        )
                    }
                }
            }
        }
    }

    fun consumeSaved() {
        submit.update { it.copy(savedTransactionId = null) }
    }

    /**
     * 编辑时原来那个账户已经被归档了：恢复它再编辑。
     * 归档只是 isArchived 置位，账户和历史数据都还在（计划铁律 5）。
     */
    fun restoreOriginalAccount(accountId: Long) {
        if (submit.value.isRestoringAccount) return
        submit.update { it.copy(isRestoringAccount = true) }
        viewModelScope.launch {
            repository.setAccountArchived(accountId, archived = false)
            submit.update { it.copy(isRestoringAccount = false) }
        }
    }

    fun requestDelete() {
        deleteRequest.value = editingTransaction.value
    }

    fun consumeDeleteRequest() {
        deleteRequest.value = null
    }

    companion object {
        fun factory(
            repository: LedgerRepository,
            editingId: Long? = null,
            pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { TransferViewModel(repository, editingId, pendingDeletionIds) }
        }
    }
}

/** 编辑入口：把库里那一行还原成表单（金额回填用不分组格式，回填后会再经 Money.parseCents） */
internal fun TransactionEntity.toTransferForm(): TransferForm = TransferForm(
    fromAccountId = accountId,
    toAccountId = toAccountId,
    amountText = Money.format(amount),
    occurredOn = occurredOn,
    note = note.orEmpty(),
)
