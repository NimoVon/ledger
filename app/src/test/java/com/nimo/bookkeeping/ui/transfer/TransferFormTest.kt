package com.nimo.bookkeeping.ui.transfer

import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.validate.TransactionValidator
import com.nimo.bookkeeping.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转账页（F04）表单纯逻辑。不依赖 Android / Room，直接 JVM 跑。
 *
 * 盯的是"转账和收支是两种数据"：产出恒为 TRANSFER、恒不带分类（数据库触发器也会拒），
 * 金额一律解析成 Long 整数分（铁律 1）。
 */
class TransferFormTest {

    private fun form(
        fromAccountId: Long? = 1L,
        toAccountId: Long? = 2L,
        amountText: String = "12.50",
        occurredOn: String = "2026-10-12",
        note: String = "",
    ) = TransferForm(fromAccountId, toAccountId, amountText, occurredOn, note)

    private fun errorsOf(form: TransferForm): List<TransferFormError> {
        val outcome = form.toOutcome()
        assertTrue("期望校验失败，实际通过：$outcome", outcome is TransferFormOutcome.Invalid)
        return (outcome as TransferFormOutcome.Invalid).errors
    }

    // ---- 默认值 ----

    @Test
    fun defaultForm_hasNoAccountsAndAnEmptyAmount() {
        val default = TransferForm()
        assertNull(default.fromAccountId)
        assertNull(default.toAccountId)
        assertEquals("", default.amountText)
        assertEquals(Dates.today(), default.occurredOn)
        assertEquals("", default.note)
    }

    // ---- 正常路径 ----

    @Test
    fun validTransfer_becomesATransferWithoutCategory() {
        val outcome = form(fromAccountId = 3L, toAccountId = 4L, amountText = "12.50").toOutcome()
        assertTrue(outcome is TransferFormOutcome.Valid)
        val input = (outcome as TransferFormOutcome.Valid).input
        assertEquals(TransactionType.TRANSFER, input.type)
        assertEquals(1_250L, input.amountCents)
        assertEquals(3L, input.accountId)
        assertEquals(4L, input.toAccountId)
        // 转账不挂分类：挂上会被 TransactionValidator 与数据库触发器一起拒掉
        assertNull(input.categoryId)
        assertEquals("2026-10-12", input.occurredOn)
        assertNull(input.note)
    }

    @Test
    fun amounts_areParsedFromYuanIntoCents() {
        fun cents(text: String): Long =
            ((form(amountText = text).toOutcome()) as TransferFormOutcome.Valid).input.amountCents

        assertEquals(1_250L, cents("12.5"))
        assertEquals(1_250L, cents("12.50"))
        assertEquals(1L, cents("0.01"))
        assertEquals(100_000L, cents("1,000.00"))
    }

    // ---- 金额 ----

    @Test
    fun blankAmount_isReportedAsBlankNotAsMalformed() {
        assertEquals(listOf(TransferFormError.AmountBlank), errorsOf(form(amountText = "  ")))
    }

    @Test
    fun malformedAmounts_areRejected() {
        listOf("abc", "1.234", "1,23", "12.5.6").forEach { text ->
            assertEquals("金额 $text 应该被拒", listOf(TransferFormError.AmountInvalid), errorsOf(form(amountText = text)))
        }
    }

    @Test
    fun zeroAmount_isRejectedByTheSharedParser() {
        // 和「记一笔」走的是同一个 Money.parseCents：它只接受**非零**金额，0 直接返回 null，
        // 所以「0」落到"格式不正确"而不是"必须大于 0"。这里把现状钉住——要改就两页一起改。
        listOf("0", "0.00").forEach { text ->
            assertEquals("金额 $text", listOf(TransferFormError.AmountInvalid), errorsOf(form(amountText = text)))
        }
    }

    @Test
    fun aNegativeAmount_mustBePositive() {
        // parseCents 接受前导 '-'（余额校准用），-1 → -100 分，于是被"必须大于 0"拦住
        assertEquals(listOf(TransferFormError.AmountMustBePositive), errorsOf(form(amountText = "-1")))
    }

    // ---- 两个账户 ----

    @Test
    fun missingFromOrToAccount_isReportedSeparately() {
        assertEquals(listOf(TransferFormError.FromAccountRequired), errorsOf(form(fromAccountId = null)))
        assertEquals(listOf(TransferFormError.ToAccountRequired), errorsOf(form(toAccountId = null)))
    }

    @Test
    fun transferringToTheSameAccount_isRejected() {
        // 计划 4.4：转账不能转给同一账户（数据库触发器 trg_tx_validate_* 也会拒）
        assertEquals(listOf(TransferFormError.SameAccount), errorsOf(form(fromAccountId = 7L, toAccountId = 7L)))
    }

    // ---- 备注 ----

    @Test
    fun note_isTrimmedAndABlankNoteBecomesNull() {
        val trimmed = (form(note = "  晚饭  ").toOutcome() as TransferFormOutcome.Valid).input
        assertEquals("晚饭", trimmed.note)
        val blank = (form(note = "   ").toOutcome() as TransferFormOutcome.Valid).input
        assertNull(blank.note)
    }

    @Test
    fun noteOverTheLimit_isReportedButCanBeTruncatedByTheForm() {
        val tooLong = "备".repeat(TransactionValidator.MAX_NOTE_LENGTH + 1)
        assertEquals(listOf(TransferFormError.NoteTooLong), errorsOf(form(note = tooLong)))

        // 页面走的是 withNote：多出来的部分直接丢掉，不让用户走到"报错才发现"
        val truncated = form(note = "").withNote(tooLong)
        assertEquals(TransactionValidator.MAX_NOTE_LENGTH, truncated.note.length)
        assertTrue(truncated.toOutcome() is TransferFormOutcome.Valid)
    }

    // ---- 日期 ----

    @Test
    fun malformedDate_isRejected() {
        assertEquals(listOf(TransferFormError.DateInvalid), errorsOf(form(occurredOn = "2026/10/12")))
        assertEquals(listOf(TransferFormError.DateInvalid), errorsOf(form(occurredOn = "2026-13-01")))
    }

    // ---- 多个错误一起报 ----

    @Test
    fun severalProblems_areReportedTogether() {
        val errors = errorsOf(form(fromAccountId = null, toAccountId = null, amountText = "", occurredOn = "x"))
        assertEquals(
            listOf(
                TransferFormError.AmountBlank,
                TransferFormError.FromAccountRequired,
                TransferFormError.ToAccountRequired,
                TransferFormError.DateInvalid,
            ),
            errors,
        )
    }
}
