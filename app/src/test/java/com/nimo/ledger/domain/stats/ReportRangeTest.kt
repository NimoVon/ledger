package com.nimo.ledger.domain.stats

import com.nimo.ledger.domain.model.TransactionRecord
import com.nimo.ledger.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计范围的口径（本轮改造）：整页只有一个时间口径，所有区间一律半开 [from, to)。
 *
 * 这里盯着三件容易出错的事：
 * 1. 「今年」是自然年 1/1–12/31，不是以当前月收尾的滚动 12 个月（旧 TrendSpan.FULL_YEAR 是后者）；
 * 2. 左右箭头一次挪**一整段**，往回翻到的正是洞察区环比用的那一段（不重叠）；
 * 3. 按天画法的柱子只画已经过去的日子，未来日期不画也不补 0。
 */
class ReportRangeTest {

    private val today = LocalDate.parse("2026-10-12")

    // ---- 单月 ----

    @Test
    fun month_coversTheWholeMonth_andSaysItsName() {
        val range = ReportRange.Month(YearMonth.of(2026, 10))

        assertEquals(LocalDate.parse("2026-10-01"), range.from)
        assertEquals(LocalDate.parse("2026-11-01"), range.to)
        assertEquals("2026年10月", range.label)
        assertEquals("较上月", range.previousLabel)
        assertEquals(ReportBucket.DAY, range.bucket)
        assertEquals(listOf(YearMonth.of(2026, 10)), range.months)
        assertTrue(range.isSingleMonth)
    }

    @Test
    fun month_elapsedDays_stopsAtToday_andIsZeroForTheFuture() {
        // 已结束的月份：整月天数
        assertEquals(30, ReportRange.Month(YearMonth.of(2026, 9)).elapsedDays(today))
        // 正在看的月份：数到今天（含今天）
        assertEquals(12, ReportRange.Month(YearMonth.of(2026, 10)).elapsedDays(today))
        // 还没到的月份：0，而不是把整月天数当分母
        assertEquals(0, ReportRange.Month(YearMonth.of(2026, 11)).elapsedDays(today))
    }

    @Test
    fun month_days_listsOnlyDaysThatHaveArrived() {
        val october = ReportRange.Month(YearMonth.of(2026, 10)).days(today)
        assertEquals(12, october.size)
        assertEquals(LocalDate.parse("2026-10-01"), october.first())
        assertEquals(LocalDate.parse("2026-10-12"), october.last())

        // 已结束的月份：整整 31 天
        assertEquals(31, ReportRange.Month(YearMonth.of(2026, 7)).days(today).size)
        // 未来月份：一根柱子都不画
        assertTrue(ReportRange.Month(YearMonth.of(2026, 11)).days(today).isEmpty())
    }

    @Test
    fun month_arrowsMoveWholeMonths_acrossTheYearBoundary() {
        val december = ReportRange.Month(YearMonth.of(2026, 12))

        assertEquals(ReportRange.Month(YearMonth.of(2026, 11)), december.previous())
        assertEquals(ReportRange.Month(YearMonth.of(2027, 1)), december.shift(1))
        assertEquals(ReportRange.Month(YearMonth.of(2026, 9)), december.shift(-3))
        // previous() 与 shift(-1) 必须同一个口径，否则箭头和环比会指向两段不同的时间
        assertEquals(december.shift(-1), december.previous())
    }

    // ---- 近 N 个月（滚动窗口） ----

    @Test
    fun lastMonths_endsAtTheGivenMonth_andListsMonthsInOrder() {
        val range = ReportRange.lastMonths(YearMonth.of(2026, 10), 6)

        assertEquals("近6个月", range.label)
        assertEquals("较前6个月", range.previousLabel)
        assertEquals(ReportBucket.MONTH, range.bucket)
        assertFalse(range.isSingleMonth)
        assertEquals(LocalDate.parse("2026-05-01"), range.from)
        assertEquals(LocalDate.parse("2026-11-01"), range.to)
        assertEquals(
            listOf(
                YearMonth.of(2026, 5),
                YearMonth.of(2026, 6),
                YearMonth.of(2026, 7),
                YearMonth.of(2026, 8),
                YearMonth.of(2026, 9),
                YearMonth.of(2026, 10),
            ),
            range.months,
        )
        // 按月画法没有逐日柱子
        assertTrue(range.days(today).isEmpty())
    }

    @Test
    fun lastMonths_windowCrossesTheYearBoundary() {
        val range = ReportRange.lastMonths(YearMonth.of(2026, 2), 6)

        assertEquals(LocalDate.parse("2025-09-01"), range.from)
        assertEquals(LocalDate.parse("2026-03-01"), range.to)
        assertEquals(YearMonth.of(2025, 9), range.months.first())
        assertEquals(YearMonth.of(2026, 2), range.months.last())
    }

