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
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.stats.PeriodSummary
import com.nimo.bookkeeping.domain.stats.StatsCalculator
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
 * F03 的「修改 / 删除 / 撤销」在流水页上的结果（M2 剩余部分）。
 *
 * 三条硬指标都在这里盯着：
 * 1. 编辑与撤销之后列表顺序不跳动（occurredOn DESC, id DESC）；
 * 2. 当月合计随编辑/删除立刻变化，且与 [StatsCalculator] 对同一批数据的结果一致（口径唯一，页面不另算）；
 * 3. 删除是"UI 层延迟物理删除"：界面先消失，数据库超时才少一行，撤销能按原 id 整笔恢复。
 */
@RunWith(RobolectricTestRunner::class)
class TransactionEditDeleteTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 固定"今天"，让今日分组标签可断言 */
    private val today = LocalDate.parse("2026-10-12")

    /** 撤销窗口压到 300ms：真实时间，够真实 IO 跑几十个来回 */
    private val undoWindowMs = 300L

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var storeScope: CoroutineScope

    private var cashId = 0L
    private var foodCategoryId = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repository = LedgerRepository(db)
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun teardown() {
        storeScope.cancel()
        db.close()
    }

    private suspend fun seed() {
        cashId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        foodCategoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, icon = "🍜", sortOrder = 0))
    }

    private suspend fun addExpense(
        amount: Long,
        date: String = "2026-10-12",
        note: String? = null,
    ): Long = repository.addTransaction(
        TransactionInput(
            type = TransactionType.EXPENSE,
            amountCents = amount,
            accountId = cashId,
            categoryId = foodCategoryId,
            occurredOn = date,
            note = note,
        ),
    ).getOrThrow()

    private suspend fun editExpense(id: Long, amount: Long, date: String, note: String?) {
        // 与记账页保存链路同一条路：整行替换，id 不变
        repository.updateTransaction(
            id,
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = amount,
                accountId = cashId,
                categoryId = foodCategoryId,
                occurredOn = date,
                note = note,
            ),
        ).getOrThrow()
    }

    private suspend fun entityOf(id: Long) = db.transactionDao().getById(id)

    private fun newStore() = PendingTransactionDeletion(repository, storeScope, undoWindowMs)

    private fun newViewModel(store: PendingTransactionDeletion) =
        TransactionsViewModel(repository, today = { today }, pendingDeletionId = store.pendingId)

    private suspend fun TransactionsViewModel.awaitLoaded(): TransactionsUiState =
        uiState.first { !it.isLoading }

    private suspend fun TransactionsViewModel.awaitState(
        predicate: (TransactionsUiState) -> Boolean,
    ): TransactionsUiState = uiState.first(predicate)

    private fun idsOf(state: TransactionsUiState): List<Long> =
        state.groups.flatMap { group -> group.rows.map { it.id } }

    /** 页面合计必须与 StatsCalculator 对同一批数据的结果一致（口径唯一实现） */
    private suspend fun summaryFromDatabase(): PeriodSummary =
        StatsCalculator.summarize(
            repository.observeTransactions("2026-10-01", "2026-11-01").first().map { it.toRecord() },
        )

    // ---- 修改：整行替换，id 不变 ----

    @Test
    fun editAmount_keepsTheRowInPlaceAndMovesTheMonthTotal() = runTest {
        seed()
        val first = addExpense(1_000L, note = "早餐")
        val second = addExpense(2_000L, note = "午餐")
        val third = addExpense(3_000L)
        val vm = newViewModel(newStore())
        assertEquals(listOf(third, second, first), idsOf(vm.awaitLoaded()))
        assertEquals(6_000L, vm.awaitLoaded().summary.expenseCents)

        editExpense(second, amount = 12_500L, date = "2026-10-12", note = "午餐加菜")

        val after = vm.awaitState { it.summary.expenseCents == 16_500L }
        // 顺序不跳动：还是原来的 id 倒序
        assertEquals(listOf(third, second, first), idsOf(after))
        // 当日合计与当月合计一起动
        assertEquals(16_500L, after.groups.single().expenseCents)
        assertEquals(after.summary, summaryFromDatabase())
        val row = after.groups.single().rows.first { it.id == second }
        assertEquals(12_500L, row.amountCents)
        assertEquals("午餐加菜", row.note)
        // 数据库里只有这一笔被改过，没有多出第二条
        assertEquals(3, db.transactionDao().count())
    }

    @Test
    fun editDate_movesTheRowToAnotherDayWithoutChangingTheTotal() = runTest {
        seed()
        val first = addExpense(1_000L, "2026-10-12", "早餐")
        val second = addExpense(2_000L, "2026-10-12", "午餐")
        val vm = newViewModel(newStore())
        assertEquals(listOf(second, first), idsOf(vm.awaitLoaded()))

        editExpense(second, amount = 2_000L, date = "2026-10-11", note = "午餐")

        val after = vm.awaitState { it.groups.size == 2 }
        // 日期倒序：10-12 在前；id 不变，跨天也只是换个分组
        assertEquals(listOf("2026-10-12", "2026-10-11"), after.groups.map { it.date })
        assertEquals(listOf(first), after.groups[0].rows.map { it.id })
        assertEquals(listOf(second), after.groups[1].rows.map { it.id })
        assertEquals(3_000L, after.summary.expenseCents)
    }

    @Test
    fun editType_switchesTheRowBetweenExpenseAndIncome() = runTest {
        seed()
        val incomeCategoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "工资", kind = CategoryKind.INCOME, sortOrder = 0))
        val id = addExpense(1_000L, note = "发错了")
        val vm = newViewModel(newStore())
        assertEquals(1_000L, vm.awaitLoaded().summary.expenseCents)

        repository.updateTransaction(
            id,
            TransactionInput(
                type = TransactionType.INCOME,
                amountCents = 1_000L,
                accountId = cashId,
                categoryId = incomeCategoryId,
                occurredOn = "2026-10-12",
                note = null,
            ),
        ).getOrThrow()

        val after = vm.awaitState { it.summary.incomeCents == 1_000L }
        assertEquals(0L, after.summary.expenseCents)
        assertEquals(1_000L, after.summary.netCents)
    }

    // ---- 删除：界面先消失，数据库超时才少一行 ----

    @Test
    fun pendingDeletion_hidesTheRowAndRollsBackTheTotalsBeforeAnyDatabaseWrite() = runTest {
        seed()
        val first = addExpense(1_000L, note = "早餐")
        val second = addExpense(2_500L, note = "午餐")
        val store = newStore()
        val vm = newViewModel(store)
        assertEquals(3_500L, vm.awaitLoaded().summary.expenseCents)

        store.schedule(entityOf(second)!!)

        val hidden = vm.awaitState { idsOf(it) == listOf(first) }
        assertEquals(1_000L, hidden.summary.expenseCents)
        assertEquals(1_000L, hidden.groups.single().expenseCents)
        // 这一笔还在数据库里：撤销窗口内删掉它才是真的删（计划 9.2）
        assertEquals(3_500L, summaryFromDatabase().expenseCents)
        assertTrue(entityOf(second) != null)
    }

    @Test
    fun undoWithinWindow_bringsTheRowBackInItsOriginalPlace() = runTest {
        seed()
        val first = addExpense(1_000L, note = "早餐")
        val second = addExpense(2_500L, note = "午餐")
        val store = newStore()
        val vm = newViewModel(store)
        vm.awaitLoaded()

        store.schedule(entityOf(second)!!)
        vm.awaitState { idsOf(it) == listOf(first) }

        assertTrue(store.undo())

        val back = vm.awaitState { idsOf(it) == listOf(second, first) }
        assertEquals(3_500L, back.summary.expenseCents)
        assertEquals(listOf(second, first), idsOf(back))
        assertEquals("午餐", back.groups.single().rows.first().note)
        assertNull(store.pending.value)
        // 没落过库：整笔连 updatedAt 都没变
        assertEquals(3_500L, summaryFromDatabase().expenseCents)
    }

    @Test
    fun deleteAfterTimeout_thenUndo_restoresByOriginalIdWithoutReordering() = runTest {
        seed()
        val first = addExpense(1_000L, note = "早餐")
        val second = addExpense(2_500L, note = "午餐")
        val third = addExpense(300L, note = "咖啡")
        val store = newStore()
        val vm = newViewModel(store)
        assertEquals(listOf(third, second, first), idsOf(vm.awaitLoaded()))

        store.schedule(entityOf(second)!!)
        // 等倒计时真的走完（真实时间），落库动作也结束
        store.awaitIdle()

        assertNull("超时后真的删了", entityOf(second))
        val afterDelete = vm.awaitState { idsOf(it) == listOf(third, first) }
        assertEquals(1_300L, afterDelete.summary.expenseCents)

        // 撤销：按原 id 恢复整笔，位置与合计都回到删除前
        assertTrue(store.undo())
        val restored = vm.awaitState { it.summary.expenseCents == 3_800L }
        assertEquals(listOf(third, second, first), idsOf(restored))
        assertEquals(3_800L, restored.summary.expenseCents)
        assertEquals("午餐", restored.groups.single().rows[1].note)
        assertEquals(3_800L, summaryFromDatabase().expenseCents)
    }
}
