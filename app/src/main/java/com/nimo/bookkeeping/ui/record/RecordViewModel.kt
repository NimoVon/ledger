package com.nimo.bookkeeping.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.validate.TransactionValidator
import com.nimo.bookkeeping.domain.validate.TxValidationError
import com.nimo.bookkeeping.domain.validate.TxValidationException
import com.nimo.bookkeeping.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 记账页状态（计划 4.4：至少覆盖加载、内容、空状态和可理解的错误提示）。
 */
data class RecordUiState(
    val isLoading: Boolean = true,
    val loadErrorMessage: String? = null,
    /** 只含**未归档**账户（M2 验收：归档账户不出现在记账页账户选择器） */
    val accounts: List<AccountEntity> = emptyList(),
    /** 当前收支方向下可选的分类，已由 DAO 过滤掉停用分类 */
    val categories: List<CategoryEntity> = emptyList(),
    val form: RecordForm = RecordForm(),
    /** 实际生效的账户：用户没显式选时用列表第一个兜底，保证"记一笔"仍然 ≤3 次点击 */
    val effectiveAccountId: Long? = null,
    /** 点击保存后的表单错误（不实时打扰输入，只在提交时给提示） */
    val formErrors: List<RecordFormError> = emptyList(),
    val isSaving: Boolean = false,
    val saveErrorMessage: String? = null,
    /** 保存成功的一次性信号：非空表示已落库，页面据此返回（编辑时就是这一笔自己的 id） */
    val savedTransactionId: Long? = null,
    /** 编辑模式（点流水行进入）：标题、按钮文案与保存链路都不同 */
    val isEditing: Boolean = false,
    /** 正在编辑的那一整笔；删除入口需要它的完整内容 */
    val editingTransaction: TransactionEntity? = null,
    /** 原账户已归档 / 原分类已停用：它们按 M2 规则不在选择器里，得明确提示重选，别让用户存不进去还找不到原因 */
    val originalAccountUnavailable: Boolean = false,
    val originalCategoryUnavailable: Boolean = false,
    /**
     * 原账户已归档时它的名字，页面据此说清"这笔记在哪个账户上"。
     * 取不到就为 null，页面退化成"请重新选择"文案。
     */
    val originalAccountName: String? = null,
    /** 正在把原账户取消归档：按钮防连点 */
    val isRestoringAccount: Boolean = false,
    /** 删除入口的一次性信号：非空表示用户要求删掉这一笔（真正的物理删除交给延迟删除策略，计划 9.2） */
    val deleteRequested: TransactionEntity? = null,
) {
    /**
     * 一个可用账户都没有：引导用户先去建账户，而不是给一张空白表单。
     *
     * **编辑模式例外**——这一笔可能记在唯一的已归档账户上，此时 accounts 为空但表单必须露出来，
     * 否则用户既看不到"这笔记在哪个账户上"，也拿不到"恢复账户并编辑"的入口。
     */
    val hasNoAccount: Boolean
        get() = !isEditing && !isLoading && loadErrorMessage == null && accounts.isEmpty()

    /**
     * 这一笔不在本页可编辑范围内：转账（F04）与余额调整（P1）有自己的入口，
     * 硬套收支表单会写出半截数据（例如丢掉 toAccountId）。
     */
    val isEditingUnsupportedType: Boolean
        get() = isEditing && editingTransaction != null && !form.isSupportedType
}

/** 提交态：把"保存中 / 表单错误 / 保存失败 / 保存成功信号"收在一起，避免 combine 参数爆炸 */
private data class SubmitState(
    val isSaving: Boolean = false,
    val errors: List<RecordFormError> = emptyList(),
    val saveErrorMessage: String? = null,
    val savedTransactionId: Long? = null,
    /** 正在取消归档原账户（F03 编辑归档账户），跟保存共用一个"动作进行中"的闸门 */
    val isRestoringAccount: Boolean = false,
)

