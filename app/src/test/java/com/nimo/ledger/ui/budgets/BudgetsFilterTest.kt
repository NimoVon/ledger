package com.nimo.ledger.ui.budgets

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.awaitUntil
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.CategoryKind
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
 * B-6：分类预算列表顶部的「全部 / 已设 / 未设」筛选。
 *
 * 只锁两件事：默认「全部」两行都在；切到「未设」后已设行消失、未设行还在；
 * 全空时给出「这个筛选下没有分类。」这句空态文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BudgetsFilterTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private val month: YearMonth = YearMonth.now()
    private val budgetCents = 50_000L // ¥500.00

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

    private suspend fun seed(): Pair<Long, Long> {
        val budgeted = repository.createCategory("有预算", CategoryKind.EXPENSE).getOrThrow()
        val unbudgeted = repository.createCategory("没预算", CategoryKind.EXPENSE).getOrThrow()
        repository.setBudget(month.toString(), categoryId = budgeted, amountCents = budgetCents)
        return budgeted to unbudgeted
    }

    private fun showBudgets() {
        composeRule.setContent {
            MaterialTheme {
                BudgetsScreen(repository = repository)
            }
        }
    }

    private fun awaitText(text: String) {
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun exists(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun defaultFilter_showsAllCategories_thenUnsetHidesTheBudgetedOne() {
        runBlocking { seed() }
        showBudgets()
        awaitText("有预算")
        awaitText("没预算")

        composeRule.onNodeWithText("未设").performClick()
        composeRule.waitUntil(2_000) { !exists("有预算") }
        assertTrue("未设筛选应保留没预算的分类", exists("没预算"))
    }

    @Test
    fun unsetFilter_withEveryCategoryBudgeted_showsEmptyHint() {
        val (budgeted, unbudgeted) = runBlocking { seed() }
        runBlocking {
            repository.setBudget(month.toString(), categoryId = unbudgeted, amountCents = budgetCents)
        }
        check(budgeted > 0)
        showBudgets()
        awaitText("有预算")

        composeRule.onNodeWithText("未设").performClick()
        awaitText("这个筛选下没有分类。")
        assertTrue(!exists("有预算"))
    }
}
