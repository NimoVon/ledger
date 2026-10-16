package com.nimo.ledger.domain.validate

import com.nimo.ledger.util.Money

/** 账户校验错误（计划 F01）。UI 层据此给可理解的提示。 */
sealed interface AccountValidationError {
    data object NameBlank : AccountValidationError
    data object NameTooLong : AccountValidationError
    data object InitialBalanceOutOfRange : AccountValidationError
}

/** Repository 账户写入失败时包装此异常（与 TxValidationException 同构） */
class AccountValidationException(val errors: List<AccountValidationError>) :
    RuntimeException("账户校验失败: ${errors.joinToString { it::class.simpleName ?: "?" }}")

/**
 * 账户写入前的业务校验：UI 校验之外的第二道，纯函数可 JVM 单测（计划 4.4）。
 * 只校验输入合法性；余额本身是推导值（铁律 2），不在此计算。
 */
object AccountValidator {

    const val MAX_NAME_LENGTH = 20

    fun validate(name: String, initialBalanceCents: Long): List<AccountValidationError> {
        val errors = mutableListOf<AccountValidationError>()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) errors += AccountValidationError.NameBlank
        if (trimmed.length > MAX_NAME_LENGTH) errors += AccountValidationError.NameTooLong
        // 不取 abs()：Long.MIN_VALUE 会溢出；信用卡余额与校准允许为负
        if (initialBalanceCents < -Money.MAX_AMOUNT_CENTS ||
            initialBalanceCents > Money.MAX_AMOUNT_CENTS
        ) {
            errors += AccountValidationError.InitialBalanceOutOfRange
        }
        return errors
    }
}
