package com.nimo.ledger.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「关于」组的组件级验收（UI-UX 规范 §7.4 · ST-1 / ST-2）。
 *
 * 设置页整体要 AppContainer（Room + DataStore）才能组装，所以把这一组抽成
 * [SettingsAboutGroup]：组标题、卡片、折叠行、版本行都在里面，展开状态与自检结果
 * 由调用方持有，测试里直接用 Compose 测试规则驱动，不碰数据库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsAboutSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val selfCheckLines = listOf(
        "数据库 user_version: v8",
        "M0 自检通过：Room + KSP + SchemaHardening 全链路生效",
    )

    private fun content(expanded: Boolean, lines: List<String>?, onToggle: () -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                SettingsAboutGroup(
                    expanded = expanded,
                    onToggleExpanded = onToggle,
                    selfCheckLines = lines,
                )
            }
        }
    }

    @Test
    fun collapsedByDefault_hidesSelfCheckOutput() {
        content(expanded = false, lines = selfCheckLines, onToggle = {})

        composeRule.onNodeWithText("关于").assertIsDisplayed()
        composeRule.onNodeWithText("开发者选项（自检）").assertIsDisplayed()
        composeRule.onAllNodesWithText("数据库 user_version: v8").assertCountEquals(0)
        composeRule.onAllNodesWithText("M0 自检通过：Room + KSP + SchemaHardening 全链路生效")
            .assertCountEquals(0)
    }

    @Test
    fun versionLine_isTheLastRowWithoutMilestoneText() {
        content(expanded = false, lines = selfCheckLines, onToggle = {})

        composeRule.onNodeWithText("记账本 · 0.1.0").assertIsDisplayed()
        // 版本行进页脚（不再占卡片一行）
        composeRule.onNodeWithTag(BRAND_MARK_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
        // ST-2：里程碑信息（M1 数据层）不进用户界面
        composeRule.onAllNodesWithText("已完成 M1（数据层）", substring = true).assertCountEquals(0)
    }

    @Test
    fun tappingTheRow_expandsAndShowsSelfCheckOutput() {
        composeRule.setContent {
            MaterialTheme {
                var expanded by remember { mutableStateOf(false) }
                SettingsAboutGroup(
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    selfCheckLines = selfCheckLines,
                )
            }
        }

        composeRule.onNodeWithText("开发者选项（自检）").performClick()

        composeRule.onNodeWithText("数据库 user_version: v8").assertIsDisplayed()
        composeRule.onNodeWithText("M0 自检通过：Room + KSP + SchemaHardening 全链路生效")
            .assertIsDisplayed()

        // 再点一次折回去：输出消失，行仍在
        composeRule.onNodeWithText("开发者选项（自检）").performClick()
        composeRule.onAllNodesWithText("数据库 user_version: v8").assertCountEquals(0)
    }

    @Test
    fun expandedWithoutResultYet_showsRunningPlaceholder() {
        content(expanded = true, lines = null, onToggle = {})

        composeRule.onNodeWithText("自检运行中…").assertIsDisplayed()
    }

    @Test
    fun brandMark_isOnTheAboutGroup_andSpeaksItsName() {
        content(expanded = false, lines = null, onToggle = {})

        // P3-5：品牌徽章是标识不是装饰，读屏要念「记账本」（§5.2）
        composeRule.onNodeWithContentDescription("记账本", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(BRAND_MARK_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun toggleCallbackIsInvokedOncePerTap() {
        var taps = 0
        content(expanded = false, lines = null, onToggle = { taps++ })

        composeRule.onNodeWithText("开发者选项（自检）").performClick()

        assertEquals(1, taps)
    }
}
