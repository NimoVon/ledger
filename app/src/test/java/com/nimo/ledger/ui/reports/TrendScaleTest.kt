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

    /** 真实数据：本月支出最高 ¥34,432.36 = 3,443,236 分 → 上界 ¥40,000.00 */
    @Test
    fun realMonthlyMaximum_scalesToAFriendlyTop() {
        assertEquals(4_000_000L, niceCeiling(3_443_236L))
        assertEquals(40_000_000L, niceCeiling(34_432_336L))
    }
}
