package com.nimo.ledger.ui.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 折线纵轴上界：必须是"好看整数"且永远 ≥ 最高那个月，否则线会顶出画布 */
class TrendScaleTest {

    @Test
    fun nothingToDraw_isFlat() {
        assertEquals(0L, niceCeiling(0L))
        assertEquals(0L, niceCeiling(-1L))
    }

    @Test
    fun ceiling_usesOneTwoFourFiveSteps() {
        assertEquals(1_000L, niceCeiling(1_000L))
        assertEquals(2_000L, niceCeiling(1_001L))
        assertEquals(2_000L, niceCeiling(2_000L))
        assertEquals(4_000L, niceCeiling(2_001L))
        assertEquals(5_000L, niceCeiling(4_999L))
        assertEquals(10_000L, niceCeiling(5_001L))
    }

    @Test
    fun ceiling_isNeverBelowTheMaximum() {
        val samples = listOf(1L, 99L, 100L, 123_456L, 3_430_336L, 34_432_336L, 9_999_999_999L)
        for (value in samples) {
            assertTrue("niceCeiling($value) 小于最大值", niceCeiling(value) >= value)
        }
    }

    /** 月底 31 天格子只有 10 来 dp：标签必须稀疏，不能再挤成一团 */
    @Test
    fun labelInterval_keepsNeighbouringLabelsApart() {
        assertEquals(1, dayLabelInterval(44f))
        assertEquals(1, dayLabelInterval(60f))
        assertEquals(2, dayLabelInterval(22f))
        assertEquals(5, dayLabelInterval(10.5f))
        // 宽度拿不到（测量前/异常值）时退化成每天都标，不能除 0
        assertEquals(1, dayLabelInterval(0f))
        assertEquals(1, dayLabelInterval(-1f))
    }

    @Test
    fun labeledDays_keepsTheFirstDay_andDropsTheCrowdedLastDay() {
        val labeled = labeledDays((1..31).toList(), dayLabelInterval(10.5f))
        assertEquals(setOf(1, 5, 10, 15, 20, 25, 30), labeled)
        // 31 日离 30 日只差一天：只标 30
        assertTrue(31 !in labeled)
        // 只有几天时每天都标（标签放得下）
        assertEquals(setOf(1, 2, 3), labeledDays(listOf(1, 2, 3), 1))
        assertEquals(emptySet<Int>(), labeledDays(emptyList(), 5))
    }

    /** 真实数据：本月支出最高 ¥34,432.36 = 3,443,236 分 → 上界 ¥40,000.00 */
    @Test
    fun realMonthlyMaximum_scalesToAFriendlyTop() {
        assertEquals(4_000_000L, niceCeiling(3_443_236L))
        assertEquals(40_000_000L, niceCeiling(34_432_336L))
    }
}
