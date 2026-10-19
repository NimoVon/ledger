package com.nimo.ledger.ui.transactions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.nimo.ledger.domain.model.TransactionType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 明细页日段头的字体竖排（设计规范 §3.3 / G-6 / 批 F 的 F-2）与当日笔数（T-6）。
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

    /** T-6：段头要显示「N 笔」，N 就是这一段里的流水条数 */
    private val groupWithRows = group.copy(
        rows = List(3) { index ->
            TransactionRow(
                id = index.toLong(),
                type = TransactionType.EXPENSE,
                categoryIcon = "🍜",
                categoryName = "餐饮",
                note = null,
                accountLabel = "现金",
                amountCents = 32_400L,
                occurredOn = "2026-10-17",
            )
        },
    )

    private fun show(dayGroup: TransactionDayGroup = group, fontScale: Float = 1.0f) {
        composeRule.setContent {
            val base = LocalDensity.current
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    DayHeader(group = dayGroup)
                }
            }
        }
    }

    private fun top(text: String): Float = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
    private fun bottom(text: String): Float = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.bottom

    @Test
    fun atNormalFontScale_totalsSitOnTheSameRowAsTheDate() {
        show()

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

    @Test
    fun dayHeader_showsHowManyEntriesThatDayHas() {
        show(dayGroup = groupWithRows)

        composeRule.onNodeWithText("3 笔").assertIsDisplayed()
        // 小字时笔数与日期同一行：分隔点、合计、笔数都在右侧那一列里
        assertTrue("笔数应与日期同一行", top("3 笔") < bottom("10月17日 周五") - 1f)
    }

    @Test
    fun dayHeader_atLargeFontScale_countGoesBelowTheDate() {
        show(dayGroup = groupWithRows, fontScale = 2.0f)

        assertTrue(
            "大字时笔数必须落在日期下面（实际 dateBottom=${bottom("10月17日 周五")} countTop=${top("3 笔")}）",
            top("3 笔") >= bottom("10月17日 周五") - 1f,
        )
    }
}
