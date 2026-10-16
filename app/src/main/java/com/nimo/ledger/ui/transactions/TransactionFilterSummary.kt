package com.nimo.ledger.ui.transactions

import com.nimo.ledger.domain.filter.TransactionFilter
import com.nimo.ledger.util.Money

/**
 * 筛选条件的文字表述（F06）。
 *
 * 摘要与筛选面板都从这里取，保证"屏幕上写的条件"与"实际用于筛选的条件"是同一份，
 * 也让真机核对时可以直接照着 chips 逐条对。
 */
object TransactionFilterSummary {

    /**
     * 条件摘要，按"账户 → 分类 → 金额 → 关键词 → 日期"固定顺序，未开条件的维度不出现。
     * @param accountNames / @param categoryNames 由 ViewModel 从实体投影，页面不再查库
     */
    fun chips(
        filter: TransactionFilter,
        accountNames: Map<Long, String> = emptyMap(),
        categoryNames: Map<Long, String> = emptyMap(),
    ): List<String> {
        val chips = mutableListOf<String>()
        if (filter.accountIds.isNotEmpty()) {
            chips += "账户：" + names(filter.accountIds, accountNames)
        }
        if (filter.categoryIds.isNotEmpty()) {
            chips += "分类：" + names(filter.categoryIds, categoryNames)
        }
        amountText(filter)?.let { chips += it }
        if (filter.noteKeyword.isNotBlank()) {
            chips += "关键词：" + filter.noteKeyword.trim()
        }
        dateRangeText(filter)?.let { chips += "日期：" + it }
        return chips
    }

    /** 「金额：¥100.00 ~ ¥500.00」/「金额：≥ ¥100.00」/「金额：≤ ¥500.00」；无界限返回 null */
    fun amountText(filter: TransactionFilter): String? {
        val min = filter.minAmountCents
        val max = filter.maxAmountCents
        return when {
            min != null && max != null ->
                "金额：" + Money.formatCurrency(min) + " ~ " + Money.formatCurrency(max)

            min != null -> "金额：≥ " + Money.formatCurrency(min)
            max != null -> "金额：≤ " + Money.formatCurrency(max)
            else -> null
        }
    }

    /** 「2026-09-01 ~ 2026-09-30」；只填一端时是「2026-09-01 起」/「截至 2026-09-30」 */
    fun dateRangeText(filter: TransactionFilter): String? {
        val (start, end) = filter.normalizedRange()
        return when {
            start != null && end != null -> "$start ~ $end"
            start != null -> "$start 起"
            end != null -> "截至 $end"
            else -> null
        }
    }

    private fun names(ids: Set<Long>, names: Map<Long, String>): String =
        ids.map { names[it] ?: "#$it" }.sorted().joinToString("、")
}
