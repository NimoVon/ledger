package com.nimo.ledger.ui.transactions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 明细页日段头的字体竖排（设计规范 §3.3 / G-6 / 批 F 的 F-2）。
 *
 * 横排时日期拿 weight(1f)、「支出 ¥972.00」只能挤在剩下的宽度里；2.0 倍字体下会被折断。
 * 这里不看颜色也不看字号，只看几何：大字时日期必须在合计上面，小字时两者必须在同一行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TransactionsDayHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val group = TransactionDayGroup(
        date = "2026-10-17",
        label = "10月17日 周五",
        incomeCents = 0L,
        expenseCents = 97_200L,
        rows = emptyList(),
    )

    private fun show(fontScale: Float) {
        composeRule.setContent {
            val base = LocalDensity.current
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    DayHeader(group = group)
                }
            }
        }
    }

    private fun top(text: String): Float = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
    private fun bottom(text: String): Float = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.bottom

    @Test
    fun atNormalFontScale_totalsSitOnTheSameRowAsTheDate() {
        show(fontScale = 1.0f)

        // 同一行 = 垂直区间重叠（允许 1px 的排版抖动）
        assertTrue("合计应与日期同一行", top("支出 ¥972.00") < bottom("10月17日 周五") - 1f)
    }

    @Test
    fun atLargeFontScale_totalsGoBelowTheDate() {
        show(fontScale = 2.0f)

        assertTrue(
            "大字时合计必须落在日期下面（实际 dateBottom=${bottom("10月17日 周五")} totalTop=${top("支出 ¥972.00")}）",
            top("支出 ¥972.00") >= bottom("10月17日 周五") - 1f,
        )
    }
}
