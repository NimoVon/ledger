package com.nimo.ledger.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 品牌色板守门（R4-L1）：换掉壁纸动态取色之后，「好看」得先用数字证明「能读」。
 *
 * 对比度按 WCAG 2.x 相对亮度公式自己算（不引第三方库）：
 * 正文级 ≥ 4.5:1，图形/边框级 ≥ 3.0:1。所有数字与本机 python 预算结果一致，
 * 见 docs/测试报告.md §14。
 */
class LedgerPaletteTest {

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun assertContrast(
        name: String,
        foreground: Color,
        background: Color,
        minimum: Double = 4.5,
    ) {
        val ratio = contrast(foreground, background)
        val shown = (ratio * 100).roundToInt() / 100.0
        assertTrue("$name 对比度 $shown:1，低于门槛 $minimum:1", ratio >= minimum)
    }

    @Test
    fun lightPalette_everyReadableRole_pairMeetsWcagAa() {
        val c = LightColors
        assertContrast("onPrimary/primary", c.onPrimary, c.primary)
        assertContrast("onPrimaryContainer/primaryContainer", c.onPrimaryContainer, c.primaryContainer)
        assertContrast("onSecondary/secondary", c.onSecondary, c.secondary)
        assertContrast("onSecondaryContainer/secondaryContainer", c.onSecondaryContainer, c.secondaryContainer)
        assertContrast("onTertiary/tertiary", c.onTertiary, c.tertiary)
        assertContrast("onTertiaryContainer/tertiaryContainer", c.onTertiaryContainer, c.tertiaryContainer)
        assertContrast("onError/error", c.onError, c.error)
        assertContrast("onErrorContainer/errorContainer", c.onErrorContainer, c.errorContainer)
        assertContrast("onSurface/surface", c.onSurface, c.surface)
        assertContrast("onBackground/background", c.onBackground, c.background)
        assertContrast("onSurfaceVariant/surfaceVariant", c.onSurfaceVariant, c.surfaceVariant)
        assertContrast("onSurfaceVariant/surface", c.onSurfaceVariant, c.surface)
        assertContrast("onSurfaceVariant/surfaceContainer", c.onSurfaceVariant, c.surfaceContainer)
        assertContrast("onSurfaceVariant/surfaceContainerHigh", c.onSurfaceVariant, c.surfaceContainerHigh)
        assertContrast("onSurface/surfaceContainerLowest", c.onSurface, c.surfaceContainerLowest)
        assertContrast("onSurface/surfaceContainerLow", c.onSurface, c.surfaceContainerLow)
        assertContrast("onSurface/surfaceContainerHigh", c.onSurface, c.surfaceContainerHigh)
        assertContrast("onSurface/surfaceContainerHighest", c.onSurface, c.surfaceContainerHighest)
        assertContrast("inverseOnSurface/inverseSurface", c.inverseOnSurface, c.inverseSurface)
    }

    @Test
    fun darkPalette_everyReadableRole_pairMeetsWcagAa() {
        val c = DarkColors
        assertContrast("onPrimary/primary", c.onPrimary, c.primary)
        assertContrast("onPrimaryContainer/primaryContainer", c.onPrimaryContainer, c.primaryContainer)
        assertContrast("onSecondary/secondary", c.onSecondary, c.secondary)
        assertContrast("onSecondaryContainer/secondaryContainer", c.onSecondaryContainer, c.secondaryContainer)
        assertContrast("onTertiary/tertiary", c.onTertiary, c.tertiary)
        assertContrast("onTertiaryContainer/tertiaryContainer", c.onTertiaryContainer, c.tertiaryContainer)
        assertContrast("onError/error", c.onError, c.error)
        assertContrast("onErrorContainer/errorContainer", c.onErrorContainer, c.errorContainer)
        assertContrast("onSurface/surface", c.onSurface, c.surface)
        assertContrast("onBackground/background", c.onBackground, c.background)
        assertContrast("onSurfaceVariant/surfaceVariant", c.onSurfaceVariant, c.surfaceVariant)
        assertContrast("onSurfaceVariant/surface", c.onSurfaceVariant, c.surface)
        assertContrast("onSurfaceVariant/surfaceContainer", c.onSurfaceVariant, c.surfaceContainer)
        assertContrast("onSurfaceVariant/surfaceContainerHigh", c.onSurfaceVariant, c.surfaceContainerHigh)
        assertContrast("onSurface/surfaceContainerLowest", c.onSurface, c.surfaceContainerLowest)
        assertContrast("onSurface/surfaceContainerLow", c.onSurface, c.surfaceContainerLow)
        assertContrast("onSurface/surfaceContainerHigh", c.onSurface, c.surfaceContainerHigh)
        assertContrast("onSurface/surfaceContainerHighest", c.onSurface, c.surfaceContainerHighest)
        assertContrast("inverseOnSurface/inverseSurface", c.inverseOnSurface, c.inverseSurface)
    }

