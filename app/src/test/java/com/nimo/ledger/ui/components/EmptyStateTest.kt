package com.nimo.ledger.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nimo.ledger.ui.theme.LedgerTheme
import com.nimo.ledger.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 空态小狗插图（R4-L1）与三种语气（设计规范 §6.7，批 D · D-4）：
 * 图在文字上方、纯装饰（读屏器不念图）、窄条里可以关掉、错误/筛选空态用小一号的图、可选操作按钮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class EmptyStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var actionClicks = 0

    private fun showEmptyState(
        illustration: Boolean = true,
        tone: EmptyTone = EmptyTone.NEUTRAL,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            LedgerTheme(preference = ThemePreference.LIGHT) {
                EmptyState(
                    title = "本月还没有记录",
                    message = "点右下角「记一笔」开始",
                    illustration = illustration,
                    tone = tone,
                    actionLabel = actionLabel,
                    onAction = onAction,
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

    @Test
    fun aNeutralEmptyState_givesTheIllustrationTheFull120dp() {
        showEmptyState(tone = EmptyTone.NEUTRAL)
        val image = composeRule.onNodeWithTag(EMPTY_STATE_ILLUSTRATION_TEST_TAG).getBoundsInRoot()
        assertEquals(120f, (image.right - image.left).value, 0.5f)
        assertEquals(120f, (image.bottom - image.top).value, 0.5f)
    }

    @Test
    fun filteredEmptyState_shrinksTheIllustrationTo96dp() {
        // 筛选没命中是「暂时的」：图小一号，别把整屏让给一张插图
        showEmptyState(tone = EmptyTone.FILTERED)
        val image = composeRule.onNodeWithTag(EMPTY_STATE_ILLUSTRATION_TEST_TAG).getBoundsInRoot()
        assertEquals(96f, (image.right - image.left).value, 0.5f)
    }

    @Test
    fun errorEmptyState_shrinksTheIllustrationTo96dp() {
        showEmptyState(tone = EmptyTone.ERROR)
        val image = composeRule.onNodeWithTag(EMPTY_STATE_ILLUSTRATION_TEST_TAG).getBoundsInRoot()
        assertEquals(96f, (image.right - image.left).value, 0.5f)
        // 出错了也一样要能读标题与说明，不能只剩一张图
        composeRule.onNodeWithText("本月还没有记录").assertIsDisplayed()
        composeRule.onNodeWithText("点右下角「记一笔」开始").assertIsDisplayed()
    }

    @Test
    fun anActionButton_appearsAndRuns() {
        showEmptyState(actionLabel = "去记一笔", onAction = { actionClicks++ })
        composeRule.onNodeWithText("去记一笔").assertIsDisplayed().performClick()
        assertEquals(1, actionClicks)
    }

    @Test
    fun aLabelWithoutACallback_neverRendersADeadButton() {
        // actionLabel 有值但没人接：宁可不出按钮，也不给一个点了没反应的假入口
        showEmptyState(actionLabel = "去记一笔", onAction = null)
        composeRule.onNodeWithText("去记一笔").assertDoesNotExist()
    }
}
