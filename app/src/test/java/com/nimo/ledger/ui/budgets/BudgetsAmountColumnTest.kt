package com.nimo.ledger.ui.budgets

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.awaitUntil
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.BudgetEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.ui.components.LEDGER_AMOUNT_MIN_WIDTH
import com.nimo.ledger.ui.components.LEDGER_AMOUNT_TEST_TAG
import com.nimo.ledger.ui.transactions.TransactionTimeline
import com.nimo.ledger.util.Money
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
 * 预算页的金额列也走 LedgerAmount（规范 §6.3 / G-5 收尾）。
 *
 * 预算页是最后一个还手写金额 `Text` 的列表：状态色（OK / WARNING / OVER）由预算状态决定，
 * 所以迁移时给 LedgerAmount 加了 color 覆盖参数。这里锁住两点：
 * 显示口径不变（仍是 ¥1,000.00）、读屏口径统一（念「1000 元」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BudgetsAmountColumnTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private val currentMonth: YearMonth = YearMonth.now()
    private val budgetCents = 100_000L // ¥1,000.00

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repository = LedgerRepository(db)
        runBlocking {
            db.budgetDao().insert(
                BudgetEntity(month = currentMonth.toString(), categoryId = null, amount = budgetCents),
            )
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun budgetAmount_isRenderedByTheSharedAmountColumn() {
        composeRule.setContent {
            MaterialTheme { BudgetsScreen(repository = repository) }
        }
        val label = TransactionTimeline.monthLabel(currentMonth)
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(Money.formatCurrency(budgetCents))
                    .fetchSemanticsNodes().isNotEmpty()
        }

        // 显示口径没变：¥1,000.00 仍在（上一行 await 已确认）；读屏口径变成「1000 元」
        composeRule.onNodeWithContentDescription("1000 元").assertExists()

        // 96dp 金额列：短金额也占满，右侧成线
        val amountNodes = composeRule.onAllNodes(hasTestTag(LEDGER_AMOUNT_TEST_TAG), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("预算页金额应来自 LedgerAmount", amountNodes.isNotEmpty())
        composeRule.onAllNodes(hasTestTag(LEDGER_AMOUNT_TEST_TAG), useUnmergedTree = true)[0]
            .assertWidthIsAtLeast(LEDGER_AMOUNT_MIN_WIDTH)
    }
}
