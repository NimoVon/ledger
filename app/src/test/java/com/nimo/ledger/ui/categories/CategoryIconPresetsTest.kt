package com.nimo.ledger.ui.categories

import com.nimo.ledger.data.local.DefaultCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分类图标（C-6 / 批 F 的 F-3）：预置宫格与默认分类同一套，输入裁剪不切断 emoji。 */
class CategoryIconPresetsTest {

    @Test
    fun theGrid_matchesTheDefaultCategoryEmoji() {
        assertEquals(
            "宫格与 DefaultCategories 的 emoji 必须完全一致（加了默认分类就要补宫格）",
            DefaultCategories.presetIcons().toSet(),
            CATEGORY_ICON_PRESETS.toSet(),
        )
    }

    @Test
    fun theGrid_hasNoDuplicates() {
        assertEquals(CATEGORY_ICON_PRESETS.size, CATEGORY_ICON_PRESETS.toSet().size)
    }

    @Test
    fun clamp_keepsAtMostTwoCodePoints() {
        assertEquals("🍜🍜", clampCategoryIconInput("🍜🍜🍜"))
        // 🛍️ 是「基础 emoji + 变体选择符」两个码点，不能被裁掉后半截
        assertEquals("🛍️", clampCategoryIconInput("🛍️"))
        assertEquals("🛍️", clampCategoryIconInput("🛍️🍜"))
    }

    @Test
    fun clamp_trimsSpacesAndKeepsShortInput() {
        assertEquals("", clampCategoryIconInput("   "))
        assertEquals("☕", clampCategoryIconInput(" ☕ "))
    }

    @Test
    fun clamp_neverLeavesAHalfSurrogate() {
        val clamped = clampCategoryIconInput("🍜")
        assertTrue("必须保留完整的代理对（码点 0x1F35C）", clamped.codePointAt(0) == 0x1F35C)
        assertEquals(1, clamped.codePointCount(0, clamped.length))
    }

    @Test
    fun blankInput_meansNoIcon() {
        assertNull(normalizedCategoryIcon(""))
        assertNull(normalizedCategoryIcon("  "))
        assertEquals("🎮", normalizedCategoryIcon("🎮"))
    }
}
