package com.nimo.ledger.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 品牌色板（R4-L1「小狗风」）。
 *
 * 取自应用图标里那只白狗身后的一抹淡蓝：图标背景色 `#79C9FC` 就是品牌强调色，
 * 同时是浅色主题的 primaryContainer（选中态、强调底色都用它）。
 * 需要人读的文字（金额、标题、说明）一律走深蓝派生色——
 * `#79C9FC` 在浅色底上的对比度只有 1.7:1，拿它当正文色就是拿可读性换好看。
 * 每个前台角色对的对比度由 [com.nimo.ledger.ui.theme.LedgerPaletteTest] 按 WCAG AA 守住。
 */

/** 品牌强调色，与图标 `#79C9FC` 逐字节一致；浅色主题的 primaryContainer */
val BrandBlue: Color = Color(0xFF79C9FC)

// === 浅色（surface 系一律冷调淡蓝白，深浅由色相而非明度堆叠） ===
val LightPrimary = Color(0xFF0B5A8C)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = BrandBlue
val LightOnPrimaryContainer = Color(0xFF06304E)
val LightSecondary = Color(0xFF4C6376)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD3E8F9)
val LightOnSecondaryContainer = Color(0xFF123043)
val LightTertiary = Color(0xFF8A5A00)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFDFA8)
val LightOnTertiaryContainer = Color(0xFF2B1A00)
val LightError = Color(0xFFB3261E)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFF9DEDC)
val LightOnErrorContainer = Color(0xFF410E0B)
val LightBackground = Color(0xFFF5FAFF)
val LightOnBackground = Color(0xFF0F1B26)
val LightSurface = Color(0xFFF5FAFF)
val LightOnSurface = Color(0xFF0F1B26)
val LightSurfaceVariant = Color(0xFFDCE7F2)
val LightOnSurfaceVariant = Color(0xFF41566A)
val LightOutline = Color(0xFF5C7183)
val LightOutlineVariant = Color(0xFFC2D3E2)

// === 深色（同一个品牌色相压到深底上，不是把浅色反相） ===
val DarkPrimary = BrandBlue
val DarkOnPrimary = Color(0xFF00314F)
val DarkPrimaryContainer = Color(0xFF0B4A73)
val DarkOnPrimaryContainer = Color(0xFFCBE6FF)
val DarkSecondary = Color(0xFFB0C8DA)
val DarkOnSecondary = Color(0xFF17323F)
val DarkSecondaryContainer = Color(0xFF2C4759)
val DarkOnSecondaryContainer = Color(0xFFCCE5F7)
val DarkTertiary = Color(0xFFF3C57B)
val DarkOnTertiary = Color(0xFF3F2A00)
val DarkTertiaryContainer = Color(0xFF5C4200)
val DarkOnTertiaryContainer = Color(0xFFFFDEA8)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)
val DarkBackground = Color(0xFF0E1418)
val DarkOnBackground = Color(0xFFE2EAF0)
val DarkSurface = Color(0xFF0E1418)
val DarkOnSurface = Color(0xFFE2EAF0)
val DarkSurfaceVariant = Color(0xFF3B4A55)
val DarkOnSurfaceVariant = Color(0xFFBECEDA)
val DarkOutline = Color(0xFF8A9BA8)
val DarkOutlineVariant = Color(0xFF3E4E59)

/** 图表/图表旁白用的柱子、占比条也吃这套语义色：支出红、收入蓝（浅色）
 *  必须写在 LightPrimary / LightError 之后——Kotlin 顶层属性按声明顺序初始化，
 *  前向引用会报 Variable ... must be initialized。 */
val BrandIncomeBlue: Color = LightPrimary
val BrandExpenseRed: Color = LightError

/**
 * 浅色主题。这里**没有** dynamicLightColorScheme —— R4-L1 明确移除壁纸取色
 * （会改写品牌色和支出红/收入蓝的语义）。守门测试：LedgerThemeSourceTest。
 */
internal val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF6FD),
    surfaceContainer = Color(0xFFE9F2FB),
    surfaceContainerHigh = Color(0xFFE1EDF8),
    surfaceContainerHighest = Color(0xFFD8E7F5),
    inverseSurface = Color(0xFF2C3137),
    inverseOnSurface = Color(0xFFEFF1F4),
    inversePrimary = Color(0xFF9ACCF6),
    surfaceTint = LightPrimary,
)

/** 深色主题；primary 直接用品牌蓝 `#79C9FC`（深底上对比度 10.2:1） */
internal val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceContainerLowest = Color(0xFF090E12),
    surfaceContainerLow = Color(0xFF161E24),
    surfaceContainer = Color(0xFF1A232A),
    surfaceContainerHigh = Color(0xFF242E36),
    surfaceContainerHighest = Color(0xFF2E3941),
    inverseSurface = Color(0xFFE2EAF0),
    inverseOnSurface = Color(0xFF2B3138),
    inversePrimary = Color(0xFF0B5A8C),
    surfaceTint = DarkPrimary,
)
