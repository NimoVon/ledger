package com.nimo.bookkeeping.ui.record

import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.validate.TransactionValidator
import com.nimo.bookkeeping.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记账页表单纯逻辑（F03）。不依赖 Android / Room，直接 JVM 跑。
 * 覆盖计划 9.2 的金额边界：空、0、负数、超上限、超两位小数。
 */
class RecordFormTest {

    private fun form(
        type: TransactionType = TransactionType.EXPENSE,
        amountText: String = "12.50",
        accountId: Long? = 1L,
        categoryId: Long? = 2L,
        occurredOn: String = "2026-10-12",
        note: String = "",
    ) = RecordForm(type, amountText, accountId, categoryId, occurredOn, note)

    private fun errorsOf(form: RecordForm): List<RecordFormError> {
        val outcome = form.toOutcome()
        assertTrue("期望校验失败，实际通过：$outcome", outcome is RecordFormOutcome.Invalid)
        return (outcome as RecordFormOutcome.Invalid).errors
    }

    // ---- 默认值 ----

    @Test
    fun defaultForm_isExpenseWithTodayDateAndEmptyAmount() {
        val default = RecordForm()
        assertEquals(TransactionType.EXPENSE, default.type)
        assertEquals("", default.amountText)
        assertNull(default.accountId)
        assertNull(default.categoryId)
        assertEquals(Dates.today(), default.occurredOn)
        assertEquals("", default.note)
    }

    // ---- 金额（铁律 1：只走 Long 整数分）----

    @Test
    fun validAmount_parsesToIntegerCents() {
        val outcome = form(amountText = "12.50").toOutcome()
        assertTrue(outcome is RecordFormOutcome.Valid)
        val input = (outcome as RecordFormOutcome.Valid).input
        assertEquals(1_250L, input.amountCents)
        assertEquals(TransactionType.EXPENSE, input.type)
        assertEquals(1L, input.accountId)
        assertEquals(2L, input.categoryId)
        assertEquals("2026-10-12", input.occurredOn)
    }

    @Test
    fun singleDecimalDigit_isPaddedToCents() {
        val input = (form(amountText = "12.5").toOutcome() as RecordFormOutcome.Valid).input
        assertEquals(1_250L, input.amountCents)
    }

    @Test
    fun integerAmount_meansWholeYuan() {
        val input = (form(amountText = "100").toOutcome() as RecordFormOutcome.Valid).input
        assertEquals(10_000L, input.amountCents)
    }

    @Test
    fun blankAmount_isReported() {
        assertEquals(listOf(RecordFormError.AmountBlank), errorsOf(form(amountText = "")))
    }

    @Test
    fun whitespaceAmount_isReportedAsBlank() {
        assertEquals(listOf(RecordFormError.AmountBlank), errorsOf(form(amountText = "   ")))
    }

    @Test
    fun zeroAmount_isRejected() {
        assertEquals(listOf(RecordFormError.AmountInvalid), errorsOf(form(amountText = "0")))
    }

    @Test
    fun negativeAmount_isRejectedForExpense() {
        // Money.parseCents 允许负号（余额校准用），但收支金额必须 > 0
        assertEquals(
            listOf(RecordFormError.AmountMustBePositive),
            errorsOf(form(amountText = "-5")),
        )
    }

    @Test
    fun negativeAmount_isRejectedForIncomeToo() {
        assertEquals(
            listOf(RecordFormError.AmountMustBePositive),
            errorsOf(form(type = TransactionType.INCOME, amountText = "-0.01")),
        )
    }

    @Test
    fun threeDecimals_isRejected() {
        assertEquals(listOf(RecordFormError.AmountInvalid), errorsOf(form(amountText = "1.234")))
    }

    @Test
    fun nonNumericAmount_isRejected() {
        assertEquals(listOf(RecordFormError.AmountInvalid), errorsOf(form(amountText = "abc")))
        assertEquals(listOf(RecordFormError.AmountInvalid), errorsOf(form(amountText = "12,50")))
    }

    @Test
    fun amountOverProductLimit_isRejected() {
        // 产品上限 ¥99,999,999.99 = 9_999_999_999 分
        assertEquals(
            listOf(RecordFormError.AmountInvalid),
            errorsOf(form(amountText = "100000000.00")),
        )
    }

    @Test
    fun amountAtProductLimit_isAccepted() {
        val input = (form(amountText = "99999999.99").toOutcome() as RecordFormOutcome.Valid).input
        assertEquals(9_999_999_999L, input.amountCents)
    }

    // ---- 账户 / 分类 ----

    @Test
    fun missingAccount_isReported() {
        assertEquals(listOf(RecordFormError.AccountRequired), errorsOf(form(accountId = null)))
    }

    @Test
    fun missingCategory_isReported() {
        assertEquals(listOf(RecordFormError.CategoryRequired), errorsOf(form(categoryId = null)))
    }

