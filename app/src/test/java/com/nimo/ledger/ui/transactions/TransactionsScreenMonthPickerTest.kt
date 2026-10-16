package com.nimo.ledger.ui.transactions

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import java.time.LocalDate
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
 * R1 日期直改的端到端接线（明细页）：点月份标签 → 选年 → 选月 → 整页换到那个年月。
 *
 * 三个部件各自的单测（YearMonthPickerTest / YearMonthPickerBodyUiTest /
 * TransactionsViewModelTest 的 setMonth）都过，接线接错依然会翻车——所以这里走真实
 * Repository + in-memory Room，把"标签点得开、选中的年月真的落到这个页面"整条链跑一遍。
 * 断言用的是月份标签与当天段头，都是页面上真实渲染出来的文字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TransactionsScreenMonthPickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    /** 打开时的月份（真实"今天"所在月）：明细页默认停在当月 */
    private val currentMonth: YearMonth = YearMonth.now()

    /** 要直改到的月份：上一个月——一定不是当前月，且列表数据必须跟着换 */
    private val targetMonth: YearMonth = currentMonth.minusMonths(1)

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
            val accountId = repository.createAccount("现金", AccountType.CASH, 0L).getOrThrow()
            val categoryId = db.categoryDao().upsert(
                CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, icon = "🍜", sortOrder = 0),
            )
            insert(accountId, categoryId, 700L, currentMonth)
            insert(accountId, categoryId, 300L, targetMonth)
        }
    }

    private suspend fun insert(accountId: Long, categoryId: Long, amountCents: Long, month: YearMonth) {
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

    private fun showTransactions() {
        composeRule.setContent {
            MaterialTheme {
                TransactionsScreen(repository = repository)
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
        showTransactions()

        val openLabel = TransactionTimeline.monthLabel(currentMonth)
        val targetLabel = TransactionTimeline.monthLabel(targetMonth)
        val monthCell = "${targetMonth.monthValue}月"
        awaitText(openLabel)

        // 第一步：点月份标签（左右箭头仍在，各自负责上下月）
        composeRule.onNodeWithText(openLabel).performClick()
        awaitText("选择年月")

        // 第二步：选年 → 进月份格 → 选月
        // 注：年份页默认停在含当前年的那一页（20 年一屏），目标月只会落在当前年或上一年，都在这一屏上
        composeRule.onNodeWithText(targetMonth.year.toString()).performClick()
        awaitText(monthCell)
        composeRule.onNodeWithText(monthCell).performClick()

        // 整页换月：标题、列表段头都跟着走，旧月份的标签消失
        awaitText(targetLabel)
        assertTrue(
            "切换后月份标签应当是 $targetLabel",
            composeRule.onAllNodesWithText(targetLabel).fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithText(TransactionTimeline.dayLabel(targetMonth.atDay(1), LocalDate.now()))
            .assertIsDisplayed()
        assertTrue(
            "切到 $targetLabel 之后不该还看得见 $openLabel",
            composeRule.onAllNodesWithText(openLabel).fetchSemanticsNodes().isEmpty(),
        )
    }
}
