package com.nimo.ledger.domain.validate

import com.nimo.ledger.domain.model.AccountRef
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.CategoryRef
import com.nimo.ledger.domain.model.TransactionInput
import com.nimo.ledger.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionValidatorTest {

    private val accounts = listOf(
        AccountRef(1, isArchived = false),
        AccountRef(2, isArchived = false),
        AccountRef(3, isArchived = true), // 已归档
    )

    private val categories = listOf(
        CategoryRef(11, CategoryKind.EXPENSE, isArchived = false),
        CategoryRef(101, CategoryKind.INCOME, isArchived = false),
        CategoryRef(12, CategoryKind.EXPENSE, isArchived = true), // 已归档
    )

    private fun input(
        type: TransactionType = TransactionType.EXPENSE,
        amountCents: Long = 1000,
        accountId: Long? = 1,
        toAccountId: Long? = null,
        categoryId: Long? = 11,
        occurredOn: String = "2026-10-12",
        note: String? = null,
    ) = TransactionInput(type, amountCents, accountId, toAccountId, categoryId, occurredOn, note)

    @Test
    fun validExpense_hasNoErrors() {
        assertTrue(TransactionValidator.validate(input(), accounts, categories).isEmpty())
    }

    @Test
    fun validIncome_usesIncomeCategory() {
        val errors = TransactionValidator.validate(
            input(type = TransactionType.INCOME, categoryId = 101),
            accounts, categories,
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun validTransfer_noCategory() {
        val errors = TransactionValidator.validate(
            input(type = TransactionType.TRANSFER, amountCents = 500, accountId = 1, toAccountId = 2, categoryId = null),
            accounts, categories,
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun validBalanceAdjust_canBeNegative() {
        val errors = TransactionValidator.validate(
            input(type = TransactionType.BALANCE_ADJUST, amountCents = -350, accountId = 1, categoryId = null),
            accounts, categories,
        )
        assertTrue(errors.isEmpty())
    }

    // ---- 金额 ----

    @Test
    fun zeroAmount_rejectedForExpense() {
        val errors = TransactionValidator.validate(input(amountCents = 0), accounts, categories)
        assertEquals(listOf(TxValidationError.AmountMustBePositive), errors)
    }

    @Test
    fun negativeAmount_rejectedForExpense() {
        val errors = TransactionValidator.validate(input(amountCents = -100), accounts, categories)
        assertEquals(listOf(TxValidationError.AmountMustBePositive), errors)
    }

    @Test
    fun zeroAmount_rejectedForBalanceAdjust() {
        val errors = TransactionValidator.validate(
            input(type = TransactionType.BALANCE_ADJUST, amountCents = 0, categoryId = null),
            accounts, categories,
        )
        assertEquals(listOf(TxValidationError.AmountMustBeNonZero), errors)
    }

    // ---- 账户 ----

    @Test
    fun transferToSameAccount_rejected() {
        val errors = TransactionValidator.validate(
            input(type = TransactionType.TRANSFER, accountId = 1, toAccountId = 1, categoryId = null),
            accounts, categories,
        )
        assertEquals(listOf(TxValidationError.TransferSameAccount), errors)
    }

    @Test
    fun transferMissingToAccount_rejected() {
        val errors = TransactionValidator.validate(
            input(type = TransactionType.TRANSFER, accountId = 1, toAccountId = null, categoryId = null),
            accounts, categories,
        )
        assertEquals(listOf(TxValidationError.TransferNeedsTwoAccounts), errors)
    }

    @Test
    fun archivedAccount_rejected() {
        val errors = TransactionValidator.validate(input(accountId = 3), accounts, categories)
        assertEquals(listOf(TxValidationError.AccountArchived), errors)
    }

    @Test
    fun unknownAccount_rejected() {
        val errors = TransactionValidator.validate(input(accountId = 99), accounts, categories)
        assertEquals(listOf(TxValidationError.AccountNotFound), errors)
    }

    // ---- 分类 ----

    @Test
    fun expenseWithIncomeCategory_rejected() {
        val errors = TransactionValidator.validate(input(categoryId = 101), accounts, categories)
        assertEquals(listOf(TxValidationError.CategoryKindMismatch), errors)
    }

    @Test
    fun missingCategory_rejectedForExpense() {
        val errors = TransactionValidator.validate(input(categoryId = null), accounts, categories)
        assertEquals(listOf(TxValidationError.CategoryRequired), errors)
    }

    @Test
    fun transferWithCategory_rejected() {
        val errors = TransactionValidator.validate(
            input(type = TransactionType.TRANSFER, accountId = 1, toAccountId = 2, categoryId = 11),
            accounts, categories,
        )
        assertEquals(listOf(TxValidationError.CategoryNotAllowed), errors)
    }

    @Test
    fun archivedCategory_rejected() {
        val errors = TransactionValidator.validate(input(categoryId = 12), accounts, categories)
        assertEquals(listOf(TxValidationError.CategoryArchived), errors)
    }

    // ---- 备注与日期 ----

    @Test
    fun noteOver200Chars_rejected() {
        val errors = TransactionValidator.validate(input(note = "x".repeat(201)), accounts, categories)
        assertEquals(listOf(TxValidationError.NoteTooLong), errors)
    }

    @Test
    fun noteExactly200Chars_isValid() {
        val errors = TransactionValidator.validate(input(note = "x".repeat(200)), accounts, categories)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun invalidDates_rejected() {
        for (bad in listOf("2026-02-30", "2026-1-5", "2026-13-01", "abc", "")) {
            val errors = TransactionValidator.validate(input(occurredOn = bad), accounts, categories)
            assertEquals("应拒绝非法日期: $bad", listOf(TxValidationError.DateInvalid), errors)
        }
    }

    @Test
    fun leapDay_isValid() {
        val errors = TransactionValidator.validate(input(occurredOn = "2028-02-29"), accounts, categories)
        assertTrue(errors.isEmpty())
    }
}
