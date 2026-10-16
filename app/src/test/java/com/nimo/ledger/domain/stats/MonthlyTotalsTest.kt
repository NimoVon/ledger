package com.nimo.ledger.domain.stats

import com.nimo.ledger.domain.model.TransactionRecord
import com.nimo.ledger.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 趋势折线的落数（F10）：按月归档、补零、类型白名单。
 * 这是折线口径的唯一来源，页面不自己算——所以它必须和 summarize 对得上。
 */
class MonthlyTotalsTest {

    private val months = listOf(
        YearMonth.of(2026, 8),
        YearMonth.of(2026, 9),
        YearMonth.of(2026, 10),
    )

    private fun tx(id: Long, type: TransactionType, amount: Long, date: String) = TransactionRecord(
        id = id,
        type = type,
        amountCents = amount,
        accountId = 1L,
        categoryId = if (type == TransactionType.INCOME) 2L else 1L,
        occurredOn = LocalDate.parse(date),
    )

    @Test
    fun everyMonthGetsItsOwnIncomeAndExpense_inTheRequestedOrder() {
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 1_200L, "2026-08-05"),
            tx(2, TransactionType.EXPENSE, 300L, "2026-08-31"),
            tx(3, TransactionType.INCOME, 50_000L, "2026-09-10"),
            tx(4, TransactionType.EXPENSE, 999L, "2026-10-31"),
        )

        val totals = StatsCalculator.monthlyTotals(records, months)

        assertEquals(months, totals.map { it.month })
        assertEquals(listOf(1_500L, 0L, 999L), totals.map { it.expenseCents })
        assertEquals(listOf(0L, 50_000L, 0L), totals.map { it.incomeCents })
    }

    @Test
    fun monthsWithoutRecords_areZeroFilledNotDropped() {
        val totals = StatsCalculator.monthlyTotals(emptyList(), months)

        assertEquals(3, totals.size)
        assertEquals(months, totals.map { it.month })
        assertTrue(totals.all { it.incomeCents == 0L && it.expenseCents == 0L })
    }

    /** 铁律 3：转账与余额调整不是收入也不是支出，一条都不许进折线 */
    @Test
    fun transferAndBalanceAdjust_neverCount() {
        val records = listOf(
            tx(1, TransactionType.TRANSFER, 800_000L, "2026-10-04"),
            tx(2, TransactionType.BALANCE_ADJUST, -50_000L, "2026-10-05"),
        )

        val totals = StatsCalculator.monthlyTotals(records, months)

        assertTrue(totals.all { it.incomeCents == 0L && it.expenseCents == 0L })
    }

    @Test
    fun recordsOutsideTheWindow_areIgnored() {
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 777L, "2026-07-31"),
            tx(2, TransactionType.EXPENSE, 111L, "2026-11-01"),
        )

        val totals = StatsCalculator.monthlyTotals(records, months)

        assertTrue(totals.all { it.expenseCents == 0L })
    }

    /** 口径一致性：折线各月之和必须等于对同一段区间直接 summarize（唯一实现） */
    @Test
    fun windowTotal_equalsSummarizeOverTheSameRange() {
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 777L, "2026-07-31"),
            tx(2, TransactionType.EXPENSE, 1_200L, "2026-08-01"),
            tx(3, TransactionType.INCOME, 50_000L, "2026-09-10"),
            tx(4, TransactionType.EXPENSE, 999L, "2026-10-31"),
        )
        val range = LocalDate.of(2026, 8, 1)..LocalDate.of(2026, 10, 31)

        val totals = StatsCalculator.monthlyTotals(records, months)
        val summary = StatsCalculator.summarize(records, range)

        assertEquals(summary.expenseCents, totals.sumOf { it.expenseCents })
        assertEquals(summary.incomeCents, totals.sumOf { it.incomeCents })
    }
}
