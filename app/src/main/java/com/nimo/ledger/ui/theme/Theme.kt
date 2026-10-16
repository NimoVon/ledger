package com.nimo.ledger.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    surface = LightSurface,
    background = LightBackground,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    surface = DarkSurface,
    background = DarkBackground,
)

/**
 * 应用根主题（F12）。
 *
 * @param preference 外观偏好：SYSTEM 跟随系统深色开关（默认，与 F12 之前的行为一致），
 *   LIGHT / DARK 手动覆盖。由 [ThemePreference.isDark] 归一成「这次到底要不要深色」，
 *   避免「偏好」与「实际深浅」两个概念在各处各判一次。
 * @param darkTheme 系统当前的深色开关（[isSystemInDarkTheme]）；手动覆盖时它只是输入，不作准。
 */
@Composable
fun LedgerTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dark = preference.isDark(darkTheme)
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