    /** 金额文字直接用 primary / error / tertiary / secondary 上色（收入金额、预算状态、超支提示），必须 AA */
    @Test
    fun accentRoles_areReadableWhenUsedAsTextColours() {
        assertContrast("light primary/surface（收入金额）", LightColors.primary, LightColors.surface)
        assertContrast("light primary/surfaceVariant", LightColors.primary, LightColors.surfaceVariant)
        assertContrast("light error/surface（支出金额）", LightColors.error, LightColors.surface)
        assertContrast("light tertiary/surface（预算预警）", LightColors.tertiary, LightColors.surface)
        assertContrast("light secondary/surface", LightColors.secondary, LightColors.surface)
        assertContrast("light outline/surface（次要边框文字）", LightColors.outline, LightColors.surface)
        assertContrast("dark primary/surface（收入金额）", DarkColors.primary, DarkColors.surface)
        assertContrast("dark primary/surfaceVariant", DarkColors.primary, DarkColors.surfaceVariant)
        assertContrast("dark error/surface（支出金额）", DarkColors.error, DarkColors.surface)
        assertContrast("dark tertiary/surface（预算预警）", DarkColors.tertiary, DarkColors.surface)
        assertContrast("dark secondary/surface", DarkColors.secondary, DarkColors.surface)
        assertContrast("dark outline/surface", DarkColors.outline, DarkColors.surface)
    }

    /** 图形级 3:1：环形图扇区、进度条 fill/track —— 这类是「看形状」不是「读字」 */
    @Test
    fun keyGraphicPairs_stayAboveThreeToOne() {
        assertContrast("light primary/primaryContainer（环形图扇区）", LightColors.primary, LightColors.primaryContainer, minimum = 3.0)
        assertContrast("dark primary/primaryContainer（环形图扇区）", DarkColors.primary, DarkColors.primaryContainer, minimum = 3.0)
        assertContrast("light outline/surfaceVariant（进度条 fill/track）", LightColors.outline, LightColors.surfaceVariant, minimum = 3.0)
        assertContrast("dark outline/surfaceVariant（进度条 fill/track）", DarkColors.outline, DarkColors.surfaceVariant, minimum = 3.0)
    }

    /** 图表网格线是纯装饰，故意低于 3:1：写成断言，防止哪天被人当成信息图形「顺手」加深 */
    @Test
    fun chartGridLines_areDecorativeAndDeliberatelyFaint() {
        assertTrue(contrast(LightColors.outlineVariant, LightColors.surface) < 3.0)
        assertTrue(contrast(DarkColors.outlineVariant, DarkColors.surface) < 3.0)
    }

    @Test
    fun brandBlue_isTheIconColour_andNeverCarriesBodyTextInLightTheme() {
        assertEquals(Color(0xFF79C9FC), BrandBlue)
        assertEquals(BrandBlue, LightColors.primaryContainer)
        assertEquals(BrandBlue, DarkColors.primary)
        // 淡蓝在浅底上只有 1.7:1 —— 只能当容器；文字色必须是另一支深蓝
        assertTrue(contrast(BrandBlue, LightColors.surface) < 4.5)
        assertNotEquals(BrandBlue, LightColors.primary)
    }

    /** 支出红 / 收入蓝的语义不能被色板换肤弄反：红偏暖（R>B）、蓝偏冷（B>R），深色同理 */
    @Test
    fun expenseColourStaysRed_andIncomeColourStaysBlue_inBothThemes() {
        assertTrue("浅色支出红应偏暖", LightColors.error.red > LightColors.error.blue)
        assertTrue("浅色收入蓝应偏冷", LightColors.primary.blue > LightColors.primary.red)
        assertTrue("深色支出红应偏暖", DarkColors.error.red > DarkColors.error.blue)
        assertTrue("深色收入蓝应偏冷", DarkColors.primary.blue > DarkColors.primary.red)
    }

    @Test
    fun chartSemanticAliases_followTheSameBlueAndRed() {
        assertEquals(LightColors.primary, BrandIncomeBlue)
        assertEquals(LightColors.error, BrandExpenseRed)
    }

    @Test
    fun brandBlue_isSharedByBothThemes_notReinventedPerTheme() {
        assertTrue(LightColors.primaryContainer == DarkColors.primary)
        assertTrue(DarkColors.surface != LightColors.surface)
        assertTrue(DarkColors.onSurface != LightColors.onSurface)
    }
}
