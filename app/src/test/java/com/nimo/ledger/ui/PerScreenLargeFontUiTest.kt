package com.nimo.ledger.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.awaitUntil
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionInput
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.ui.accounts.AccountsScreen
import com.nimo.ledger.ui.components.LEDGER_AMOUNT_TEST_TAG
import com.nimo.ledger.ui.reports.ReportsScreen
import com.nimo.ledger.ui.transactions.TransactionsScreen
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 200% 文本缩放（设计规范 §10.5 逐屏清单 / §13 AA-8）的逐屏断言。
 *
 * 之前只有 MonthSummaryBar 与预算进度条各有一条 1.5~2.0 的断言，其余屏幕靠 E-4 / E-7 的
 * 真机 fontScale 走查截图——截图会过期，断言不会。这里对三张有真实数据驱动的页面
 * （明细 / 统计 / 账户）在 fontScale = 2.0 下量「关键文字没有互相压住、没有挤出屏幕」。
 *
 * 断言口径只认两件事：
 * ① 关键节点必须 `assertIsDisplayed()`；
 * ② 同一行里两个锚点的未裁剪边界不能相交（左锚右缘 <= 右锚左缘），且金额不越过可用内容宽。
 *
 * 走真实 Repository + in-memory Room，理由同 `ReportsInsightCardUiTest`：字号变大后
 * 「会不会挤」取决于真实行数与真实金额位数，假 state 撑不出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PerScreenLargeFontUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private val month: YearMonth = YearMonth.now()

    /** 411dp 屏宽扣掉页面左右各 16dp */
    private val rowAreaRight = 395.dp

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repository = LedgerRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun seedOneExpense(amountCents: Long = 500_000L) {
        runBlocking {
            val accountId = repository.createAccount("招商银行储蓄卡", AccountType.CASH, 0L).getOrThrow()
            val categoryId = db.categoryDao().upsert(
                CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, icon = "🍜", sortOrder = 0),
            )
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountCents = amountCents,
                    accountId = accountId,
                    categoryId = categoryId,
                    occurredOn = month.atDay(1).toString(),
                ),
            ).getOrThrow()
        }
    }

    /** 在 200% 字号下渲染；density 保持屏幕原值，只放大 fontScale */
    private fun showAt200(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2.0f)) {
                MaterialTheme { content() }
            }
        }
    }

    /** 精确文字节点的未裁剪边界（dp） */
    private fun boundsOfExactText(text: String): DpRect =
        composeRule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    /**
     * 金额节点边界：`LedgerAmount` 带 [LEDGER_AMOUNT_TEST_TAG]，同一页有多个；
     * 这里按「节点文本完全等于 [text]」挑，避免汇总金额与行内金额撞名（v1 曾用
     * `onNodeWithText(substring = true)`，结果匹配到同数字的汇总节点）。
     */
    private fun amountBounds(text: String): DpRect {
        val node = composeRule
            .onAllNodes(hasTestTag(LEDGER_AMOUNT_TEST_TAG) and hasText(text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single()
        val density = composeRule.density.density
        val rect = node.boundsInRoot
        return DpRect((rect.left / density).dp, (rect.top / density).dp, (rect.right / density).dp, (rect.bottom / density).dp)
    }

    private fun assertLeftOf(leftRightEdge: Dp, rightLeftEdge: Dp, label: String) {
        assertTrue(
            "$label：200% 字号下两个节点重叠（左锚右缘 $leftRightEdge，右锚左缘 $rightLeftEdge）",
            leftRightEdge <= rightLeftEdge,
        )
    }

    @Test
    fun transactionsScreen_at200_keepsCategoryNameAndAmountApart() {
        seedOneExpense()
        showAt200 { TransactionsScreen(repository = repository) }
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText("餐饮", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("餐饮", useUnmergedTree = true).assertIsDisplayed()
        val name = boundsOfExactText("餐饮")
        val amount = amountBounds("-¥5,000.00")

        assertLeftOf(name.right, amount.left, "明细行")
        assertTrue(
            "明细金额挤出内容区：右缘 ${amount.right} > $rowAreaRight",
            amount.right <= rowAreaRight + 1.dp,
        )
    }

    @Test
    fun reportsSectionHeader_at200_putsTheHintOnItsOwnLine() {
        seedOneExpense()
        showAt200 { ReportsScreen(repository = repository) }
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText("分类占比", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 2.0 字号下洞察卡 + 趋势图更高，分类占比段头会掉到首屏外——先滚到它再量
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("点一块看明细"))
        composeRule.waitForIdle()

        // 段头「分类占比」的 hint 是「点一块看明细」；1.0 时同一行右侧，2.0 时折到下一行
        val title = boundsOfExactText("分类占比")
        val hint = boundsOfExactText("点一块看明细")

        composeRule.onNodeWithText("点一块看明细", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "段头 hint 没折行：hint 顶 ${hint.top} 应 >= 标题底 ${title.bottom}",
            hint.top >= title.bottom - 1.dp,
        )
    }

    @Test
    fun accountsScreen_at200_keepsNameAndBalanceApart() {
        runBlocking {
            repository.createAccount("招商银行储蓄卡", AccountType.CASH, 123_456L).getOrThrow()
            // 第二个账户让「净资产」与单账户余额不撞数字，余额节点才唯一
            repository.createAccount("微信零钱", AccountType.CASH, 100_000L).getOrThrow()
        }
        showAt200 { AccountsScreen(repository = repository, onBack = {}) }
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText("¥1,234.56", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("招商银行储蓄卡", useUnmergedTree = true).assertIsDisplayed()
        val title = boundsOfExactText("招商银行储蓄卡")
        val balance = amountBounds("¥1,234.56")

        assertLeftOf(title.right, balance.left, "账户行")
        assertTrue(
            "账户余额挤出内容区：右缘 ${balance.right} > $rowAreaRight",
            balance.right <= rowAreaRight + 1.dp,
        )
    }
}
