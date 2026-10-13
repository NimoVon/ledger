package com.nimo.bookkeeping.ui.reports

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * 趋势折线（F10）的界面行为：默认画近半年、点"全年"之后窗口真的变成 12 个月。
 * 画布本身用无障碍描述断言行数，避免依赖像素。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TrendChartUiTest {

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

    /** 记一笔 ¥600 的娱乐支出到 [month]（账户与分类一并建好） */
    private fun seedExpense(month: YearMonth): YearMonth {
        runBlocking {
            val accountId = repository.createAccount("CMB", AccountType.CASH, 0L).getOrThrow()
            val categoryId = db.categoryDao().upsert(
                CategoryEntity(name = "娱乐", kind = CategoryKind.EXPENSE, icon = "🎮", sortOrder = 0),
            )
            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountCents = 60_000L,
                    accountId = accountId,
                    categoryId = categoryId,
                    occurredOn = month.atDay(1).toString(),
                ),
            ).getOrThrow()
        }
        return month
    }

    private fun seedInCurrentMonth(): YearMonth = seedExpense(YearMonth.now())

    private fun seedLastMonth(): YearMonth = seedExpense(YearMonth.now().minusMonths(1))

    /** 只建账户不记账：让报表读完 references，停在"整个窗口都没有记录"的状态 */
    private fun seedAccountOnly() {
        runBlocking {
            repository.createAccount("CMB", AccountType.CASH, 0L).getOrThrow()
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showReports() {
        composeRule.setContent {
            MaterialTheme { ReportsScreen(repository = repository) }
        }
    }

    private fun awaitDescription(description: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun chartStartsAtHalfAYear_andTheFullYearSwitchWidensIt() {
        val month = seedInCurrentMonth()
        showReports()

        awaitDescription(trendDescription(TrendSpan.HALF_YEAR))
        composeRule.onNodeWithContentDescription(trendDescription(TrendSpan.HALF_YEAR)).performScrollTo().assertIsDisplayed()
        // 图例 + 纵轴最高值（本月唯一一笔支出 ¥600.00）
        composeRule.onNodeWithText("最高 ¥600.00").performScrollTo().assertIsDisplayed()
        // 横轴最后一个月就是当前查看的月份
        composeRule.onNodeWithText("${month.monthValue}月").performScrollTo().assertIsDisplayed()
        // 默认选中的是"半年"
        composeRule.onNode(hasClickAction() and hasText(TrendSpan.HALF_YEAR.label)).performScrollTo().assertIsDisplayed()

        composeRule.onNode(hasClickAction() and hasText(TrendSpan.FULL_YEAR.label)).performScrollTo().performClick()

        awaitDescription(trendDescription(TrendSpan.FULL_YEAR))
        composeRule.onNodeWithContentDescription(trendDescription(TrendSpan.FULL_YEAR)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun switchingBackToHalfAYear_shrinksTheWindowAgain() {
        seedInCurrentMonth()
        showReports()

        awaitDescription(trendDescription(TrendSpan.HALF_YEAR))
        composeRule.onNode(hasClickAction() and hasText(TrendSpan.FULL_YEAR.label)).performScrollTo().performClick()
        awaitDescription(trendDescription(TrendSpan.FULL_YEAR))

        composeRule.onNode(hasClickAction() and hasText(TrendSpan.HALF_YEAR.label)).performScrollTo().performClick()

        awaitDescription(trendDescription(TrendSpan.HALF_YEAR))
        composeRule.onNodeWithContentDescription(trendDescription(TrendSpan.HALF_YEAR)).performScrollTo().assertIsDisplayed()
    }

    /** 本月没记账、但窗口内有数据：趋势图不能被空状态挡掉（F10 的可达性） */
    @Test
    fun anEmptyMonth_stillShowsTheTrend_whenTheWindowHasRecords() {
        seedLastMonth()
        showReports()

        awaitText("本月还没有记录")
        composeRule.onNodeWithText("本月还没有记录").assertIsDisplayed()
        awaitDescription(trendDescription(TrendSpan.HALF_YEAR))
        composeRule.onNodeWithContentDescription(trendDescription(TrendSpan.HALF_YEAR))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("最高 ¥600.00").performScrollTo().assertIsDisplayed()
    }

    /** 整个窗口都没有记录：保持原来的纯空状态，不摆一张空图 */
    @Test
    fun anEmptyWindow_keepsThePlainEmptyState() {
        seedAccountOnly()
        showReports()

        awaitText("本月还没有记录")
        composeRule.onNodeWithText("本月还没有记录").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(trendDescription(TrendSpan.HALF_YEAR))
            .assertCountEquals(0)
    }
}
