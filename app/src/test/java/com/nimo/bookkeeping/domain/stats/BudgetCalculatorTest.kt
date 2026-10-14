package com.nimo.bookkeeping.domain.stats

import com.nimo.bookkeeping.domain.model.CategoryInfo
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionRecord
import com.nimo.bookkeeping.domain.model.TransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 预算口径（F09）：只算支出、转账不进预算、父子归并、80/100 阈值。
 * 这些数字直接决定页面上"接近上限/已超支"的判定，是 F09 的核心逻辑。
 */
class BudgetCalculatorTest {

    private val food = CategoryInfo(id = 1L, kind = CategoryKind.EXPENSE, parentId = null, name = "餐饮")
    private val breakfast = CategoryInfo(id = 2L, kind = CategoryKind.EXPENSE, parentId = 1L, name = "早餐")
    private val shopping = CategoryInfo(id = 3L, kind = CategoryKind.EXPENSE, parentId = null, name = "购物")
    private val salary = CategoryInfo(id = 4L, kind = CategoryKind.INCOME, parentId = null, name = "工资")
    private val categories = listOf(food, breakfast, shopping, salary).associateBy { it.id }

    private var nextId = 1L
    private fun record(
        type: TransactionType,
        amountCents: Long,
        categoryId: Long?,
        date: String = "2026-10-05",
    ) = TransactionRecord(
        id = nextId++,
        type = type,
        amountCents = amountCents,
        accountId = 1L,
        categoryId = categoryId,
        occurredOn = LocalDate.parse(date),
    )

    @Test
    fun status_thresholds_areInclusiveAtEightyAndHundred() {
        assertEquals(BudgetStatus.NONE, BudgetCalculator.status(0L, 0L))
        assertEquals(BudgetStatus.OVER, BudgetCalculator.status(600_00L, 600_00L))
        assertEquals(BudgetStatus.OVER, BudgetCalculator.status(600_01L, 600_00L))
        assertEquals(BudgetStatus.WARNING, BudgetCalculator.status(480_00L, 600_00L))
        assertEquals(BudgetStatus.OK, BudgetCalculator.status(479_99L, 600_00L))
        assertEquals(BudgetStatus.OK, BudgetCalculator.status(0L, 600_00L))
    }

    @Test
    fun percent_isZeroWhenBudgetMissing() {
        assertEquals(0.0, BudgetCalculator.percent(100L, 0L), 0.0001)
        assertEquals(0.5, BudgetCalculator.percent(50L, 100L), 0.0001)
    }

    @Test
    fun totalSpent_countsUncategorizedExpense_butNotTransfersOrIncome() {
        val records = listOf(
            record(TransactionType.EXPENSE, 30_000L, 1L),
            record(TransactionType.EXPENSE, 20_000L, null),
            record(TransactionType.INCOME, 999_999L, 4L),
            record(TransactionType.TRANSFER, 500_000L, null),
            record(TransactionType.BALANCE_ADJUST, -1_000L, null),
        )
        val summary = BudgetCalculator.summarize(
            records = records,
            categories = categories,
            budgets = listOf(BudgetInput(id = 1L, categoryId = null, amountCents = 60_000L)),
        )
        // 总额含未分类支出，等于统计页「支出」；转账/余额调整/收入都不算
        assertEquals(50_000L, summary.totalSpentCents)
        assertEquals(BudgetStatus.WARNING, summary.totalStatus)
        assertEquals(30_000L, summary.spentByCategory[1L])
    }

    @Test
    fun categoryBudget_withoutTotalBudget_isAllowed() {
        val records = listOf(record(TransactionType.EXPENSE, 10_000L, 3L))
        val summary = BudgetCalculator.summarize(
            records = records,
            categories = categories,
            budgets = listOf(BudgetInput(id = 9L, categoryId = 3L, amountCents = 20_000L)),
        )
        assertNull(summary.totalBudgetCents)
        assertEquals(BudgetStatus.NONE, summary.totalStatus)
        assertEquals(10_000L, summary.spentByCategory[3L])
    }

    @Test
    fun parentSpending_includesDirectChild_spending() {
        val records = listOf(
            record(TransactionType.EXPENSE, 5_000L, 1L),
            record(TransactionType.EXPENSE, 10_000L, 2L),
            record(TransactionType.EXPENSE, 7_000L, 3L),
        )
        val summary = BudgetCalculator.summarize(
            records = records,
            categories = categories,
            budgets = listOf(BudgetInput(id = 1L, categoryId = 1L, amountCents = 20_000L)),
        )
        // 父分类 = 自己 5000 + 子分类 10000
        assertEquals(15_000L, summary.spentByCategory[1L])
        // 子分类只算自己，父的额度不摊到子身上
        assertEquals(10_000L, summary.spentByCategory[2L])
        assertEquals(7_000L, summary.spentByCategory[3L])
    }

    @Test
    fun childSpending_isNotCountedIntoUnrelatedParent() {
        val records = listOf(record(TransactionType.EXPENSE, 10_000L, 2L))
        val summary = BudgetCalculator.summarize(
            records = records,
            categories = categories,
            budgets = listOf(BudgetInput(id = 1L, categoryId = 3L, amountCents = 20_000L)),
        )
        assertEquals(10_000L, summary.spentByCategory[1L])
        assertEquals(null, summary.spentByCategory[3L])
    }

    @Test
    fun range_filtersOutOtherMonths() {
        val records = listOf(
            record(TransactionType.EXPENSE, 1_000L, 1L, date = "2026-09-30"),
            record(TransactionType.EXPENSE, 2_000L, 1L, date = "2026-10-01"),
            record(TransactionType.EXPENSE, 4_000L, 1L, date = "2026-10-31"),
            record(TransactionType.EXPENSE, 8_000L, 1L, date = "2026-11-01"),
        )
        val summary = BudgetCalculator.summarize(
            records = records,
            categories = categories,
            budgets = listOf(BudgetInput(id = 1L, categoryId = null, amountCents = 10_000L)),
            range = LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 31),
        )
        assertEquals(6_000L, summary.totalSpentCents)
        assertEquals(6_000L, summary.spentByCategory[1L])
    }

    @Test
    fun incomeBudget_neverAccumulatesSpending() {
        val records = listOf(record(TransactionType.INCOME, 100_000L, 4L))
        val summary = BudgetCalculator.summarize(
            records = records,
            categories = categories,
            budgets = listOf(BudgetInput(id = 1L, categoryId = 4L, amountCents = 50_000L)),
        )
        assertEquals(0L, summary.totalSpentCents)
        assertEquals(null, summary.spentByCategory[4L])
    }
}
