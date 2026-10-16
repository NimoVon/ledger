package com.nimo.ledger.ui.reports

import com.nimo.ledger.domain.stats.CategoryBreakdownRow
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 环形图命中判定（纯函数）：点哪块选哪块，点洞里 / 环外不选。
 * 画布 540px = 180dp @3x、环宽 78px = 26dp——与 CategoryDonut.kt 的常量同源；
 * 这里不摆 Compose，只钉住"角度 + 半径 → 哪一块"这段算术，免得靠截图猜。
 */
class CategoryDonutHitTest {

    private val rows = listOf(
        CategoryBreakdownRow(1L, "娱乐", 60_000L, 0.75),
        CategoryBreakdownRow(2L, "餐饮", 20_000L, 0.25),
    )
    private val total = 80_000L
    private val canvas = 540f
    private val stroke = 78f

    private fun tapAt(degrees: Double, radiusFraction: Double = 0.95): Int? {
        val center = canvas / 2f
        val radius = (canvas - stroke) / 2f * radiusFraction
        val radians = Math.toRadians(degrees)
        return donutSliceAt(
            rows = rows,
            totalCents = total,
            tapX = center + (radius * cos(radians)).toFloat(),
            tapY = center + (radius * sin(radians)).toFloat(),
            canvasWidthPx = canvas,
            canvasHeightPx = canvas,
            strokePx = stroke,
        )
    }

    /** 3 点方向 = 从 12 点顺时针 90°：落在第一块（娱乐 75%，占 0–270°） */
    @Test
    fun tappingThreeOClock_picksTheFirstCategory() {
        assertEquals(0, tapAt(0.0))
    }

    /** 10 点半方向 = 225°：落在第二块（餐饮 25%，占 270–360°） */
    @Test
    fun tappingTenThirty_picksTheSecondCategory() {
        assertEquals(1, tapAt(225.0))
    }

    /** 环心是「支出合计」，不是可选项：点洞里不算命中 */
    @Test
    fun tappingTheHole_isNotASlice() {
        assertNull(donutSliceAt(rows, total, 270f, 270f, canvas, canvas, stroke))
    }

    /** 画布左上角（环外）：不算命中，等于取消选中 */
    @Test
    fun tappingOutsideTheRing_isNotASlice() {
        assertNull(donutSliceAt(rows, total, 5f, 5f, canvas, canvas, stroke))
    }

    /** 没有分类 / 没有支出：一切都点不中 */
    @Test
    fun withoutRowsOrSpending_nothingIsPickable() {
        assertNull(donutSliceAt(emptyList(), 0L, 501f, 270f, canvas, canvas, stroke))
        assertNull(donutSliceAt(rows, 0L, 501f, 270f, canvas, canvas, stroke))
    }
}
