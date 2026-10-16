package com.nimo.ledger.ui.components

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 年月选择器的两步逻辑（R1 日期直改）：年份分页窗口 + 月份格 + 高亮判定。
 *
 * 用不着 Compose：这一步是纯算术，坏起来也只坏在算术上（比如年份页起点算错，
 * 用户打开面板就看不见今年）。交互本身由 YearMonthPickerBodyUiTest 守。
 */
class YearMonthPickerTest {

    @Test
    fun thePageContainingTheViewedYear_holdsTwentyYearsAroundIt() {
        val years = YearMonthPicker.years(2026)

        assertEquals(20, years.size)
        assertTrue(2026 in years)
        // 按 20 年一页对齐：2026 落在 2020–2039
        assertEquals(2020, years.first())
        assertEquals(2039, years.last())
        assertEquals("2020–2039", YearMonthPicker.pageLabel(2026))
    }

    @Test
    fun paging_movesByOneWholePageInBothDirections() {
        assertEquals((2040..2059).toList(), YearMonthPicker.years(2026, 1))
        assertEquals((2000..2019).toList(), YearMonthPicker.years(2026, -1))
        assertEquals("2040–2059", YearMonthPicker.pageLabel(2026, 1))
        assertEquals("2000–2019", YearMonthPicker.pageLabel(2026, -1))
    }

    @Test
    fun januaryAndDecember_areOnTheSameYearPage_butOnlyOneMonthCellLightsUp() {
        val years = YearMonthPicker.years(2026)
        assertTrue(2026 in years)

        // 1 月与 12 月同属 2026：年份格都亮
        assertTrue(YearMonthPicker.isSelectedYear(YearMonth.of(2026, 1), 2026))
        assertTrue(YearMonthPicker.isSelectedYear(YearMonth.of(2026, 12), 2026))
        // 月份格只在同一年里亮：2026 年那屏上，"1 月"不能因为现在是 2027-01 就亮
        assertFalse(YearMonthPicker.isSelectedMonth(YearMonth.of(2027, 1), 2026, 1))
        assertTrue(YearMonthPicker.isSelectedMonth(YearMonth.of(2026, 1), 2026, 1))
        assertFalse(YearMonthPicker.isSelectedMonth(YearMonth.of(2026, 12), 2026, 11))
    }

    @Test
    fun theYearBoundaryBetweenTwoPages_isReachableByPaging() {
        // 1999 在 1980–1999 这一页；2000 是相邻年但属于下一页，翻一页就到
        assertEquals((1980..1999).toList(), YearMonthPicker.years(1999))
        assertEquals(2000, YearMonthPicker.years(1999, 1).first())
        assertEquals((2000..2019).toList(), YearMonthPicker.years(1999, 1))
        assertEquals((1980..1999).toList(), YearMonthPicker.years(2000, -1))
    }

    @Test
    fun months_areOneToTwelve_withSingleDigitLabelsNotZeroPadded() {
        assertEquals((1..12).toList(), YearMonthPicker.months())
        // 与月份标签 "2026年10月" 同一套写法：1 月不写成 "01月"
        assertEquals("1月", YearMonthPicker.monthLabel(1))
        assertEquals("12月", YearMonthPicker.monthLabel(12))
        assertEquals("2026年", YearMonthPicker.yearTitle(2026))
        assertEquals("2026", YearMonthPicker.yearLabel(2026))
    }
}
