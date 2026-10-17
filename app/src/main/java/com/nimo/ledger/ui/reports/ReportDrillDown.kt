package com.nimo.ledger.ui.reports

import com.nimo.ledger.domain.filter.TransactionFilter
import com.nimo.ledger.domain.stats.ReportRange
import java.time.YearMonth

/**
 * 报表下钻（F07）：把「被点的那一行」翻译成明细页能执行的筛选条件。
 *
 * 条件 = 被点的那一维 + 报表当前看的那个范围，缺一不可：
 * 只带分类会让用户在 9 月的报表上点一下、落到 10 月的明细（数字对不上，看起来像 bug）。
 *
 * 范围用 [ReportRange] 的**闭区间** [from, to-1]：用户看到的是"3 月 1 日到 4 月 15 日"，
 * 明细页的日期筛选也是闭区间（留空表示不限）。
 * 查询时由 TransactionFilter.queryBounds 转成 [from, to+1) 半开区间，仍走日期范围比较，不用 substr。
 *
 * 分类按父分类 id 下钻：StatsCalculator.categoryBreakdown 的金额本来就是"父 + 其子"，
 * TransactionFilter.matches 也把父分类当作命中其子分类，两边口径一致。
 */
object ReportDrillDown {

    /** 按范围 + 分类下钻（统计页当前口径） */
    fun byCategory(range: ReportRange, categoryId: Long): TransactionFilter = TransactionFilter(
        categoryIds = setOf(categoryId),
        from = range.from,
        to = range.to.minusDays(1),
    )

    /** 按范围 + 账户下钻 */
    fun byAccount(range: ReportRange, accountId: Long): TransactionFilter = TransactionFilter(
        accountIds = setOf(accountId),
        from = range.from,
        to = range.to.minusDays(1),
    )

    /** 单月下钻（旧入口，等价于 Month 范围） */
    fun byCategory(month: YearMonth, categoryId: Long): TransactionFilter =
        byCategory(ReportRange.Month(month), categoryId)

    fun byAccount(month: YearMonth, accountId: Long): TransactionFilter =
        byAccount(ReportRange.Month(month), accountId)
}
