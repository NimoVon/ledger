package com.nimo.ledger.ui.reports

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类图表色板（批 D）：10 色一套、浅色与深色共用，同分类恒同色、同一张图里不撞色。
 *
 * 对比度按 WCAG 相对亮度自己算，不读主题 token——断言钉的是"色值本身在两种底色上都看得见"，
 * 换成别的 token 实现时这道门禁不该跟着漂移。
 */
class CategoryPaletteTest {

    private fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    /** 与 Color.kt 里浅色 LightSurface / 深色 DarkSurface 同值；图表必须铺在 surface 上 */
    private val lightSurface = Color(0xFFF5FAFF)
    private val darkSurface = Color(0xFF0E1418)

    @Test
    fun palette_hasTenDistinctColours() {
        assertEquals(CATEGORY_SLOT_COUNT, CATEGORY_SLOT_COLORS.size)
        assertEquals("10 个色位必须互不相同，否则同图两块同色", CATEGORY_SLOT_COUNT, CATEGORY_SLOT_COLORS.toSet().size)
    }

    @Test
    fun everySlotColour_clearsThreeToOneOnBothThemes() {
        CATEGORY_SLOT_COLORS.forEachIndexed { index, colour ->
            val onLight = contrast(colour, lightSurface)
            val onDark = contrast(colour, darkSurface)
            assertTrue("slot ${index} 在浅色 surface 上只有 $onLight", onLight >= 3.0)
            assertTrue("slot ${index} 在深色 surface 上只有 $onDark", onDark >= 3.0)
        }
    }

    @Test
    fun slotFor_isStableForTheSameCategory() {
        // 同一个分类在任何月份、任何设备上都应该是同一个色位
        assertEquals(categorySlotFor(7L), categorySlotFor(7L))
        assertEquals("id 差 10 是满周期的整数倍", categorySlotFor(1L), categorySlotFor(11L))
        assertTrue(categorySlotFor(0L) in 0 until CATEGORY_SLOT_COUNT)
        assertTrue("负数 id 也要归一进 0..9", categorySlotFor(-3L) in 0 until CATEGORY_SLOT_COUNT)
    }

    @Test
    fun slotFor_keepsNeighbouringCategoryIdsApart() {
        // 默认分类是连续 id：1 和 2 若同色，用户就分不清「餐饮」和「交通」
        for (id in 1L..30L) {
            assertNotEquals("id $id 与 ${id + 1} 撞了同一个色位", categorySlotFor(id), categorySlotFor(id + 1))
        }
    }

    @Test
    fun resolveSlots_ignoresTheOrderOfTheInput() {
        // 环形图与排行条各自算一遍，必须得到同一张表
        val a = resolveCategorySlots(listOf(3L, 1L, 2L))
        val b = resolveCategorySlots(listOf(2L, 3L, 1L))
        assertEquals(a, b)
        assertEquals(3, a.size)
    }

    @Test
    fun resolveSlots_givesDistinctSlotsWithinTenCategories() {
        // 第三组是哈希必撞的三个 id（11/21/31 的基础色位都是 0），靠线性探测分开
        for (ids in listOf((1L..5L).toList(), (1L..10L).toList(), listOf(11L, 21L, 31L))) {
            val resolved = resolveCategorySlots(ids)
            assertEquals("同一张图里不能有两块同色：$ids", ids.size, resolved.values.toSet().size)
        }
    }

    @Test
    fun resolveSlots_avoidsTheCollisionTheHashWouldProduce() {
        val ids = (1L..40L).toList()
        val colliding = ids.flatMap { a -> ids.filter { b -> b > a }.map { b -> a to b } }
            .first { (a, b) -> categorySlotFor(a) == categorySlotFor(b) }
        val resolved = resolveCategorySlots(listOf(colliding.first, colliding.second))
        assertNotEquals(resolved.getValue(colliding.first), resolved.getValue(colliding.second))
    }

    @Test
    fun resolveSlots_staysDeterministicBeyondTenCategories() {
        val ids = (1L..14L).toList()
        val first = resolveCategorySlots(ids)
        assertEquals(first, resolveCategorySlots(ids))
        assertEquals("槽位用满 10 个，超出的按基础色位复用", CATEGORY_SLOT_COUNT, first.values.toSet().size)
        assertTrue(first.values.all { it in 0 until CATEGORY_SLOT_COUNT })
    }
}
