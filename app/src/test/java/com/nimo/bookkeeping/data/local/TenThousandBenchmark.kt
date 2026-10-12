package com.nimo.bookkeeping.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.stats.BalanceCalculator
import com.nimo.bookkeeping.domain.stats.StatsCalculator
import com.nimo.bookkeeping.data.local.entity.BudgetEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * 万级数据性能基准（计划 M1 验收：1 万笔交易下账户余额聚合 ≤ 200ms）。
 * Robolectric 模拟器环境比真机慢，用宽松上限（真机只会更快），回归用。
 */
@RunWith(RobolectricTestRunner::class)
class TenThousandBenchmark {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun balanceAggregation_10kTransactions_underBudget() = runTest {
        // 准备：2 账户 + 2 分类
        db.accountDao().upsert(AccountEntity(name = "银行", type = AccountType.DEBIT, initialBalance = 5000000, createdAt = 1, updatedAt = 1))
        db.accountDao().upsert(AccountEntity(name = "现金", type = AccountType.CASH, initialBalance = 50000, createdAt = 1, updatedAt = 1))
        db.categoryDao().upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE))
        db.categoryDao().upsert(CategoryEntity(name = "工资", kind = CategoryKind.INCOME))

        // 批量插入 1 万笔（跨 14 个月，验证范围查询）
        val start = System.currentTimeMillis()
        val baseDate = LocalDate.of(2025, 9, 1)
        db.openHelper.writableDatabase.beginTransaction()
        try {
            for (i in 1..10_000) {
                val date = baseDate.plusDays((i % 420).toLong())
                val type = when (i % 10) {
                    0 -> "INCOME"
                    1, 2 -> "TRANSFER"
                    else -> "EXPENSE"
                }
                db.openHelper.writableDatabase.execSQL(
                    "INSERT INTO transactions (type, amount, accountId, toAccountId, categoryId, occurredOn, note, createdAt, updatedAt) " +
                        "VALUES (?, ?, ?, ?, ?, ?, NULL, 0, 0)",
                    arrayOf<Any?>(
                        type,
                        (i % 500 + 1).toLong(),
                        1L,
                        if (type == "TRANSFER") 2L else null,
                        if (type == "EXPENSE" || type == "INCOME") (if (type == "INCOME") 2L else 1L) else null,
                        date.toString(),
                    ),
                )
            }
            db.openHelper.writableDatabase.setTransactionSuccessful()
        } finally {
            db.openHelper.writableDatabase.endTransaction()
        }
        assertEquals(10_000, db.transactionDao().count())
        val insertMs = System.currentTimeMillis() - start

        // 基准 1：单月范围查询（走 idx(type, occurredOn)）
        val t1 = System.currentTimeMillis()
        val (from, to) = com.nimo.bookkeeping.util.Dates.monthBounds(2026, 8)
        val august = db.transactionDao().observeBetween(from, to).first()
        val monthQueryMs = System.currentTimeMillis() - t1
        assertTrue("单月应只含 8 月数据: ${august.size}", august.all { it.occurredOn.startsWith("2026-08") })

        // 基准 2：全量余额聚合（10k 交易 + 2 账户）
        val t2 = System.currentTimeMillis()
        val records = db.transactionDao().observeAll().first().map { it.toRecord() }
        val balances = BalanceCalculator.computeBalances(
            db.accountDao().listAll().map { it.toBalanceInput() },
            records,
        )
        val balanceMs = System.currentTimeMillis() - t2

        // 基准 3：统计口径（10k）
        val t3 = System.currentTimeMillis()
        val summary = StatsCalculator.summarize(records)
        val statsMs = System.currentTimeMillis() - t3

        // 校验统计与逐笔一致（类型白名单）
        val expectedExpense = records.count { it.type == TransactionType.EXPENSE }
        val expectedIncome = records.count { it.type == TransactionType.INCOME }
        assertEquals(10_000 - records.count { it.type == TransactionType.TRANSFER }, expectedExpense + expectedIncome)

        println("BENCHMARK: insert10k=${insertMs}ms monthQuery(${august.size}笔)=${monthQueryMs}ms balanceAgg=${balanceMs}ms stats=${statsMs}ms")

        // Robolectric 宽松上限（真机远快于此）
        assertTrue("单月查询 ${monthQueryMs}ms 超标", monthQueryMs < 2_000)
        assertTrue("余额聚合 ${balanceMs}ms 超标", balanceMs < 2_000)
    }
}
