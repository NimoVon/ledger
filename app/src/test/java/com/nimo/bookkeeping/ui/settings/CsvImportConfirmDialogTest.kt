package com.nimo.bookkeeping.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nimo.bookkeeping.domain.backup.CsvExporter
import com.nimo.bookkeeping.domain.backup.CsvImportCounts
import com.nimo.bookkeeping.domain.backup.CsvImportPreview
import com.nimo.bookkeeping.domain.backup.CsvRowProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CSV 导入前的预览/确认（F14 + 计划 line 312）。
 *
 * 这里守两件事：预览把「将写入多少行、跳过哪些行」摊开给用户看；只有按下「导入」才走写库那条路
 * （写库本身由 BackupRepositoryTest 覆盖，UI 只负责不替用户按下去）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CsvImportConfirmDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun preview(
        inserted: CsvImportCounts = CsvImportCounts(accounts = 1, transactions = 1),
        updated: CsvImportCounts = CsvImportCounts(transactions = 1),
        problems: List<CsvRowProblem> = emptyList(),
        missingFiles: List<String> = emptyList(),
    ) = CsvImportPreview(
        inserted = inserted,
        updated = updated,
        problems = problems,
        missingFiles = missingFiles,
    )

    private fun show(
        p: CsvImportPreview,
        fileNames: List<String> = listOf("accounts.csv", "transactions.csv"),
        onDismiss: () -> Unit = {},
        onConfirm: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                CsvImportConfirmDialog(
                    preview = p,
                    fileNames = fileNames,
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                )
            }
        }
    }

    @Test
    fun onlyConfirmGoesOnToWrite() {
        var confirmed = 0
        var dismissed = 0
        show(preview(), onDismiss = { dismissed++ }, onConfirm = { confirmed++ })

        composeRule.onNodeWithText("确认导入这些 CSV？").assertIsDisplayed()
        // 正文很长（Robolectric 的对话框窗口装不下整段），内容本身由 previewText 那个用例逐条断言
        composeRule.onNodeWithText("将写入 3 行（新增 2、覆盖 1）", substring = true).assertExists()
        composeRule.onNodeWithText("accounts.csv", substring = true).assertExists()
        assertEquals(0, confirmed)
        assertEquals(0, dismissed)

        composeRule.onNodeWithText("导入").performClick()

        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun cancel_writesNothing() {
        var confirmed = 0
        var dismissed = 0
        show(preview(), onDismiss = { dismissed++ }, onConfirm = { confirmed++ })

        composeRule.onNodeWithText("取消").performClick()

        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    @Test
    fun whenNothingToWrite_confirmIsDisabled() {
        var confirmed = 0
        show(
            preview(
                inserted = CsvImportCounts(),
                updated = CsvImportCounts(),
                missingFiles = listOf("budgets.csv"),
            ),
            onConfirm = { confirmed++ },
        )

        composeRule.onNodeWithText("这些 CSV 没有可写入的行").assertIsDisplayed()
        composeRule.onNodeWithText("导入").assertIsNotEnabled()
        assertEquals(0, confirmed)
    }

    @Test
    fun theText_putsEveryProblemOnItsOwnFileAndLine() {
        val text = previewText(
            preview(
                problems = listOf(
                    CsvRowProblem(
                        CsvExporter.TRANSACTIONS_FILE,
                        7,
                        "account_id 99 在本机数据和这个文件里都不存在",
                    ),
                ),
                missingFiles = listOf(CsvExporter.BUDGETS_FILE),
            ),
            listOf(CsvExporter.TRANSACTIONS_FILE),
        )

        assertTrue(text.contains("读到的文件：transactions.csv"))
        assertTrue(text.contains("会跳过 1 行："))
        assertTrue(text.contains("transactions.csv 第 7 行：account_id 99 在本机数据和这个文件里都不存在"))
        assertTrue(text.contains("没找到的文件：budgets.csv"))
    }
}
