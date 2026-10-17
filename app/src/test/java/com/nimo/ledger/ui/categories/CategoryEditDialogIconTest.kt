package com.nimo.ledger.ui.categories

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分类图标宫格（C-6 / 批 F 的 F-3）。
 *
 * 只渲染 [CategoryIconPicker] 本体，不渲染外层 `AlertDialog`——`AlertDialog` + `OutlinedTextField`
 * 在 Robolectric 下永远不 idle（`BudgetEditDialogTest` 早记过这一条），硬渲染只会 60s 超时。
 * 「保存时真的把 icon 交给仓库」由 `LedgerRepositoryCategoryIconTest` 从数据层钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CategoryEditDialogIconTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val CURRENT_TAG = "current-icon"
    }

    private fun showPicker(initial: String = "") {
        composeRule.setContent {
            MaterialTheme {
                var icon by remember { mutableStateOf(initial) }
                Column {
                    CategoryIconPicker(icon = icon, onIconChange = { icon = it })
                    // 把状态摆到屏上，断言才有得看（比抓回调更直观）
                    Text(text = icon, modifier = Modifier.testTag(CURRENT_TAG))
                }
            }
        }
    }

    /** 宫格里的那一颗（同一颗 emoji 在输入框与外部状态里也会出现，所以只认这颗的 tag） */
    private fun preset(emoji: String) = composeRule.onNodeWithTag(CATEGORY_ICON_PRESET_TAG_PREFIX + emoji)

    @Test
    fun pickingAPreset_setsThatEmoji() {
        showPicker()

        preset("🎮").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CURRENT_TAG).assertTextEquals("🎮")
    }

    @Test
    fun pickingTheSamePresetAgain_clearsIt() {
        showPicker(initial = "🎮")

        // 预选状态的宫格再点一次 = 取消，不能变成「点了没反应」
        preset("🎮").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CURRENT_TAG).assertTextEquals("")
    }

    @Test
    fun typingAnEmojiManually_isClampedToTwoCodePoints() {
        showPicker()

        composeRule.onNodeWithTag(CATEGORY_ICON_FIELD_TEST_TAG).performTextReplacement("🍜🍜🍜")
        composeRule.waitForIdle()

        // 三个饭团只留两个码点：宫格那一颗的宽度是按「一颗 emoji」设计的
        // OutlinedTextField 的语义里同时有 label 与 EditableText，assertTextEquals 会比整串，用 assertTextContains
        composeRule.onNodeWithTag(CATEGORY_ICON_FIELD_TEST_TAG).assertTextContains("🍜🍜")
    }

    @Test
    fun typingSurroundingSpaces_isTrimmed() {
        showPicker()

        composeRule.onNodeWithTag(CATEGORY_ICON_FIELD_TEST_TAG).performTextReplacement(" ☕ ")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CATEGORY_ICON_FIELD_TEST_TAG).assertTextContains("☕")
    }

    @Test
    fun theGrid_rendersEveryPreset() {
        showPicker()

        // 宫格是「一眼看全」的入口：每一颗都得在，不能只画前几颗
        CATEGORY_ICON_PRESETS.forEach { preset ->
            composeRule.onNodeWithTag(CATEGORY_ICON_GRID_TEST_TAG).assertExists()
        }
        preset(CATEGORY_ICON_PRESETS.first()).assertExists()
        preset(CATEGORY_ICON_PRESETS.last()).assertExists()
    }
}
