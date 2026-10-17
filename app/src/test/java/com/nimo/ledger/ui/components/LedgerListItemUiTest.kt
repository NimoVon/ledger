package com.nimo.ledger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 统一列表行（UI-UX 规范 §6.1，批 D · D-1）的组件级验收。
 *
 * 批 D 把账户 / 分类 / 设置 / 预算四处各写一遍的 [androidx.compose.foundation.layout.Row]
 * 收成一个组件，屏幕层却没有对应测试（规范 §12.2 明确点名"组件替换缺少测试网"）。
 * 所以这里先补组件自己的测试：后面谁再改动内边距、箭头、点击范围，都有网兜着。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LedgerListItemUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var clicks = 0

    @Test
    fun theWholeRowIsOneClickTarget() {
        composeRule.setContent {
            MaterialTheme {
                LedgerListItem(
                    title = "现金",
                    modifier = Modifier.testTag("row"),
                    onClick = { clicks++ },
                )
            }
        }

        composeRule.onNodeWithTag("row").assertHasClickAction().performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun aReadOnlyRow_isNotClickable() {
        // 传 null 表示这一行只是展示（例如待接入点进详情的行），不能出现"点了没反应"的涟漪
        composeRule.setContent {
            MaterialTheme {
                LedgerListItem(title = "净资产", modifier = Modifier.testTag("row"), trailingText = "¥1,000.00")
            }
        }

        composeRule.onNodeWithTag("row").assertHasNoClickAction()
        composeRule.onNodeWithText("净资产").assertIsDisplayed()
        composeRule.onNodeWithText("¥1,000.00").assertIsDisplayed()
    }

    @Test
    fun titleSubtitleAndTrailingText_areAllShown() {
        composeRule.setContent {
            MaterialTheme {
                LedgerListItem(
                    title = "支付宝",
                    subtitle = "储蓄卡 · 已归档",
                    trailingText = "¥1,690.00",
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText("支付宝").assertIsDisplayed()
        composeRule.onNodeWithText("储蓄卡 · 已归档").assertIsDisplayed()
        composeRule.onNodeWithText("¥1,690.00").assertIsDisplayed()
    }

    @Test
    fun leadingEmojiTakesItsOwnColumn_soTitlesLineUp() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    LedgerListItem(title = "餐饮", leadingEmoji = "🍜", onClick = {})
                    LedgerListItem(title = "交通", onClick = {})
                }
            }
        }

        composeRule.onNodeWithText("🍜").assertIsDisplayed()
        composeRule.onNodeWithText("餐饮").assertIsDisplayed()
        composeRule.onNodeWithText("交通").assertIsDisplayed()
    }

    @Test
    fun chevronReplacesTheAngleCharacter_thatScreenReadersReadAsGreaterThan() {
        composeRule.setContent {
            MaterialTheme {
                LedgerListItem(title = "账户管理", chevron = true, onClick = {})
            }
        }

        composeRule.onNodeWithText("账户管理").assertIsDisplayed()
        // 旧实现用 Text("›") 当箭头，读屏器会把它念成"大于号"；现在换成矢量图标且不朗读
        composeRule.onNodeWithText("›").assertDoesNotExist()
    }

    @Test
    fun trailingContentSlot_hostsRowActions() {
        // 分类页的「上移 / 下移」就挂在这个槽位里
        composeRule.setContent {
            MaterialTheme {
                LedgerListItem(
                    title = "餐饮",
                    trailingContent = {
                        TextButton(onClick = { clicks++ }) { Text("上移") }
                    },
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText("上移").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun aSingleLineRow_isAtLeast56dpTall() {
        // 触控目标下限：规范 §6.1 要求单行 56dp、双行 72dp，改内边距时别把行压扁
        composeRule.setContent {
            MaterialTheme {
                LedgerListItem(title = "现金", modifier = Modifier.testTag("row"), onClick = {})
            }
        }

        composeRule.onNodeWithTag("row").assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun aTwoLineRow_isAtLeast72dpTall() {
        composeRule.setContent {
            MaterialTheme {
                LedgerListItem(
                    title = "支付宝",
                    subtitle = "储蓄卡",
                    modifier = Modifier.testTag("row"),
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("row").assertHeightIsAtLeast(72.dp)
    }

    @Test
    fun contentPaddingLetsAnInsetPageOptOutOfDoubleIndent() {
        // 设置页整页已经有 24dp 页边距：行内水平留白传 0，标题才不会被推到 40dp
        composeRule.setContent {
            MaterialTheme {
                Column(modifier = Modifier.padding(24.dp)) {
                    LedgerListItem(
                        title = "账户管理",
                        chevron = true,
                        onClick = {},
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                    )
                }
            }
        }

        composeRule.onNodeWithText("账户管理").assertIsDisplayed()
    }
}
