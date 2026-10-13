package com.nimo.bookkeeping.ui.accounts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.MainDispatcherRule
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * 账户页（F01）：列表显示的是**当前余额**（初始余额 + 全部交易变动，由 BalanceCalculator 推导），
 * 而不是建账时的初始余额快照。走真实 Repository + in-memory Room，验证端到端行为。
 */
@RunWith(RobolectricTestRunner::class)
class AccountsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
    fun teardown() = db.close()

    private suspend fun expenseCategoryId(): Long = db.categoryDao().upsert(
        CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, sortOrder = 0),
    )

    private fun newViewModel(pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet())) =
        AccountsViewModel(repository, pendingDeletionIds)

    private suspend fun AccountsViewModel.awaitLoaded(): AccountsUiState = uiState.first { !it.isLoading }

    private suspend fun AccountsViewModel.awaitBalance(accountId: Long, cents: Long): AccountsUiState =
        uiState.first { it.balances[accountId] == cents }

    private suspend fun addExpense(accountId: Long, amount: Long, date: String = "2026-10-12"): Long =
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = amount,
                accountId = accountId,
                categoryId = expenseCategoryId(),
                occurredOn = date,
            ),
        ).getOrThrow()

    @Test
    fun balance_isInitialBalancePlusAllTransactions() = runTest {
        // 设备实例：初始 ¥1234.00 减一笔 ¥600.00 支出 → 当前余额必须是 ¥634.00
        val accountId = repository.createAccount("CMB", AccountType.CASH, 123_400L).getOrThrow()
        addExpense(accountId, 60_000L)

        val state = newViewModel().awaitLoaded()

        assertEquals(63_400L, state.balances[accountId])
        assertEquals(63_400L, state.netWorthCents)
        assertEquals(63_400L, state.balanceOf(state.accounts.single()))
        // 初始余额仍原样保存在账户上：编辑对话框要显示并允许修改它
        assertEquals(123_400L, state.accounts.single().initialBalance)
        assertNull(state.errorMessage)
    }

    @Test
    fun pendingDeletion_isLeftOutOfBalanceUntilItSettles() = runTest {
        val accountId = repository.createAccount("CMB", AccountType.CASH, 123_400L).getOrThrow()
        val txId = addExpense(accountId, 60_000L)
        val pending = MutableStateFlow<Set<Long>>(emptySet())
        val vm = newViewModel(pending)

        assertEquals(63_400L, vm.awaitLoaded().balances[accountId])

        // 撤销窗口内：数据库里那一行还在，但余额必须当它不存在（计划 9.2 延迟物理删除）
        pending.value = setOf(txId)
        assertEquals(123_400L, vm.awaitBalance(accountId, 123_400L).netWorthCents)

        // 撤销/落库后余额要跟着回到正确值
        pending.value = emptySet()
        assertEquals(63_400L, vm.awaitBalance(accountId, 63_400L).netWorthCents)
    }

    @Test
    fun pendingDeletions_twoRowsAtOnce_areBothLeftOutOfTheBalance() = runTest {
        val accountId = repository.createAccount("CMB", AccountType.CASH, 123_400L).getOrThrow()
        val first = addExpense(accountId, 60_000L)
        val second = addExpense(accountId, 10_000L)
        val pending = MutableStateFlow<Set<Long>>(emptySet())
        val vm = newViewModel(pending)

        assertEquals(53_400L, vm.awaitLoaded().balances[accountId])

        // 5 秒内连删两笔：两笔都要同时退出余额（多笔并行待撤销）
        pending.value = setOf(first, second)
        assertEquals(123_400L, vm.awaitBalance(accountId, 123_400L).netWorthCents)

        // 只撤回其中一笔：另一笔仍要当不存在
        pending.value = setOf(first)
        assertEquals(113_400L, vm.awaitBalance(accountId, 113_400L).netWorthCents)
    }

    @Test
    fun transfer_movesMoneyBetweenAccountsWithoutChangingNetWorth() = runTest {
        val from = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        val to = repository.createAccount("招行卡", AccountType.DEBIT, 0L).getOrThrow()
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.TRANSFER,
                amountCents = 30_000L,
                accountId = from,
                toAccountId = to,
                occurredOn = "2026-10-12",
            ),
        ).getOrThrow()

        val state = newViewModel().awaitLoaded()

        assertEquals(70_000L, state.balances[from])
        assertEquals(30_000L, state.balances[to])
        assertEquals(100_000L, state.netWorthCents)
    }

    @Test
    fun accountsOutsideNetWorth_stillShowTheirOwnBalance() = runTest {
        val cash = repository.createAccount("现金", AccountType.CASH, 50_000L).getOrThrow()
        val credit = repository.createAccount("信用卡", AccountType.CREDIT, 0L).getOrThrow()
        repository.updateAccount(credit, "信用卡", AccountType.CREDIT, 0L, includeInTotal = false)
            .getOrThrow()
        addExpense(credit, 20_000L)

        val state = newViewModel().awaitLoaded()

        // 不计入净资产不等于不显示：这张卡自己的余额照样是 -¥200.00
        assertEquals(-20_000L, state.balances[credit])
        assertEquals(50_000L, state.balances[cash])
        assertEquals(50_000L, state.netWorthCents)
    }

    @Test
    fun archivedAccount_staysInTheListWithItsBalance_butDropsOutOfNetWorth() = runTest {
        val accountId = repository.createAccount("CMB", AccountType.CASH, 123_400L).getOrThrow()
        addExpense(accountId, 60_000L)
        assertEquals(63_400L, newViewModel().awaitLoaded().netWorthCents)

        repository.setAccountArchived(accountId, archived = true).getOrThrow()

        val state = newViewModel().awaitLoaded()

        // 归档只影响两件事：能不能用来记新账、算不算净资产。历史、自己的余额照常显示
        assertTrue(state.accounts.single().isArchived)
        assertEquals(63_400L, state.balances[accountId])
        assertEquals(63_400L, state.balanceOf(state.accounts.single()))
        assertEquals(0L, state.netWorthCents)
    }

    @Test
    fun malformedOccurredOn_doesNotKillTheBalanceStream() = runTest {
        val accountId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        addExpense(accountId, 1_000L)
        val categoryId = expenseCategoryId()
        // 绕过 TransactionValidator 直插非法日期：触发器只校验金额与账户/分类，不管日期格式。
        // 备份导入（F08）等路径一旦出现缺口就会产生这种行。
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO transactions (type, amount, accountId, toAccountId, categoryId, occurredOn, note, createdAt, updatedAt) " +
                "VALUES ('EXPENSE', 9999, $accountId, NULL, $categoryId, '2026-10-XX', NULL, 0, 0)",
        )

        val state = newViewModel().awaitLoaded()

        // 非法行不进余额，更不能把 combine/stateIn 拖死（流水页曾因裸 LocalDate.parse 永远停在加载态）
        assertEquals(99_000L, state.balances[accountId])
        assertEquals(99_000L, state.netWorthCents)
        assertNull(state.errorMessage)
    }
}
