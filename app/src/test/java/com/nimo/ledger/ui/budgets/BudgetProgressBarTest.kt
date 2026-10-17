package com.nimo.ledger.ui.budgets

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.nimo.ledger.domain.stats.BudgetStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 预算进度条下行的字体竖排（设计规范 §3.3 / G-6 / B-3 / 批 F 的 F-2）。
 *
 * 横排时「已用 ¥1,234.00 / ¥5,000.00」和「还剩 ¥3,766.00」各占一半宽度；
 * 2.0 倍字体下两段会互相挤压。大字时必须竖排。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BudgetProgressBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(fontScale: Float) {
        composeRule.setContent {
            val base = LocalDensity.current
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    BudgetProgressBar(spentCents = 123_400L, budgetCents = 500_000L, status = BudgetStatus.OK)
                }
            }
        }
    }

    private fun top(text: String): Float = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
    private fun bottom(text: String): Float = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.bottom

    private val usedText = "已用 ¥1,234.00 / ¥5,000.00"
    private val statusText = "还剩 ¥3,766.00"

    @Test
    fun atNormalFontScale_theTwoTextsShareARow() {
        show(fontScale = 1.0f)

        assertTrue("两段文字应同处一行", top(statusText) < bottom(usedText) - 1f)
    }

    @Test
    fun atLargeFontScale_theStatusGoesBelowTheUsedLine() {
        show(fontScale = 2.0f)

        assertTrue(
            "大字时状态行必须落在「已用」下面（实际 usedBottom=${bottom(usedText)} statusTop=${top(statusText)}）",
            top(statusText) >= bottom(usedText) - 1f,
        )
    }
}
