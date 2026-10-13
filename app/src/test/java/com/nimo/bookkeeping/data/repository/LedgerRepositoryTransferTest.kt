package com.nimo.bookkeeping.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.toBalanceInput
import com.nimo.bookkeeping.data.local.toRecordOrNull
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.stats.BalanceCalculator
import com.nimo.bookkeeping.domain.stats.StatsCalculator
import com.nimo.bookkeeping.domain.validate.TxValidationError
import com.nimo.bookkeeping.domain.validate.TxValidationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 转账（F04）的写入链路：真实 Repository + in-memory Room。
 *
 * M2 验收项「转账后两个账户余额均正确变化，且不出现在收支统计里」的自动化版本。
 * 这里钉住的关键事实：转账就是**一行** transactions，两端余额都是
 * [BalanceCalculator] 从流水推导出来的（余额不落库，铁律 2），所以
 * "两端一起变""编辑不重复扣款"是数据结构本身保证的，不靠额外的补偿代码。
 */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryTransferTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private var cashId = 0L
    private var alipayId = 0L

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repository = LedgerRepository(db)

        cashId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        alipayId = repository.createAccount("支付宝", AccountType.DEBIT, 0L).getOrThrow()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun transfer(
        amountCents: Long = 30_000L,
        from: Long = cashId,
        to: Long = alipayId,
        note: String? = null,
        occurredOn: String = "2026-10-12",
    ) = TransactionInput(
        type = TransactionType.TRANSFER,
        amountCents = amountCents,
        accountId = from,
        toAccountId = to,
        categoryId = null,
        occurredOn = occurredOn,
        note = note,
    )

    /** 余额唯一算法：[BalanceCalculator]（铁律 2，页面不得另写一套） */
    private suspend fun balances(): Map<Long, Long> {
        val accounts = repository.observeAccounts().first()
        val records = repository.observeAllTransactions().first().mapNotNull { it.toRecordOrNull() }
        return BalanceCalculator.computeBalances(accounts.map { it.toBalanceInput() }, records)
    }

    private suspend fun summary() =
        StatsCalculator.summarize(
            repository.observeAllTransactions().first().mapNotNull { it.toRecordOrNull() },
        )

    private suspend fun errorsOf(input: TransactionInput): List<TxValidationError> {
        val result = repository.addTransaction(input)
        assertTrue("期望写入失败，实际成功", result.isFailure)
        return (result.exceptionOrNull() as TxValidationException).errors
    }

    // ---- 两端余额 ----

    @Test
    fun aTransfer_movesMoneyOutOfOneAccountAndIntoTheOther() = runTest {
        repository.addTransaction(transfer(amountCents = 30_000L)).getOrThrow()

        val after = balances()
        assertEquals(70_000L, after[cashId])
        assertEquals(30_000L, after[alipayId])
        // 一行记录承载两端：不是两条各自记账的流水
        assertEquals(1, db.transactionDao().count())
    }

    @Test
    fun aTransfer_doesNotShowUpInIncomeOrExpense() = runTest {
        repository.addTransaction(transfer(amountCents = 30_000L)).getOrThrow()

        val s = summary()
        assertEquals(0L, s.incomeCents)
        assertEquals(0L, s.expenseCents)
        assertEquals(0L, s.netCents)
    }

    // ---- 编辑：整行替换，不能重复扣款（计划 4.4）----

    @Test
    fun editingATransfer_doesNotDeductTwice() = runTest {
        val id = repository.addTransaction(transfer(amountCents = 30_000L)).getOrThrow()

        // 只改备注：两端余额必须原样不动
        repository.updateTransaction(id, transfer(amountCents = 30_000L, note = "改完备注")).getOrThrow()

        val after = balances()
        assertEquals(70_000L, after[cashId])
        assertEquals(30_000L, after[alipayId])
        assertEquals(1, db.transactionDao().count())
        assertEquals("改完备注", db.transactionDao().getById(id)!!.note)
    }

    @Test
    fun editingATransferAmount_movesBothEndsToTheNewAmount() = runTest {
        val id = repository.addTransaction(transfer(amountCents = 30_000L)).getOrThrow()

        repository.updateTransaction(id, transfer(amountCents = 50_000L)).getOrThrow()

        val after = balances()
        assertEquals(50_000L, after[cashId])
        assertEquals(50_000L, after[alipayId])
        // 旧的 30_000 扣款已经不存在了：不是 70_000 / 80_000
        assertEquals(1, db.transactionDao().count())
    }

    @Test
    fun editingATransferDestination_returnsTheOldDestinationToZero() = runTest {
        val third = repository.createAccount("招行", AccountType.DEBIT, 0L).getOrThrow()
        val id = repository.addTransaction(transfer(amountCents = 30_000L)).getOrThrow()

        repository.updateTransaction(id, transfer(amountCents = 30_000L, to = third)).getOrThrow()

        val after = balances()
        assertEquals(70_000L, after[cashId])
        assertEquals(0L, after[alipayId])
        assertEquals(30_000L, after[third])
    }

    // ---- 校验（第二道防线）----

    @Test
    fun aTransferWithACategory_isRejected() = runTest {
        val categoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, sortOrder = 0))
        val input = transfer().copy(categoryId = categoryId)

        assertEquals(listOf(TxValidationError.CategoryNotAllowed), errorsOf(input))
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun aTransferToTheSameAccount_isRejected() = runTest {
        assertEquals(listOf(TxValidationError.TransferSameAccount), errorsOf(transfer(to = cashId)))
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun aTransferWithoutADestination_isRejected() = runTest {
        val input = transfer().copy(toAccountId = null)
        val errors = errorsOf(input)

        assertTrue(errors.contains(TxValidationError.TransferNeedsTwoAccounts))
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun aZeroAmountTransfer_isRejected() = runTest {
        assertTrue(errorsOf(transfer(amountCents = 0L)).contains(TxValidationError.AmountMustBePositive))
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun aTransferOutOfAnArchivedAccount_isRejected() = runTest {
        val archived = repository.createAccount("已归档", AccountType.DEBIT, 10_000L).getOrThrow()
        repository.setAccountArchived(archived, archived = true).getOrThrow()

        val errors = errorsOf(transfer(from = archived, to = cashId))
        assertTrue(errors.contains(TxValidationError.AccountArchived))
        assertEquals(0, db.transactionDao().count())
    }
}
