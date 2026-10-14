package com.nimo.bookkeeping.ui.reports

import com.nimo.bookkeeping.domain.stats.CategoryBreakdownRow
import com.nimo.bookkeeping.domain.stats.MonthlyTotal
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 图表无障碍描述（F15）：读屏听到的必须是真的数据，而不是一句笼统的"近半年收支趋势"。
 * 纯函数测试，不需要 Compose / Robolectric。
 */
class ChartDescriptionsTest {

    @Test
    fun trendHead_countsTheWindowInMonths() {
        assertEquals("近6个月收支趋势", trendHead(TrendSpan.HALF_YEAR))
        assertEquals("近12个月收支趋势", trendHead(TrendSpan.FULL_YEAR))
    }

    @Test
    fun trendDescription_readsTheWindowTotals_andTheLatestMonth() {
        val points = listOf(
            MonthlyTotal(YearMonth.of(2026, 5), 100_000L, 60_000L),
            MonthlyTotal(YearMonth.of(2026, 6), 0L, 25_050L),
        )
        assertEquals(
            "近6个月收支趋势，共 2 个月：收入 ¥1,000.00，支出 ¥850.50；" +
                "最近一个月 2026年6月：收入 ¥0.00，支出 ¥250.50",
            trendDescription(points, TrendSpan.HALF_YEAR),
        )
    }

    @Test
    fun trendDescription_withoutRecords_saysThereIsNoData() {
        assertEquals("近12个月收支趋势：暂无数据", trendDescription(emptyList(), TrendSpan.FULL_YEAR))
    }

    @Test
    fun donutDescription_readsTheTotal_andTheBiggestCategories() {
        val rows = listOf(
            CategoryBreakdownRow(1L, "娱乐", 60_000L, 0.75),
            CategoryBreakdownRow(2L, "餐饮", 20_000L, 0.25),
        )
        assertEquals(
            "本月支出分类占比，合计 ¥800.00：娱乐 ¥600.00（75.0%），餐饮 ¥200.00（25.0%）",
            donutDescription(rows, 80_000L),
        )
    }

    @Test
    fun donutDescription_countsTheRest_whenThereAreMoreThanThreeCategories() {
        val rows = listOf(
            CategoryBreakdownRow(1L, "娱乐", 60_000L, 0.6),
            CategoryBreakdownRow(2L, "餐饮", 20_000L, 0.2),
            CategoryBreakdownRow(3L, "交通", 10_000L, 0.1),
            CategoryBreakdownRow(4L, "购物", 10_000L, 0.1),
        )
        assertEquals(
            "本月支出分类占比，合计 ¥1,000.00：娱乐 ¥600.00（60.0%），餐饮 ¥200.00（20.0%），" +
                "交通 ¥100.00（10.0%），其余 1 个分类",
            donutDescription(rows, 100_000L),
        )
    }

    @Test
    fun donutDescription_withoutSpending_staysTheHeadLine() {
        assertEquals(DONUT_DESCRIPTION, donutDescription(emptyList(), 0L))
        assertEquals(
            DONUT_DESCRIPTION,
            donutDescription(listOf(CategoryBreakdownRow(1L, "娱乐", 0L, null)), 0L),
        )
    }
}
