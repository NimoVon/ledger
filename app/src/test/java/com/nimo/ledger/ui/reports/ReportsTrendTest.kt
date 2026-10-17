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
import com.nimo.ledger.domain.stats.ReportRange
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 统计范围（本轮改造）：整页只有一个时间口径，趋势点数始终等于范围覆盖的月份数。
 *
 * 旧版「全年」是以当前月为终点的**近 12 个月滚动窗口**；改造后「今年」是**自然年 1–12 月**，
 * 两者的差值正是 [switchingToCalendarYear_excludesLastYearsMonths] 盯着的事。
 * 走真实 Repository + in-memory Room，验证端到端行为。
 */
@RunWith(RobolectricTestRunner::class)
class ReportsTrendTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 固定"今天"：默认查看 2026-10 */
    private val today = LocalDate.parse("2026-10-12")

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

    private fun newViewModel() = ReportsViewModel(repository, today = { today })

    private val month2026_10: ReportRange = ReportRange.Month(YearMonth.of(2026, 10))

    private suspend fun ReportsViewModel.awaitRange(range: ReportRange = month2026_10): ReportsUiState =
        uiState.first { !it.isLoading && !it.isTrendLoading && it.range == range }

    @Test
    fun defaultRange_isTheViewedMonth_andTheTrendIsJustThatMonth() = runTest {
        seed()
        addExpense(1_000L, "2026-10-05")
        addIncome(50_000L, "2026-10-10")
        addExpense(2_000L, "2026-09-05")
        addExpense(9_999L, "2026-04-30")

        val state = newViewModel().awaitRange()

        assertEquals(month2026_10, state.range)
        assertEquals("2026年10月", state.rangeLabel)
        assertEquals(1, state.trend.size)
        assertEquals(YearMonth.of(2026, 10), state.trend.single().month)
        assertEquals(1_000L, state.trend.single().expenseCents)
        assertEquals(50_000L, state.trend.single().incomeCents)
        // 折线的最后一个月必须和标题上的范围一致，且合计与摘要同口径
        assertEquals(YearMonth.from(state.range.from), state.trend.last().month)
        assertEquals(state.summary.expenseCents, state.trend.last().expenseCents)
        assertEquals(state.summary.incomeCents, state.trend.last().incomeCents)
    }

    @Test
    fun switchingToLastSixMonths_bringsOlderMonthsIn() = runTest {
        seed()
        addExpense(7_000L, "2025-11-20")
        addExpense(2_000L, "2026-09-05")
        addExpense(1_000L, "2026-10-05")

        val viewModel = newViewModel()
        val month = viewModel.awaitRange()
        assertEquals(1, month.trend.size)

        val six = ReportRange.lastMonths(YearMonth.of(2026, 10), 6)
        viewModel.setRange(six)
        val half = viewModel.awaitRange(six)
        assertEquals(
            listOf(
                YearMonth.of(2026, 5),
                YearMonth.of(2026, 6),
                YearMonth.of(2026, 7),
                YearMonth.of(2026, 8),
                YearMonth.of(2026, 9),
                YearMonth.of(2026, 10),
            ),
            half.trend.map { it.month },
        )
        assertEquals(listOf(0L, 0L, 0L, 0L, 2_000L, 1_000L), half.trend.map { it.expenseCents })
        assertEquals("近6个月", half.rangeLabel)
        // 跨月区间没有"月预算"这个概念
        assertFalse(half.hasTotalBudget)
    }

    @Test
    fun switchingToCalendarYear_excludesLastYearsMonths() = runTest {
        seed()
        addExpense(7_000L, "2025-11-20") // 近 12 个月里有它，自然年 2026 里没有
        addExpense(2_000L, "2026-09-05")
        addExpense(1_000L, "2026-10-05")

        val viewModel = newViewModel()
        viewModel.awaitRange()

        val year = ReportRange.year(2026)
        viewModel.setRange(year)
        val state = viewModel.awaitRange(year)

        assertEquals("2026年", state.rangeLabel)
        assertEquals(12, state.trend.size)
        assertEquals(YearMonth.of(2026, 1), state.trend.first().month)
        assertEquals(YearMonth.of(2026, 12), state.trend.last().month)
        // 去年 11 月那笔一分都不该进来：自然年不是滚动 12 个月
        assertEquals(3_000L, state.trend.sumOf { it.expenseCents })
        // 未来的月份补 0，不是缺项
        assertEquals(0L, state.trend.last().expenseCents)
    }

    @Test
    fun switchingBack_returnsToTheViewedMonth() = runTest {
        seed()
        addExpense(1_000L, "2026-10-05")

        val viewModel = newViewModel()
        viewModel.awaitRange()
        val year = ReportRange.year(2026)
        viewModel.setRange(year)
        viewModel.awaitRange(year)

        val six = ReportRange.lastMonths(YearMonth.of(2026, 10), 6)
        viewModel.setRange(six)
        val half = viewModel.awaitRange(six)
        assertEquals(6, half.trend.size)
        assertEquals(YearMonth.of(2026, 5), half.trend.first().month)

        viewModel.setRange(month2026_10)
        val back = viewModel.awaitRange()
        assertEquals(1, back.trend.size)
        assertEquals(YearMonth.of(2026, 10), back.trend.single().month)
    }

    @Test
    fun movingToThePreviousRange_movesTheWholeWindow() = runTest {
        seed()
        addExpense(1_000L, "2026-09-05")

        val viewModel = newViewModel()
        viewModel.awaitRange()
        viewModel.showPreviousRange()
        val september = viewModel.awaitRange(ReportRange.Month(YearMonth.of(2026, 9)))

        assertEquals(YearMonth.of(2026, 9), YearMonth.from(september.range.from))
        assertEquals(1, september.trend.size)
        assertEquals(1_000L, september.trend.single().expenseCents)

        // 切到近 6 个月后箭头仍然按"一整段"走，不是按天也不是回到单月
        val six = ReportRange.lastMonths(YearMonth.of(2026, 9), 6)
        viewModel.setRange(six)
        viewModel.awaitRange(six)
        viewModel.showPreviousRange()
        // 一次挪一整段：近 6 个月往回翻 = 再往前 6 个月，恰好是洞察区环比用的那一段，不重叠
        val previousSix = viewModel.awaitRange(ReportRange.lastMonths(YearMonth.of(2026, 3), 6))
        assertEquals(
            listOf(
                YearMonth.of(2025, 10),
                YearMonth.of(2025, 11),
                YearMonth.of(2025, 12),
                YearMonth.of(2026, 1),
                YearMonth.of(2026, 2),
                YearMonth.of(2026, 3),
            ),
            previousSix.trend.map { it.month },
        )
        assertEquals(0L, previousSix.trend.sumOf { it.expenseCents })
    }

    @Test
    fun transfersNeverEnterTheTrend_evenInTheRange() = runTest {
        seed()
        addExpense(1_000L, "2026-10-05")
        addTransfer(500_000L, "2026-09-09")

        val viewModel = newViewModel()
        val month = viewModel.awaitRange()

        assertEquals(1_000L, month.trend.single().expenseCents)
        assertTrue(month.trend.single().incomeCents == 0L)

        val year = ReportRange.year(2026)
        viewModel.setRange(year)
        val full = viewModel.awaitRange(year)

        // 整年里的支出只有那 ¥10.00，转账一分都没进来
        assertEquals(1_000L, full.trend.sumOf { it.expenseCents })
        assertTrue(full.trend.all { it.incomeCents == 0L })
    }

    /**
     * 蓝军自检的那个坑：切范围时如果先更新标题、后拿到数据，
     * 中途会出现"标着今年、画的是上个月"的状态。逐帧检查不变量。
     *
     * 范围是唯一口径，所以「范围覆盖的月份数」与「折线点数」必须从第一帧就对得上。
     */
    @Test
    fun everyEmittedState_keepsRangeAndTrendLengthConsistent() = runTest {
        seed()
        addExpense(1_000L, "2026-10-05")
        val viewModel = newViewModel()

        val seen = mutableListOf<ReportsUiState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { seen += it } }
        val year = ReportRange.year(2026)
        try {
            viewModel.awaitRange()
            viewModel.setRange(year)
            viewModel.awaitRange(year)
            viewModel.setRange(month2026_10)
            viewModel.awaitRange()
        } finally {
            job.cancelAndJoin()
        }

        assertTrue(seen.isNotEmpty())
        seen.forEach { state ->
            if (!state.isTrendLoading) {
                assertEquals(
                    "范围 ${state.range} 的折线点数不对",
                    state.range.months.size,
                    state.trend.size,
                )
                // 折线月份列表与范围覆盖的月份逐个对齐（自然年是 1–12 月，近 N 个月以终点月收尾）
                assertEquals(state.range.months, state.trend.map { it.month })
                // 按天画法的柱子根数必须等于"已经过去的天数"，未来日期不补 0
                if (state.range.bucket == com.nimo.ledger.domain.stats.ReportBucket.DAY) {
                    assertEquals(state.elapsedDays, state.daily.size)
                } else {
                    assertTrue(state.daily.isEmpty())
                }
            }
        }
    }
}
