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
import com.nimo.bookkeeping.util.Dates
import com.nimo.bookkeeping.util.Money
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 余额校准（F11）的写入链路：真实 Repository + in-memory Room。
 *
 * 计划 :383 验收项「余额校准后 Σ账户余额 与所有交易变动一致」的自动化版本：
 * 校准 = **追加**一笔 BALANCE_ADJUST（差额 = 实际 − 账面），历史一行都不动，
 * 余额仍是 [BalanceCalculator] 推导出来的（铁律 2），且不进收入 / 支出统计（铁律 3）。
 */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryBalanceAdjustTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private var cashId = 0L
    private var cardId = 0L

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repository = LedgerRepository(db)

        cashId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        cardId = repository.createAccount("信用卡", AccountType.CREDIT, 0L).getOrThrow()
    }

    @After
    fun teardown() = db.close()

    /** 余额唯一算法：[BalanceCalculator]（铁律 2，页面与测试都不得另写一套） */
    private suspend fun balances(): Map<Long, Long> = BalanceCalculator.computeBalances(
        accounts = db.accountDao().listAll().map { it.toBalanceInput() },
        records = db.transactionDao().listAll().mapNotNull { it.toRecordOrNull() },
    )

    private suspend fun balanceOf(id: Long): Long = balances()[id] ?: 0L

    private suspend fun rowCount(): Int = db.transactionDao().count()

    private suspend fun rowOf(id: Long) = requireNotNull(db.transactionDao().getById(id))

    private suspend fun categoryId(name: String, kind: CategoryKind): Long =
        db.categoryDao().upsert(CategoryEntity(name = name, kind = kind, sortOrder = 0))

    private suspend fun addExpense(amount: Long = 30_000L, date: String = "2026-10-12"): Long =
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = amount,
                accountId = cashId,
                categoryId = categoryId("餐饮", CategoryKind.EXPENSE),
                occurredOn = date,
            ),
        ).getOrThrow()

    @Test
    fun adjustingUp_appendsExactlyOneBalanceAdjustRow() = runTest {
        addExpense(30_000L)
        assertEquals(70_000L, balanceOf(cashId))

        val id = repository.adjustBalanceTo(cashId, 100_000L).getOrThrow()

        val row = rowOf(id)
        assertEquals(TransactionType.BALANCE_ADJUST, row.type)
        assertEquals(30_000L, row.amount)
        assertEquals(cashId, row.accountId ?: -1L)
        assertNull(row.categoryId)
        assertNull(row.toAccountId)
        assertEquals(Dates.today(), row.occurredOn)
        // 备注留证据：几个月后回看流水能知道这笔差额怎么来的
        assertTrue(row.note!!.contains("余额校准"))
        assertTrue(row.note!!.contains(Money.formatCurrency(70_000L)))
        assertTrue(row.note!!.contains(Money.formatCurrency(100_000L)))

        assertEquals(2, rowCount())
        assertEquals(100_000L, balanceOf(cashId))
    }

    @Test
    fun adjustingDown_keepsEveryHistoryRow() = runTest {
        val expenseId = addExpense(30_000L)

        // 账面 100_000 − 30_000 支出 = 70_000，校准到 10_000 需要写 −60_000
        val id = repository.adjustBalanceTo(cashId, 10_000L).getOrThrow()

        assertEquals(-60_000L, rowOf(id).amount)
        // 历史不动：计划 F11 明写"不删除历史"
        assertNotNull(db.transactionDao().getById(expenseId))
        assertEquals(10_000L, balanceOf(cashId))
        assertEquals(2, rowCount())
    }

    @Test
    fun adjustingToZero_isAllowed() = runTest {
        val id = repository.adjustBalanceTo(cashId, 0L).getOrThrow()
        assertEquals(-100_000L, rowOf(id).amount)
        assertEquals(0L, balanceOf(cashId))
    }

    @Test
    fun adjustingACreditCardIntoDebt_onlyTouchesThatAccount() = runTest {
        val id = repository.adjustBalanceTo(cardId, -50_000L).getOrThrow()

        assertEquals(-50_000L, rowOf(id).amount)
        assertEquals(-50_000L, balanceOf(cardId))
        // 现金账户一分不动：校准只写目标账户那一行
        assertEquals(100_000L, balanceOf(cashId))
        // 净资产 = 100,000 − 50,000（信用卡欠款以负余额体现，计划 3.4）
        assertEquals(50_000L, BalanceCalculator.netWorthCents(balances(), db.accountDao().listAll().map { it.toBalanceInput() }))
    }

    @Test
    fun adjustingToTheCurrentBalance_isRejectedWithoutWriting() = runTest {
        val result = repository.adjustBalanceTo(cashId, 100_000L)

        assertTrue(result.isFailure)
        assertEquals("账面余额与实际一致，无需校准", result.exceptionOrNull()!!.message)
        // 不写行：数据库触发器不允许 amount = 0（铁律 1），这里根本不该走到插入
        assertEquals(0, rowCount())
    }

    @Test
    fun adjustingAnArchivedAccount_isRejectedByTheValidator() = runTest {
        repository.setAccountArchived(cashId, true).getOrThrow()

        val result = repository.adjustBalanceTo(cashId, 50_000L)

        assertEquals(
            listOf(TxValidationError.AccountArchived),
            (result.exceptionOrNull() as TxValidationException).errors,
        )
        assertEquals(0, rowCount())
    }

    @Test
    fun adjustingAnUnknownAccount_fails() = runTest {
        val result = repository.adjustBalanceTo(9_999L, 1_000L)
        assertTrue(result.isFailure)
        assertEquals("账户不存在", result.exceptionOrNull()!!.message)
        assertEquals(0, rowCount())
    }

    @Test
    fun aDeltaBeyondOneRow_isRejectedWithoutWriting() = runTest {
        // 差额 = 单笔上限 + 1 分
        val result = repository.adjustBalanceTo(cashId, Money.MAX_AMOUNT_CENTS + 100_001L)

        assertTrue(result.isFailure)
        assertTrue(
            (result.exceptionOrNull() as TxValidationException)
                .errors.contains(TxValidationError.AmountOutOfRange),
        )
        assertEquals(0, rowCount())
    }

    @Test
    fun theAdjustment_neverEntersIncomeExpenseOrBudget() = runTest {
        val expenseCategory = categoryId("餐饮", CategoryKind.EXPENSE)
        val incomeCategory = categoryId("工资", CategoryKind.INCOME)
        repository.addTransaction(
            TransactionInput(
                TransactionType.EXPENSE, 30_000L, cashId,
                categoryId = expenseCategory, occurredOn = "2026-10-12",
            ),
        ).getOrThrow()
        repository.addTransaction(
            TransactionInput(
                TransactionType.INCOME, 20_000L, cashId,
                categoryId = incomeCategory, occurredOn = "2026-10-12",
            ),
        ).getOrThrow()

        val id = repository.adjustBalanceTo(cashId, 500_000L).getOrThrow()

        val records = db.transactionDao().listAll().mapNotNull { it.toRecordOrNull() }
        // 合计口径不变（铁律 3：转账与余额调整不进收入/支出统计）——差额有 3,700.00 之巨，
        // 一旦被算进收支，下面的断言立刻爆
        val all = StatsCalculator.summarize(records, null)
        assertEquals(20_000L, all.incomeCents)
        assertEquals(30_000L, all.expenseCents)

        // 就在校准那一天：收入/支出都是 0
        val adjustDay = LocalDate.parse(rowOf(id).occurredOn)
        val thatDay = StatsCalculator.summarize(records, adjustDay..adjustDay)
        assertEquals(0L, thatDay.incomeCents)
        assertEquals(0L, thatDay.expenseCents)
    }
}
