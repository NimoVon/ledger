package com.nimo.bookkeeping.domain.validate

import com.nimo.bookkeeping.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountValidatorTest {

    @Test
    fun validNameAndBalance_hasNoErrors() {
        assertTrue(AccountValidator.validate("招商银行", 100_000L).isEmpty())
    }

    @Test
    fun emptyName_rejected() {
        val errors = AccountValidator.validate("", 0L)
        assertEquals(listOf(AccountValidationError.NameBlank), errors)
    }

    @Test
    fun whitespaceOnlyName_rejected() {
        val errors = AccountValidator.validate("   ", 0L)
        assertEquals(listOf(AccountValidationError.NameBlank), errors)
    }

    @Test
    fun nameAtMaxLength_isValid() {
        val name = "账".repeat(AccountValidator.MAX_NAME_LENGTH)
        assertTrue(AccountValidator.validate(name, 0L).isEmpty())
    }

    @Test
    fun nameOverMaxLength_rejected() {
        val name = "账".repeat(AccountValidator.MAX_NAME_LENGTH + 1)
        assertEquals(listOf(AccountValidationError.NameTooLong), AccountValidator.validate(name, 0L))
    }

    @Test
    fun nameTrimmedBeforeLengthCheck() {
        // 前后空白不计入长度
        val name = "  " + "账".repeat(AccountValidator.MAX_NAME_LENGTH) + "  "
        assertTrue(AccountValidator.validate(name, 0L).isEmpty())
    }

    @Test
    fun balanceAtMax_isValid() {
        assertTrue(AccountValidator.validate("现金", Money.MAX_AMOUNT_CENTS).isEmpty())
    }

    @Test
    fun negativeBalanceAtMin_isValid() {
        // 信用卡欠款 / 余额校准可以为负
        assertTrue(AccountValidator.validate("信用卡", -Money.MAX_AMOUNT_CENTS).isEmpty())
    }

    @Test
    fun balanceOverMax_rejected() {
        val errors = AccountValidator.validate("现金", Money.MAX_AMOUNT_CENTS + 1)
        assertEquals(listOf(AccountValidationError.InitialBalanceOutOfRange), errors)
    }

    @Test
    fun balanceBelowMin_rejected() {
        val errors = AccountValidator.validate("现金", -Money.MAX_AMOUNT_CENTS - 1)
        assertEquals(listOf(AccountValidationError.InitialBalanceOutOfRange), errors)
    }

    @Test
    fun longMinValue_doesNotOverflowIntoValid() {
        // abs(Long.MIN_VALUE) 会溢出成负数，这里必须仍判为超范围
        assertEquals(
            listOf(AccountValidationError.InitialBalanceOutOfRange),
            AccountValidator.validate("现金", Long.MIN_VALUE),
        )
    }

    @Test
    fun blankNameAndBadBalance_bothReported() {
        val errors = AccountValidator.validate("", Long.MAX_VALUE)
        assertEquals(
            listOf(AccountValidationError.NameBlank, AccountValidationError.InitialBalanceOutOfRange),
            errors,
        )
    }
}
