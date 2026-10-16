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
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 趋势区三段开关（附.1 R2）：默认"本月"、切半年/全年只是换画法，折线窗口始终跟着"正在看的月份"走。
 *
 * R2 起趋势查询固定取近 12 个月，跨度只做视图切片——所以每一帧里
 * "选中的跨度"和"折线点数"必须对得上（下面最后那个逐帧不变量测试盯着这件事）。
 * 走真实 Repository + in-memory Room，验证端到端行为。
 */
@RunWith(RobolectricTestRunner::class)
class ReportsTrendTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 固定"今天"：默认查看 2026-10，全年窗口 = 2025-11 ~ 2026-10 */
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

    private suspend fun ReportsViewModel.awaitTrend(span: TrendSpan = TrendSpan.MONTH): ReportsUiState =
        uiState.first { !it.isLoading && !it.isTrendLoading && it.trendSpan == span }

    @Test
    fun defaultSpan_isTheViewedMonth_andTheTrendIsJustThatMonth() = runTest {
        seed()
        addExpense(1_000L, "2026-10-05")
        addIncome(50_000L, "2026-10-10")
        addExpense(2_000L, "2026-09-05")
        addExpense(9_999L, "2026-04-30")

        val state = newViewModel().awaitTrend()

        // 默认"本月"（附.1 R2）
        assertEquals(TrendSpan.MONTH, state.trendSpan)
        assertEquals(1, state.trend.size)
        assertEquals(YearMonth.of(2026, 10), state.trend.single().month)
        assertEquals(1_000L, state.trend.single().expenseCents)
        assertEquals(50_000L, state.trend.single().incomeCents)
        // 折线的最后一个月必须和标题上的月份一致，且合计与月报摘要同口径
        assertEquals(state.month, state.trend.last().month)
        assertEquals(state.summary.expenseCents, state.trend.last().expenseCents)
        assertEquals(state.summary.incomeCents, state.trend.last().incomeCents)
    }

    @Test
    fun switchingToHalfAYearAndFullYear_bringsOlderMonthsIn() = runTest {
        seed()
        addExpense(7_000L, "2025-11-20") // 只在全年窗口里
        addExpense(2_000L, "2026-09-05") // 只在半年/全年窗口里
        addExpense(1_000L, "2026-10-05")

        val viewModel = newViewModel()
        val month = viewModel.awaitTrend()
        assertEquals(1, month.trend.size)
        assertEquals(YearMonth.of(2026, 10), month.trend.single().month)

        viewModel.setTrendSpan(TrendSpan.HALF_YEAR)
        val half = viewModel.awaitTrend(TrendSpan.HALF_YEAR)
        assertEquals(
            listOf(YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10)),
            half.trend.map { it.month },
        )
        assertEquals(listOf(0L, 0L, 0L, 0L, 2_000L, 1_000L), half.trend.map { it.expenseCents })

        viewModel.setTrendSpan(TrendSpan.FULL_YEAR)
        val full = viewModel.awaitTrend(TrendSpan.FULL_YEAR)

        assertEquals(12, full.trend.size)
        assertEquals(YearMonth.of(2025, 11), full.trend.first().month)
        assertEquals(YearMonth.of(2026, 10), full.trend.last().month)
        assertEquals(7_000L, full.trend.first().expenseCents)
        assertEquals(1_000L, full.trend.last().expenseCents)
    }

    @Test
    fun switchingBack_returnsToTheViewedMonth() = runTest {
        seed()
        addExpense(1_000L, "2026-10-05")

        val viewModel = newViewModel()
        viewModel.awaitTrend()
        viewModel.setTrendSpan(TrendSpan.FULL_YEAR)
        viewModel.awaitTrend(TrendSpan.FULL_YEAR)

        viewModel.setTrendSpan(TrendSpan.HALF_YEAR)
        val half = viewModel.awaitTrend(TrendSpan.HALF_YEAR)
        assertEquals(6, half.trend.size)
        assertEquals(YearMonth.of(2026, 5), half.trend.first().month)

        viewModel.setTrendSpan(TrendSpan.MONTH)
        val back = viewModel.awaitTrend()
        assertEquals(1, back.trend.size)
        assertEquals(YearMonth.of(2026, 10), back.trend.single().month)
    }

    @Test
    fun movingToThePreviousMonth_movesTheWholeWindow() = runTest {
        seed()
        addExpense(1_000L, "2026-09-05")

        val viewModel = newViewModel()
        viewModel.awaitTrend()
        viewModel.showPreviousMonth()
        val september = viewModel.awaitTrend()

        assertEquals(YearMonth.of(2026, 9), september.month)
        assertEquals(1, september.trend.size)
        assertEquals(YearMonth.of(2026, 9), september.trend.single().month)
        assertEquals(1_000L, september.trend.single().expenseCents)

        // 切到半年：窗口整体左移，仍然以"正在看的月份"收尾
        viewModel.setTrendSpan(TrendSpan.HALF_YEAR)
        val half = viewModel.awaitTrend(TrendSpan.HALF_YEAR)
        assertEquals(
            listOf(YearMonth.of(2026, 4), YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9)),
            half.trend.map { it.month },
        )
        assertEquals(1_000L, half.trend.last().expenseCents)
    }

    @Test
    fun transfersNeverEnterTheTrend_evenInTheWindow() = runTest {
        seed()
        addExpense(1_000L, "2026-10-05")
        addTransfer(500_000L, "2026-09-09")

        val viewModel = newViewModel()
        val month = viewModel.awaitTrend()

        assertEquals(1_000L, month.trend.single().expenseCents)
        assertTrue(month.trend.single().incomeCents == 0L)

        viewModel.setTrendSpan(TrendSpan.FULL_YEAR)
        val full = viewModel.awaitTrend(TrendSpan.FULL_YEAR)

        // 整段窗口里的支出只有那 ¥10.00，转账一分都没进来
        assertEquals(1_000L, full.trend.sumOf { it.expenseCents })
        assertTrue(full.trend.all { it.incomeCents == 0L })
    }

    /**
     * 蓝军自检的那个坑：开关切到全年时，如果先更新开关、后拿到数据，
     * 中途会出现"标着全年、画的是半年"的状态。逐帧检查不变量。
     *
     * R2 起趋势查询固定 12 个月、跨度只切片，所以这条不变量应当从第一帧就成立。
     */
    @Test
    fun everyEmittedState_keepsTrendSpanAndTrendLengthConsistent() = runTest {
        seed()
        addExpense(1_000L, "2026-10-05")
        val viewModel = newViewModel()

        val seen = mutableListOf<ReportsUiState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { seen += it } }
        try {
            viewModel.awaitTrend()
            viewModel.setTrendSpan(TrendSpan.FULL_YEAR)
            viewModel.awaitTrend(TrendSpan.FULL_YEAR)
            viewModel.setTrendSpan(TrendSpan.MONTH)
            viewModel.awaitTrend()
        } finally {
            job.cancelAndJoin()
        }

        assertTrue(seen.isNotEmpty())
        seen.forEach { state ->
            if (!state.isTrendLoading) {
                assertEquals(
                    "跨度 ${state.trendSpan} 的折线点数不对",
                    state.trendSpan.months,
                    state.trend.size,
                )
                assertEquals(state.month, state.trend.last().month)
                // 本月视图的柱子根数必须等于"已经过去的天数"，未来日期不补 0
                if (state.trendSpan == TrendSpan.MONTH) {
                    assertEquals(state.elapsedDays, state.daily.size)
                }
            }
        }
    }
}
