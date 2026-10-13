package com.nimo.bookkeeping.ui.transactions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.MainDispatcherRule
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.toRecord
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.filter.TransactionFilter
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.stats.StatsCalculator
import com.nimo.bookkeeping.ui.DrillDownRequests
import com.nimo.bookkeeping.ui.reports.ReportDrillDown
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first
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
 * 流水时间线（F05）：按月查询、按日分组、合计口径、归档账户历史显示。
 * 走真实 Repository + in-memory Room，验证的是端到端行为而不是桩。
 */
@RunWith(RobolectricTestRunner::class)
class TransactionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 固定"今天"，让月/日标签可断言 */
    private val today = LocalDate.parse("2026-10-12")

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private var cashAccountId = 0L
    private var debitAccountId = 0L
    private var expenseCategoryId = 0L
    private var incomeCategoryId = 0L

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
        expenseCategoryId = db.categoryDao().upsert(
            CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, icon = "🍜", sortOrder = 0),
        )
        incomeCategoryId = db.categoryDao().upsert(
            CategoryEntity(name = "工资", kind = CategoryKind.INCOME, icon = "💼", sortOrder = 0),
        )
    }

    private suspend fun addExpense(amount: Long, date: String, note: String? = null): Long =
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = amount,
                accountId = cashAccountId,
                categoryId = expenseCategoryId,
                occurredOn = date,
                note = note,
            ),
        ).getOrThrow()

    private suspend fun addIncome(amount: Long, date: String): Long =
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.INCOME,
                amountCents = amount,
                accountId = cashAccountId,
                categoryId = incomeCategoryId,
                occurredOn = date,
            ),
        ).getOrThrow()

    private fun newViewModel() = TransactionsViewModel(repository, today = { today })

    private suspend fun TransactionsViewModel.awaitLoaded(): TransactionsUiState =
        uiState.first { !it.isLoading }

    private suspend fun TransactionsViewModel.awaitMonth(month: YearMonth): TransactionsUiState =
        uiState.first { it.month == month && !it.isLoading }

    // ---- 初始加载 ----

    @Test
    fun initialState_loadsCurrentMonthGroupedWithTotals() = runTest {
        seed()
        addExpense(1_250L, "2026-10-12", note = "午餐")
        addIncome(500_000L, "2026-10-12")

        val state = newViewModel().awaitLoaded()

        assertEquals(YearMonth.of(2026, 10), state.month)
        assertEquals("2026年10月", state.monthLabel)
        assertNull(state.errorMessage)
        assertFalse(state.isEmpty)
        assertEquals(1_250L, state.summary.expenseCents)
        assertEquals(500_000L, state.summary.incomeCents)
        assertEquals(498_750L, state.summary.netCents)

        val day = state.groups.single()
        assertEquals("2026-10-12", day.date)
        assertEquals("今天", day.label)
        // 同日两笔：id 大的（后录入的收入）在前
        assertEquals(2, day.rows.size)
        assertTrue(day.rows[0].id > day.rows[1].id)
        assertEquals("工资", day.rows[0].categoryName)
        assertEquals("💼", day.rows[0].categoryIcon)
        assertEquals("现金", day.rows[0].accountLabel)
        assertEquals("餐饮", day.rows[1].categoryName)
        assertEquals("午餐", day.rows[1].note)
        assertEquals(1_250L, day.rows[1].amountCents)
    }

    @Test
    fun emptyMonth_flagsEmptyStateInsteadOfBlankPage() = runTest {
        seed()
        val state = newViewModel().awaitLoaded()

        assertTrue(state.isEmpty)
        assertTrue(state.groups.isEmpty())
        assertEquals(0L, state.summary.expenseCents)
        assertEquals(0L, state.summary.incomeCents)
        assertEquals("2026年10月", state.monthLabel)
    }

    // ---- 合计口径：转账与余额调整不进收支 ----

    @Test
    fun monthSummary_excludesTransferAndBalanceAdjust() = runTest {
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
        // 页面合计必须与 StatsCalculator 对同一批数据的结果一致（口径唯一）
        val rows = repository.observeTransactions("2026-10-01", "2026-11-01").first()
        assertEquals(
            StatsCalculator.summarize(rows.map { it.toRecord() }),
            state.summary,
        )
        // 三笔都在时间线里（转账/调整只是不进合计）
        assertEquals(3, state.groups.sumOf { it.rows.size })
    }

    // ---- 脏数据防御：非法 occurredOn 不能让整页崩掉 ----

    @Test
    fun malformedDateRow_isShownButExcludedFromTotalsWithoutCrash() = runTest {
        seed()
        addExpense(1_000L, "2026-10-03")
        // 绕过 TransactionValidator 直插非法日期：数据库触发器只校验金额与账户/分类，不校验日期格式。
        // 备份导入（F08）等路径一旦出现缺口，就会产生这种行。
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO transactions (type, amount, accountId, toAccountId, categoryId, occurredOn, note, createdAt, updatedAt) " +
                "VALUES ('EXPENSE', 9999, $cashAccountId, NULL, $expenseCategoryId, '2026-10-XX', NULL, 0, 0)",
        )

        val state = newViewModel().awaitLoaded()

        // 页面照常渲染：非法行仍然可见（不能让用户的数据凭空消失）
        assertEquals(2, state.groups.sumOf { it.rows.size })
        // 但不计入任何合计，也不能把整个流水页拖崩
        assertEquals(1_000L, state.summary.expenseCents)
        assertEquals(0L, state.summary.incomeCents)
        assertEquals(0L, state.groups.first { it.date == "2026-10-XX" }.expenseCents)
    }

    // ---- 按月切换：区间来自 Dates.monthBounds ----

    @Test
    fun monthSwitch_loadsAdjacentMonthByDateRange() = runTest {
        seed()
        addExpense(300L, "2026-09-30")
        addExpense(700L, "2026-10-01")

        val vm = newViewModel()
        assertEquals(listOf("2026-10-01"), vm.awaitLoaded().groups.flatMap { g -> g.rows.map { it.occurredOn } })

        vm.showPreviousMonth()
        val september = vm.awaitMonth(YearMonth.of(2026, 9))
        assertEquals("2026年9月", september.monthLabel)
        assertEquals(listOf("2026-09-30"), september.groups.map { it.date })
        assertEquals(300L, september.summary.expenseCents)
        // 9月30日在"今天(10-12)"之前，显示日期而不是"今天"
        assertEquals("9月30日 周三", september.groups.single().label)

        vm.showNextMonth()
        val october = vm.awaitMonth(YearMonth.of(2026, 10))
        assertEquals(listOf("2026-10-01"), october.groups.map { it.date })
        assertEquals(700L, october.summary.expenseCents)
    }

    @Test
    fun monthSwitch_toMonthWithoutRecords_isEmptyButNotError() = runTest {
        seed()
        addExpense(100L, "2026-10-12")

        val vm = newViewModel()
        vm.awaitLoaded()

        vm.showNextMonth()
        val november = vm.awaitMonth(YearMonth.of(2026, 11))
        assertTrue(november.isEmpty)
        assertNull(november.errorMessage)
    }

    @Test
    fun newTransaction_appearsWithoutManualRefresh() = runTest {
        seed()
        val vm = newViewModel()
        assertTrue(vm.awaitLoaded().isEmpty)

        addExpense(2_500L, "2026-10-12")

        // 流水页观察的是同一份 Room Flow：记一笔保存后无需手动刷新
        val state = vm.uiState.first { it.groups.isNotEmpty() }
        assertEquals(2_500L, state.summary.expenseCents)
    }

    // ---- 同日稳定排序 ----

    @Test
    fun sameDayTransactions_keepIdDescendingOrder() = runTest {
        seed()
        val first = addExpense(100L, "2026-10-12")
        val second = addExpense(200L, "2026-10-12")
        val third = addExpense(300L, "2026-10-12")

        val rows = newViewModel().awaitLoaded().groups.single().rows
        assertEquals(listOf(third, second, first), rows.map { it.id })
    }

    // ---- 归档账户：不出现在记账页，但历史流水正常显示 ----

    @Test
    fun archivedAccount_historyStillShowsAccountName() = runTest {
        seed()
        addExpense(1_500L, "2026-10-12")
        repository.setAccountArchived(cashAccountId, archived = true).getOrThrow()

        val state = newViewModel().awaitLoaded()

        assertEquals("现金", state.groups.single().rows.single().accountLabel)
        assertEquals(1_500L, state.summary.expenseCents)
    }

    // ---- 其它月份的历史流水不受影响 ----

    @Test
    fun previousMonthRecords_areNotMixedIntoCurrentMonth() = runTest {
        seed()
        addExpense(100L, "2026-08-31")
        addExpense(200L, "2026-09-01")
        addExpense(400L, "2026-10-31")

        val state = newViewModel().awaitLoaded()

        assertEquals(listOf("2026-10-31"), state.groups.map { it.date })
        assertEquals(400L, state.summary.expenseCents)
    }

    // ---- 行点击入口（编辑/删除的结构预留） ----

    @Test
    fun rows_exposeIdForLaterEditOrDeleteEntry() = runTest {
        seed()
        val id = addExpense(1_100L, "2026-10-12")

        assertEquals(id, newViewModel().awaitLoaded().groups.single().rows.single().id)
    }

    // ---- 筛选与搜索（F06）：账户 / 分类（父命中子）/ 金额 / 关键词 / 日期 ----

    private suspend fun TransactionsViewModel.awaitFilter(filter: TransactionFilter): TransactionsUiState =
        uiState.first { it.filter == filter && !it.isLoading }

    @Test
    fun filter_byAccountKeepsOnlyThatAccountsRows() = runTest {
        seed()
        addExpense(1_000L, "2026-10-12")
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = 2_000L,
                accountId = debitAccountId,
                categoryId = expenseCategoryId,
                occurredOn = "2026-10-12",
            ),
        ).getOrThrow()

        val filter = TransactionFilter(accountIds = setOf(debitAccountId))
        val state = newViewModel().apply { applyFilter(filter) }.awaitFilter(filter)

        assertEquals(2_000L, state.groups.single().rows.single().amountCents)
        assertEquals(2_000L, state.summary.expenseCents)
        assertTrue("现金" !in state.groups.single().rows.single().accountLabel.orEmpty())
    }

    @Test
    fun filter_byParentCategoryAlsoKeepsTheChildRows() = runTest {
        seed()
        val childId = db.categoryDao().upsert(
            CategoryEntity(
                name = "午餐",
                kind = CategoryKind.EXPENSE,
                parentId = expenseCategoryId,
                sortOrder = 1,
            ),
        )
        addExpense(1_000L, "2026-10-12", note = "食堂")
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = 500L,
                accountId = cashAccountId,
                categoryId = childId,
                occurredOn = "2026-10-11",
            ),
        ).getOrThrow()

        val filter = TransactionFilter(categoryIds = setOf(expenseCategoryId))
        val state = newViewModel().apply { applyFilter(filter) }.awaitFilter(filter)

        assertEquals(listOf("2026-10-12", "2026-10-11"), state.groups.map { it.date })
        assertEquals(1_500L, state.summary.expenseCents)
        assertTrue(state.hasActiveFilter)
        assertTrue(state.filterChips.any { it == "分类：餐饮" })
    }

    @Test
    fun filter_byKeywordAndAmountRangeKeepsOnlyTheMatchingRow() = runTest {
        seed()
        addExpense(1_250L, "2026-10-12", note = "午餐")
        addExpense(980L, "2026-10-12", note = "晚饭")
        addExpense(3_000L, "2026-10-11", note = "午餐")

        val filter = TransactionFilter(
            minAmountCents = 1_000L,
            maxAmountCents = 2_000L,
            noteKeyword = "午",
        )
        val state = newViewModel().apply { applyFilter(filter) }.awaitFilter(filter)

        assertEquals(1_250L, state.groups.single().rows.single().amountCents)
        assertEquals(1_250L, state.summary.expenseCents)
        assertTrue(state.filterChips.any { it == "关键词：午" })
    }

    @Test
    fun filter_byDateRangeSpansMonthsAndTakesOverTheMonthSwitcher() = runTest {
        seed()
        addExpense(600L, "2026-08-31")
        addExpense(700L, "2026-09-20")
        addExpense(800L, "2026-10-12")
        addExpense(900L, "2026-11-01")

        val filter = TransactionFilter(
            from = LocalDate.parse("2026-09-01"),
            to = LocalDate.parse("2026-10-31"),
        )
        val state = newViewModel().apply { applyFilter(filter) }.awaitFilter(filter)

        assertEquals(listOf("2026-10-12", "2026-09-20"), state.groups.map { it.date })
        assertEquals(1_500L, state.summary.expenseCents)
        assertFalse(state.showsMonthSwitcher)
        assertTrue(state.filterChips.any { it == "日期：2026-09-01 ~ 2026-10-31" })
    }

    @Test
    fun filter_withNoMatchIsAFilteredEmptyStateNotAnError() = runTest {
        seed()
        addExpense(1_000L, "2026-10-12", note = "午餐")

        val filter = TransactionFilter(noteKeyword = "飞机")
        val state = newViewModel().apply { applyFilter(filter) }.awaitFilter(filter)

        assertTrue(state.groups.isEmpty())
        assertTrue(state.isEmpty)
        assertTrue(state.hasActiveFilter)
        assertNull(state.errorMessage)
        assertEquals(0L, state.summary.expenseCents)
    }

    @Test
    fun clearFilter_bringsBackTheWholeMonth() = runTest {
        seed()
        addExpense(1_000L, "2026-10-12", note = "午餐")
        addExpense(2_000L, "2026-10-11", note = "晚饭")

        val viewModel = newViewModel()
        viewModel.applyFilter(TransactionFilter(noteKeyword = "午餐"))
        assertEquals(1_000L, viewModel.awaitFilter(TransactionFilter(noteKeyword = "午餐")).summary.expenseCents)

        viewModel.clearFilter()
        val restored = viewModel.awaitFilter(TransactionFilter())

        assertEquals(2, restored.groups.sumOf { it.rows.size })
        assertEquals(3_000L, restored.summary.expenseCents)
        assertTrue(restored.filterChips.isEmpty())
        assertTrue(restored.showsMonthSwitcher)
    }

    @Test
    fun filterOptions_offerArchivedAccountsAndChildCategories() = runTest {
        seed()
        val childId = db.categoryDao().upsert(
            CategoryEntity(
                name = "午餐",
                kind = CategoryKind.EXPENSE,
                parentId = expenseCategoryId,
                sortOrder = 1,
            ),
        )
        repository.setAccountArchived(debitAccountId, archived = true).getOrThrow()
        addExpense(1_000L, "2026-10-12")

        val options = newViewModel().awaitLoaded().filterOptions

        assertEquals(2, options.accounts.size)
        assertTrue(options.accounts.any { it.id == debitAccountId && it.archived })
        assertTrue(options.parents(CategoryKind.EXPENSE).any { it.id == expenseCategoryId })
        // 子分类是"子"，不出现在顶层列表里
        assertTrue(options.parents(CategoryKind.EXPENSE).none { it.id == childId })
        assertTrue(options.children(expenseCategoryId).any { it.id == childId })
    }

    // ---- 下钻（F07）：统计页点一行 → 明细页带着条件落过来 ----

    @Test
    fun drillDown_appliesTheRequestedFilterAndConsumesItSoThatClearingSticks() = runTest {
        seed()
        addExpense(8_000L, "2026-10-12", note = "晚餐")
        addIncome(500_000L, "2026-10-12")

        val requests = DrillDownRequests()
        val viewModel = TransactionsViewModel(repository, today = { today }, drillDown = requests)
        // 统计页点了「餐饮」那一行：条件是"这个分类 + 报表当时看的那个月"
        val drilledFilter = ReportDrillDown.byCategory(YearMonth.of(2026, 10), expenseCategoryId)

        requests.request(drilledFilter)
        val drilled = viewModel.awaitFilter(drilledFilter)

        assertEquals(setOf(expenseCategoryId), drilled.filter.categoryIds)
        assertEquals(8_000L, drilled.summary.expenseCents)
        assertEquals(0L, drilled.summary.incomeCents)
        assertEquals(1, drilled.groups.sumOf { it.rows.size })
        assertTrue(drilled.filterChips.any { it == "分类：餐饮" })
        // 一次性请求：用掉即清，否则用户「清空」筛选后再切回来又会被这个过期请求套上条件
        assertNull(requests.pending.value)

        viewModel.clearFilter()
        val cleared = viewModel.awaitFilter(TransactionFilter())

        assertEquals(2, cleared.groups.sumOf { it.rows.size })
        assertTrue(cleared.filterChips.isEmpty())
        assertTrue(cleared.showsMonthSwitcher)
    }
}
