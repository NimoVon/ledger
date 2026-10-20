package com.nimo.ledger.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.nimo.ledger.ui.theme.ThemePreference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ST-4：主题三选一每行左侧要有 24dp 预览色块（跟随系统=半深半浅、浅色、深色各一块） */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ThemePreviewSwatchTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun eachThemeOption_hasItsOwnPreviewSwatch() {
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    ThemeOptionRow(ThemePreference.SYSTEM, selected = true, onSelect = {})
                    ThemeOptionRow(ThemePreference.LIGHT, selected = false, onSelect = {})
                    ThemeOptionRow(ThemePreference.DARK, selected = false, onSelect = {})
                }
            }
        }

        composeRule.onNodeWithTag(THEME_PREVIEW_SYSTEM_TEST_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(THEME_PREVIEW_LIGHT_TEST_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(THEME_PREVIEW_DARK_TEST_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
    }
}