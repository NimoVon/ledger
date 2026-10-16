package com.nimo.ledger.ui.transactions

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 筛选面板的输入校验（F06）：金额与日期都允许留空（表示不限），
 * 上下限/起止写反了自动互换，填错的给出可照做的例子。
 *
 * 面板外壳在 Robolectric 里渲染不了（见 TransactionFilterDialogUiTest 的说明），
 * 所以「点应用会发生什么」这一层由本用例直接钉在 validate() 上。
 */
class TransactionFilterValidationTest {

    private fun outcome(min: String = "", max: String = "", from: String = "", to: String = "") =
        validate(min, max, from, to)

    private fun validated(min: String = "", max: String = "", from: String = "", to: String = "") =
        outcome(min, max, from, to) as Validated

    private fun message(min: String = "", max: String = "", from: String = "", to: String = "") =
        (outcome(min, max, from, to) as ValidationError).message

    @Test
    fun blankFields_meanNoLimits() {
        val result = validated()

        assertNull(result.minAmountCents)
        assertNull(result.maxAmountCents)
        assertNull(result.from)
        assertNull(result.to)
    }

    @Test
    fun amounts_areParsedFromYuanIntoCents() {
        val result = validated(min = "100", max = "100.50")

        assertEquals(10_000L, result.minAmountCents)
        assertEquals(10_050L, result.maxAmountCents)
    }

    @Test
    fun onlyOneEndOfEachRange_isFine() {
        val result = validated(min = "30", from = "2026-09-01")

        assertEquals(3_000L, result.minAmountCents)
        assertNull(result.maxAmountCents)
        assertEquals(LocalDate.parse("2026-09-01"), result.from)
        assertNull(result.to)
    }

    @Test
    fun malformedMinAmount_isReportedWithAnExample() {
        assertEquals("最低金额填得不对（例：100 或 100.50）", message(min = "abc"))
    }

    @Test
    fun malformedMaxAmount_isReportedWithAnExample() {
        assertEquals("最高金额填得不对（例：100 或 100.50）", message(max = "abc"))
    }

    @Test
    fun malformedDates_areReportedWithAnExample() {
        assertEquals("起始日期填得不对（例：2026-09-01）", message(from = "2026-13-01"))
        assertEquals("结束日期填得不对（例：2026-09-01）", message(to = "9月1日"))
    }

    @Test
    fun aSwappedAmountRange_isNormalizedInsteadOfRejected() {
        val result = validated(min = "500", max = "100")

        assertEquals(10_000L, result.minAmountCents)
        assertEquals(50_000L, result.maxAmountCents)
    }

    @Test
    fun swappedDates_areNormalizedInsteadOfRejected() {
        val result = validated(from = "2026-10-31", to = "2026-09-01")

        assertEquals(LocalDate.parse("2026-09-01"), result.from)
        assertEquals(LocalDate.parse("2026-10-31"), result.to)
    }

    @Test
    fun aMalformedField_stopsAtTheFirstProblem() {
        assertTrue(outcome(min = "abc", max = "def") is ValidationError)
    }
}
