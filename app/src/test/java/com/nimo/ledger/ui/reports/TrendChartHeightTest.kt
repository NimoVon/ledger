package com.nimo.ledger.ui.reports

import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.stats.ReportRange
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

/**
 * S-6：自然年 12 个点在窄屏（< 360dp）上更密，图表抬高 20dp；其余保持 160dp。
 *
 * 设备是 411dp 宽，真机触发不到这条分支，所以阈值与判定放在纯函数里用 JVM 锁住——
 * 宁可测函数，也不去把设备 density 改成非标准值来"演"一次。
 */
class TrendChartHeightTest {

    @Test
    fun calendarYearOnNarrowScreen_isTaller() {
        val height = trendChartHeight(ReportRange.CalendarYear(2026), 359.dp)
        assertEquals(180.dp, height)
    }

    @Test
    fun calendarYearAtTheBoundary_staysDefault() {
        // 门槛是"小于 360dp"，正好 360 不算窄屏
        assertEquals(160.dp, trendChartHeight(ReportRange.CalendarYear(2026), 360.dp))
    }

    @Test
    fun singleMonthOnNarrowScreen_staysDefault() {
        assertEquals(160.dp, trendChartHeight(ReportRange.Month(YearMonth.of(2026, 10)), 320.dp))
    }

    @Test
    fun calendarYearOnWideScreen_staysDefault() {
        assertEquals(160.dp, trendChartHeight(ReportRange.CalendarYear(2026), 411.dp))
    }
}
