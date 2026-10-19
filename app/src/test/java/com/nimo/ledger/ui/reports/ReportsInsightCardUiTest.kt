package com.nimo.ledger.ui.reports

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
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
 * 统计页分段化（附.1 S-5 / S-2）的两处结构：
 *
 * ①「本月洞察」的日均 / 环比 / 预算行被一张 surfaceContainerLow 卡片包住，不再是一屏裸行；
 * ② 五个段头是 `stickyHeader`——滑到页面下半段时，「支出排行」还钉在内容区顶沿。
 *
 * 走真实 Repository + in-memory Room：卡片里有没有「预算达成率」取决于设没设总预算，
 * 段头钉不钉得住取决于内容够不够长，这两点都靠真实数据撑起来，不用假 state。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReportsInsightCardUiTest {

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

    /**
     * 在当前月第一天记 [count] 笔支出（每笔一个独立分类）：分类越多排行越长，
     * 页面才真的能往下滚——滚不动就测不到段头钉住。
     */
    private fun seedExpenses(count: Int) {
        val accountId = runBlocking { repository.createAccount("CMB", AccountType.CASH, 0L).getOrThrow() }
        repeat(count) { index ->
            val name = "类目%02d".format(index + 1)
            runBlocking {
                val categoryId = db.categoryDao().upsert(
                    CategoryEntity(name = name, kind = CategoryKind.EXPENSE, icon = "🏷", sortOrder = index),
                )
                repository.addTransaction(
                    TransactionInput(
                        type = TransactionType.EXPENSE,
                        amountCents = 10_000L,
                        accountId = accountId,
                        categoryId = categoryId,
                        occurredOn = month.atDay(1).toString(),
                    ),
                ).getOrThrow()
            }
        }
    }

    private fun showReports() {
        composeRule.setContent {
            MaterialTheme {
                ReportsScreen(repository = repository)
            }
        }
    }

    private fun awaitText(text: String) {
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun insightRows_sitInsideOneCard() {
        seedExpenses(1)
        runBlocking { repository.setBudget(month.toString(), categoryId = null, amountCents = 500_000L).getOrThrow() }
        showReports()

        awaitText("日均支出")
        composeRule.onNode(hasTestTag(INSIGHT_CARD_TEST_TAG)).assertExists()

        // 四行都在同一张卡片里：预算达成率只在设了总预算时出现，这里设了 5000 元
        listOf("日均支出", "支出环比", "收入环比", "预算达成率").forEach { label ->
            composeRule
                .onNode(hasText(label) and hasAnyAncestor(hasTestTag(INSIGHT_CARD_TEST_TAG)))
                .assertIsDisplayed()
        }
    }

    @Test
    fun withoutTotalBudget_theCardHasNoBudgetRateRow() {
        seedExpenses(1)
        showReports()

        awaitText("日均支出")
        composeRule.onNode(hasTestTag(INSIGHT_CARD_TEST_TAG)).assertExists()
        composeRule.onNodeWithText("预算达成率").assertDoesNotExist()
    }

    @Test
    fun sectionHeaders_stayPinned_whenScrollingDownThePage() {
        seedExpenses(12)
        showReports()

        awaitText("日均支出")
        composeRule
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("账户支出分布"))

        // 已经滚到「账户支出分布」这一屏，前面的「支出排行」段头仍应钉在内容区顶沿；
        // 段头要是普通 item，它早被内容顶出屏幕，这条断言会红。
        composeRule.onNodeWithText("支出排行").assertIsDisplayed()
        composeRule.onNodeWithText("账户支出分布").assertIsDisplayed()
    }
}
