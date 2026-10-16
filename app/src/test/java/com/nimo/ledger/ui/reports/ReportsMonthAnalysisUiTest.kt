package com.nimo.ledger.ui.reports

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.nimo.ledger.domain.stats.DailyTotal
import com.nimo.ledger.ui.transactions.TransactionTimeline
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 单月分析（附.1 R2）的界面行为：
 * 点逐日柱子打开当日流水弹层、预算达成率的显示条件、以及"本月洞察"把日均/环比/预算归到一处。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReportsMonthAnalysisUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private val month: YearMonth = YearMonth.now()

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

    private fun seedAccount(): Long = runBlocking {
        repository.createAccount("CMB", AccountType.CASH, 0L).getOrThrow()
    }

    private fun seedCategory(name: String, icon: String, kind: CategoryKind): Long = runBlocking {
        db.categoryDao().upsert(CategoryEntity(name = name, kind = kind, icon = icon, sortOrder = 0))
    }

    private fun addExpense(accountId: Long, categoryId: Long, amountCents: Long, date: LocalDate) {
        runBlocking {
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountCents = amountCents,
                    accountId = accountId,
                    categoryId = categoryId,
                    occurredOn = date.toString(),
                ),
            ).getOrThrow()
        }
    }

    private fun addTransfer(accountId: Long, toAccountId: Long, amountCents: Long, date: LocalDate) {
        runBlocking {
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.TRANSFER,
                    amountCents = amountCents,
                    accountId = accountId,
                    toAccountId = toAccountId,
                    occurredOn = date.toString(),
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
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 逐日柱子（某一天那张可点的格子）的无障碍描述 */
    private fun dayNode(date: LocalDate, expenseCents: Long, incomeCents: Long = 0L) =
        composeRule.onNode(
            hasContentDescription(
                dayDescription(DailyTotal(date, incomeCents = incomeCents, expenseCents = expenseCents)),
            ),
        )

    @Test
    fun tappingADayBar_opensThatDaysTransactions() {
        val accountId = seedAccount()
        val otherId = runBlocking { repository.createAccount("招行卡", AccountType.DEBIT, 0L).getOrThrow() }
        val entertainmentId = seedCategory("娱乐", "🎮", CategoryKind.EXPENSE)
        val firstDay = month.atDay(1)

        addExpense(accountId, entertainmentId, 60_000L, firstDay)
        addTransfer(accountId, otherId, 50_000L, firstDay) // 转账：明细里看得见，统计里不算
        showReports()

        awaitText("最高 ¥600.00")
        dayNode(firstDay, expenseCents = 60_000L).performScrollTo().performClick()

        composeRule.awaitUntil {
            composeRule.onAllNodes(hasTestTag(DAY_DETAIL_DIALOG_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasTestTag(DAY_DETAIL_DIALOG_TEST_TAG)).assertIsDisplayed()
        // 标题就是那一天（"今天"/"昨天"/"10月1日 周四"）
        composeRule.onNodeWithText(TransactionTimeline.dayLabel(firstDay, LocalDate.now())).assertIsDisplayed()
        // 支出带负号、转账行也在（明细页的同一套规则）。
        // 必须限定在弹层内：账户余额为负时顶部「结余」也是 "-¥600.00"，页面上同名文本有两处
        composeRule.onNode(
            hasText("-¥600.00") and hasAnyAncestor(hasTestTag(DAY_DETAIL_DIALOG_TEST_TAG)),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("转账").assertIsDisplayed()

        composeRule.onNodeWithText("关闭").performClick()
        composeRule.awaitUntil {
            composeRule.onAllNodes(hasTestTag(DAY_DETAIL_DIALOG_TEST_TAG)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun tappingADayWithoutRecords_saysSo() {
        val accountId = seedAccount()
        val entertainmentId = seedCategory("娱乐", "🎮", CategoryKind.EXPENSE)
        addExpense(accountId, entertainmentId, 60_000L, month.atDay(1))
        // 只有 2 号以后才存在"已过但没有记录的天"
        assumeTrue(LocalDate.now().dayOfMonth >= 2)
        val emptyDay = month.atDay(2)

        showReports()

        awaitText("最高 ¥600.00")
        dayNode(emptyDay, expenseCents = 0L).performScrollTo().performClick()

        composeRule.awaitUntil {
            composeRule.onAllNodes(hasTestTag(DAY_DETAIL_DIALOG_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("这一天没有流水。").assertIsDisplayed()
    }

    @Test
    fun budgetRate_isHiddenUntilATotalBudgetExists() {
        val accountId = seedAccount()
        val entertainmentId = seedCategory("娱乐", "🎮", CategoryKind.EXPENSE)
        addExpense(accountId, entertainmentId, 60_000L, month.atDay(1))

        showReports()

        awaitText("日均支出")
        composeRule.onNodeWithText("日均支出").assertIsDisplayed()
        composeRule.onAllNodesWithText("预算达成率").assertCountEquals(0)
    }

    @Test
    fun budgetRate_showsTheAchievementOfTheTotalBudget() {
        val accountId = seedAccount()
        val entertainmentId = seedCategory("娱乐", "🎮", CategoryKind.EXPENSE)
        addExpense(accountId, entertainmentId, 60_000L, month.atDay(1))
        runBlocking { repository.setBudget(month.toString(), null, 100_000L).getOrThrow() }

        showReports()

        awaitText("预算达成率")
        composeRule.onNodeWithText("预算达成率").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("60.0%").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theInsightSection_gathersDailyAverageChangeAndBudget() {
        val accountId = seedAccount()
        val entertainmentId = seedCategory("娱乐", "🎮", CategoryKind.EXPENSE)
        addExpense(accountId, entertainmentId, 60_000L, month.atDay(1))

        showReports()

        awaitText("本月洞察")
        composeRule.onNodeWithText("本月洞察").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("日均 / 环比 / 预算").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("日均支出").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("支出环比").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("收入环比").performScrollTo().assertIsDisplayed()
        // 趋势区提示改成三段
        composeRule.onNodeWithText("本月 / 近半年 / 近全年").performScrollTo().assertIsDisplayed()
    }
}
