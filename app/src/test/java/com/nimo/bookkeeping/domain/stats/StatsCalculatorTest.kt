package com.nimo.bookkeeping.domain.stats

import com.nimo.bookkeeping.domain.model.CategoryInfo
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionRecord
import com.nimo.bookkeeping.domain.model.TransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsCalculatorTest {

    private fun tx(
        id: Long,
        type: TransactionType,
        amount: Long,
        day: Int,
        categoryId: Long? = null,
        accountId: Long = 1L,
        toAccountId: Long? = null,
    ) = TransactionRecord(
        id = id,
        type = type,
        amountCents = amount,
        accountId = accountId,
        toAccountId = toAccountId,
        categoryId = categoryId,
        occurredOn = LocalDate.of(2026, 10, day),
    )

    private val october = listOf(
        tx(1, TransactionType.INCOME, 1000000, 1, categoryId = 101),   // 收入 ¥10,000
        tx(2, TransactionType.EXPENSE, 250000, 2, categoryId = 11),    // 支出 ¥2,500
        tx(3, TransactionType.EXPENSE, 125000, 3, categoryId = 12),    // 支出 ¥1,250
        tx(4, TransactionType.TRANSFER, 800000, 4, accountId = 1, toAccountId = 2), // 转账 ¥8,000
        tx(5, TransactionType.BALANCE_ADJUST, -50000, 5),              // 校准 -¥500
    )

    // ---- 汇总口径 ----

    @Test
    fun summarize_excludesTransferAndBalanceAdjust() {
        val s = StatsCalculator.summarize(october)
        assertEquals(1000000L, s.incomeCents)
        assertEquals(375000L, s.expenseCents)
        assertEquals(625000L, s.netCents)
    }

    @Test
    fun summarize_respectsDateRange() {
        val range = LocalDate.of(2026, 10, 2)..LocalDate.of(2026, 10, 3)
        val s = StatsCalculator.summarize(october, range)
        assertEquals(0L, s.incomeCents)
        assertEquals(375000L, s.expenseCents)
    }

    @Test
    fun summarize_emptyList() {
        val s = StatsCalculator.summarize(emptyList())
        assertEquals(0L, s.incomeCents)
        assertEquals(0L, s.expenseCents)
        assertEquals(0L, s.netCents)
    }

    // ---- 分类排行（父子聚合） ----

    private val categories = mapOf(
        1L to CategoryInfo(1, CategoryKind.EXPENSE, null, "餐饮"),
        11L to CategoryInfo(11, CategoryKind.EXPENSE, 1, "午餐"),
        12L to CategoryInfo(12, CategoryKind.EXPENSE, 1, "晚餐"),
        2L to CategoryInfo(2, CategoryKind.EXPENSE, null, "交通"),
    )

    @Test
    fun categoryBreakdown_rollsChildrenIntoParent() {
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 100000, 1, categoryId = 11), // 午餐 ¥1,000
            tx(2, TransactionType.EXPENSE, 50000, 2, categoryId = 12),  // 晚餐 ¥500
            tx(3, TransactionType.EXPENSE, 20000, 3, categoryId = 1),   // 餐饮直接记 ¥200
            tx(4, TransactionType.EXPENSE, 70000, 4, categoryId = 2),   // 交通 ¥700
            tx(5, TransactionType.INCOME, 999999, 5, categoryId = 101), // 收入不进支出排行
            tx(6, TransactionType.TRANSFER, 50000, 6, accountId = 1, toAccountId = 2), // 转账不算
        )
        val rows = StatsCalculator.categoryBreakdown(records, categories)
        assertEquals(2, rows.size)
        val dining = rows[0]
        assertEquals(1L, dining.categoryId)
        assertEquals("餐饮", dining.name)
        assertEquals(170000L, dining.amountCents)          // 100000 + 50000 + 20000
        assertEquals(0.7083, dining.percent!!, 0.0001)     // 170000 / 240000
        val transport = rows[1]
        assertEquals(70000L, transport.amountCents)
        assertEquals(0.2916, transport.percent!!, 0.0001)
    }

    @Test
    fun categoryBreakdown_zeroTotalGivesNullPercent() {
        val rows = StatsCalculator.categoryBreakdown(emptyList(), categories)
        assertEquals(0, rows.size)
    }

    @Test
    fun categoryBreakdown_incomeKind() {
        val incomeCats = mapOf(
            101L to CategoryInfo(101, CategoryKind.INCOME, null, "工资"),
        )
        val records = listOf(
            tx(1, TransactionType.INCOME, 100000, 1, categoryId = 101),
            tx(2, TransactionType.EXPENSE, 50000, 2, categoryId = 1),
        )
        val rows = StatsCalculator.categoryBreakdown(records, incomeCats, kind = CategoryKind.INCOME)
        assertEquals(1, rows.size)
        assertEquals(100000L, rows[0].amountCents)
    }

    // ---- 环比与日均 ----

    @Test
    fun monthOverMonth_cases() {
        assertNull(StatsCalculator.monthOverMonth(1000L, 0L))          // 上月为 0 → null
        assertEquals(1.5, StatsCalculator.monthOverMonth(1000L, 400L)!!, 1e-9)
        assertEquals(-0.6, StatsCalculator.monthOverMonth(400L, 1000L)!!, 1e-9)
        assertEquals(0.0, StatsCalculator.monthOverMonth(400L, 400L)!!, 1e-9)
    }

    @Test
    fun dailyAverage_cases() {
        assertEquals(10000L, StatsCalculator.dailyAverage(310000L, 31))  // 整除：¥3100/31天 = ¥100/天
        assertEquals(12096L, StatsCalculator.dailyAverage(375000L, 31))  // 向下取整到分
        assertEquals(0L, StatsCalculator.dailyAverage(375000L, 0))       // 非法天数
        assertEquals(0L, StatsCalculator.dailyAverage(0L, 31))
    }
}
