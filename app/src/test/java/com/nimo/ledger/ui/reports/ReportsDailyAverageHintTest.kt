package com.nimo.ledger.ui.reports

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import com.nimo.ledger.domain.stats.StatsCalculator
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S-7：「日均支出」要把分母说出来（「按已过 N 天算」），且 N 必须来自
 * [StatsCalculator.elapsedDays]——页面不得自己数天数。
 *
 * 断言用「算出来的 N」而不是写死的 19：测试跑在别的日期仍然成立，
 * 只有页面的数字和统计口径分叉时才会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReportsDailyAverageHintTest {

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

    private fun seedOneExpense() {
        val accountId = runBlocking { repository.createAccount("现金", AccountType.CASH, 0L).getOrThrow() }
        runBlocking {
            val categoryId = db.categoryDao().upsert(
                CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, icon = "🍜", sortOrder = 0),
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

    @Test
    fun dailyAverage_spellsOutTheDenominator() {
        seedOneExpense()

        composeRule.setContent {
            MaterialTheme {
                ReportsScreen(repository = repository)
            }
        }
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText("日均支出").fetchSemanticsNodes().isNotEmpty()
        }

        val days = StatsCalculator.elapsedDays(month, LocalDate.now())
        assertTrue("当月并已过至少一天，分母应 > 0", days > 0)

        val expected = "按已过 $days 天算"
        assertEquals(
            "分母文案只应出现一次（页面不得自算第二份）",
            1,
            composeRule.onAllNodesWithText(expected).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }
}
