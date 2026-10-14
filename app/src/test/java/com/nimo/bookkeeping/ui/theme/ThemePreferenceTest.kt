package com.nimo.bookkeeping.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观偏好（F12）的纯逻辑：三选一怎么落成「到底深色不深色」。
 * 不带 Android 依赖，不需要 Robolectric。
 */
class ThemePreferenceTest {

    @Test
    fun followTheSystem_takesTheSystemFlag() {
        assertTrue(ThemePreference.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemePreference.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun light_overridesASystemDarkTheme() {
        assertFalse(ThemePreference.LIGHT.isDark(systemDark = true))
        assertFalse(ThemePreference.LIGHT.isDark(systemDark = false))
    }

    @Test
    fun dark_overridesASystemLightTheme() {
        assertTrue(ThemePreference.DARK.isDark(systemDark = false))
        assertTrue(ThemePreference.DARK.isDark(systemDark = true))
    }

    @Test
    fun fromStorage_readsEveryOwnValueBack() {
        ThemePreference.entries.forEach { preference ->
            assertEquals(preference, ThemePreference.fromStorage(preference.storageValue))
        }
    }

    @Test
    fun fromStorage_fallsBackToFollowingTheSystem() {
        // null（从没设置过）、空串、大小写不符、老版本残留的别的值
        listOf(null, "", " ", "SYSTEM", "Dark", "auto", "follow-system", "1")
            .forEach { raw ->
                assertEquals("raw=" + raw, ThemePreference.SYSTEM, ThemePreference.fromStorage(raw))
            }
    }

    @Test
    fun everyOption_hasAStableStorageValueAndAReadableLabel() {
        assertEquals(listOf("system", "light", "dark"), ThemePreference.entries.map { it.storageValue })
        assertEquals(
            listOf("跟随系统", "浅色", "深色"),
            ThemePreference.entries.map { it.label },
        )
        assertEquals("跟随系统", ThemePreference.SYSTEM.label)
    }
}