    @Test
    fun lastMonths_arrowsMoveTheWholeWindow_soThePreviousWindowDoesNotOverlap() {
        val range = ReportRange.lastMonths(YearMonth.of(2026, 10), 6)

        val previous = range.previous()
        assertEquals(ReportRange.lastMonths(YearMonth.of(2026, 4), 6), previous)
        // 不重叠：上一段的终点正好是这一段的起点
        assertEquals(range.from, previous.to)
        assertEquals(ReportRange.lastMonths(YearMonth.of(2027, 4), 6), range.shift(1))
        // 近 N 个月至少要 2 个月：单月请用 Month
        assertThrows(IllegalArgumentException::class.java) { ReportRange.lastMonths(YearMonth.of(2026, 10), 1) }
    }

    // ---- 自然年（1/1–12/31） ----

    @Test
    fun calendarYear_isTheNaturalYear_notARollingTwelveMonths() {
        val range = ReportRange.year(2026)

        assertEquals("2026年", range.label)
        assertEquals("较上一年", range.previousLabel)
        assertEquals(LocalDate.parse("2026-01-01"), range.from)
        assertEquals(LocalDate.parse("2027-01-01"), range.to)
        assertEquals(ReportBucket.MONTH, range.bucket)
        assertFalse(range.isSingleMonth)
        assertEquals((1..12).map { YearMonth.of(2026, it) }, range.months)
        // 关键差别：去年 11 月不在这个范围里（滚动 12 个月会有它）
        assertTrue(LocalDate.parse("2025-11-20").isBefore(range.from))
    }

    @Test
    fun calendarYear_elapsedDaysIsZeroForAFutureYear_andPartialForTheCurrentOne() {
        assertEquals(0, ReportRange.year(2027).elapsedDays(today))
        // 2026 年 10 月 12 日 = 该年第 285 天
        assertEquals(285, ReportRange.year(2026).elapsedDays(today))
        assertEquals(365, ReportRange.year(2025).elapsedDays(today))
    }

    @Test
    fun calendarYear_arrowsMoveWholeYears() {
        val range = ReportRange.year(2026)

        assertEquals(ReportRange.year(2025), range.previous())
        assertEquals(ReportRange.year(2027), range.shift(1))
        assertEquals(ReportRange.year(2024), range.shift(-2))
    }

    // ---- 自定义区间 ----