    // ---- 备注：不超过 200 字 ----

    @Test
    fun noteAtLimit_isAccepted() {
        val note = "字".repeat(TransactionValidator.MAX_NOTE_LENGTH)
        val input = (form(note = note).toOutcome() as RecordFormOutcome.Valid).input
        assertEquals(note, input.note)
    }

    @Test
    fun noteOverLimit_isReported() {
        val note = "字".repeat(TransactionValidator.MAX_NOTE_LENGTH + 1)
        assertEquals(listOf(RecordFormError.NoteTooLong), errorsOf(form(note = note)))
    }

    @Test
    fun blankNote_isStoredAsNull() {
        assertNull((form(note = "   ").toOutcome() as RecordFormOutcome.Valid).input.note)
    }

    @Test
    fun note_isTrimmed() {
        assertEquals("午餐", (form(note = "  午餐  ").toOutcome() as RecordFormOutcome.Valid).input.note)
    }

    @Test
    fun withNote_truncatesToLimit() {
        val limited = RecordForm().withNote("字".repeat(500))
        assertEquals(TransactionValidator.MAX_NOTE_LENGTH, limited.note.length)
    }

    @Test
    fun withNote_doesNotSplitSurrogatePair() {
        // 199 个 'a' 之后是一个 emoji（UTF-16 两个码元），正好卡在第 200 个码元上
        val text = "a".repeat(199) + "\uD83C\uDF5C"
        assertEquals(201, text.length)
        assertEquals("a".repeat(199), RecordForm().withNote(text).note)
    }

    // ---- 日期 ----

    @Test
    fun invalidDate_isReported() {
        assertEquals(listOf(RecordFormError.DateInvalid), errorsOf(form(occurredOn = "2026-02-30")))
        assertEquals(listOf(RecordFormError.DateInvalid), errorsOf(form(occurredOn = "2026/10/12")))
        assertEquals(listOf(RecordFormError.DateInvalid), errorsOf(form(occurredOn = "")))
    }

    @Test
    fun leapDay_isAccepted() {
        val input = (form(occurredOn = "2028-02-29").toOutcome() as RecordFormOutcome.Valid).input
        assertEquals("2028-02-29", input.occurredOn)
    }

    // ---- 收支方向切换（收入分类不能用于支出）----

    @Test
    fun switchType_clearsCategoryButKeepsEverythingElse() {
        val expense = form(
            type = TransactionType.EXPENSE,
            amountText = "12.50",
            accountId = 7L,
            categoryId = 3L,
            occurredOn = "2026-10-01",
            note = "午餐",
        )
        val income = expense.withType(TransactionType.INCOME)
        assertEquals(TransactionType.INCOME, income.type)
        assertNull("切换方向必须清掉旧分类", income.categoryId)
        assertEquals("12.50", income.amountText)
        assertEquals(7L, income.accountId)
        assertEquals("2026-10-01", income.occurredOn)
        assertEquals("午餐", income.note)
    }

    @Test
    fun switchToSameType_keepsCategory() {
        val expense = form(type = TransactionType.EXPENSE, categoryId = 3L)
        assertEquals(3L, expense.withType(TransactionType.EXPENSE).categoryId)
    }

    // ---- 只允许支出/收入 ----

    @Test
    fun transferType_isRejected() {
        // 转账（F04）不走记账页，避免出现没有 toAccountId 的半截转账
        val errors = errorsOf(form(type = TransactionType.TRANSFER))
        assertEquals(listOf(RecordFormError.UnsupportedType), errors)
    }

    @Test
    fun balanceAdjustType_isRejected() {
        val errors = errorsOf(form(type = TransactionType.BALANCE_ADJUST))
        assertEquals(listOf(RecordFormError.UnsupportedType), errors)
    }

    // ---- 多错并报 ----

    @Test
    fun multipleErrors_areAllReported() {
        val errors = errorsOf(
            RecordForm(amountText = "", accountId = null, categoryId = null, occurredOn = "bad"),
        )
        assertEquals(
            listOf(
                RecordFormError.AmountBlank,
                RecordFormError.AccountRequired,
                RecordFormError.CategoryRequired,
                RecordFormError.DateInvalid,
            ),
            errors,
        )
    }

    @Test
    fun everyError_hasNonBlankChineseMessage() {
        val all = listOf(
            RecordFormError.UnsupportedType,
            RecordFormError.AmountBlank,
            RecordFormError.AmountInvalid,
            RecordFormError.AmountMustBePositive,
            RecordFormError.AccountRequired,
            RecordFormError.CategoryRequired,
            RecordFormError.NoteTooLong,
            RecordFormError.DateInvalid,
        )
        all.forEach { error ->
            assertTrue("错误缺提示文案：$error", error.toMessage().isNotBlank())
        }
    }
}
