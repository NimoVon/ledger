package com.nimo.bookkeeping.domain.stats

import com.nimo.bookkeeping.domain.model.CategoryInfo
import com.nimo.bookkeeping.domain.model.TransactionRecord
import com.nimo.bookkeeping.domain.model.TransactionType
import java.time.LocalDate

/** 预算达成状态。NONE 表示"这个月没设预算"，不是"0 元预算"。 */
enum class BudgetStatus { NONE, OK, WARNING, OVER }

/** 一条预算的纯数据输入（不依赖 Room 实体，保持 domain 层可单测） */
data class BudgetInput(
    val id: Long,
    val categoryId: Long?,
    val amountCents: Long,
)

/** 预算页要用的计算结果 */
data class BudgetSummary(
    /** 当月总预算；null = 没设 */
    val totalBudgetCents: Long?,
    /** 当月支出合计（含未分类支出，与统计页「支出」同一口径） */
    val totalSpentCents: Long,
    val totalStatus: BudgetStatus,
    /** categoryId → 该分类本月支出；父分类含其直接子分类的支出 */
    val spentByCategory: Map<Long, Long>,
)

/**
 * 预算口径唯一实现（F09）。
 *
 * 三条硬口径，改这里之前先想清楚：
 * 1. **只算支出**：预算管的是花销，收入不进预算分母（与计划 F09「按月设置总预算 / 分类预算」一致）。
 * 2. **支出白名单**：只认 TransactionType.EXPENSE，转账（TRANSFER）与余额调整（BALANCE_ADJUST）
 *    天然不在白名单里，不能靠"不等于转账"来排除。
 * 3. **区间口径复用** [inRange]（StatsCalculator.kt 顶层的同一份实现），预算与统计必须用同样的
 *    日期边界，occurredOn >= from AND < to。
 *
 * 汇总与统计页的分工：总额口径 = 本月支出合计（含未分类），单分类口径 = 该分类（父分类再并入直接子分类），
 * 两者相加不相等是正常的——未分类支出只进总额。
 */
object BudgetCalculator {

    /** 告警阈值：已用达到预算的 80% 提示「接近上限」，达到 100% 算超支（计划 F09） */
    const val WARNING_PERCENT: Int = 80

    /**
     * 预算状态。金额用整数比较，**不用 Double 判金额**：
     * spent * 100 >= budget * 80 就是"已用 ≥ 80%"，没有浮点误差，也不会在边界上抖。
     * 预算缺失（null）由调用方转成 BudgetStatus.NONE。
     */
    fun status(spentCents: Long, budgetCents: Long): BudgetStatus {
        if (budgetCents <= 0L) return BudgetStatus.NONE
        val spent = if (spentCents < 0L) 0L else spentCents
        return when {
            spent >= budgetCents -> BudgetStatus.OVER
            spent * 100L >= budgetCents * WARNING_PERCENT -> BudgetStatus.WARNING
            else -> BudgetStatus.OK
        }
    }

    /** 已用比例，仅用于展示（进度条、百分比文案）。没设预算时返回 0.0 */
    fun percent(spentCents: Long, budgetCents: Long): Double =
        if (budgetCents <= 0L) 0.0 else spentCents.toDouble() / budgetCents.toDouble()

    /**
     * 汇总一个月的预算执行情况。
     *
     * @param records 该月已可见的流水（调用方须先过 TransactionVisibility）
     * @param categories 全部分类（含停用），parentId 决定父子归并
     * @param budgets 该月的预算行，categoryId == null 的是总预算
     * @param range 额外区间过滤，null 表示 records 已经是当月范围
     */
    fun summarize(
        records: List<TransactionRecord>,
        categories: Map<Long, CategoryInfo>,
        budgets: List<BudgetInput>,
        range: ClosedRange<LocalDate>? = null,
    ): BudgetSummary {
        // 每个分类自己的支出
        val own = HashMap<Long, Long>()
        for (r in records) {
            if (r.type != TransactionType.EXPENSE) continue
            if (!inRange(r.occurredOn, range)) continue
            val categoryId = r.categoryId ?: continue
            own[categoryId] = (own[categoryId] ?: 0L) + r.amountCents
        }

        // 父分类的预算管整棵子树：子分类的支出并入父分类。
        // 子分类自己的预算只算它自己——父的额度不摊到子身上，两者互不代算（与 categoryBreakdown 同口径）。
        val spentByCategory = HashMap<Long, Long>(own)
        for (info in categories.values) {
            val parentId = info.parentId ?: continue
            val childAmount = own[info.id] ?: continue
            if (!categories.containsKey(parentId)) continue
            spentByCategory[parentId] = (spentByCategory[parentId] ?: 0L) + childAmount
        }

        val totalBudgetCents = budgets.firstOrNull { it.categoryId == null }?.amountCents
        // 总额直接取统计口径的支出合计：它能包含"没有分类"的支出，和统计页显示的数字一致
        val totalSpentCents = StatsCalculator.summarize(records, range).expenseCents
        return BudgetSummary(
            totalBudgetCents = totalBudgetCents,
            totalSpentCents = totalSpentCents,
            totalStatus = totalBudgetCents?.let { status(totalSpentCents, it) } ?: BudgetStatus.NONE,
            spentByCategory = spentByCategory,
        )
    }
}
