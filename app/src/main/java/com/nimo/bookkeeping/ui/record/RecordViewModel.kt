package com.nimo.bookkeeping.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.validate.TransactionValidator
import com.nimo.bookkeeping.domain.validate.TxValidationError
import com.nimo.bookkeeping.domain.validate.TxValidationException
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
    /** 保存成功的一次性信号：非空表示已落库，页面据此返回 */
    val savedTransactionId: Long? = null,
) {
    /** 一个可用账户都没有：引导用户先去建账户，而不是给一张空白表单 */
    val hasNoAccount: Boolean
        get() = !isLoading && loadErrorMessage == null && accounts.isEmpty()
}

/** 提交态：把"保存中 / 表单错误 / 保存失败 / 保存成功信号"收在一起，避免 combine 参数爆炸 */
private data class SubmitState(
    val isSaving: Boolean = false,
    val errors: List<RecordFormError> = emptyList(),
    val saveErrorMessage: String? = null,
    val savedTransactionId: Long? = null,
)

/**
 * 记一笔（F03，仅支出/收入）。
 *
 * UI 不直接访问 DAO，读写全部经 [LedgerRepository]（计划 4.3）。
 * 表单状态放在 ViewModel，页面只负责输入与呈现（计划 4.4）。
 */
class RecordViewModel(private val repository: LedgerRepository) : ViewModel() {

    private val form = MutableStateFlow(RecordForm())
    private val submit = MutableStateFlow(SubmitState())

    /** null = 尚未加载完成 */
    private val accounts = MutableStateFlow<List<AccountEntity>?>(null)
    private val categories = MutableStateFlow<List<CategoryEntity>?>(null)
    private val loadError = MutableStateFlow<String?>(null)

    init {
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

    val uiState: StateFlow<RecordUiState> = combine(
        accounts,
        categories,
        form,
        submit,
        loadError,
    ) { accounts, categories, form, submit, loadError ->
        val accountList = accounts.orEmpty()
        RecordUiState(
            isLoading = accounts == null || categories == null,
            loadErrorMessage = loadError,
            accounts = accountList,
            categories = categories.orEmpty(),
            form = form,
            effectiveAccountId = form.accountId ?: accountList.firstOrNull()?.id,
            formErrors = submit.errors,
            isSaving = submit.isSaving,
            saveErrorMessage = submit.saveErrorMessage,
            savedTransactionId = submit.savedTransactionId,
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

    // ---- 保存 ----

    /**
     * 保存。金额经 Money.parseCents 解析为整数分后交给 Repository；
     * Repository 会再跑一遍 [TransactionValidator]，数据库触发器是最后一道。
     */
    fun save() {
        if (submit.value.isSaving) return

        val current = form.value
        // 与 UI 显示的 effectiveAccountId 保持一致：没显式选就用第一个可用账户
        val toSubmit = current.copy(
            accountId = current.accountId ?: accounts.value.orEmpty().firstOrNull()?.id,
        )

        when (val outcome = toSubmit.toOutcome()) {
            is RecordFormOutcome.Invalid ->
                submit.update { it.copy(errors = outcome.errors, saveErrorMessage = null) }

            is RecordFormOutcome.Valid -> {
                submit.update {
                    it.copy(isSaving = true, errors = emptyList(), saveErrorMessage = null)
                }
                viewModelScope.launch {
                    val result = repository.addTransaction(outcome.input)
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

    companion object {
        fun factory(repository: LedgerRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { RecordViewModel(repository) }
        }
    }
}

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
