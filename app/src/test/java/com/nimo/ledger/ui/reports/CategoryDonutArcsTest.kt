package com.nimo.ledger.ui.reports

import com.nimo.ledger.domain.stats.CategoryBreakdownRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环形图扇形角度（批 D）：2dp 色缝从每块末尾吃掉、整块前移半个缝，n 块加起来仍是满满 360°。
 * 只钉算术，不摆 Compose；"2dp 在真实半径上占多少度"由 [seamAngleDeg] 换算。
 */
class CategoryDonutArcsTest {

    private val rows = listOf(
        CategoryBreakdownRow(1L, "娱乐", 60_000L, 0.75),
        CategoryBreakdownRow(2L, "餐饮", 20_000L, 0.25),
    )
    private val total = 80_000L

    @Test
    fun everySliceLosesExactlyOneSeam() {
        val arcs = donutArcs(rows, total, seamAngleDeg = 2f)

        assertEquals(2, arcs.size)
        assertEquals("270° 扣掉一条 2° 的缝", 268f, arcs[0].sweepAngleDeg, 0.001f)
        assertEquals("90° 扣掉一条 2° 的缝", 88f, arcs[1].sweepAngleDeg, 0.001f)
    }

    @Test
    fun slicesStillFillTheWholeCircle() {
        val seam = 2f
        val arcs = donutArcs(rows, total, seamAngleDeg = seam)

        val covered = arcs.sumOf { it.sweepAngleDeg.toDouble() } + arcs.size * seam
        assertEquals("留缝不能让环看起来少一截", 360.0, covered, 0.001)
        assertEquals("首块从 12 点前移半个缝开始", DONUT_START_ANGLE_DEG + seam / 2f, arcs[0].startAngleDeg, 0.001f)
        val endOfLast = arcs.last().startAngleDeg + arcs.last().sweepAngleDeg
        assertEquals("末块收在 12 点后半个缝，环闭合", DONUT_START_ANGLE_DEG + 360f - seam / 2f, endOfLast, 0.001f)
    }

    @Test
    fun slicesAreContiguousWhenTheSeamIsZero() {
        val arcs = donutArcs(rows, total, seamAngleDeg = 0f)
        val seamBetween = arcs[1].startAngleDeg - (arcs[0].startAngleDeg + arcs[0].sweepAngleDeg)
        assertEquals(0f, seamBetween, 0.001f)
    }

    @Test
    fun aSingleCategoryGetsAnUnbrokenRing() {
        // 只有一块还给一道缺口，看着像画坏了
        val arcs = donutArcs(listOf(rows[0]), 60_000L, seamAngleDeg = 2f)

        assertEquals(1, arcs.size)
        assertEquals(360f, arcs[0].sweepAngleDeg, 0.001f)
        assertEquals(DONUT_START_ANGLE_DEG, arcs[0].startAngleDeg, 0.001f)
    }

    @Test
    fun sweepIsClampedWhenTheSeamWouldEatTheWholeSlice() {
        // 十等分的小块遇上夸张的缝：不出现负 sweep（Compose 会反向画）
        val tiny = (1L..10L).map { id -> CategoryBreakdownRow(id, "类 $id", 100L, 0.1) }
        val arcs = donutArcs(tiny, 1_000L, seamAngleDeg = 90f)

        assertTrue(arcs.all { it.sweepAngleDeg >= 0f })
        assertEquals("36° - 90° 夹到 0", 0f, arcs.first().sweepAngleDeg, 0.001f)
    }

    @Test
    fun seamAngleOnTheRealRing_worksOutToAboutOneAndAHalfDegrees() {
        // 180dp 外径、26dp 环宽 → 半径 77dp；3x 屏下 2dp 缝 = 6px、半径 231px
        assertEquals(1.488f, seamAngleDeg(seamPx = 6f, radiusPx = 231f), 0.002f)
        assertEquals("半径退化时不除零", 0f, seamAngleDeg(seamPx = 6f, radiusPx = 0f), 0f)
    }

    @Test
    fun zeroTotal_producesNoArcs() {
        assertTrue(donutArcs(rows, 0L, 2f).isEmpty())
        assertTrue(donutArcs(emptyList(), total, 2f).isEmpty())
    }
}
