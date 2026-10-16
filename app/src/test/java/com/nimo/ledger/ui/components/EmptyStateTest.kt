package com.nimo.ledger.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.nimo.ledger.ui.theme.LedgerTheme
import com.nimo.ledger.ui.theme.ThemePreference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 空态小狗插图（R4-L1）：图在文字上方、纯装饰（读屏器不念图）、窄条里可以关掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class EmptyStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showEmptyState(illustration: Boolean = true) {
        composeRule.setContent {
            LedgerTheme(preference = ThemePreference.LIGHT) {
                EmptyState(
                    title = "本月还没有记录",
                    message = "点右下角「记一笔」开始",
                    illustration = illustration,
                )
            }
        }
    }

    @Test
    fun puppyIllustration_showsAboveTheTitleAndMessage() {
        showEmptyState()
        val image = composeRule.onNodeWithTag(EMPTY_STATE_ILLUSTRATION_TEST_TAG).getBoundsInRoot()
        val title = composeRule.onNodeWithText("本月还没有记录").getBoundsInRoot()
        composeRule.onNodeWithText("点右下角「记一笔」开始").assertIsDisplayed()
        assertTrue(
            "插图应在标题上方（插图底 ${image.bottom} vs 标题顶 ${title.top}）",
            image.bottom <= title.top,
        )
    }

    @Test
    fun illustration_isDecorative_onlyForTheEyes() {
        showEmptyState()
        composeRule.onNodeWithTag(EMPTY_STATE_ILLUSTRATION_TEST_TAG)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun illustration_canBeTurnedOffInATightStrip() {
        showEmptyState(illustration = false)
        composeRule.onNodeWithTag(EMPTY_STATE_ILLUSTRATION_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("本月还没有记录").assertIsDisplayed()
    }
}
