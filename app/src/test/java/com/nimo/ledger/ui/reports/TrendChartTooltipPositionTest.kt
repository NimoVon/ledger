package com.nimo.ledger.ui.reports

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图表气泡落点（问题 D）：气泡要贴在被点柱子的上方，且不越出绘图区。
 *
 * 这里锁三件事：
 * 1. 柱子越高，气泡越靠上（同一张图里随金额单调变化）；
 * 2. 没被夹紧时，气泡下沿在柱顶之上并留出 [DAY_TOOLTIP_GAP]；
 * 3. 顶到天花板/贴近地板时被夹在绘图区内，不会跑到图外。
 */
class TrendChartTooltipPositionTest {

    private val plot: Dp = 160.dp
    private val inset: Dp = 8.dp
    private val bubble: Dp = DAY_TOOLTIP_HEIGHT
    private val usable: Dp = plot - inset * 2

    private fun y(fraction: Float): Dp = tooltipTopOffset(
        plotHeight = plot,
        inset = inset,
        bubbleHeight = bubble,
        barFraction = fraction,
    )

    private fun barTop(fraction: Float): Dp = inset + usable * (1f - fraction)

    @Test
    fun tallerBar_pushesTheBubbleHigher() {
        val low = y(0.20f)
        val mid = y(0.50f)
        val high = y(0.80f)
        assertTrue("0.20 的气泡应低于 0.50：$low vs $mid", low > mid)
        assertTrue("0.50 的气泡应低于 0.80：$mid vs $high", mid > high)
    }

    @Test
    fun bubbleStaysAboveTheBarTop_withAGap() {
        // 0.10 / 0.50 还没被夹到顶，缝必须完整
        listOf(0.10f, 0.50f).forEach { fraction ->
            val top = y(fraction)
            assertTrue("fraction=$fraction 不该被夹到顶：$top", top > 0.dp)
            val bar = barTop(fraction)
            val gap = bar - (top + bubble)
            assertTrue(
                "fraction=$fraction 气泡下沿应在柱顶之上：gap=$gap",
                gap >= DAY_TOOLTIP_GAP - 0.5.dp,
            )
        }
    }

    @Test
    fun bubbleIsClampedInsideThePlot_atBothEnds() {
        // 满柱：算出来是负数，夹到 0，气泡贴顶
        assertEquals(0.dp, y(1.0f))
        // 零柱：气泡落到底部（柱顶之下再减气泡高度与缝），仍在图内
        val zero = y(0f)
        assertEquals(barTop(0f) - bubble - DAY_TOOLTIP_GAP, zero)
        assertTrue("零柱气泡不能越出绘图区", zero + bubble <= plot)
        // 越界比例也不炸
        assertEquals(0.dp, y(1.5f))
        assertEquals(zero, y(-0.3f))
    }
}
