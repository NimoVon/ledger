package com.nimo.bookkeeping.domain.validate

import com.nimo.bookkeeping.domain.model.AccountRef
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.CategoryRef
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.util.Dates
import com.nimo.bookkeeping.util.Money
import kotlin.math.abs

/** 交易校验错误。UI 层据此给出可理解的提示（计划 4.4）。 */
sealed interface TxValidationError {
    data object AmountMustBePositive : TxValidationError
    data object AmountMustBeNonZero : TxValidationError
    data object AmountOutOfRange : TxValidationError
    data object AccountRequired : TxValidationError
    data object AccountNotFound : TxValidationError
    data object AccountArchived : TxValidationError
    data object TransferNeedsTwoAccounts : TxValidationError
    data object TransferSameAccount : TxValidationError
    data object CategoryRequired : TxValidationError
    data object CategoryNotAllowed : TxValidationError
    data object CategoryNotFound : TxValidationError
    data object CategoryArchived : TxValidationError
    data object CategoryKindMismatch : TxValidationError
    data object NoteTooLong : TxValidationError
    data object DateInvalid : TxValidationError
}

/** Repository 写入失败时包装此异常（Result.failure 需要 Throwable） */
class TxValidationException(val errors: List<TxValidationError>) :
    RuntimeException("交易校验失败: ${errors.joinToString { it::class.simpleName ?: "?" }}")

/**
 * 交易写入前的业务校验（计划 4.4：UI 校验只是第一道，这里是第二道；
 * 数据库触发器是第三道）。返回空列表表示合法。
 */
object TransactionValidator {

    const val MAX_NOTE_LENGTH = 200

    fun validate(
        input: TransactionInput,
        accounts: List<AccountRef>,
        categories: List<CategoryRef>,
    ): List<TxValidationError> {
        val errors = mutableListOf<TxValidationError>()
        val type = input.type

        // 金额（铁律 1）
        if (abs(input.amountCents) > Money.MAX_AMOUNT_CENTS) {
            errors += TxValidationError.AmountOutOfRange
        }
        if (type == TransactionType.BALANCE_ADJUST) {
            if (input.amountCents == 0L) errors += TxValidationError.AmountMustBeNonZero
        } else {
            if (input.amountCents <= 0L) errors += TxValidationError.AmountMustBePositive
        }

        // 账户
        if (type == TransactionType.TRANSFER) {
            if (input.accountId == null || input.toAccountId == null) {
                errors += TxValidationError.TransferNeedsTwoAccounts
            } else if (input.accountId == input.toAccountId) {
                errors += TxValidationError.TransferSameAccount
            }
        } else if (input.accountId == null) {
            errors += TxValidationError.AccountRequired
        }
        for (id in listOfNotNull(input.accountId, input.toAccountId)) {
            val acc = accounts.find { it.id == id }
            if (acc == null) errors += TxValidationError.AccountNotFound
            else if (acc.isArchived) errors += TxValidationError.AccountArchived
        }

        // 分类
        if (type == TransactionType.TRANSFER || type == TransactionType.BALANCE_ADJUST) {
            if (input.categoryId != null) errors += TxValidationError.CategoryNotAllowed
        } else {
            if (input.categoryId == null) {
                errors += TxValidationError.CategoryRequired
            } else {
                val cat = categories.find { it.id == input.categoryId }
                when {
                    cat == null -> errors += TxValidationError.CategoryNotFound
                    cat.isArchived -> errors += TxValidationError.CategoryArchived
                    type == TransactionType.EXPENSE && cat.kind != CategoryKind.EXPENSE ->
                        errors += TxValidationError.CategoryKindMismatch
                    type == TransactionType.INCOME && cat.kind != CategoryKind.INCOME ->
                        errors += TxValidationError.CategoryKindMismatch
                }
            }
        }

        // 备注
        if ((input.note?.length ?: 0) > MAX_NOTE_LENGTH) {
            errors += TxValidationError.NoteTooLong
        }

        // 日期：格式合法 + 真实存在（2026-02-30 这类要拒掉）
        if (!Dates.isValidIsoDate(input.occurredOn)) {
            errors += TxValidationError.DateInvalid
        }

        return errors
    }
}
