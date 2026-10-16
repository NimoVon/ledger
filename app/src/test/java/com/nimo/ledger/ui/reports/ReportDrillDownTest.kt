package com.nimo.ledger.ui.reports

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月报下钻条件的翻译（F07）：被点的那一维 + 报表当时看的那个月，一个都不能少。
 * 纯 JVM：条件本身对不对，和 Room/Compose 无关。
 */
class ReportDrillDownTest {

    @Test
    fun byCategory_carriesThatCategoryAndTheWholeMonth() {
        val filter = ReportDrillDown.byCategory(YearMonth.of(2026, 9), 6L)

        assertEquals(setOf(6L), filter.categoryIds)
        assertTrue(filter.accountIds.isEmpty())
        assertEquals("2026-09-01", filter.from.toString())
        assertEquals("2026-09-30", filter.to.toString())
        assertTrue(filter.isActive)
    }

    @Test
    fun byAccount_carriesThatAccountAndTheWholeMonth() {
        val filter = ReportDrillDown.byAccount(YearMonth.of(2026, 10), 2L)

        assertEquals(setOf(2L), filter.accountIds)
        assertTrue(filter.categoryIds.isEmpty())
        assertEquals("2026-10-01", filter.from.toString())
        assertEquals("2026-10-31", filter.to.toString())
        assertTrue(filter.isActive)
    }

    /** 闭区间 [1 号, 月末] 落到查询上仍是半开区间 [from, to+1)，照旧走 occurredOn 范围比较 */
    @Test
    fun monthRange_turnsIntoAHalfOpenQueryThatCoversTheWholeMonth() {
        val bounds = ReportDrillDown.byCategory(YearMonth.of(2026, 9), 6L).queryBounds()

        assertEquals("2026-09-01" to "2026-10-01", bounds)
    }

    /** 月末按日历算，不是"30 天" */
    @Test
    fun monthEnd_followsTheCalendarIncludingLeapFebruary() {
        assertEquals("2026-02-28", ReportDrillDown.byCategory(YearMonth.of(2026, 2), 6L).to.toString())
        assertEquals("2028-02-29", ReportDrillDown.byAccount(YearMonth.of(2028, 2), 1L).to.toString())
    }
}
