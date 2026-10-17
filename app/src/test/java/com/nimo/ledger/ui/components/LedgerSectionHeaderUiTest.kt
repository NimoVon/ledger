package com.nimo.ledger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通栏段头（UI-UX 规范 §6.2，批 D · D-2）的组件级验收。
 *
 * 统计页的「收入 / 支出 / 结余」段头与明细页的日段头是同一种东西，
 * 现在共用 [LedgerSectionHeader]：这个测试锁住"标题在左、提示在右、提示可省略"三条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LedgerSectionHeaderUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun titleAndHint_areBothShown() {
        composeRule.setContent {
            MaterialTheme {
                LedgerSectionHeader(title = "支出排行", hint = "点一行看明细")
            }
        }

        composeRule.onNodeWithText("支出排行").assertIsDisplayed()
        composeRule.onNodeWithText("点一行看明细").assertIsDisplayed()
    }

    @Test
    fun withoutAHint_onlyTheTitleIsShown() {
        // 「收支趋势」这种段头没有可点的行，不该硬塞一句提示
        composeRule.setContent {
            MaterialTheme {
                LedgerSectionHeader(title = "收支趋势")
            }
        }

        composeRule.onNodeWithText("收支趋势").assertIsDisplayed()
        composeRule.onNodeWithText("点一行看明细").assertDoesNotExist()
    }

    @Test
    fun theTrailingSlot_hostsTheDayTotals() {
        // 明细页的日段头要同时给「支出 X」和「收入 Y」，hint 一个字符串装不下
        composeRule.setContent {
            MaterialTheme {
                LedgerSectionHeader(
                    title = "10月17日 周五",
                    trailing = {
                        Column {
                            Text("支出 ¥25.00")
                            Text("收入 ¥0.00")
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("10月17日 周五").assertIsDisplayed()
        composeRule.onNodeWithText("支出 ¥25.00").assertIsDisplayed()
        composeRule.onNodeWithText("收入 ¥0.00").assertIsDisplayed()
    }

    @Test
    fun hintAndTrailing_canCoexist() {
        composeRule.setContent {
            MaterialTheme {
                LedgerSectionHeader(
                    title = "区间洞察",
                    hint = "日均 / 环比",
                    trailing = { Text("重置") },
                )
            }
        }

        composeRule.onNodeWithText("区间洞察").assertIsDisplayed()
        composeRule.onNodeWithText("日均 / 环比").assertIsDisplayed()
        composeRule.onNodeWithText("重置").assertIsDisplayed()
    }
}
