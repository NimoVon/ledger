package com.nimo.ledger.ui.accounts

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
 * 账户页的余额校准（F11）：页面显示的余额必须跟着校准走，且失败时提示的是**给人看的中文**，
 * 不是 "交易校验失败: ..." 这种原始异常（计划 4.4）。
 */
@RunWith(RobolectricTestRunner::class)
class AccountsViewModelBalanceAdjustTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var viewModel: AccountsViewModel

    private var cashId = 0L

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repository = LedgerRepository(db)
        cashId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        viewModel = AccountsViewModel(repository, flowOf(emptySet()))
    }

    @After
    fun teardown() = db.close()

    private suspend fun awaitBalance(cents: Long): AccountsUiState =
        viewModel.uiState.first { !it.isLoading && it.balances[cashId] == cents }

    @Test
    fun adjusting_movesTheDerivedBalanceToTheActualValue() = runTest {
        val spent = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = 30_000L,
                accountId = cashId,
                categoryId = db.categoryDao().upsert(
                    CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, sortOrder = 0),
                ),
                occurredOn = "2026-10-12",
            ),
        ).getOrThrow()

        awaitBalance(70_000L)
        viewModel.adjustBalance(cashId, 100_000L).getOrThrow()

        val state = awaitBalance(100_000L)
        val account = state.accounts.first { it.id == cashId }
        assertEquals(100_000L, state.balanceOf(account))
        // 校准是"追加一笔"，那笔支出仍在库里
        assertTrue(db.transactionDao().getById(spent) != null)
    }

    @Test
    fun adjustingAnArchivedAccount_showsAReadableMessage() = runTest {
        viewModel.toggleArchive(cashId, true).getOrThrow()

        val result = viewModel.adjustBalance(cashId, 50_000L)

        assertTrue(result.isFailure)
        assertEquals("该账户已归档，不能再记新账", result.exceptionOrNull()!!.toAccountMessage())
        // 余额没动过
        assertEquals(100_000L, viewModel.uiState.first { !it.isLoading }.balances[cashId])
    }

    @Test
    fun adjustingWhenNothingChanged_surfacesTheReasonAndKeepsTheBalance() = runTest {
        val result = viewModel.adjustBalance(cashId, 100_000L)

        assertTrue(result.isFailure)
        assertEquals("账面余额与实际一致，无需校准", result.exceptionOrNull()!!.toAccountMessage())
        assertEquals(100_000L, viewModel.uiState.first { !it.isLoading }.balances[cashId])
    }
}
