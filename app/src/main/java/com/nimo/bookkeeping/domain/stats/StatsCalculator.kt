package com.nimo.bookkeeping.domain.stats

import com.nimo.bookkeeping.domain.model.CategoryInfo
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionRecord
import com.nimo.bookkeeping.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth

/** 期间收支汇总。净结余 = 收入 - 支出，转账与余额调整永不进入（铁律 3：类型白名单） */
data class PeriodSummary(
    val incomeCents: Long,
    val expenseCents: Long,
) {
    val netCents: Long get() = incomeCents - expenseCents
}

/** 分类排行行（一级分类，子分类金额已并入父分类） */
data class CategoryBreakdownRow(
    val categoryId: Long,
    val name: String,
    val amountCents: Long,
    /** 占支出（或收入）合计的比例，合计为 0 时为 null */
    val percent: Double?,
)

/** 账户支出分布行（F07「账户支出分布」） */
data class AccountBreakdownRow(
    val accountId: Long,
    val name: String,
    val amountCents: Long,
    /** 占本月支出合计的比例，合计为 0 时为 null */
    val percent: Double?,
)

/** 趋势折线上的一个点（F10）：某个月的收支合计 */
data class MonthlyTotal(
    val month: YearMonth,
    val incomeCents: Long,
    val expenseCents: Long,
)

/**
 * 统计口径唯一实现（计划 3.4）。纯函数，不依赖 Android / Room / Context。
 * 任何页面显示的统计数字都必须来自这里，不允许另写一套首页算法。
 */
object StatsCalculator {

    fun summarize(
        records: List<TransactionRecord>,
        range: ClosedRange<LocalDate>? = null,
    ): PeriodSummary {
        var income = 0L
        var expense = 0L
        for (r in records) {
            if (!inRange(r.occurredOn, range)) continue
            when (r.type) {
                TransactionType.INCOME -> income += r.amountCents
                TransactionType.EXPENSE -> expense += r.amountCents
                TransactionType.TRANSFER, TransactionType.BALANCE_ADJUST -> Unit
            }
        }
        return PeriodSummary(incomeCents = income, expenseCents = expense)
    }

    /**
     * 分类排行（一级分类粒度）：子分类金额并入父分类；
     * 自身没有父分类的按自身输出。按金额降序。
     */
    fun categoryBreakdown(
        records: List<TransactionRecord>,
        categories: Map<Long, CategoryInfo>,
        range: ClosedRange<LocalDate>? = null,
        kind: CategoryKind = CategoryKind.EXPENSE,
    ): List<CategoryBreakdownRow> {
        val wantedType = if (kind == CategoryKind.EXPENSE) TransactionType.EXPENSE else TransactionType.INCOME
        val byCategory = HashMap<Long, Long>()
        for (r in records) {
            if (r.type != wantedType) continue
            if (!inRange(r.occurredOn, range)) continue
            val cid = r.categoryId ?: continue
            byCategory[cid] = (byCategory[cid] ?: 0L) + r.amountCents
        }
        val rolledUp = HashMap<Long, Long>()
        for ((cid, amount) in byCategory) {
            val info = categories[cid] ?: continue
            val parentId = info.parentId
            if (parentId != null && categories.containsKey(parentId)) {
                rolledUp[parentId] = (rolledUp[parentId] ?: 0L) + amount
            } else {
                rolledUp[cid] = (rolledUp[cid] ?: 0L) + amount
            }
        }
        val total = byCategory.values.sum()
        return rolledUp.map { (cid, amount) ->
            CategoryBreakdownRow(
                categoryId = cid,
                name = categories[cid]?.name ?: "?",
                amountCents = amount,
                percent = if (total > 0) amount.toDouble() / total else null,
            )
        }.sortedByDescending { it.amountCents }
    }

    /**
     * 账户支出分布（F07）：按支出账户聚合，按金额降序。
     * 只统计 EXPENSE——转账按定义不是支出，它的两端都不出现在这里（铁律 3）。
     */
    fun accountBreakdown(
        records: List<TransactionRecord>,
        accountNames: Map<Long, String>,
        range: ClosedRange<LocalDate>? = null,
    ): List<AccountBreakdownRow> {
        val byAccount = HashMap<Long, Long>()
        for (r in records) {
            if (r.type != TransactionType.EXPENSE) continue
            if (!inRange(r.occurredOn, range)) continue
            // 支出按定义必有账户；直插脏数据可能没有，丢掉它好过把 page 拖崩
            val accountId = r.accountId ?: continue
            byAccount[accountId] = (byAccount[accountId] ?: 0L) + r.amountCents
        }
        val total = byAccount.values.sum()
        return byAccount.map { (accountId, amount) ->
            AccountBreakdownRow(
                accountId = accountId,
                name = accountNames[accountId] ?: "?",
                amountCents = amount,
                percent = if (total > 0) amount.toDouble() / total else null,
            )
        }.sortedByDescending { it.amountCents }
    }

    /** 环比：(当前-上月)/上月。上月为 0 时返回 null，UI 显示 "—"（计划 3.4） */
    fun monthOverMonth(currentCents: Long, previousCents: Long): Double? =
        if (previousCents == 0L) null else (currentCents - previousCents).toDouble() / previousCents

    /** 日均支出（向下取整到分） */
    fun dailyAverage(expenseCents: Long, days: Int): Long =
        if (days <= 0) 0L else expenseCents / days

    /**
     * 日均支出的分母（已过去的天数）：查看当月时按"今天几号"，
     * 已结束的月份按整月天数，还没到的月份按 0。用整月天数当分母会把月中看到的日均压得偏低。
     */
    fun elapsedDays(month: YearMonth, today: LocalDate): Int {
        val current = YearMonth.from(today)
        return when {
            month == current -> today.dayOfMonth
            month.isBefore(current) -> month.lengthOfMonth()
            else -> 0
        }
    }

    /**
     * 趋势折线（F10）：把 [records] 按月归档成收支合计，输出顺序与 [months] 一致——没有记录的月份补 0。
     * 只算 INCOME / EXPENSE：转账与余额调整永不进入（铁律 3），与 [summarize] 同一套白名单。
     * 不在 [months] 里的记录直接忽略：区间由调用方按月边界给出（月度查询禁止 substr 截取）。
     */
    fun monthlyTotals(
        records: List<TransactionRecord>,
        months: List<YearMonth>,
    ): List<MonthlyTotal> {
        if (months.isEmpty()) return emptyList()
        val wanted = months.toHashSet()
        val income = HashMap<YearMonth, Long>()
        val expense = HashMap<YearMonth, Long>()
        for (r in records) {
            val ym = YearMonth.from(r.occurredOn)
            if (ym !in wanted) continue
            when (r.type) {
                TransactionType.INCOME -> income[ym] = (income[ym] ?: 0L) + r.amountCents
                TransactionType.EXPENSE -> expense[ym] = (expense[ym] ?: 0L) + r.amountCents
                TransactionType.TRANSFER, TransactionType.BALANCE_ADJUST -> Unit
            }
        }
        return months.map { ym ->
            MonthlyTotal(
                month = ym,
                incomeCents = income[ym] ?: 0L,
                expenseCents = expense[ym] ?: 0L,
            )
        }
    }

    private fun inRange(date: LocalDate, range: ClosedRange<LocalDate>?): Boolean =
        range == null || (date >= range.start && date <= range.endInclusive)
}
