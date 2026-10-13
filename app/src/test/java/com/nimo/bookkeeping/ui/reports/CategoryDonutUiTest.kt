package com.nimo.bookkeeping.ui.reports

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionType
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分类占比环形图（F10）的界面行为（计划 6.1「UI 测试 → Compose UI Test」的落点）。
 *
 * 画布上没有可读文字，用无障碍描述认它（与趋势折线同一套做法）；
 * 环心是"支出合计 + 金额"合一的节点，占比的明细由下面的「支出排行」承担——
 * 所以这里断言三件事：环画出来了、环心等于两个分类之和、排行把占比写在行里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CategoryDonutUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

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

    /** 本月两笔支出：娱乐 ¥600（75%）、餐饮 ¥200（25%） */
    private fun seedTwoExpenses() {
        val month = YearMonth.now()
        runBlocking {
            val accountId = repository.createAccount("CMB", AccountType.CASH, 0L).getOrThrow()
            val entertainmentId = db.categoryDao().upsert(
                CategoryEntity(name = "娱乐", kind = CategoryKind.EXPENSE, icon = "🎮", sortOrder = 0),
            )
            val diningId = db.categoryDao().upsert(
                CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, icon = "🍜", sortOrder = 1),
            )
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountCents = 60_000L,
                    accountId = accountId,
                    categoryId = entertainmentId,
                    occurredOn = month.atDay(1).toString(),
                ),
            ).getOrThrow()
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountCents = 20_000L,
                    accountId = accountId,
                    categoryId = diningId,
                    occurredOn = month.atDay(2).toString(),
                ),
            ).getOrThrow()
        }
    }

    /** 本月只有一笔收入：有流水、但没有支出可分类 */
    private fun seedIncomeOnly() {
        val month = YearMonth.now()
        runBlocking {
            val accountId = repository.createAccount("CMB", AccountType.CASH, 0L).getOrThrow()
            val salaryId = db.categoryDao().upsert(
                CategoryEntity(name = "工资", kind = CategoryKind.INCOME, icon = "💼", sortOrder = 0),
            )
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.INCOME,
                    amountCents = 100_000L,
                    accountId = accountId,
                    categoryId = salaryId,
                    occurredOn = month.atDay(1).toString(),
                ),
            ).getOrThrow()
        }
    }

    private fun showReports() {
        composeRule.setContent {
            MaterialTheme { ReportsScreen(repository = repository) }
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theDonut_rendersTheShares_andItsCenterMatchesTheTotal() {
        seedTwoExpenses()
        showReports()
        awaitText("日均支出")

        // 环形图在趋势折线下面：让列表自己把它滚进视野
        val list = composeRule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("分类占比"))
        composeRule.onNodeWithText("分类占比").assertIsDisplayed()

        list.performScrollToNode(hasTestTag(DONUT_TEST_TAG))
        composeRule.onNodeWithContentDescription(DONUT_DESCRIPTION).assertIsDisplayed()
        // 环心 = 两个分类之和，和顶部「支出」是同一个数
        composeRule.onNodeWithTag(DONUT_CENTER_TEST_TAG).assertTextContains("支出合计")
        composeRule.onNodeWithTag(DONUT_CENTER_TEST_TAG).assertTextContains("¥800.00", substring = true)

        // 图例就是下面的「支出排行」：占比写在行里（占比条与环形图同一套颜色）
        list.performScrollToNode(hasText("75.0%", substring = true))
        composeRule.onNode(hasClickAction() and hasText("75.0%", substring = true)).assertIsDisplayed()
        list.performScrollToNode(hasText("25.0%", substring = true))
        composeRule.onNode(hasClickAction() and hasText("25.0%", substring = true)).assertIsDisplayed()
    }

    /** 本月只有收入（分类占比画的是支出）：不摆一张空环，支出排行的提示行照旧 */
    @Test
    fun anIncomeOnlyMonth_hasNoDonut() {
        seedIncomeOnly()
        showReports()
        awaitText("日均支出")

        val hint = "本月没有支出。转账与余额调整不算收支，所以不会出现在这里。"
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(hint))
        composeRule.onNodeWithText(hint).assertIsDisplayed()
        composeRule.onAllNodesWithText("分类占比").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(DONUT_DESCRIPTION).assertCountEquals(0)
    }
}
