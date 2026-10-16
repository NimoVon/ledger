package com.nimo.ledger.ui.reports

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
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
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionInput
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.stats.DailyTotal
import com.nimo.ledger.domain.stats.MonthlyTotal
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import com.nimo.ledger.awaitUntil
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 趋势区三段开关（附.1 R2）的界面行为：默认画"本月"的逐日柱、点"半年/全年"换成月度折线。
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
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showReports() {
        composeRule.setContent {
            MaterialTheme { ReportsScreen(repository = repository) }
        }
    }

    /**
     * 折线图的描述现在带真实数字（F15）：按头一句 [trendHead] 做前缀匹配——
     * 金额随数据变，测试不写死一串钱；窗口月数（1 / 6 / 12）仍是断言的重点。
     */
    private fun trendNode(span: TrendSpan): SemanticsNodeInteraction =
        composeRule.onNode(hasContentDescription(trendHead(span), substring = true))

    private fun awaitTrend(span: TrendSpan) {
        composeRule.awaitUntil {
            composeRule.onAllNodes(hasContentDescription(trendHead(span), substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 逐日柱状图的根节点（本月视图） */
    private fun dailyChart(): SemanticsNodeInteraction =
        composeRule.onNode(hasTestTag(DAILY_CHART_TEST_TAG))

    private fun awaitDailyChart() {
        composeRule.awaitUntil {
            composeRule.onAllNodes(hasTestTag(DAILY_CHART_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun segment(span: TrendSpan): SemanticsNodeInteraction =
        composeRule.onNode(hasClickAction() and hasText(span.label))

    @Test
    fun chartStartsAtTheCurrentMonth_thenTheSwitchesReshapeIt() {
        val month = seedInCurrentMonth()
        showReports()

        // 默认"本月"：逐日柱状图，不是折线
        awaitDailyChart()
        dailyChart().performScrollTo().assertIsDisplayed()
        segment(TrendSpan.MONTH).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("最高 ¥600.00").performScrollTo().assertIsDisplayed()
        // 本月的横轴是"1日"这样的日子，而不是"10月"这样的月份
        composeRule.onNodeWithText("1日").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("${month.monthValue}月").assertCountEquals(0)

        // 切"半年"：换成 6 个月的折线
        segment(TrendSpan.HALF_YEAR).performScrollTo().performClick()
        awaitTrend(TrendSpan.HALF_YEAR)
        trendNode(TrendSpan.HALF_YEAR).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("${month.monthValue}月").performScrollTo().assertIsDisplayed()

        // 切"全年"：12 个月
        segment(TrendSpan.FULL_YEAR).performScrollTo().performClick()
        awaitTrend(TrendSpan.FULL_YEAR)
        trendNode(TrendSpan.FULL_YEAR).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun switchingBackToTheCurrentMonth_bringsTheDailyBarsBack() {
        seedInCurrentMonth()
        showReports()

        awaitDailyChart()
        segment(TrendSpan.FULL_YEAR).performScrollTo().performClick()
        awaitTrend(TrendSpan.FULL_YEAR)

        segment(TrendSpan.HALF_YEAR).performScrollTo().performClick()
        awaitTrend(TrendSpan.HALF_YEAR)
        trendNode(TrendSpan.HALF_YEAR).performScrollTo().assertIsDisplayed()

        segment(TrendSpan.MONTH).performScrollTo().performClick()
        awaitDailyChart()
        dailyChart().performScrollTo().assertIsDisplayed()
    }

    /** 本月没记账、但近 12 个月里有数据：趋势区仍然在，切一段就能看到（F10 的可达性） */
    @Test
    fun anEmptyMonth_stillLetsYouReachTheTrend_whenTheWindowHasRecords() {
        seedLastMonth()
        showReports()

        awaitText("本月还没有记录")
        composeRule.onNodeWithText("本月还没有记录").assertIsDisplayed()
        // 默认"本月"没有已过天数的记录：不摆空图，但三段开关还在
        composeRule.onNodeWithText("本月还没有收支记录。").performScrollTo().assertIsDisplayed()

        segment(TrendSpan.HALF_YEAR).performScrollTo().performClick()
        awaitTrend(TrendSpan.HALF_YEAR)
        trendNode(TrendSpan.HALF_YEAR).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("最高 ¥600.00").performScrollTo().assertIsDisplayed()
    }

    /** 整个 12 个月窗口都没有记录：保持原来的纯空状态，不摆一张空图 */
    @Test
    fun anEmptyWindow_keepsThePlainEmptyState() {
        seedAccountOnly()
        showReports()

        awaitText("本月还没有记录")
        composeRule.onNodeWithText("本月还没有记录").assertIsDisplayed()
        composeRule.onAllNodes(hasContentDescription("收支趋势", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag(DAILY_CHART_TEST_TAG)).assertCountEquals(0)
    }

    /**
     * 月底（31 天）的密度：格子比最小宽度还窄 → 横轴标签稀疏、图能左右滑、纵轴刻度仍在。
     * 直接渲染 [TrendSection] 造 31 天数据（只有 31 天的月份才够挤），不走数据库。
     */
    @Test
    fun aThirtyOneDayMonth_sparsensTheLabelsAndScrolls() {
        val month = YearMonth.of(2026, 7)
        val daily = (1..31).map { day ->
            DailyTotal(
                date = month.atDay(day),
                incomeCents = 0L,
                expenseCents = if (day == 1) 60_000L else 0L,
            )
        }
        composeRule.setContent {
            MaterialTheme {
                TrendSection(
                    span = TrendSpan.MONTH,
                    month = month,
                    points = listOf(MonthlyTotal(month, incomeCents = 0L, expenseCents = 60_000L)),
                    daily = daily,
                    isLoading = false,
                    onSpanChange = {},
                    onDayClick = {},
                )
            }
        }

        composeRule.onNode(hasTestTag(DAILY_CHART_TEST_TAG)).assertExists()
        // 纵轴：上界 niceCeiling(¥600.00) = ¥1,000，中线 ¥500，底线 ¥0
        composeRule.onNode(hasTestTag(CHART_Y_AXIS_TEST_TAG)).assertExists()
        composeRule.onAllNodesWithText("¥1,000").assertCountEquals(1)
        composeRule.onAllNodesWithText("¥500").assertCountEquals(1)
        composeRule.onAllNodesWithText("¥0").assertCountEquals(1)

        // 标签稀疏：1 号必标、相邻的 2 号不标；最后一天离上一个标签太近也不标
        // （格子宽 18dp 时每 3 天一个标签，所以 3 号仍在；2 号在任何间隔下都不该出现）
        composeRule.onAllNodesWithText("1日").assertCountEquals(1)
        composeRule.onAllNodesWithText("2日").assertCountEquals(0)
        composeRule.onAllNodesWithText("31日").assertCountEquals(0)
        composeRule.onAllNodesWithText(DAY_LABEL_SCROLL_HINT).assertCountEquals(1)
    }
}