/** 页面数据来源：外部列表 + 被编辑的整笔 + 读取错误，凑齐后才知道页面能不能画 */
private data class LoadState(
    val accounts: List<AccountEntity>?,
    val categories: List<CategoryEntity>?,
    val editingTransaction: TransactionEntity?,
    val editingLoaded: Boolean,
    val errorMessage: String?,
)

/**
 * 记一笔 / 改一笔（F03，仅支出/收入）。
 *
 * UI 不直接访问 DAO，读写全部经 [LedgerRepository]（计划 4.3）。
 * 表单状态放在 ViewModel，页面只负责输入与呈现（计划 4.4）。
 *
 * @param editingId 非空 = 编辑这一笔：预填整笔内容，保存走整行替换（id 不变，顺序不跳动）
 */
class RecordViewModel(
    private val repository: LedgerRepository,
    private val editingId: Long? = null,
) : ViewModel() {

    private val form = MutableStateFlow(RecordForm())
    private val submit = MutableStateFlow(SubmitState())

    /** null = 尚未加载完成 */
    private val accounts = MutableStateFlow<List<AccountEntity>?>(null)
    private val categories = MutableStateFlow<List<CategoryEntity>?>(null)
    private val loadError = MutableStateFlow<String?>(null)

    /** 编辑模式：被编辑的那一整笔（null = 还没加载完，或这一笔已不存在） */
    private val editingTransaction = MutableStateFlow<TransactionEntity?>(null)

    /** 编辑模式：这一笔原本记在哪个账户（含已归档），提示里要说出它的名字 */
    private val editingAccount = MutableStateFlow<AccountEntity?>(null)

    /** 编辑对象是否已读取完毕。非编辑模式没有东西要读，一开始就是 true */
    private val editingLoaded = MutableStateFlow(editingId == null)

    /** 删除入口的一次性信号 */
    private val deleteRequest = MutableStateFlow<TransactionEntity?>(null)

    init {
        viewModelScope.launch {
            val id = editingId ?: return@launch
            val entity = runCatching { repository.getTransaction(id) }.getOrNull()
            if (entity == null) {
                // 例：流水行还在屏幕上，但这一笔刚被删掉
                loadError.value = "这笔记录不存在，可能已被删除"
            } else {
                editingTransaction.value = entity
                form.value = entity.toRecordForm()
                // 账户可能已归档（不在选择器里），但提示里要报出名字
                editingAccount.value = entity.accountId?.let { accountId ->
                    runCatching { repository.getAccount(accountId) }.getOrNull()
                }
            }
            editingLoaded.value = true
        }
        viewModelScope.launch {
            repository.observeActiveAccounts()
                .catch {
                    // 不吞异常：转成用户能看懂的提示（日志不打交易内容）
                    loadError.value = "读取账户失败，请返回重试"
                    emit(emptyList())
                }
                .collect { accounts.value = it }
        }
        viewModelScope.launch {
            form.map { it.type }
                .distinctUntilChanged()
                .collectLatest { type ->
                    // 换收支方向就换一套分类（支出/收入分类互不通用）
                    repository.observeActiveCategories(type.toCategoryKind())
                        .catch {
                            loadError.value = "读取分类失败，请返回重试"
                            emit(emptyList())
                        }
                        .collect { categories.value = it }
                }
        }
    }

    private val load: Flow<LoadState> = combine(
        accounts,
        categories,
        editingTransaction,
        editingLoaded,
        loadError,
    ) { accounts, categories, editingTransaction, editingLoaded, loadError ->
        LoadState(
            accounts = accounts,
            categories = categories,
            editingTransaction = editingTransaction,
            editingLoaded = editingLoaded,
            errorMessage = loadError,
        )
    }

    val uiState: StateFlow<RecordUiState> = combine(
        load,
        form,
        submit,
        deleteRequest,
        editingAccount,
    ) { load, form, submit, deleteRequest, originalAccount ->
        val accountList = load.accounts.orEmpty()
        val categoryList = load.categories.orEmpty()
        val isEditing = editingId != null
        // 选择器只给未归档/未停用的项（M2 验收），原值不在其中就提示重选，而不是静默丢掉
        val originalAccountUnavailable = isEditing && form.accountId != null &&
            accountList.none { it.id == form.accountId }
        RecordUiState(
            isLoading = load.accounts == null || load.categories == null || !load.editingLoaded,
            loadErrorMessage = load.errorMessage,
            accounts = accountList,
            categories = categoryList,
            form = form,
            // 新建时没选就兜底第一个可用账户；编辑时绝不兜底，否则会把这一笔悄悄记到别的账户上
            effectiveAccountId = if (isEditing) {
                form.accountId
            } else {
                form.accountId ?: accountList.firstOrNull()?.id
            },
            isEditing = isEditing,
            editingTransaction = load.editingTransaction,
            originalAccountUnavailable = originalAccountUnavailable,
            originalAccountName = if (originalAccountUnavailable) originalAccount?.name else null,
            originalCategoryUnavailable = isEditing && form.categoryId != null &&
                categoryList.none { it.id == form.categoryId },
            isRestoringAccount = submit.isRestoringAccount,
            formErrors = submit.errors,
            isSaving = submit.isSaving,
            saveErrorMessage = submit.saveErrorMessage,
            savedTransactionId = submit.savedTransactionId,
            deleteRequested = deleteRequest,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordUiState())

    // ---- 表单输入 ----

    fun setType(type: TransactionType) = updateForm { it.withType(type) }

    fun setAmount(text: String) = updateForm { it.copy(amountText = text) }

    fun setAccount(id: Long) = updateForm { it.copy(accountId = id) }

    fun setCategory(id: Long) = updateForm { it.copy(categoryId = id) }

    fun setDate(isoDate: String) = updateForm { it.copy(occurredOn = isoDate) }

    fun setNote(text: String) = updateForm { it.withNote(text) }

    /** 表单一改，上一次的提交错误就过期了，清掉免得误导 */
    private fun updateForm(transform: (RecordForm) -> RecordForm) {
        form.update(transform)
        submit.update { it.copy(errors = emptyList(), saveErrorMessage = null) }
    }

    // ---- 保存：新建走 insert，编辑走整行替换 ----

    /**
     * 保存。金额经 Money.parseCents 解析为整数分后交给 Repository；
     * Repository 会再跑一遍 [TransactionValidator]，数据库触发器是最后一道。
     */
    fun save() {
        if (submit.value.isSaving) return

        val current = form.value
        val editing = editingId
        // 新建时与 UI 显示的 effectiveAccountId 保持一致：没显式选就用第一个可用账户
        val toSubmit = if (editing == null) {
            current.copy(accountId = current.accountId ?: accounts.value.orEmpty().firstOrNull()?.id)
        } else {
            current
        }

        when (val outcome = toSubmit.toOutcome()) {
            is RecordFormOutcome.Invalid ->
                submit.update { it.copy(errors = outcome.errors, saveErrorMessage = null) }

            is RecordFormOutcome.Valid -> {
                submit.update {
                    it.copy(isSaving = true, errors = emptyList(), saveErrorMessage = null)
                }
                viewModelScope.launch {
                    // 编辑 = 整行替换，id 与 createdAt 都保持原样（Repository 负责）
                    val result: Result<Long> = if (editing == null) {
                        repository.addTransaction(outcome.input)
                    } else {
                        repository.updateTransaction(editing, outcome.input).map { editing }
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

    /** 页面完成返回后调用，避免保存成功信号在重建时重复触发导航 */
    fun consumeSaved() {
        submit.update { it.copy(savedTransactionId = null) }
    }

    // ---- 恢复原账户（仅编辑模式）----

    /**
     * 把这一笔的原账户取消归档，然后继续编辑。
     *
     * 归档账户不能再记新账（M2 验收），直接保存会被第二道防线拒绝。与其让用户把这一笔挪到别的
     * 账户上——那会污染历史口径——不如就地恢复原账户：账户回到选择器，这一笔的归属一个字不动。
     * [observeActiveAccounts] 是热流，恢复成功后账户自己会回到列表，这里不需要改表单。
     */
    fun restoreOriginalAccount() {
        val accountId = form.value.accountId ?: return
        if (submit.value.isRestoringAccount) return
        submit.update { it.copy(isRestoringAccount = true, saveErrorMessage = null) }
        viewModelScope.launch {
            val failure = repository.setAccountArchived(accountId, archived = false).exceptionOrNull()
            submit.update { it.copy(isRestoringAccount = false, saveErrorMessage = failure?.toRecordMessage()) }
        }
    }

    // ---- 删除（仅编辑模式）----

    /**
     * 请求删除这一笔。这里**不做物理删除**：把整笔交出去，由 UI 层延迟物理删除策略
     * （计划 9.2：先消失 + Snackbar 可撤销，超时才落库）决定何时真正删。
     */
    fun requestDelete() {
        deleteRequest.value = editingTransaction.value
    }

    /** 页面完成返回后调用，避免删除信号在重建时重复触发 */
    fun consumeDeleteRequest() {
        deleteRequest.value = null
    }

    companion object {
        fun factory(
            repository: LedgerRepository,
            editingId: Long? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RecordViewModel(repository, editingId) }
        }
    }
}

/**
 * 整笔 → 表单（编辑入口预填）。
 *
 * 金额用 [Money.format] 还原成"元"文本，保存时再经同一条 [Money.parseCents] 回到整数分：
 * 全程只有整数运算，绝不出现 Float/Double（铁律 1）。
 */
internal fun TransactionEntity.toRecordForm(): RecordForm = RecordForm(
    type = type,
    amountText = Money.format(amount),
    accountId = accountId,
    categoryId = categoryId,
    occurredOn = occurredOn,
    note = note.orEmpty(),
)

/** 记账页只会切到这两种分类体系；其余类型被 [RecordForm.toOutcome] 挡在提交之前。 */
private fun TransactionType.toCategoryKind(): CategoryKind = when (this) {
    TransactionType.EXPENSE -> CategoryKind.EXPENSE
    TransactionType.INCOME -> CategoryKind.INCOME
    TransactionType.TRANSFER, TransactionType.BALANCE_ADJUST -> CategoryKind.EXPENSE
}

/** 保存失败 → 可理解的中文提示（计划 4.4：不吞异常，也不把原始异常甩给用户） */
internal fun Throwable.toRecordMessage(): String = when (this) {
    is TxValidationException -> errors.joinToString("；") { it.toMessage() }
    else -> message ?: "保存失败，请重试"
}

internal fun TxValidationError.toMessage(): String = when (this) {
    TxValidationError.AmountMustBePositive -> "金额必须大于 0"
    TxValidationError.AmountMustBeNonZero -> "金额不能为 0"
    TxValidationError.AmountOutOfRange -> "金额超出允许范围"
    TxValidationError.AccountRequired -> "请选择账户"
    TxValidationError.AccountNotFound -> "账户不存在，请重新选择"
    TxValidationError.AccountArchived -> "该账户已归档，不能再记新账"
    TxValidationError.TransferNeedsTwoAccounts -> "转账需要两个不同的账户"
    TxValidationError.TransferSameAccount -> "转出与转入账户不能相同"
    TxValidationError.CategoryRequired -> "请选择分类"
    TxValidationError.CategoryNotAllowed -> "该类型不允许选择分类"
    TxValidationError.CategoryNotFound -> "分类不存在，请重新选择"
    TxValidationError.CategoryArchived -> "该分类已停用，请重新选择"
    TxValidationError.CategoryKindMismatch -> "分类与收支类型不符"
    TxValidationError.NoteTooLong -> "备注最多 ${TransactionValidator.MAX_NOTE_LENGTH} 字"
    TxValidationError.DateInvalid -> "日期不正确"
}
