package com.nimo.bookkeeping.ui.reports

import com.nimo.bookkeeping.domain.filter.TransactionFilter
import java.time.YearMonth

/**
 * 月报下钻（F07）：把「被点的那一行」翻译成明细页能执行的筛选条件。
 *
 * 条件 = 被点的那一维 + 报表当前看的那个月，缺一不可：
 * 只带分类会让用户在 9 月的报表上点一下、落到 10 月的明细（数字对不上，看起来像 bug）；
 * 月份用自然月闭区间 [1 号, 月末]，与 [com.nimo.bookkeeping.util.Dates.monthBounds] 的月度边界一致
 * （查询时由 TransactionFilter.queryBounds 转成 [from, to+1) 半开区间，仍走日期范围比较，不用 substr）。
 *
 * 分类按父分类 id 下钻：StatsCalculator.categoryBreakdown 的金额本来就是"父 + 其子"，
 * TransactionFilter.matches 也把父分类当作命中其子分类，两边口径一致。
 */
object ReportDrillDown {

    fun byCategory(month: YearMonth, categoryId: Long): TransactionFilter = TransactionFilter(
        categoryIds = setOf(categoryId),
        from = month.atDay(1),
        to = month.atEndOfMonth(),
    )

    fun byAccount(month: YearMonth, accountId: Long): TransactionFilter = TransactionFilter(
        accountIds = setOf(accountId),
        from = month.atDay(1),
        to = month.atEndOfMonth(),
    )
}
