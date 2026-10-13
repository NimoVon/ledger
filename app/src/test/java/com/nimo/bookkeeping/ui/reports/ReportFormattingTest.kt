package com.nimo.bookkeeping.ui.reports

import org.junit.Assert.assertEquals
import org.junit.Test

/** 统计页文本格式：占比与环比，必须没有一个 NaN / Infinity 能溜到界面上 */
class ReportFormattingTest {

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
        assertEquals("较上月 ↑12.5%", ReportFormatting.change(0.125))
        assertEquals("较上月 ↓50.0%", ReportFormatting.change(-0.5))
    }
}
