package com.nimo.ledger.ui.reports

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
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
import com.nimo.ledger.domain.stats.ReportRange
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import com.nimo.ledger.awaitUntil
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 统计范围在界面上的行为：默认画"本月"的逐日柱，点范围标签 → 弹层里选「近6个月/今年」换成月度折线，
 * 再选回「本月」又回到柱子。画布本身用无障碍描述断言行数，避免依赖像素。
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

    /** 只建账户不记账：让报表读完 references，停在"整个范围都没有记录"的状态 */
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

    /** 顶部范围标签（可点开范围弹层）的文案，与 ReportRange.Month.label 同源 */
    private fun monthLabel(month: YearMonth): String = "${month.year}年${month.monthValue}月"

    /** 点范围标签打开弹层：标签不复用旧的三段 chip，范围入口只有这一个 */
    private fun openRangeDialog(currentLabel: String) {
        // 标签在顶部固定栏（不在滚动容器里），不能 performScrollTo
        composeRule.onNode(hasText(currentLabel) and hasClickAction()).performClick()
        composeRule.onNode(hasTestTag(RANGE_DIALOG_TEST_TAG)).assertExists()
    }

    /** 点弹层里的快捷项（tag = reports-range-quick-<key>） */
    private fun pickQuick(tag: String) {
        composeRule.onNode(hasTestTag(RANGE_QUICK_PREFIX + tag)).performClick()
        composeRule.awaitUntil {
            composeRule.onAllNodes(hasTestTag(RANGE_DIALOG_TEST_TAG)).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * 折线图的描述带真实数字（F15）：按头一句 [trendHead] 做前缀匹配——
     * 金额随数据变，测试不写死一串钱；窗口月数（1 / 6 / 12）仍是断言的重点。
     */
    private fun trendNode(range: ReportRange): SemanticsNodeInteraction =
        composeRule.onNode(hasContentDescription(trendHead(range), substring = true))

    private fun awaitTrend(range: ReportRange) {
        composeRule.awaitUntil {
            composeRule.onAllNodes(hasContentDescription(trendHead(range), substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 逐日柱状图的根节点（按天画法的范围） */
    private fun dailyChart(): SemanticsNodeInteraction =
        composeRule.onNode(hasTestTag(DAILY_CHART_TEST_TAG))

    private fun awaitDailyChart() {
        composeRule.awaitUntil {
            composeRule.onAllNodes(hasTestTag(DAILY_CHART_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun thisMonth(): YearMonth = YearMonth.now()

    @Test
    fun chartStartsAtTheCurrentMonth_thenTheRangeChangesReshapeIt() {
        val month = seedInCurrentMonth()
        showReports()

        // 默认"本月"：逐日柱状图，不是折线
        awaitDailyChart()
        dailyChart().performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("最高 ¥600.00").performScrollTo().assertIsDisplayed()
        // 本月的横轴是"1日"这样的日子，而不是"10月"这样的月份
        composeRule.onNodeWithText("1日").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("${month.monthValue}月").assertCountEquals(0)

        // 切"近6个月"：换成 6 个月的折线
        val six = ReportRange.lastMonths(month, 6)
        openRangeDialog(monthLabel(month))
        pickQuick("last6")
        awaitTrend(six)
        trendNode(six).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("${month.monthValue}月").performScrollTo().assertIsDisplayed()

        // 切"今年"：自然年 12 个月，折线头也换成"今年"
        val year = ReportRange.year(month.year)
        openRangeDialog(six.label)
        pickQuick("year-${month.year}")
        awaitTrend(year)
        trendNode(year).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun switchingBackToTheCurrentMonth_bringsTheDailyBarsBack() {
        seedInCurrentMonth()
        showReports()

        awaitDailyChart()
        val year = ReportRange.year(thisMonth().year)
        openRangeDialog(monthLabel(thisMonth()))
        pickQuick("year-${thisMonth().year}")
        awaitTrend(year)

        val six = ReportRange.lastMonths(thisMonth(), 6)
        openRangeDialog(year.label)
        pickQuick("last6")
        awaitTrend(six)
        trendNode(six).performScrollTo().assertIsDisplayed()

        openRangeDialog(six.label)
        pickQuick("month")
        awaitDailyChart()
        dailyChart().performScrollTo().assertIsDisplayed()
    }

    /** 本月没记账、但近 6 个月里有数据：趋势区仍然可达，选一段就能看到（F10 的可达性） */
    @Test
    fun anEmptyMonth_stillLetsYouReachTheTrend_whenTheRangeHasRecords() {
        seedLastMonth()
        showReports()

        awaitText("本月还没有记录")
        composeRule.onNodeWithText("本月还没有记录").assertIsDisplayed()
        // 默认"本月"一段流水都没有：整页空态，不摆空图（趋势区此时不渲染），但顶部范围入口还在
        composeRule.onNodeWithText("记一笔之后，这里会给出收入、支出、结余与环比。").assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(DAILY_CHART_TEST_TAG)).assertCountEquals(0)

        val six = ReportRange.lastMonths(thisMonth(), 6)
        openRangeDialog(monthLabel(thisMonth()))
        pickQuick("last6")
        awaitTrend(six)
        trendNode(six).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("最高 ¥600.00").performScrollTo().assertIsDisplayed()
    }

    /** 整个范围都没有记录：保持原来的纯空状态，不摆一张空图 */
    @Test
    fun anEmptyRange_keepsThePlainEmptyState() {
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
                    range = ReportRange.Month(month),
                    points = listOf(MonthlyTotal(month, incomeCents = 0L, expenseCents = 60_000L)),
                    daily = daily,
                    isLoading = false,
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
        composeRule.onAllNodesWithText("1日").assertCountEquals(1)
        composeRule.onAllNodesWithText("2日").assertCountEquals(0)
        composeRule.onAllNodesWithText("31日").assertCountEquals(0)
        composeRule.onAllNodesWithText(DAY_LABEL_SCROLL_HINT).assertCountEquals(1)
    }

    /**
     * AA-4：相邻柱子必须是各自独立的无障碍节点，TalkBack 不该把三天读成一句长话。
     *
     * 机制上靠两件事：格子内部 `mergeDescendants = true`（把两根柱合成一天的读法），
     * 容器层不用 mergeDescendants（不把相邻天数合并）。这里直接断言结果：
     * 每天一条描述、每条只含一个日期、父容器总述不吞子节点。
     */
    @Test
    fun adjacentBars_readAsSeparateDays_notOneMergedSentence() {
        val month = YearMonth.of(2026, 10)
        val daily = (1..3).map { day ->
            DailyTotal(
                date = month.atDay(day),
                incomeCents = 0L,
                expenseCents = day * 10_000L,
            )
        }
        composeRule.setContent {
            MaterialTheme {
                TrendSection(
                    range = ReportRange.Month(month),
                    points = listOf(MonthlyTotal(month, incomeCents = 0L, expenseCents = 60_000L)),
                    daily = daily,
                    isLoading = false,
                    onDayClick = {},
                )
            }
        }

        val dayNodes = composeRule
            .onAllNodes(hasContentDescription("日：支出", substring = true))
            .fetchSemanticsNodes()
        assertEquals("三天应各是一个独立节点", 3, dayNodes.size)

        val descriptions = dayNodes.map { node ->
            node.config[SemanticsProperties.ContentDescription].first()
        }
        val datePattern = Regex("""\d+月\d+日""")
        descriptions.forEach { text ->
            assertEquals("每条描述只该带自己那一天：「$text」", 1, datePattern.findAll(text).count())
        }
        assertEquals("三天的描述不应重复", 3, descriptions.toSet().size)

        // 图表容器的总述仍在（"本月逐日收支，共 3 天…"），但里面没有某一天的明细——
        // 说明容器没有把子节点合并成一句话。
        val chartDescription = composeRule
            .onNode(hasTestTag(DAILY_CHART_TEST_TAG))
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .first()
        assertTrue("整图描述应保留汇总：「$chartDescription」", chartDescription.contains("共 3 天"))
        assertEquals(
            "整图描述不该混入单日明细：「$chartDescription」",
            0,
            Regex("""\d+月\d+日：""").findAll(chartDescription).count(),
        )
    }
}
