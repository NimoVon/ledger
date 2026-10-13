package com.nimo.bookkeeping.ui.reports

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.filter.TransactionFilter
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 月报页（F07）的 UI 冒烟测试——计划 6.1「UI 测试 → Compose UI Test」的落点。
 *
 * 走真实 Repository + in-memory Room，只验"页面把这一行交给了谁"：
 * 点支出排行的一行必须把「该分类 + 当前这个月」的筛选条件交出去（M3 验收：月报金额与流水逐笔对账）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReportsScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private var drilled: TransactionFilter? = null
    private var entertainmentId = 0L

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

    /** 数据落在"当前这个月"的第一天：月报默认打开的就是当月 */
    private fun seedInCurrentMonth(): YearMonth {
        val month = YearMonth.now()
        runBlocking {
            val accountId = repository.createAccount("CMB", AccountType.CASH, 0L).getOrThrow()
            entertainmentId = db.categoryDao().upsert(
                CategoryEntity(name = "娱乐", kind = CategoryKind.EXPENSE, icon = "🎮", sortOrder = 0),
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
        }
        return month
    }

    private fun showReports() {
        composeRule.setContent {
            MaterialTheme {
                ReportsScreen(repository = repository, onDrillDown = { drilled = it })
            }
        }
    }

    @Test
    fun tappingTheCategoryRow_handsOverThatCategoryAndTheCurrentMonth() {
        val month = seedInCurrentMonth()
        showReports()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("娱乐", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // 两个段头都带这句提示：支出排行 + 账户支出分布
        composeRule.onAllNodesWithText("点一行看明细")[0].performScrollTo().assertIsDisplayed()
        // 合并后的语义树里，可点的整行自己就带上了子节点的文字
        composeRule
            .onNode(hasClickAction() and hasText("娱乐", substring = true))
            .performScrollTo()
            .performClick()

        assertEquals(ReportDrillDown.byCategory(month, entertainmentId), drilled)
    }

    @Test
    fun theMonthSummary_comesFromTheRepository() {
        seedInCurrentMonth()
        showReports()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("¥600.00").fetchSemanticsNodes().isNotEmpty()
        }
        // 合计条、分类排行、账户分布都会出现这个金额
        composeRule.onAllNodesWithText("¥600.00")[0].assertIsDisplayed()
    }
}
