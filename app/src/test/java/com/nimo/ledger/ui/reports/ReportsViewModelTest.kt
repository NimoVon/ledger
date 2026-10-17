package com.nimo.ledger.ui.reports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.MainDispatcherRule
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.local.toRecordOrNull
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionInput
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.stats.PeriodSummary
import com.nimo.ledger.domain.stats.ReportRange
import com.nimo.ledger.domain.stats.StatsCalculator
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
 * 月报（F07）：合计口径、分类排行（子分类并入父分类）、环比、切月、空态。
 * 走真实 Repository + in-memory Room，验证端到端行为而不是桩。
 */
@RunWith(RobolectricTestRunner::class)
class ReportsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 固定"今天"，让默认月份与标签可断言 */
    private val today = LocalDate.parse("2026-10-12")

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private var cashAccountId = 0L
    private var debitAccountId = 0L
    /** 一级：餐饮 */
    private var foodId = 0L
    /** 二级：午餐（父 = 餐饮） */
    private var lunchId = 0L
    /** 一级：交通 */
    private var trafficId = 0L
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
        cashAccountId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        debitAccountId = repository.createAccount("招行卡", AccountType.DEBIT, 0L).getOrThrow()
        foodId = db.categoryDao().upsert(
            CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, icon = "🍜", sortOrder = 0),
        )
        lunchId = db.categoryDao().upsert(
            CategoryEntity(
                name = "午餐",
                kind = CategoryKind.EXPENSE,
                parentId = foodId,
                icon = "🍱",
                sortOrder = 1,
            ),
        )
        trafficId = db.categoryDao().upsert(
            CategoryEntity(name = "交通", kind = CategoryKind.EXPENSE, icon = "🚇", sortOrder = 2),
        )
        salaryId = db.categoryDao().upsert(
            CategoryEntity(name = "工资", kind = CategoryKind.INCOME, icon = "💼", sortOrder = 0),
        )
    }

    private suspend fun addExpense(amount: Long, date: String, categoryId: Long? = null): Long =
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = amount,
                accountId = cashAccountId,
                categoryId = categoryId ?: foodId,
                occurredOn = date,
            ),
        ).getOrThrow()

    /** 指定账户记一笔支出（账户分布用） */
    private suspend fun addExpenseFrom(amount: Long, date: String, accountId: Long): Long =
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = amount,
                accountId = accountId,
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

    private fun newViewModel(pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet())) =
        ReportsViewModel(repository, today = { today }, pendingDeletionIds = pendingDeletionIds)

    private suspend fun ReportsViewModel.awaitLoaded(): ReportsUiState =
        uiState.first { !it.isLoading }

    private suspend fun ReportsViewModel.awaitRange(range: ReportRange): ReportsUiState =
        uiState.first { it.range == range && !it.isLoading }

    // ---- 合计与分类排行 ----

    @Test
    fun initialState_summaryAndBreakdownComeFromStatsCalculator() = runTest {
        seed()
        addExpense(12_500L, "2026-10-05")
        // 子分类的金额要并入父分类（计划 3.4）
        addExpense(3_000L, "2026-10-06", categoryId = lunchId)
        addExpense(1_500L, "2026-10-07", categoryId = trafficId)
        addIncome(500_000L, "2026-10-10")

        val state = newViewModel().awaitLoaded()

        assertEquals(ReportRange.Month(YearMonth.of(2026, 10)), state.range)
        assertEquals("2026年10月", state.rangeLabel)
        assertNull(state.errorMessage)
        assertFalse(state.isEmpty)
        assertEquals(17_000L, state.summary.expenseCents)
        assertEquals(500_000L, state.summary.incomeCents)
        assertEquals(483_000L, state.summary.netCents)

        // 排行：一级分类粒度、按金额降序
        assertEquals(listOf("餐饮", "交通"), state.breakdown.map { it.name })
        assertEquals(15_500L, state.breakdown[0].amountCents)
        assertEquals(1_500L, state.breakdown[1].amountCents)
        // 占比分母是本月支出合计，不是该分类所属 kind 的合计
        assertEquals(15_500.0 / 17_000.0, state.breakdown[0].percent!!, 1e-9)
        assertEquals("91.2%", ReportFormatting.percent(state.breakdown[0].percent))
    }

    @Test
    fun breakdown_excludesTransferAndBalanceAdjust() = runTest {
        seed()
        addExpense(1_000L, "2026-10-03")
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.TRANSFER,
                amountCents = 99_999L,
                accountId = cashAccountId,
                toAccountId = debitAccountId,
                occurredOn = "2026-10-04",
            ),
        ).getOrThrow()
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.BALANCE_ADJUST,
                amountCents = -5_000L,
                accountId = cashAccountId,
                occurredOn = "2026-10-05",
            ),
        ).getOrThrow()

        val state = newViewModel().awaitLoaded()

        assertEquals(1_000L, state.summary.expenseCents)
        assertEquals(0L, state.summary.incomeCents)
        assertEquals(1, state.breakdown.size)
        // 本月有三笔（含转账/调整），不是空月，只是它们不进收支
        assertTrue(state.hasRecords)
        // 与 StatsCalculator 对同一批数据的结果逐字一致（口径唯一）
        val rows = repository.observeTransactions("2026-10-01", "2026-11-01").first()
        assertEquals(StatsCalculator.summarize(rows.mapNotNull { it.toRecordOrNull() }), state.summary)
    }

    // ---- 环比 ----

    @Test
    fun changeRatio_comparesCurrentMonthWithTheOneBefore() = runTest {
        seed()
        addExpense(10_000L, "2026-09-15")
        addExpense(12_500L, "2026-10-05")
        addIncome(100_000L, "2026-09-20")
        addIncome(50_000L, "2026-10-20")

        val state = newViewModel().awaitLoaded()

        assertEquals(10_000L, state.previousSummary.expenseCents)
        assertEquals(0.25, state.expenseChangeRatio!!, 1e-9)
        assertEquals(-0.5, state.incomeChangeRatio!!, 1e-9)
        assertEquals("较上月 25.0%", ReportFormatting.change(state.expenseChangeRatio))
        assertEquals("较上月 50.0%", ReportFormatting.change(state.incomeChangeRatio))
    }

    @Test
    fun changeRatio_isNullWhenThePreviousMonthIsEmpty() = runTest {
        seed()
        addExpense(1_000L, "2026-10-05")

        val state = newViewModel().awaitLoaded()

        assertEquals(0L, state.previousSummary.expenseCents)
        assertNull(state.expenseChangeRatio)
        assertNull(state.incomeChangeRatio)
        assertEquals("较上月 —", ReportFormatting.change(state.expenseChangeRatio))
    }

    // ---- 切月：本月的合计与"它的上个月"一起换 ----

    @Test
    fun monthSwitch_bringsItsOwnSummaryAndItsOwnPreviousMonth() = runTest {
        seed()
        addExpense(300L, "2026-08-31")
        addExpense(700L, "2026-09-30")
        addExpense(1_100L, "2026-10-01")

        val vm = newViewModel()
        val october = vm.awaitLoaded()
        assertEquals("2026年10月", october.rangeLabel)
        assertEquals(1_100L, october.summary.expenseCents)
        assertEquals(700L, october.previousSummary.expenseCents)
        assertEquals("较上月 57.1%", ReportFormatting.change(october.expenseChangeRatio))

        vm.showPreviousRange()
        val september = vm.awaitRange(ReportRange.Month(YearMonth.of(2026, 9)))
        assertEquals("2026年9月", september.rangeLabel)
        assertEquals(700L, september.summary.expenseCents)
        assertEquals(300L, september.previousSummary.expenseCents)
        assertEquals("较上月 133.3%", ReportFormatting.change(september.expenseChangeRatio))

        vm.showNextRange()
        val back = vm.awaitRange(ReportRange.Month(YearMonth.of(2026, 10)))
        assertEquals(1_100L, back.summary.expenseCents)
    }

    @Test
    fun emptyMonth_isEmptyStateAndNotAnError() = runTest {
        seed()

        val state = newViewModel().awaitLoaded()

        assertTrue(state.isEmpty)
        assertNull(state.errorMessage)
        assertTrue(state.breakdown.isEmpty())
        assertEquals(PeriodSummary(incomeCents = 0, expenseCents = 0), state.summary)
    }

    // ---- 撤销窗口内那几笔：与明细页/账户页同一口径 ----

    @Test
    fun pendingDeletion_isExcludedFromSummaryAndBreakdown() = runTest {
        seed()
        val deleted = addExpense(12_500L, "2026-10-05")
        addExpense(10_000L, "2026-09-05")

        val pending = MutableStateFlow<Set<Long>>(emptySet())
        val vm = newViewModel(pendingDeletionIds = pending)
        assertEquals(12_500L, vm.awaitLoaded().summary.expenseCents)

        // 删除后倒计时内：数据库里其实还在，但月报必须当它不存在
        pending.value = setOf(deleted)
        val state = vm.uiState.first { !it.isLoading && it.summary.expenseCents == 0L }

        assertTrue(state.breakdown.isEmpty())
        assertFalse(state.hasRecords)
        assertTrue(state.isEmpty)
    }

    // ---- 脏数据防御：非法 occurredOn 不能让整页崩掉 ----

    @Test
    fun malformedDateRow_isExcludedFromTotalsWithoutCrash() = runTest {
        seed()
        addExpense(1_000L, "2026-10-03")
        // 绕过 TransactionValidator 直插非法日期：数据库触发器只校验金额与账户/分类，不校验日期格式
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO transactions (type, amount, accountId, toAccountId, categoryId, occurredOn, note, createdAt, updatedAt) " +
                "VALUES ('EXPENSE', 9999, $cashAccountId, NULL, $foodId, '2026-10-XX', NULL, 0, 0)",
        )

        val state = newViewModel().awaitLoaded()

        assertEquals(1_000L, state.summary.expenseCents)
        assertEquals(1_000L, state.breakdown.single().amountCents)
        // 脏行仍然算"本月有记录"，不该把整页推成空态
        assertTrue(state.hasRecords)
    }

    @Test
    fun newTransaction_appearsWithoutManualRefresh() = runTest {
        seed()
        val vm = newViewModel()
        assertTrue(vm.awaitLoaded().isEmpty)

        addExpense(2_500L, "2026-10-12")

        // 月报观察的是同一份 Room Flow：记一笔保存后无需手动刷新
        val state = vm.uiState.first { it.summary.expenseCents == 2_500L }
        assertEquals("餐饮", state.breakdown.single().name)
    }

    @Test
    fun accountBreakdown_splitsExpensesByAccountAndSkipsTransfers() = runTest {
        seed()
        addExpense(10_000L, "2026-10-05")
        addExpenseFrom(4_000L, "2026-10-06", debitAccountId)
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.TRANSFER,
                amountCents = 99_999L,
                accountId = cashAccountId,
                toAccountId = debitAccountId,
                occurredOn = "2026-10-07",
            ),
        ).getOrThrow()

        val state = newViewModel().awaitLoaded()

        assertEquals(14_000L, state.summary.expenseCents)
        assertEquals(listOf("现金", "招行卡"), state.accountBreakdown.map { it.name })
        assertEquals(10_000L, state.accountBreakdown[0].amountCents)
        assertEquals(4_000L, state.accountBreakdown[1].amountCents)
        assertEquals(10_000.0 / 14_000.0, state.accountBreakdown[0].percent!!, 1e-9)
    }

    @Test
    fun accountBreakdown_keepsArchivedAccountNames() = runTest {
        seed()
        addExpenseFrom(4_000L, "2026-10-06", debitAccountId)
        repository.setAccountArchived(debitAccountId, true).getOrThrow()

        val state = newViewModel().awaitLoaded()

        // 归档只是不再出现在选择器里，历史流水的账户名照样要显示
        assertEquals("招行卡", state.accountBreakdown.single().name)
        assertEquals(4_000L, state.accountBreakdown.single().amountCents)
    }

    @Test
    fun dailyAverage_dividesByElapsedDaysOfTheViewedMonth() = runTest {
        seed()
        addExpense(31_000L, "2026-10-05")
        addExpense(30_000L, "2026-09-10")

        val vm = newViewModel()
        // 今天 = 2026-10-12 → 当月分母是 12，不是 31
        assertEquals(2_583L, vm.awaitLoaded().dailyAverageExpenseCents)

        vm.showPreviousRange()
        val september = vm.awaitRange(ReportRange.Month(YearMonth.of(2026, 9)))
        // 已结束的月份按整月天数（30 天）：30_000 / 30 = 1_000
        assertEquals(1_000L, september.dailyAverageExpenseCents)
    }

    // ---- R1 日期直改：统计页直接 set 到指定年月（合计、环比一起换） ----

    @Test
    fun setMonth_bringsThatMonthsSummaryAndItsPreviousMonthAcrossTheYearBoundary() = runTest {
        seed()
        addExpense(300L, "2025-12-31")
        addExpense(900L, "2026-01-05")

        val vm = newViewModel()
        vm.awaitLoaded()

        // 跨年往回：2026-10 → 2026-01，环比的上个月要跟着跨到 2025-12
        vm.setMonth(YearMonth.of(2026, 1))
        val january = vm.awaitRange(ReportRange.Month(YearMonth.of(2026, 1)))
        assertEquals("2026年1月", january.rangeLabel)
        assertEquals(900L, january.summary.expenseCents)
        assertEquals(300L, january.previousSummary.expenseCents)
        assertEquals("较上月 200.0%", ReportFormatting.change(january.expenseChangeRatio))

        // 再往回一年的 12 月：上月没有记录 → 环比无意义
        vm.setMonth(YearMonth.of(2025, 12))
        val december = vm.awaitRange(ReportRange.Month(YearMonth.of(2025, 12)))
        assertEquals("2025年12月", december.rangeLabel)
        assertEquals(300L, december.summary.expenseCents)
        assertEquals(0L, december.previousSummary.expenseCents)
        assertNull(december.expenseChangeRatio)
    }

    @Test
    fun setMonth_toDecember_stopsBeforeNextYearJanuarySecond() = runTest {
        seed()
        addExpense(100L, "2026-11-30")
        addExpense(700L, "2026-12-31")
        addExpense(500L, "2027-01-02")

        val vm = newViewModel()
        vm.awaitLoaded()

        vm.setMonth(YearMonth.of(2026, 12))
        val december = vm.awaitRange(ReportRange.Month(YearMonth.of(2026, 12)))
        assertEquals("2026年12月", december.rangeLabel)
        // 12 月区间右端是 2027-01-01（不含）：2027-01-02 不算进 12 月
        assertEquals(700L, december.summary.expenseCents)
        assertEquals(100L, december.previousSummary.expenseCents)

        vm.setMonth(YearMonth.of(2027, 1))
        val january = vm.awaitRange(ReportRange.Month(YearMonth.of(2027, 1)))
        assertEquals(500L, january.summary.expenseCents)
        assertEquals(700L, january.previousSummary.expenseCents)
        assertEquals("较上月 28.6%", ReportFormatting.change(january.expenseChangeRatio))

        // 直选与箭头等价：从 2027-01 退一个月，回到同一个 2026-12
        vm.showPreviousRange()
        assertEquals(700L, vm.awaitRange(ReportRange.Month(YearMonth.of(2026, 12))).summary.expenseCents)
    }
}
