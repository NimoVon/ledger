package com.nimo.ledger.ui.reports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.MainDispatcherRule
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionInput
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.stats.BudgetStatus
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 单月分析（附.1 R2）的取数：逐日聚合、预算达成率、当日流水明细。
 *
 * 走真实 Repository + in-memory Room。三条最容易被写错的规则都在这里钉住：
 * 未来日期不画、转账/余额调整不进统计但当日明细里要看得到、预算达成率只在设了总预算时才有值。
 */
@RunWith(RobolectricTestRunner::class)
class ReportsMonthAnalysisTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = LocalDate.parse("2026-10-12")
    private val october = YearMonth.of(2026, 10)

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private var cashAccountId = 0L
    private var debitAccountId = 0L
    private var foodId = 0L
    private var salaryId = 0L

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

    private suspend fun seed() {
        cashAccountId = repository.createAccount("现金", AccountType.CASH, 1_000_000L).getOrThrow()
        debitAccountId = repository.createAccount("招行卡", AccountType.DEBIT, 0L).getOrThrow()
        foodId = db.categoryDao().upsert(
            CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, icon = "🍜", sortOrder = 0),
        )
        salaryId = db.categoryDao().upsert(
            CategoryEntity(name = "工资", kind = CategoryKind.INCOME, icon = "💼", sortOrder = 0),
        )
    }

    private suspend fun addExpense(amount: Long, date: String): Long =
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = amount,
                accountId = cashAccountId,
                categoryId = foodId,
                occurredOn = date,
            ),
        ).getOrThrow()

    private suspend fun addIncome(amount: Long, date: String): Long =
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.INCOME,
                amountCents = amount,
                accountId = cashAccountId,
                categoryId = salaryId,
                occurredOn = date,
            ),
        ).getOrThrow()

    private suspend fun addTransfer(amount: Long, date: String): Long =
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.TRANSFER,
                amountCents = amount,
                accountId = cashAccountId,
                toAccountId = debitAccountId,
                occurredOn = date,
            ),
        ).getOrThrow()

    private fun newViewModel(
        pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
    ) = ReportsViewModel(repository, today = { today }, pendingDeletionIds = pendingDeletionIds)

    private suspend fun ReportsViewModel.awaitLoaded(): ReportsUiState = uiState.first { !it.isLoading }

    private suspend fun ReportsViewModel.awaitSpan(span: TrendSpan): ReportsUiState =
        uiState.first { !it.isLoading && !it.isTrendLoading && it.trendSpan == span }

    private suspend fun ReportsViewModel.awaitDayDetail(date: LocalDate): DayDetailUiState =
        uiState.first { it.dayDetail?.date == date && it.dayDetail?.isLoading == false }.dayDetail!!

    // ---- 逐日聚合（StatsCalculator.dailyTotals 的端到端口径） ----

    @Test
    fun dailyBars_coverOnlyTheDaysThatHavePassed() = runTest {
        seed()
        addExpense(1_200L, "2026-10-01")
        addExpense(300L, "2026-10-05")
        addIncome(50_000L, "2026-10-12")
        addExpense(9_999L, "2026-10-20") // 未来：既不该画出来，也不该被折进别的天

        val state = newViewModel().awaitLoaded()

        assertEquals(12, state.elapsedDays)
        assertEquals(12, state.daily.size)
        assertEquals((1..12).map { october.atDay(it) }, state.daily.map { it.date })
        assertEquals(1_200L, state.daily[0].expenseCents)
        assertEquals(300L, state.daily[4].expenseCents)
        assertEquals(50_000L, state.daily[11].incomeCents)
        // 柱子只覆盖已过的天：1 200 + 300 = 1 500（10-20 那笔是未来，不画）
        assertEquals(1_500L, state.daily.sumOf { it.expenseCents })
        assertEquals(50_000L, state.daily.sumOf { it.incomeCents })
        // 顶部合计是整月区间（与明细页同一口径）：含未来的 10-20 那笔 9 999 → 11 499
        assertEquals(11_499L, state.summary.expenseCents)
        assertEquals(50_000L, state.summary.incomeCents)
    }

    @Test
    fun dailyBars_followTheViewedMonth_whenTheSegmentChanges() = runTest {
        seed()
        addExpense(1_200L, "2026-10-01")
        addExpense(2_000L, "2026-09-05")

        val viewModel = newViewModel()
        val month = viewModel.awaitLoaded()
        assertEquals(1, month.trend.size)

        viewModel.setTrendSpan(TrendSpan.FULL_YEAR)
        val full = viewModel.awaitSpan(TrendSpan.FULL_YEAR)

        // 换的是画法，不是这个月的逐日数据
        assertEquals(12, full.trend.size)
        assertEquals(month.daily, full.daily)
        assertEquals(month.elapsedDays, full.elapsedDays)
    }

    @Test
    fun dailyBars_switchMonthWithTheHeader() = runTest {
        seed()
        addExpense(2_000L, "2026-09-05")
        addExpense(400L, "2026-09-30")

        val viewModel = newViewModel()
        viewModel.awaitLoaded()
        viewModel.showPreviousMonth()
        val september = viewModel.uiState.first { it.month == YearMonth.of(2026, 9) && !it.isLoading }

        // 9 月是过去的月份：整月 30 天都要画，不受"今天 12 号"限制
        assertEquals(30, september.elapsedDays)
        assertEquals(30, september.daily.size)
        // daily 是 1 号起的一天一格：9-05 在索引 4，9-30 在最后（索引 29）
        assertEquals(2_000L, september.daily[4].expenseCents)
        assertEquals(400L, september.daily.last().expenseCents)
    }

    // ---- 预算达成率（附.1 R2：设了总预算才显示） ----

    @Test
    fun budgetAchievement_isAbsentWithoutATotalBudget() = runTest {
        seed()
        addExpense(60_000L, "2026-10-01")

        val state = newViewModel().awaitLoaded()

        assertFalse(state.hasTotalBudget)
        assertNull(state.totalBudgetCents)
        assertNull(state.budgetAchievementRatio)
        assertEquals(BudgetStatus.NONE, state.budgetStatus)
    }

    @Test
    fun budgetAchievement_onlyCountsTheTotalBudgetOfTheViewedMonth() = runTest {
        seed()
        addExpense(60_000L, "2026-10-01")
        repository.setBudget("2026-10", foodId, 50_000L).getOrThrow()   // 只有分类预算
        repository.setBudget("2026-11", null, 500_000L).getOrThrow()    // 总预算在下个月

        val categoryOnly = newViewModel().awaitLoaded()

        assertNull(categoryOnly.totalBudgetCents)
        assertFalse(categoryOnly.hasTotalBudget)

        repository.setBudget("2026-10", null, 100_000L).getOrThrow()

        val state = newViewModel().awaitLoaded()

        assertTrue(state.hasTotalBudget)
        assertEquals(100_000L, state.totalBudgetCents)
        assertEquals(0.6, state.budgetAchievementRatio!!, 1e-9)
        assertEquals(BudgetStatus.OK, state.budgetStatus)
        // 达成率的分母是总预算、分子是本月支出：和摘要同口径
        assertEquals(state.summary.expenseCents, 60_000L)
    }

    @Test
    fun budgetAchievement_reactsToWarningAndOver() = runTest {
        seed()
        addExpense(90_000L, "2026-10-01")
        val viewModel = newViewModel()
        viewModel.awaitLoaded()

        repository.setBudget("2026-10", null, 100_000L).getOrThrow()
        val warning = viewModel.uiState.first { it.totalBudgetCents != null }

        assertEquals(0.9, warning.budgetAchievementRatio!!, 1e-9)
        assertEquals(BudgetStatus.WARNING, warning.budgetStatus)

        repository.setBudget("2026-10", null, 80_000L).getOrThrow()
        val over = viewModel.uiState.first { (it.budgetAchievementRatio ?: 0.0) > 1.0 }

        assertEquals(BudgetStatus.OVER, over.budgetStatus)
        assertEquals(1.125, over.budgetAchievementRatio!!, 1e-9)
    }

    // ---- 当日流水明细（点柱子） ----

    @Test
    fun selectingADay_listsThatDaysTransactions_newestFirst() = runTest {
        seed()
        val first = addExpense(1_200L, "2026-10-05")
        val second = addExpense(300L, "2026-10-05")
        addExpense(9_999L, "2026-10-06")

        val viewModel = newViewModel()
        viewModel.awaitLoaded()
        viewModel.setSelectedDay(LocalDate.parse("2026-10-05"))
        val detail = viewModel.awaitDayDetail(LocalDate.parse("2026-10-05"))

        // 同一天按 id 倒序（ORDER BY occurredOn DESC, id DESC）
        assertEquals(listOf(second, first), detail.rows.map { it.id })
        assertEquals(1_500L, detail.summary.expenseCents)
        assertEquals(0L, detail.summary.incomeCents)
        assertTrue(detail.hasRecords)
        assertFalse(detail.isEmpty)
        assertTrue(detail.label.contains("10月5日"))
    }

    @Test
    fun selectingADay_showsTransfers_butTheyStayOutOfTheSummary() = runTest {
        seed()
        addExpense(300L, "2026-10-05")
        addTransfer(500_000L, "2026-10-05")

        val viewModel = newViewModel()
        viewModel.awaitLoaded()
        viewModel.setSelectedDay(LocalDate.parse("2026-10-05"))
        val detail = viewModel.awaitDayDetail(LocalDate.parse("2026-10-05"))

        // 明细要看得见转账（它确实动过钱），但合计里一分都不算
        assertEquals(2, detail.rows.size)
        assertTrue(detail.rows.any { it.type == TransactionType.TRANSFER })
        assertEquals(300L, detail.summary.expenseCents)
        assertEquals(0L, detail.summary.incomeCents)
    }

    @Test
    fun selectingADayWithoutRecords_isAnEmptyDetail() = runTest {
        seed()
        addExpense(300L, "2026-10-05")

        val viewModel = newViewModel()
        viewModel.awaitLoaded()
        viewModel.setSelectedDay(LocalDate.parse("2026-10-07"))
        val detail = viewModel.awaitDayDetail(LocalDate.parse("2026-10-07"))

        assertTrue(detail.isEmpty)
        assertTrue(detail.rows.isEmpty())
        assertEquals(0L, detail.summary.expenseCents)
        assertFalse(detail.hasRecords)
    }

    @Test
    fun pendingDeletion_isExcludedFromTheDayDetail() = runTest {
        seed()
        val kept = addExpense(300L, "2026-10-05")
        val deleted = addExpense(1_200L, "2026-10-05")

        val viewModel = newViewModel(pendingDeletionIds = MutableStateFlow(setOf(deleted)))
        viewModel.awaitLoaded()
        viewModel.setSelectedDay(LocalDate.parse("2026-10-05"))
        val detail = viewModel.awaitDayDetail(LocalDate.parse("2026-10-05"))

        assertEquals(listOf(kept), detail.rows.map { it.id })
        assertEquals(300L, detail.summary.expenseCents)
    }

    @Test
    fun clearingTheSelection_closesTheDetail() = runTest {
        seed()
        addExpense(300L, "2026-10-05")

        val viewModel = newViewModel()
        viewModel.awaitLoaded()
        viewModel.setSelectedDay(LocalDate.parse("2026-10-05"))
        viewModel.awaitDayDetail(LocalDate.parse("2026-10-05"))

        viewModel.clearSelectedDay()

        assertNull(viewModel.uiState.first { it.dayDetail == null }.dayDetail)
    }
}
