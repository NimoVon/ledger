package com.nimo.ledger.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.data.local.entity.AccountEntity
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.local.entity.TransactionEntity
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.filter.TransactionFilter
import com.nimo.ledger.domain.stats.BalanceCalculator
import com.nimo.ledger.domain.stats.StatsCalculator
import com.nimo.ledger.ui.reports.ReportDrillDown
import com.nimo.ledger.ui.reports.TrendSpan
import com.nimo.ledger.data.local.entity.BudgetEntity
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
import java.time.YearMonth

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
        val insertMs = seed10k()

        // 基准 1：单月范围查询（走 idx(type, occurredOn)）
        val t1 = System.currentTimeMillis()
        val (from, to) = com.nimo.ledger.util.Dates.monthBounds(2026, 8)
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

    /**
     * 播 1 万笔种子（跨 14 个月）：2 账户、3 分类（餐饮 1 / 工资 2 / 餐饮的子分类「早餐」3），每 7 笔 1 笔备注 coffee。
     * 返回插入耗时（毫秒）。所有基准共用同一份数据——改这里等于改所有基准。
     */
    private suspend fun seed10k(): Long {
        db.accountDao().upsert(AccountEntity(name = "银行", type = AccountType.DEBIT, initialBalance = 5000000, createdAt = 1, updatedAt = 1))
        db.accountDao().upsert(AccountEntity(name = "现金", type = AccountType.CASH, initialBalance = 50000, createdAt = 1, updatedAt = 1))
        db.categoryDao().upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE))
        db.categoryDao().upsert(CategoryEntity(name = "工资", kind = CategoryKind.INCOME))
        db.categoryDao().upsert(CategoryEntity(name = "早餐", kind = CategoryKind.EXPENSE, parentId = 1L))
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
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)",
                    arrayOf<Any?>(
                        type,
                        (i % 500 + 1).toLong(),
                        1L,
                        if (type == "TRANSFER") 2L else null,
                        when {
                            type == "INCOME" -> 2L
                            type == "EXPENSE" -> if (i % 3 == 0) 3L else 1L
                            else -> null
                        },
                        date.toString(),
                        if (i % 7 == 0) "coffee" else null,
                    ),
                )
            }
            db.openHelper.writableDatabase.setTransactionSuccessful()
        } finally {
            db.openHelper.writableDatabase.endTransaction()
        }
        assertEquals(10_000, db.transactionDao().count())
        return System.currentTimeMillis() - start
    }

    @Test
    fun filterAndStats_10kTransactions_underBudget() = runTest {
        seed10k()
        // 计划 6.2：1 万笔交易下的筛选与统计口径切换要有预算。这里走真口径：
        // 日期区间（命中索引）→ TransactionFilter.matches（父分类连子分类）→ StatsCalculator。
        val filter = TransactionFilter(
            accountIds = setOf(1L),
            categoryIds = setOf(1L),
            minAmountCents = 100,
            maxAmountCents = 400,
            noteKeyword = "coffee",
            from = LocalDate.of(2025, 9, 1),
            to = LocalDate.of(2026, 10, 25),
        )
        val parentIds = mapOf(3L to 1L)
        val (from, to) = filter.queryBounds()!!

        val t0 = System.currentTimeMillis()
        val rows = db.transactionDao().observeBetween(from, to).first()
        val filtered = rows.map { it.toRecord() }.filter { filter.matches(it, parentIds) }
        val summary = StatsCalculator.summarize(filtered)
        val totalMs = System.currentTimeMillis() - t0

        println("BENCHMARK-FILTER: rangeQuery(${rows.size}笔) 筛选后(${filtered.size}笔) 支出=${summary.expenseCents}分 合计=${totalMs}ms")

        assertTrue("筛选应命中数据: ${filtered.size}", filtered.isNotEmpty())
        assertTrue("父分类命中应含子分类「早餐」的行", filtered.any { it.categoryId == 3L })
        assertEquals(
            filtered.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountCents },
            summary.expenseCents,
        )
        assertTrue("筛选+统计 ${totalMs}ms 超标", totalMs < 2_000)
    }

    @Test
    fun drillDownFromReport_10kTransactions_underBudget() = runTest {
        seed10k()
        // 用户点统计页「支出排行」里的一行 = 把该分类 + 报表当月做成筛选条件，跳明细查一次。
        // 走的是真实链路：ReportDrillDown 造的 filter → queryBounds() 日期区间 → DAO 范围查询
        // → TransactionFilter.matches（父分类连子分类）→ StatsCalculator。
        val month = YearMonth.of(2026, 8)
        val filter = ReportDrillDown.byCategory(month = month, categoryId = 1L)
        val (from, to) = filter.queryBounds()!!

        val t0 = System.currentTimeMillis()
        val rows = db.transactionDao().observeBetween(from, to).first()
        val filtered = rows.map { it.toRecord() }.filter { filter.matches(it, mapOf(3L to 1L)) }
        val summary = StatsCalculator.summarize(filtered)
        val totalMs = System.currentTimeMillis() - t0

        println("BENCHMARK-DRILLDOWN: monthQuery(${rows.size}笔) 下钻后(${filtered.size}笔) 支出=${summary.expenseCents}分 合计=${totalMs}ms")

        // 区间只能是 2026-08（半开：8/1 ≤ d < 9/1），下钻后每行都在这个月里
        assertEquals(from, "2026-08-01")
        assertEquals(to, "2026-09-01")
        assertTrue("下钻区间应命中该月数据: ${rows.size}", rows.isNotEmpty())
        assertTrue("下钻区间外不允许有行", rows.all { it.occurredOn < "2026-09-01" })
        assertTrue("下钻应命中父分类及其子分类", filtered.isNotEmpty())
        assertEquals(
            filtered.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountCents },
            summary.expenseCents,
        )
        assertTrue("下钻查询+${filtered.size}笔统计 ${totalMs}ms 超标", totalMs < 2_000)
    }

    @Test
    fun monthlyTrend_10kTransactions_underBudget() = runTest {
        seed10k()
        // F10 趋势折线：全年 12 个月的窗口查询 + 按月归档，链路与页面一致——
        // Dates.monthBounds 给 [首月1号, 次月1号) → DAO 范围查询 → StatsCalculator.monthlyTotals。
        val month = YearMonth.of(2026, 8)
        val span = TrendSpan.FULL_YEAR
        val firstTrendMonth = month.minusMonths((span.months - 1).toLong())
        val (from, _) = com.nimo.ledger.util.Dates.monthBounds(firstTrendMonth)
        val (_, to) = com.nimo.ledger.util.Dates.monthBounds(month)
        val months = (span.months - 1 downTo 0).map { month.minusMonths(it.toLong()) }

        val t0 = System.currentTimeMillis()
        val rows = db.transactionDao().observeBetween(from, to).first()
        val totals = StatsCalculator.monthlyTotals(rows.map { it.toRecord() }, months)
        val totalMs = System.currentTimeMillis() - t0

        println("BENCHMARK-TREND: yearQuery(${rows.size}笔) ${months.size}个月 支出=${totals.sumOf { it.expenseCents }}分 合计=${totalMs}ms")

        assertEquals("2025-09-01", from)
        assertEquals("2026-09-01", to)
        assertEquals(span.months, totals.size)
        assertEquals(month, totals.last().month)
        // 口径唯一：折线各月之和必须等于对同一段区间直接 summarize
        assertEquals(
            StatsCalculator.summarize(rows.map { it.toRecord() }).expenseCents,
            totals.sumOf { it.expenseCents },
        )
        assertTrue("全年趋势查询+归档 ${totalMs}ms 超标", totalMs < 2_000)
    }
}