    @Test
    fun custom_isAnInclusiveRange_exposedAsAHalfOpenOne() {
        // 工厂返回 ReportRange，daysInclusive 只在自己身上：这里直接建 Custom 断言区间天数
        val range = ReportRange.Custom(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-17"))

        assertEquals(LocalDate.parse("2026-10-01"), range.from)
        // 闭区间 17 日 → 半开区间 18 日：DAO 的 from <= occurredOn < to 才不会漏掉 17 日
        assertEquals(LocalDate.parse("2026-10-18"), range.to)
        assertEquals("10月1日–10月17日", range.label)
        assertEquals("较上一段", range.previousLabel)
        assertEquals(17, range.daysInclusive)
        assertFalse(range.isSingleMonth)
    }

    @Test
    fun custom_isLabelledWithYears_whenItSpansTwoYears() {
        val range = ReportRange.custom(LocalDate.parse("2025-12-20"), LocalDate.parse("2026-01-10"))

        assertEquals("2025.12.20–2026.1.10", range.label)
        assertEquals(LocalDate.parse("2025-12-20"), range.from)
        assertEquals(LocalDate.parse("2026-01-11"), range.to)
    }

    @Test
    fun custom_usesDayBarsUpToTheLimit_andMonthLinesBeyondIt() {
        val start = LocalDate.parse("2026-03-01")

        // 62 天：刚好还能按天画
        val dayRange = ReportRange.Custom(start, start.plusDays(61))
        assertEquals(62, dayRange.daysInclusive)
        assertEquals(ReportBucket.DAY, dayRange.bucket)
        assertEquals(62, dayRange.days(today).size)

        // 63 天：超一天就换成按月折线
        val monthRange = ReportRange.Custom(start, start.plusDays(62))
        assertEquals(63, monthRange.daysInclusive)
        assertEquals(ReportBucket.MONTH, monthRange.bucket)
        assertTrue(monthRange.days(today).isEmpty())
        assertEquals(
            listOf(YearMonth.of(2026, 3), YearMonth.of(2026, 4), YearMonth.of(2026, 5)),
            monthRange.months,
        )
    }

    @Test
    fun custom_rejectsABackwardsRange_andKeepsTheMaximumSpan() {
        assertThrows(IllegalArgumentException::class.java) {
            ReportRange.custom(LocalDate.parse("2026-10-17"), LocalDate.parse("2026-10-01"))
        }
        // 上限是"看得完"的量级：10 年左右
        assertEquals(3660, ReportRange.CUSTOM_MAX_DAYS)
        assertEquals(62, ReportRange.CUSTOM_DAY_LIMIT_DAYS)
    }

    @Test
    fun custom_arrowsMoveByItsOwnLength_soNeighbouringWindowsDoNotOverlap() {
        val range = ReportRange.custom(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-05"))

        val previous = range.previous()
        assertEquals(
            ReportRange.custom(LocalDate.parse("2026-09-26"), LocalDate.parse("2026-09-30")),
            previous,
        )
        assertEquals(range.from, previous.to)
        assertEquals(
            ReportRange.custom(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-10")),
            range.shift(1),
        )
    }

    @Test
    fun custom_elapsedDaysAndDaysIgnoreTheFuture() {
        // 今天之前就结束的区间：整段天数
        val past = ReportRange.custom(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-05"))
        assertEquals(5, past.elapsedDays(today))
        assertEquals(5, past.days(today).size)

        // 跨过今天的区间：只数到今天
        val spanning = ReportRange.custom(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-20"))
        assertEquals(3, spanning.elapsedDays(today))
        assertEquals(listOf(10, 11, 12), spanning.days(today).map { it.dayOfMonth })

        // 全在未来的区间：0 天、没有柱子
        val future = ReportRange.custom(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-05"))
        assertEquals(0, future.elapsedDays(today))
        assertTrue(future.days(today).isEmpty())
    }

    // ---- 工厂与区间聚合（StatsCalculator 的区间版本） ----

    @Test
    fun factoriesBuildTheSameRanges() {
        assertEquals(ReportRange.Month(YearMonth.of(2026, 10)), ReportRange.month(YearMonth.of(2026, 10)))
        assertEquals(
            ReportRange.LastMonths(YearMonth.of(2026, 10), 3),
            ReportRange.lastMonths(YearMonth.of(2026, 10), 3),
        )
        assertEquals(ReportRange.CalendarYear(2026), ReportRange.year(2026))
        assertEquals(
            ReportRange.Custom(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-05")),
            ReportRange.custom(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-05")),
        )
    }

    private fun tx(id: Long, type: TransactionType, amount: Long, date: String) = TransactionRecord(
        id = id,
        type = type,
        amountCents = amount,
        accountId = 1L,
        categoryId = if (type == TransactionType.INCOME) 2L else 1L,
        occurredOn = LocalDate.parse(date),
    )

    @Test
    fun elapsedDaysOfARange_matchesTheRangeItself() {
        val range = ReportRange.custom(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-17"))

        assertEquals(range.elapsedDays(today), StatsCalculator.elapsedDays(range.from, range.to, today))
        // 未来区间：0，不是负数
        assertEquals(
            0,
            StatsCalculator.elapsedDays(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-01"), today),
        )
    }

    @Test
    fun dailyTotalsOfARange_archivesByRealDate_acrossMonths() {
        val range = ReportRange.custom(LocalDate.parse("2026-09-29"), LocalDate.parse("2026-10-03"))
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 1_000L, "2026-09-29"),
            tx(2, TransactionType.EXPENSE, 2_000L, "2026-09-30"),
            tx(3, TransactionType.INCOME, 50_000L, "2026-10-01"),
            tx(4, TransactionType.EXPENSE, 300L, "2026-10-03"),
            // 区间外的记录一律忽略
            tx(5, TransactionType.EXPENSE, 9_999L, "2026-09-28"),
            tx(6, TransactionType.EXPENSE, 9_999L, "2026-10-04"),
            // 铁律 3：转账与余额调整不是收支
            tx(7, TransactionType.TRANSFER, 9_999L, "2026-10-02"),
            tx(8, TransactionType.BALANCE_ADJUST, 9_999L, "2026-10-02"),
        )

        val daily = StatsCalculator.dailyTotals(records, range.from, range.to, today)

        assertEquals(
            listOf(
                LocalDate.parse("2026-09-29"),
                LocalDate.parse("2026-09-30"),
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-02"),
                LocalDate.parse("2026-10-03"),
            ),
            daily.map { it.date },
        )
        assertEquals(listOf(1_000L, 2_000L, 0L, 0L, 300L), daily.map { it.expenseCents })
        assertEquals(listOf(0L, 0L, 50_000L, 0L, 0L), daily.map { it.incomeCents })
        // 区间合计与逐日合计同口径
        assertEquals(3_300L, daily.sumOf { it.expenseCents })
        assertEquals(50_000L, daily.sumOf { it.incomeCents })
    }

    @Test
    fun dailyTotalsOfARange_stopsAtToday() {
        val range = ReportRange.custom(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-20"))
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 500L, "2026-10-12"),
            // 未来日期（预设/脏数据）：不画也不补 0
            tx(2, TransactionType.EXPENSE, 700L, "2026-10-15"),
        )

        val daily = StatsCalculator.dailyTotals(records, range.from, range.to, today)

        assertEquals(3, daily.size)
        assertEquals(500L, daily.last().expenseCents)
        assertEquals(500L, daily.sumOf { it.expenseCents })
    }

    @Test
    fun dailyTotalsOfARange_isEmpty_forAFutureRange() {
        val daily = StatsCalculator.dailyTotals(
            records = emptyList(),
            from = LocalDate.parse("2026-11-01"),
            to = LocalDate.parse("2026-12-01"),
            today = today,
        )

        assertTrue(daily.isEmpty())
    }
}
