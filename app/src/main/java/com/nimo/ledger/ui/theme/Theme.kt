package com.nimo.ledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 应用根主题（F12 外观偏好 + R4-L1 品牌色）。
 *
 * 品牌色是**静态**的：浅色 [LightColors] / 深色 [DarkColors] 两套写死在 Color.kt。
 * R4-L1 按计划移除了 Android 12+ 的壁纸动态取色分支 —— 取色会把图标里那抹淡蓝、
 * 以及「支出红 / 收入蓝」的语义色一并改写成壁纸的颜色，品牌就不叫品牌了。
 * 守门测试见 LedgerThemeSourceTest（源码级）与 LedgerPaletteTest（对比度级）。
 *
 * @param preference 外观偏好：SYSTEM 跟随系统深色开关（默认），LIGHT / DARK 手动覆盖。
 *   由 [ThemePreference.isDark] 归一成「这次到底要不要深色」。
 * @param darkTheme 系统当前的深色开关（[isSystemInDarkTheme]）；手动覆盖时它只是输入，不作准。
 */
@Composable
fun LedgerTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dark = preference.isDark(darkTheme)
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = LedgerShapes,
        content = content,
    )
}

/** 气泡类浮层的圆角（趋势图 / 环形图共用）：走容器档，不各写一个魔数 */
internal val TooltipShape = RoundedCornerShape(BRAND_SHAPE_CONTAINER_DP[0].dp)
