package com.nimo.ledger.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.nimo.ledger.domain.stats.PeriodSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 当月合计三格（F15）：字体放到 2.0 时，一行三格的宽度不够，「结余」的金额会被折行断成两半
 * （真机取证：金额节点高 316px = 两行）。所以字体倍数达到门槛后改成竖排，金额整行展示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MonthSummaryBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 只改字体倍数，屏幕密度仍是设备真实值——否则"三格够不够宽"这个前提就变了 */
    private fun showAt(fontScale: Float) {
        composeRule.setContent {
            MaterialTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = fontScale),
                ) {
                    MonthSummaryBar(
                        summary = PeriodSummary(incomeCents = 0L, expenseCents = 60_000L),
                        loading = false,
                    )
                }
            }
        }
    }

    @Test
    fun theThreshold_sitsBetweenTheStandardFontStepsThatStillFit_andTheOneThatDoesNot() {
        assertFalse(stackSummaryCells(1.0f))
        assertFalse(stackSummaryCells(1.3f))
        assertTrue(stackSummaryCells(1.5f))
        assertTrue(stackSummaryCells(2.0f))
    }

    @Test
    fun eachCellExposesSpokenChineseToTalkBack() {
        showAt(1.0f)

        // AA-5：三格各自读「支出 600 元」「收入 0 元」「结余 负 600 元」，
        // 而不是把 "¥600.00" 逐字念成「¥ 六 零 零 点 零 零」
        composeRule.onNodeWithContentDescription("支出 600 元").assertExists()
        composeRule.onNodeWithContentDescription("收入 0 元").assertExists()
        composeRule.onNodeWithContentDescription("结余 负 600 元").assertExists()
    }    @Test
    fun atNormalFontScale_theThreeCellsShareOneRow() {
        showAt(1.0f)

        val expense = composeRule.onNodeWithText("支出").getBoundsInRoot()
        val income = composeRule.onNodeWithText("收入").getBoundsInRoot()
        val net = composeRule.onNodeWithText("结余").getBoundsInRoot()

        assertEquals(expense.top.value, income.top.value, 0.5f)
        assertEquals(income.top.value, net.top.value, 0.5f)
        assertTrue("支出在左、收入在中、结余在右", expense.left.value < income.left.value)
        assertTrue("支出在左、收入在中、结余在右", income.left.value < net.left.value)
    }

    @Test
    fun atExtraLargeFontScale_theCellsStack_soAmountsAreNotBrokenInHalf() {
        showAt(2.0f)

        val expense = composeRule.onNodeWithText("支出").getBoundsInRoot()
        val income = composeRule.onNodeWithText("收入").getBoundsInRoot()
        val net = composeRule.onNodeWithText("结余").getBoundsInRoot()

        assertEquals(expense.left.value, income.left.value, 0.5f)
        assertEquals(income.left.value, net.left.value, 0.5f)
        assertTrue("竖排后收入应整格排在支出下方", income.top.value >= expense.bottom.value)
        assertTrue("竖排后结余应整格排在收入下方", net.top.value >= income.bottom.value)
    }
}
