package com.nimo.ledger.ui.budgets

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.MainDispatcherRule
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.BudgetEntity
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.local.entity.TransactionEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.stats.BudgetStatus
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 预算页状态（F09）：树序、停用分类的取舍、父分类含子分类、三种状态、切月、撤销窗口。
 * 断言的是页面真正拿到的 [BudgetsUiState]，不是中间计算。
 */
@RunWith(RobolectricTestRunner::class)
class BudgetsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private val month = "2026-10"
    private val today = LocalDate.of(2026, 10, 20)

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

    private suspend fun seedCategory(
        name: String,
        kind: CategoryKind = CategoryKind.EXPENSE,
        parentId: Long? = null,
        sortOrder: Int = 0,
        isArchived: Boolean = false,
    ): Long = db.categoryDao().insert(
        CategoryEntity(
            name = name,
            kind = kind,
            parentId = parentId,
            sortOrder = sortOrder,
            isArchived = isArchived,
        ),
    )

    private suspend fun seedBudget(categoryId: Long?, amount: Long, month: String = "2026-10"): Long =
        db.budgetDao().insert(BudgetEntity(month = month, categoryId = categoryId, amount = amount))

    private suspend fun seedTransaction(
        type: TransactionType,
        amount: Long,
        categoryId: Long?,
        occurredOn: String = "2026-10-12",
        toAccountId: Long? = null,
    ): Long = db.transactionDao().insert(
        TransactionEntity(
            type = type,
            amount = amount,
            accountId = 1L,
            toAccountId = toAccountId,
            categoryId = categoryId,
            occurredOn = occurredOn,
            createdAt = 0L,
            updatedAt = 0L,
        ),
    )

    private fun viewModel(pending: Set<Long> = emptySet()): BudgetsViewModel =
        BudgetsViewModel(repository, today = { today }, pendingDeletionIds = flowOf(pending))

    private suspend fun loaded(vm: BudgetsViewModel): BudgetsUiState = vm.uiState.first { !it.isLoading }

    @Test
    fun expenseCategories_areListedInTreeOrderWithDepth() = runTest {
        val food = seedCategory("餐饮", sortOrder = 1)
        seedCategory("交通", sortOrder = 0)
        seedCategory("早餐", parentId = food, sortOrder = 0)

        val state = loaded(viewModel())

        assertEquals(listOf("交通", "餐饮", "早餐"), state.rows.map { it.name })
        assertEquals(listOf(0, 0, 1), state.rows.map { it.depth })
        assertTrue(state.rows.all { it.budgetCents == null })
        assertTrue(state.rows.all { it.status == BudgetStatus.NONE })
    }

    @Test
    fun incomeCategories_neverAppear() = runTest {
        seedCategory("工资", kind = CategoryKind.INCOME)
        val state = loaded(viewModel())

        assertTrue(state.rows.isEmpty())
        assertTrue(state.isEmpty)
    }

    @Test
    fun archivedCategory_isHiddenWithoutBudget_andShownWithBudget() = runTest {
        seedCategory("娱乐", isArchived = true)
        val travel = seedCategory("旅行", isArchived = true, sortOrder = 1)
        seedBudget(travel, 50_000L)

        val state = loaded(viewModel())

        assertEquals(listOf("旅行"), state.rows.map { it.name })
        assertTrue(state.rows.single().isArchived)
        assertEquals(50_000L, state.rows.single().budgetCents)
    }

    @Test
    fun budgetOnMissingCategory_showsPlaceholderRow() = runTest {
        seedBudget(999L, 30_000L)

        val state = loaded(viewModel())

        val row = state.rows.single()
        assertEquals("分类已不存在", row.name)
        assertEquals(999L, row.categoryId)
        assertEquals(30_000L, row.budgetCents)
    }

    @Test
    fun statuses_followSpentAgainstBudget() = runTest {
        val food = seedCategory("餐饮", sortOrder = 0)
        val shopping = seedCategory("购物", sortOrder = 1)
        val telecom = seedCategory("通讯", sortOrder = 2)
        seedBudget(food, 60_000L)
        seedBudget(shopping, 70_000L)
        seedBudget(telecom, 100_000L)
        seedTransaction(TransactionType.EXPENSE, 60_000L, food)
        seedTransaction(TransactionType.EXPENSE, 60_000L, shopping)
        seedTransaction(TransactionType.EXPENSE, 60_000L, telecom)

        val state = loaded(viewModel())
        val byName = state.rows.associateBy { it.name }

        assertEquals(BudgetStatus.OVER, byName.getValue("餐饮").status)
        assertEquals(BudgetStatus.WARNING, byName.getValue("购物").status)
        assertEquals(BudgetStatus.OK, byName.getValue("通讯").status)
        assertEquals(60_000L, byName.getValue("餐饮").spentCents)
        assertEquals(1, state.overCount)
        assertEquals(1, state.warningCount)
    }

    @Test
    fun totalBudget_sumsExpensesAcrossAllCategories() = runTest {
        val food = seedCategory("餐饮")
        val shopping = seedCategory("购物", sortOrder = 1)
        seedBudget(null, 100_000L)
        seedTransaction(TransactionType.EXPENSE, 90_000L, food)
        seedTransaction(TransactionType.EXPENSE, 10_000L, shopping)

        val state = loaded(viewModel())

        assertEquals(100_000L, state.totalSpentCents)
        assertEquals(BudgetStatus.OVER, state.totalStatus)
    }

    @Test
    fun transferAndBalanceAdjust_doNotCountAsSpending() = runTest {
        val food = seedCategory("餐饮")
        seedBudget(null, 10_000L)
        seedTransaction(TransactionType.EXPENSE, 10_000L, food)
        seedTransaction(TransactionType.TRANSFER, 500_000L, null, toAccountId = 2L)
        seedTransaction(TransactionType.BALANCE_ADJUST, -50_000L, null)

        val state = loaded(viewModel())

        assertEquals(10_000L, state.totalSpentCents)
        assertEquals(BudgetStatus.OVER, state.totalStatus)
    }

    @Test
    fun parentBudget_countsChildSpending_childBudget_countsOnlyItself() = runTest {
        val food = seedCategory("餐饮")
        val breakfast = seedCategory("早餐", parentId = food, sortOrder = 0)
        seedBudget(food, 17_000L)
        seedBudget(breakfast, 10_000L)
        seedTransaction(TransactionType.EXPENSE, 5_000L, food)
        seedTransaction(TransactionType.EXPENSE, 10_000L, breakfast)

        val state = loaded(viewModel())
        val byName = state.rows.associateBy { it.name }

        assertEquals(15_000L, byName.getValue("餐饮").spentCents)
        assertEquals(10_000L, byName.getValue("早餐").spentCents)
        assertEquals(BudgetStatus.WARNING, byName.getValue("餐饮").status)
        assertEquals(BudgetStatus.OVER, byName.getValue("早餐").status)
    }

    @Test
    fun pendingDeletionTransactions_areExcludedFromSpending() = runTest {
        val food = seedCategory("餐饮")
        seedBudget(null, 100_000L)
        val pendingId = seedTransaction(TransactionType.EXPENSE, 80_000L, food)

        val withoutPending = loaded(viewModel())
        val withPending = loaded(viewModel(pending = setOf(pendingId)))

        assertEquals(80_000L, withoutPending.totalSpentCents)
        assertEquals(0L, withPending.totalSpentCents)
    }

    @Test
    fun switchingMonth_showsThatMonthsBudgets() = runTest {
        seedBudget(null, 100_000L, month = "2026-10")
        seedBudget(null, 500_000L, month = "2026-11")
        val vm = viewModel()

        val october = loaded(vm)
        vm.showNextMonth()
        val november = vm.uiState.first { !it.isLoading && it.totalBudgetCents == 500_000L }

        assertEquals(100_000L, october.totalBudgetCents)
        assertEquals(500_000L, november.totalBudgetCents)
        assertTrue(november.monthLabel.contains("11"))
    }

    @Test
    fun nextMonth_withoutBudget_reportsUnset() = runTest {
        seedBudget(null, 100_000L, month = "2026-10")
        seedCategory("餐饮")
        val vm = viewModel()

        loaded(vm)
        vm.showNextMonth()
        val november = vm.uiState.first { !it.isLoading && it.totalBudgetCents == null }

        assertNull(november.totalBudgetCents)
        assertEquals(BudgetStatus.NONE, november.totalStatus)
        // 分类行还在（分类是全月的，不随月份消失），只是没有预算
        assertEquals(1, november.rows.size)
        assertNull(november.rows.single().budgetId)
    }
}
