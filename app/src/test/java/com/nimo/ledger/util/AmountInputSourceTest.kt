package com.nimo.ledger.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全角归一化的接线回归网（测试报告 §23）：[Money.normalizeAmountInput] 本身有单测，
 * 但真正会漏的是"某个输入框忘了调它"——那种漏法在画面上毫无痕迹，只有换个输入法才发作。
 *
 * 这里用源码级不变量把七个金额输入框都钉住：每个金额键盘（KeyboardType.Decimal）所在的文件，
 * 要么自己就地调 normalizeAmountInput，要么把 onValueChange 交给 ViewModel 的 setAmount，
 * 而两个 ViewModel 的 setAmount 必须在归一化后才写入表单。
 *
 * 为什么不渲染每个对话框做行为测试：Robolectric 下 Dialog 窗口的 idling 不稳（同 DialogTitleSourceTest
 * 的取舍），而且这里要守的是"接线对不对"，不是"弹窗能不能画"。真机证据见 §23.4。
 */
class AmountInputSourceTest {

    private val repoRoot: File = run {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        requireNotNull(dir) { "找不到仓库根目录（settings.gradle.kts）" }
    }

    private fun source(relativePath: String): String =
        File(repoRoot, "app/src/main/java/com/nimo/ledger/" + relativePath).readText()

    private fun occurrences(text: String, needle: String): Int =
        text.windowed(needle.length).count { it == needle }

    /** 文件 → 该文件里金额键盘（KeyboardType.Decimal）的数量 */
    private val amountFieldFiles = mapOf(
        "ui/record/RecordScreen.kt" to 1,
        "ui/transfer/TransferScreen.kt" to 1,
        "ui/accounts/AccountEditDialog.kt" to 1,
        "ui/accounts/BalanceAdjustDialog.kt" to 1,
        "ui/budgets/BudgetEditDialog.kt" to 1,
        "ui/transactions/TransactionFilterDialog.kt" to 2,
    )

    @Test
    fun everyAmountKeyboard_isWiredToTheNormalizer() {
        for ((path, expectedFields) in amountFieldFiles) {
            val text = source(path)
            assertEquals("$path 的金额键盘数量变了，请同步这张表", expectedFields, occurrences(text, "KeyboardType.Decimal"))
            assertTrue(
                "$path 的金额输入没有走 Money.normalizeAmountInput（就地调用或经 viewModel::setAmount）",
                text.contains("normalizeAmountInput") || text.contains("::setAmount"),
            )
        }
    }

    @Test
    fun theTwoFormViewModels_normalizeBeforeStoringTheText() {
        for (path in listOf("ui/record/RecordViewModel.kt", "ui/transfer/TransferViewModel.kt")) {
            val text = source(path)
            assertEquals("$path 的 setAmount 必须原地归一化", 1, occurrences(text, "amountText = Money.normalizeAmountInput(text)"))
            // 旧写法（直接把原始文本塞进表单）必须绝迹，否则全角又漏回来
            assertEquals("$path 仍在直接写入原始输入", 0, occurrences(text, "amountText = text) }"))
        }
    }

    @Test
    fun theNormalizerItself_keepsItsStrictParsingCounterpart() {
        // 归一化是"字符替换"，不是"让解析变宽容"：parseCents 的严格口径不能改
        val money = source("util/Money.kt")
        assertTrue(money.contains("fun normalizeAmountInput(input: String): String"))
        assertTrue(money.contains("private val AMOUNT_RE = Regex"))
    }
}
