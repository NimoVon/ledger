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
import com.nimo.ledger.data.local.entity.BudgetEntity
import com.nimo.ledger.data.repository.LedgerRepository
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
 * R1 日期直改的接线（预算页，B-4）：点月份标签 → 选年 → 选月 → 整页换到那个年月。
 *
 * 年月选择器自己的单测（YearMonthPickerTest / YearMonthPickerBodyUiTest）和 ViewModel 的
 * setMonth 都过，接线接错依然会翻车——所以这里走真实 Repository + in-memory Room，
 * 把"标签点得开、选中的年月真的落到这一页的总预算上"整条链跑一遍。
 *
 * 断言用的是页面上真实渲染的文字：月份标签（TransactionTimeline.monthLabel）与总预算金额
 * （Money.formatCurrency）。两个月的总预算故意设成不同金额，只有整页真的换了月，
 * 金额才会跟着换——只断言标签的话，标签换了但数据没换也照样绿。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BudgetsScreenMonthPickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    /** 打开时的月份（真实"今天"所在月）：预算页默认停在当月 */
    private val currentMonth: YearMonth = YearMonth.now()

    /** 要直改到的月份：上一个月——一定不是当前月，且总预算金额不同 */
    private val targetMonth: YearMonth = currentMonth.minusMonths(1)

    private val currentBudgetCents = 100_000L   // ¥1,000.00
    private val targetBudgetCents = 500_000L    // ¥5,000.00

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

    private fun seed() {
        runBlocking {
            seedBudget(currentMonth, currentBudgetCents)
            seedBudget(targetMonth, targetBudgetCents)
        }
    }

    /** 总预算（categoryId = null）只写库，不走 UI 路径：这条测试测的是切月接线 */
    private suspend fun seedBudget(month: YearMonth, amountCents: Long) {
        db.budgetDao().insert(
            BudgetEntity(month = month.toString(), categoryId = null, amount = amountCents),
        )
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

    @Test
    fun labelTapThenYearThenMonth_switchesThePageToThatYearMonth() {
        seed()
        showBudgets()

        val openLabel = TransactionTimeline.monthLabel(currentMonth)
        val targetLabel = TransactionTimeline.monthLabel(targetMonth)
        val monthCell = "${targetMonth.monthValue}月"
        val openTotal = Money.formatCurrency(currentBudgetCents)
        val targetTotal = Money.formatCurrency(targetBudgetCents)
        awaitText(openLabel)
        awaitText(openTotal)

        // 第一步：点月份标签（左右箭头仍在，各自负责上下月）
        composeRule.onNodeWithText(openLabel).performClick()
        awaitText("选择年月")

        // 第二步：选年 → 进月份格 → 选月
        // 注：年份页默认停在含当前年的那一屏（20 年一屏），目标月只会落在当前年或上一年，都在这一屏上
        composeRule.onNodeWithText(targetMonth.year.toString()).performClick()
        awaitText(monthCell)
        composeRule.onNodeWithText(monthCell).performClick()

        // 整页换月：标签与总预算金额都跟着走，旧月份的数据消失
        awaitText(targetLabel)
        awaitText(targetTotal)
        assertTrue(
            "切换后月份标签应当是 $targetLabel",
            composeRule.onAllNodesWithText(targetLabel).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "切到 $targetLabel 之后不该还看得见 $openLabel",
            composeRule.onAllNodesWithText(openLabel).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "切到 $targetLabel 之后总预算应当是该月的 $targetTotal，而不是 $openTotal",
            composeRule.onAllNodesWithText(openTotal).fetchSemanticsNodes().isEmpty(),
        )
    }
}
