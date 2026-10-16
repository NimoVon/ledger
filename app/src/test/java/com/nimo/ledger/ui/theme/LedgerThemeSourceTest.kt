package com.nimo.ledger.ui.theme

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级守门（R4-L1）：不再有壁纸动态取色，品牌色只能是图标里那抹淡蓝。
 *
 * 行为测试（LedgerPaletteTest）只能证明「当前这两套色板可读」，
 * 挡不住有人把 dynamicLightColorScheme 分支加回来——那才会让品牌色和
 * 支出红/收入蓝随壁纸变化。所以这里直接查源码。
 */
class LedgerThemeSourceTest {

    private val repoRoot: File = run {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        requireNotNull(dir) { "找不到仓库根目录（settings.gradle.kts）" }
    }

    private val appearanceDir = File(repoRoot, "app/src/main/java/com/nimo/ledger/ui/theme")
    private val mainSourceDir = File(repoRoot, "app/src/main/java")

    private val themeSource = stripComments(File(appearanceDir, "Theme.kt").readText())
    private val colorSource = stripComments(File(appearanceDir, "Color.kt").readText())

    /**
     * 注释里写「这里没有 dynamicLightColorScheme」是文档，不是违规代码。
     * 扫描前先去掉块注释与行注释，只留真正会被编译的部分。
     */
    private fun stripComments(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\\n]*"), " ")

    private val dynamicColourMarkers = listOf(
        "dynamicLightColorScheme",
        "dynamicDarkColorScheme",
        "DynamicColors",
    )

    @Test
    fun theme_noLongerBranchesOnAndroid12WallpaperColours() {
        (dynamicColourMarkers + listOf("Build.VERSION", "LocalContext")).forEach { marker ->
            assertFalse("Theme.kt 不应再出现 $marker", themeSource.contains(marker))
        }
    }

    @Test
    fun theme_wiresTheStaticBrandSchemesAndBrandShapes() {
        assertTrue(themeSource.contains("if (dark) DarkColors else LightColors"))
        assertTrue(themeSource.contains("shapes = LedgerShapes"))
    }

    @Test
    fun noProductionSource_readsWallpaperColours() {
        val offenders = mainSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> dynamicColourMarkers.any { stripComments(file.readText()).contains(it) } }
            .map { it.relativeTo(repoRoot).path.replace(File.separatorChar, '/') }
            .toList()
        assertTrue("这些源码还在读壁纸取色：$offenders", offenders.isEmpty())
    }

    @Test
    fun brandBlue_isTheIconColour_andTheOnlyBrandEntryPoint() {
        assertTrue(colorSource.contains("val BrandBlue: Color = Color(0xFF79C9FC)"))
        assertTrue(colorSource.contains("val LightPrimaryContainer = BrandBlue"))
        assertTrue(colorSource.contains("val DarkPrimary = BrandBlue"))
        assertFalse("浅色 primary 必须是深蓝文字色，不能拿淡蓝顶替", colorSource.contains("val LightPrimary = BrandBlue"))
    }

    /** 守门机制自检：注释里的 API 名不算违规，代码里的必须算——否则这条守门两头都不靠谱 */
    @Test
    fun commentStripping_keepsTheGuardHonest() {
        val documented = """
            /* R4-L1 已移除 dynamicLightColorScheme / dynamicDarkColorScheme */
            // 也不再调用 DynamicColors.applyToActivitiesIfAvailable(activity)
            val scheme = LightColors
        """.trimIndent()
        assertFalse(stripComments(documented).contains("dynamic"))
        assertFalse(stripComments(documented).contains("DynamicColors"))

        val real = "val scheme = dynamicLightColorScheme(LocalContext.current)"
        assertTrue(stripComments(real).contains("dynamicLightColorScheme"))
    }

    @Test
    fun emptyStateIllustration_isDeclaredDecorativeInSource() {
        val emptyState = File(mainSourceDir, "com/nimo/ledger/ui/components/EmptyState.kt").readText()
        assertTrue(emptyState.contains("contentDescription = null"))
        assertTrue(emptyState.contains("R.drawable.ic_launcher_foreground"))
    }
}
