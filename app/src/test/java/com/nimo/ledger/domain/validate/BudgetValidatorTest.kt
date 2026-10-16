package com.nimo.ledger.domain.validate

import com.nimo.ledger.util.Money
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预算校验（F09）：月份格式、金额正数、金额上限。
 * 纯函数测试，不碰数据库——校验规则先在这里钉死，Repository 再调它。
 */
class BudgetValidatorTest {

    @Test
    fun validMonthAndAmount_hasNoErrors() {
        assertTrue(BudgetValidator.validate("2026-10", 100_00L).isEmpty())
        assertTrue(BudgetValidator.validate("2026-10", 1L).isEmpty())
        assertTrue(BudgetValidator.validate("2026-10", Money.MAX_AMOUNT_CENTS).isEmpty())
    }

    @Test
    fun monthMustBeYyyyDashMm() {
        for (bad in listOf("2026-1", "2026-13", "2026-00", "26-10", "2026/10", "2026-10-01", "", "abc")) {
            val errors = BudgetValidator.validate(bad, 100L)
            assertTrue("月份「" + bad + "」应该被判非法", errors.contains(BudgetValidationError.MonthFormat))
            assertNull("月份「" + bad + "」不该解析成功", BudgetValidator.parseMonth(bad))
        }
    }

    @Test
    fun parseMonth_acceptsRealMonths() {
        assertEquals(YearMonth.of(2026, 10), BudgetValidator.parseMonth("2026-10"))
        assertEquals(YearMonth.of(2026, 1), BudgetValidator.parseMonth("2026-01"))
        assertEquals(YearMonth.of(2024, 2), BudgetValidator.parseMonth("2024-02"))
    }

    @Test
    fun amountMustBePositive() {
        assertTrue(BudgetValidator.validate("2026-10", 0L).contains(BudgetValidationError.AmountNotPositive))
        assertTrue(BudgetValidator.validate("2026-10", -1L).contains(BudgetValidationError.AmountNotPositive))
    }

    @Test
    fun amountMustNotExceedProductLimit() {
        assertTrue(
            BudgetValidator.validate("2026-10", Money.MAX_AMOUNT_CENTS + 1)
                .contains(BudgetValidationError.AmountTooLarge),
        )
    }

    @Test
    fun multipleErrors_areAllReported() {
        val errors = BudgetValidator.validate("2026-13", -5L)
        assertTrue(errors.contains(BudgetValidationError.MonthFormat))
        assertTrue(errors.contains(BudgetValidationError.AmountNotPositive))
    }
}
