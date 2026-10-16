package com.nimo.ledger.domain.stats

import com.nimo.ledger.domain.model.TransactionRecord
import com.nimo.ledger.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐日聚合（附.1 R2）：本月视图的柱子数据。
 *
 * 三条铁律都在这里钉住：只画已经过去的天（未来不补 0）、转账与余额调整一条都不进、
 * 逐日之和必须等于对同一段区间直接 summarize（口径唯一实现，页面不自己算）。
 */
class DailyTotalsTest {

    private val today = LocalDate.of(2026, 10, 12)
    private val october = YearMonth.of(2026, 10)

    private fun tx(id: Long, type: TransactionType, amount: Long, date: String) = TransactionRecord(
        id = id,
        type = type,
        amountCents = amount,
        accountId = 1L,
        categoryId = if (type == TransactionType.INCOME) 2L else 1L,
        occurredOn = LocalDate.parse(date),
    )

    @Test
    fun currentMonth_hasOneEntryPerElapsedDay_inDateOrder() {
        val daily = StatsCalculator.dailyTotals(emptyList(), october, today)

        // 10 月 12 日看 10 月：只有 1..12 号，13 号以后的格子根本不存在
        assertEquals(12, daily.size)
        assertEquals((1..12).map { october.atDay(it) }, daily.map { it.date })
        assertTrue(daily.all { it.incomeCents == 0L && it.expenseCents == 0L })
    }

    @Test
    fun eachDayCarriesItsOwnIncomeAndExpense() {
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 1_200L, "2026-10-05"),
            tx(2, TransactionType.EXPENSE, 300L, "2026-10-05"),
            tx(3, TransactionType.INCOME, 50_000L, "2026-10-12"),
            tx(4, TransactionType.EXPENSE, 999L, "2026-10-11"),
        )

        val daily = StatsCalculator.dailyTotals(records, october, today)

        assertEquals(1_500L, daily[4].expenseCents)   // 10-05
        assertEquals(0L, daily[4].incomeCents)
        assertEquals(999L, daily[10].expenseCents)    // 10-11
        assertEquals(50_000L, daily[11].incomeCents)  // 10-12
        // 没有记录的天补 0，而不是被跳过（否则柱子会错位）
        assertEquals(0L, daily[0].expenseCents)
        assertEquals(0L, daily[0].incomeCents)
    }

    /** 未来日期不画：今天 12 号，20 号那笔既不出现也不被折进任何一天 */
    @Test
    fun futureDays_areNeitherDrawnNorPadded() {
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 500L, "2026-10-20"),
        )

        val daily = StatsCalculator.dailyTotals(records, october, today)

        assertEquals(12, daily.size)
        assertTrue(daily.none { it.date.dayOfMonth > 12 })
        assertEquals(0L, daily.sumOf { it.expenseCents })
    }

    /** 铁律 3：转账与余额调整不是收入也不是支出，一根柱子都不许有 */
    @Test
    fun transferAndBalanceAdjust_neverCount() {
        val records = listOf(
            tx(1, TransactionType.TRANSFER, 800_000L, "2026-10-04"),
            tx(2, TransactionType.BALANCE_ADJUST, -50_000L, "2026-10-05"),
        )

        val daily = StatsCalculator.dailyTotals(records, october, today)

        assertEquals(12, daily.size)
        assertTrue(daily.all { it.incomeCents == 0L && it.expenseCents == 0L })
    }

    @Test
    fun recordsFromOtherMonths_areIgnored() {
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 777L, "2026-09-30"),
            tx(2, TransactionType.EXPENSE, 111L, "2026-11-01"),
        )

        val daily = StatsCalculator.dailyTotals(records, october, today)

        assertEquals(12, daily.size)
        assertTrue(daily.all { it.expenseCents == 0L })
    }

    /** 看已经过去的月份：整月都算，不受"今天几号"影响 */
    @Test
    fun pastMonth_usesWholeMonthNotElapsedDays() {
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 400L, "2026-09-01"),
            tx(2, TransactionType.EXPENSE, 600L, "2026-09-30"),
        )

        val daily = StatsCalculator.dailyTotals(records, YearMonth.of(2026, 9), today)

        assertEquals(30, daily.size)
        assertEquals(400L, daily.first().expenseCents)
        assertEquals(600L, daily.last().expenseCents)
    }

    /** 还没到的月份：一天都没过，没有柱子可画 */
    @Test
    fun futureMonth_isEmpty() {
        val daily = StatsCalculator.dailyTotals(
            listOf(tx(1, TransactionType.EXPENSE, 100L, "2026-11-03")),
            YearMonth.of(2026, 11),
            today,
        )

        assertTrue(daily.isEmpty())
    }

    /** 口径一致性：逐日之和 = 对同一段已过天数直接 summarize（唯一实现） */
    @Test
    fun dailySum_equalsSummarizeOverTheElapsedRange() {
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 1_200L, "2026-10-01"),
            tx(2, TransactionType.INCOME, 50_000L, "2026-10-07"),
            tx(3, TransactionType.EXPENSE, 999L, "2026-10-20"),  // 未来：不进逐日
            tx(4, TransactionType.TRANSFER, 500_000L, "2026-10-08"),
            tx(5, TransactionType.EXPENSE, 300L, "2026-09-30"),  // 上个月
        )

        val daily = StatsCalculator.dailyTotals(records, october, today)
        val summary = StatsCalculator.summarize(records, october.atDay(1)..today)

        assertEquals(summary.expenseCents, daily.sumOf { it.expenseCents })
        assertEquals(summary.incomeCents, daily.sumOf { it.incomeCents })
    }

    /** 与"日均支出"用同一套天数：柱子根数 = elapsedDays */
    @Test
    fun dayCount_matchesElapsedDays() {
        val daily = StatsCalculator.dailyTotals(emptyList(), october, today)

        assertEquals(StatsCalculator.elapsedDays(october, today), daily.size)
    }
}
