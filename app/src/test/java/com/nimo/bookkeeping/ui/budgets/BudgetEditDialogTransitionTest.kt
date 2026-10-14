package com.nimo.bookkeeping.ui.budgets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nimo.bookkeeping.ui.theme.LedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 编辑态（AlertDialog + OutlinedTextField）在 Robolectric 默认（PAUSED）Looper 模式下永远等不到 idle，
 * 会让 ComposeTestRule.setContent 卡在 AppNotIdleException。改成 LEGACY 模式后对话框能正常渲染，
 * 于是「编辑态点删除 → 确认态 → 真的删除」这条链路可以在 JVM 里跑完，不用只靠真机流程。
 * 实测：同一个用例在默认模式下 60 秒超时报 AppNotIdleException，在 LEGACY 模式下 5.9 秒通过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@LooperMode(LooperMode.Mode.LEGACY)
class BudgetEditDialogTransitionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var deleted = 0
    private var dismissed = 0

    private fun showDialog(saving: Boolean = false) {
        composeRule.setContent {
            LedgerTheme {
                BudgetEditDialog(
                    title = "总预算",
                    subtitle = "本月已支出 ¥600.00。",
                    currentAmountCents = 100_000L,
                    saving = saving,
                    errorMessage = null,
                    onDismiss = { dismissed++ },
                    onSave = { },
                    onDelete = { deleted++ },
                )
            }
        }
    }

    @Test
    fun delete_asksFirst_andOnlyTheConfirmDeletes() {
        showDialog()
        composeRule.onNodeWithText("预算金额").assertIsDisplayed()

        composeRule.onNodeWithText("删除").performClick()

        composeRule.onNodeWithText("删除这条预算？").assertIsDisplayed()
        composeRule.onNodeWithText("预算金额").assertDoesNotExist()
        assertEquals("点第一下删除不该真的删", 0, deleted)

        composeRule.onNodeWithText("删除").performClick()

        assertEquals("确认删除后才调用 onDelete", 1, deleted)
    }

    @Test
    fun cancelInConfirm_goesBackToEditingAndKeepsTheBudget() {
        showDialog()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.onNodeWithText("删除这条预算？").assertIsDisplayed()

        composeRule.onNodeWithText("取消").performClick()

        composeRule.onNodeWithText("删除这条预算？").assertDoesNotExist()
        composeRule.onNodeWithText("预算金额").assertIsDisplayed()
        assertEquals("确认态取消不该删预算", 0, deleted)
        assertEquals("确认态取消只是回编辑态，不该关掉对话框", 0, dismissed)
    }

    @Test
    fun whileSaving_theConfirmStepCannotBeOpened() {
        showDialog(saving = true)
        composeRule.onNodeWithText("删除").assertIsNotEnabled()
        composeRule.onNodeWithText("取消").assertIsNotEnabled()
    }
}
