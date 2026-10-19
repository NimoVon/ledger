package com.nimo.ledger.ui.transactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T-5：筛选条左侧的搜索图标（设计规范 §12 T-5）。
 *
 * 只测「点了走哪条路」——搜索图标与「筛选」按钮都开同一个面板，
 * 区别在 `onSearch` 会让面板里的关键词框直接拿焦点（聚焦本身由真机与对话框测试覆盖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FilterSearchShortcutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun searchIcon_isInTheBar_andCallsTheSearchEntry() {
        var searched = 0
        var opened = 0
        composeRule.setContent {
            FilterBar(
                chips = emptyList(),
                onOpen = { opened++ },
                onSearch = { searched++ },
                onClear = {},
            )
        }

        composeRule.onNodeWithContentDescription("搜索").assertIsDisplayed().performClick()

        assertEquals(1, searched)
        assertEquals(0, opened)
    }

    @Test
    fun filterButton_stillOpensThePlainEntry() {
        var searched = 0
        var opened = 0
        composeRule.setContent {
            FilterBar(
                chips = listOf("分类：餐饮"),
                onOpen = { opened++ },
                onSearch = { searched++ },
                onClear = {},
            )
        }

        composeRule.onNodeWithText("筛选").performClick()

        assertEquals(1, opened)
        assertEquals(0, searched)
    }
}
