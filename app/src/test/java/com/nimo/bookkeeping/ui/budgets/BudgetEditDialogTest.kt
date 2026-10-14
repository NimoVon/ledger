package com.nimo.bookkeeping.ui.budgets

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 删除预算的二次确认（F15）——计划「关键操作二次确认」的落点。
 *
 * 删除预算既不可撤销、也不进撤销队列（删除流水才有 5 秒撤销窗口），所以点「删除」必须只切到
 * 确认态、确认态再点「删除」才真删。这里守的是确认态本身：文案、以及两个按钮各自回到哪个回调。
 *
 * 只渲染确认态，不渲染编辑态：AlertDialog 里带 OutlinedTextField 在 Robolectric 里永远不 idle
 * （app/src/test/java/com/nimo/bookkeeping/ui/transactions/TransactionFilterDialogUiTest.kt:22-24 记着这条），
 * 「编辑态点删除 → 进确认态」这一步由真机流程验证覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BudgetEditDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showConfirm(saving: Boolean = false, onDismiss: () -> Unit = {}, onConfirm: () -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                BudgetDeleteConfirmDialog(saving = saving, onDismiss = onDismiss, onConfirm = onConfirm)
            }
        }
    }

    @Test
    fun theConfirmDialog_saysWhatGetsLostAndWhatDoesNot() {
        var confirmed = 0
        showConfirm(onConfirm = { confirmed++ })

        composeRule.onNodeWithText("删除这条预算？").assertIsDisplayed()
        composeRule.onNodeWithText("超支提示也会一起消失", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("已经记下的流水不受影响", substring = true).assertIsDisplayed()

        // 确认态下「删除」只有一个节点：编辑态那个删除按钮这时不该还在
        composeRule.onNodeWithText("删除").assertIsDisplayed()
        org.junit.Assert.assertEquals(0, confirmed)

        composeRule.onNodeWithText("删除").performClick()
        org.junit.Assert.assertEquals(1, confirmed)
    }

    @Test
    fun cancel_leavesTheBudgetAlone() {
        var dismissed = 0
        var confirmed = 0
        showConfirm(onDismiss = { dismissed++ }, onConfirm = { confirmed++ })

        composeRule.onNodeWithText("取消").performClick()

        org.junit.Assert.assertEquals(1, dismissed)
        org.junit.Assert.assertEquals(0, confirmed)
    }

    @Test
    fun whileSaving_bothButtonsAreDisabled() {
        var dismissed = 0
        var confirmed = 0
        showConfirm(saving = true, onDismiss = { dismissed++ }, onConfirm = { confirmed++ })

        composeRule.onNodeWithText("删除").assertIsNotEnabled()
        composeRule.onNodeWithText("取消").assertIsNotEnabled()

        org.junit.Assert.assertEquals(0, dismissed)
        org.junit.Assert.assertEquals(0, confirmed)
    }
}
