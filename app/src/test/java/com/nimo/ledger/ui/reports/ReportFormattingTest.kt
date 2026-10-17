package com.nimo.ledger.ui.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 统计页文本格式：占比与环比，必须没有一个 NaN / Infinity 能溜到界面上 */
class ReportFormattingTest {

    /** 环比方向（§5.1 TrendUp / TrendDown）：箭头交给 Icon 画，文本里不许再有 ↑↓ 字符 */
    @Test
    fun changeDirection_tellsWindowsWhichArrowToDraw() {
        assertEquals(ChangeDirection.UP, ReportFormatting.changeDirection(0.125))
        assertEquals(ChangeDirection.DOWN, ReportFormatting.changeDirection(-0.5))
        assertEquals(ChangeDirection.NONE, ReportFormatting.changeDirection(null))
        // 与文本同一门槛：差不到 0.05% 就是持平，不给箭头
        assertEquals(ChangeDirection.NONE, ReportFormatting.changeDirection(0.0))
        assertEquals(ChangeDirection.NONE, ReportFormatting.changeDirection(0.00004))
    }

    @Test
    fun theText_neverContainsArrowGlyphs() {
        // 字符基线随字体漂移：旧实现把 ↑↓ 拼进字符串，16dp 下会和数字高低不齐
        listOf(0.125, -0.5, 2.0, null, 0.0).forEach { ratio ->
            val text = ReportFormatting.change(ratio)
            assertFalse("文本里不该再有 ↑：$text", text.contains("↑"))
            assertFalse("文本里不该再有 ↓：$text", text.contains("↓"))
        }
    }

    @Test
    fun percent_roundsToTenthsAndFallsBackWhenThereIsNothingToCompare() {
        assertEquals("—", ReportFormatting.percent(null))
        assertEquals("0.0%", ReportFormatting.percent(0.0))
        assertEquals("42.3%", ReportFormatting.percent(0.4231))
        assertEquals("100.0%", ReportFormatting.percent(1.0))
        assertEquals("33.3%", ReportFormatting.percent(1.0 / 3.0))
    }

    @Test
    fun change_showsArrowAndFallsBackInsteadOfNaN() {
        assertEquals("较上月 —", ReportFormatting.change(null))
        assertEquals("较上月 持平", ReportFormatting.change(0.0))
        // 差 0.004% 就别拿出来吓人
        assertEquals("较上月 持平", ReportFormatting.change(0.00004))
        assertEquals("较上月 12.5%", ReportFormatting.change(0.125))
        assertEquals("较上月 50.0%", ReportFormatting.change(-0.5))
    }

    /**
     * 「跟谁比」必须由范围决定：自然年比上一年、N 个月窗口比前 N 个月、自选区间比上一段。
     * 单月不传就是原来的「较上月」——旧调用点一个都不用改。
     */
    @Test
    fun change_namesTheComparedRange_insteadOfAlwaysSayingLastMonth() {
        assertEquals("较上一年 12.5%", ReportFormatting.change(0.125, "较上一年"))
        assertEquals("较上一年 —", ReportFormatting.change(null, "较上一年"))
        assertEquals("较上一年 持平", ReportFormatting.change(0.0, "较上一年"))
        assertEquals("较前6个月 50.0%", ReportFormatting.change(-0.5, "较前6个月"))
        assertEquals("较上一段 200.0%", ReportFormatting.change(2.0, "较上一段"))
        // 默认参数仍走单月口径
        assertEquals("较上月 12.5%", ReportFormatting.change(0.125))
    }
}
