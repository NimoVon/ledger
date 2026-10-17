package com.nimo.ledger.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 年月选择器的两屏交互（R1 日期直改）：
 * 年份一屏可选 → 选中年进月份格 → 选中月回调目标年月。
 *
 * 只渲染内容容器（YearMonthPickerBody），外壳 AlertDialog 由真机走查覆盖：
 * 与 FilterDialogBody 同一处理方式，避免 Robolectric 下的 idling 问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class YearMonthPickerBodyUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var picked: YearMonth? = null

    private fun showPicker(current: YearMonth) {
        composeRule.setContent {
            MaterialTheme {
                YearMonthPickerBody(current = current, onSelect = { picked = it })
            }
        }
    }

    @Test
    fun twoTapsFromTheYearScreen_reachThePreviousYearsDecember() {
        // 跨年往回：现在看 2026-01，两下拿到 2025-12
        showPicker(YearMonth.of(2026, 1))

        composeRule.onNodeWithText("2025").assertIsDisplayed().performClick()
        // 第二步：月份格（选中 2025 之后标题变成 2025年）
        composeRule.onNodeWithText("2025年").assertIsDisplayed()
        composeRule.onNodeWithText("12月").assertIsDisplayed().performClick()

        assertEquals(YearMonth.of(2025, 12), picked)
    }

    @Test
    fun twoTapsFromTheYearScreen_reachTheNextYearsJanuary() {
        // 跨年往前：现在看 2026-12，两下拿到 2027-01
        showPicker(YearMonth.of(2026, 12))

        composeRule.onNodeWithText("2027").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("1月").assertIsDisplayed().performClick()

        assertEquals(YearMonth.of(2027, 1), picked)
    }

    @Test
    fun allTwentyYearsOfThePage_areVisibleWithoutScrolling() {
        showPicker(YearMonth.of(2026, 10))

        // "年份一屏可选"：这一屏上的 20 个年份都得看得见，不能靠滚动去找
        YearMonthPicker.years(2026).forEach { year ->
            composeRule.onNodeWithText(year.toString()).assertIsDisplayed()
        }
        assertEquals("2020–2039", YearMonthPicker.pageLabel(2026))
    }

    @Test
    fun everyMonthCell_meetsThe44dpTouchTarget() {
        // AA-2：40dp 的格子低于 44dp 的最小触控目标，手指按界上下两格容易点错
        showPicker(YearMonth.of(2026, 10))
        composeRule.onNodeWithText("2026").performClick()

        composeRule.onNode(hasText("11月") and hasClickAction()).assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun pagingForward_showsTheNextTwentyYears() {
        showPicker(YearMonth.of(2026, 10))

        composeRule.onNodeWithContentDescription("后二十年").performClick()

        composeRule.onNodeWithText("2040").assertIsDisplayed()
        composeRule.onNodeWithText("2020").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("前二十年").performClick()
        composeRule.onNodeWithText("2020").assertIsDisplayed()
    }

    @Test
    fun goingBack_fromTheMonthScreen_returnsToTheSameYearScreen() {
        showPicker(YearMonth.of(2026, 10))

        composeRule.onNodeWithText("2026").performClick()
        composeRule.onNodeWithText("11月").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("返回选择年份").performClick()

        // 选错年份不用关掉重开：回到年份屏，当前年月还是标在 2026 上
        composeRule.onNodeWithText("2026").assertIsDisplayed()
        composeRule.onNodeWithText("11月").assertDoesNotExist()
    }

    @Test
    fun theMonthLabel_isClickableOnlyWhenThePickerIsWired_andArrowsKeepTheirOwnJobs() {
        var labelClicks = 0
        var previousClicks = 0
        var nextClicks = 0
        composeRule.setContent {
            MaterialTheme {
                MonthSwitcher(
                    label = "2026年10月",
                    onPrevious = { previousClicks++ },
                    onNext = { nextClicks++ },
                    onLabelClick = { labelClicks++ },
                )
            }
        }

        composeRule.onNodeWithText("2026年10月").assertHasClickAction().performClick()
        composeRule.onNodeWithContentDescription("上个月").performClick()
        composeRule.onNodeWithContentDescription("下个月").performClick()

        assertEquals(1, labelClicks)
        // 箭头没有被标签的点击吞掉：上下月还是各按各的
        assertEquals(1, previousClicks)
        assertEquals(1, nextClicks)
    }

    @Test
    fun withoutAWiredPicker_theMonthLabel_isNotClickable() {
        // 预算页（F08）还没接入年月直选：标签保持只读，不能出现"点了没反应"的死区
        composeRule.setContent {
            MaterialTheme {
                MonthSwitcher(label = "2026年10月", onPrevious = {}, onNext = {})
            }
        }

        composeRule.onNodeWithText("2026年10月").assertHasNoClickAction()
    }
}
