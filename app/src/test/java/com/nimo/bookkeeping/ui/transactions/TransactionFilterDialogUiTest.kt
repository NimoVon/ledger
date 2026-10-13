package com.nimo.bookkeeping.ui.transactions

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 筛选面板内容区（F06）的 UI 回归守卫——计划 6.1「UI 测试 → Compose UI Test」的落点。
 *
 * 守的是 [FilterDialogBody] 里那条修饰符顺序：先 heightIn 限高、再 verticalScroll 挂滚动。
 * 顺序写反时下半截会被裁掉、滚动范围为 0，用户选不到金额/日期/关键词——这个缺陷真机出过，
 * 纯逻辑单测抓不到（顺带验证过：把顺序反过来，本用例确实会失败）。
 *
 * 面板的 AlertDialog 外壳不能在 Robolectric 里渲染（里面的 OutlinedTextField 会让
 * Compose 永远不 idle，Espresso 报 AppNotIdleException），所以这里只渲染内容容器本身；
 * 面板的交互（勾选、金额校验、应用）由 TransactionFilterValidationTest 与真机验证覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TransactionFilterDialogUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theLastFieldOfThePanel_canBeScrolledIntoView() {
        composeRule.setContent {
            MaterialTheme {
                FilterDialogBody {
                    Text("账户（可多选）")
                    Text("CMB")
                    Text("分类（可多选）")
                    Text("🎮 娱乐")
                    Text("金额区间（元）")
                    Text("最低")
                    Text("最高")
                    Text("日期区间")
                    Text("起始")
                    Text("结束")
                    Text("备注关键词")
                    Text("包含文字")
                }
            }
        }

        // 面板最底下的那一行：顺序写反时它永远滚不进视野
        composeRule.onNodeWithText("包含文字").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("备注关键词").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("账户（可多选）").performScrollTo().assertIsDisplayed()
    }
}
