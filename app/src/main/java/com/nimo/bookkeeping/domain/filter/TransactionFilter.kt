package com.nimo.bookkeeping.domain.filter

import com.nimo.bookkeeping.domain.model.TransactionRecord
import java.time.LocalDate
import kotlin.math.abs

/**
 * 流水筛选条件（F06：按账户、分类（父子均命中）、金额区间、日期区间、备注关键词组合筛选）。
 *
 * 纯数据 + 纯函数，不依赖 Room / Android，可在 JVM 上直接单测。
 * 空集合、空关键字、空区间一律表示"这一维不限"，因此 [TransactionFilter] 默认值就是"不筛"。
 * 各维度之间是 AND 组合；同一维度内是多选 OR。
 */
data class TransactionFilter(
    /** 选中账户；转账只要转出或转入任一端命中就算命中（"这个账户的流水"的直觉） */
    val accountIds: Set<Long> = emptySet(),
    /** 选中分类；父分类被选中时它的子分类一并命中（计划 F06：父子均命中） */
    val categoryIds: Set<Long> = emptySet(),
    /** 金额下限（分，含）；比较的是绝对金额，余额调整的负数按数值大小命中 */
    val minAmountCents: Long? = null,
    /** 金额上限（分，含） */
    val maxAmountCents: Long? = null,
    /** 备注关键词；忽略大小写与首尾空白，空串表示不限 */
    val noteKeyword: String = "",
    /** 日期下限（闭区间，可只填一端） */
    val from: LocalDate? = null,
    /** 日期上限（闭区间，可只填一端） */
    val to: LocalDate? = null,
) {

    /** 是否开了任意条件：用来区分"本月没有记录"与"没有符合条件的记录" */
    val isActive: Boolean
        get() = accountIds.isNotEmpty() ||
            categoryIds.isNotEmpty() ||
            minAmountCents != null ||
            maxAmountCents != null ||
            noteKeyword.isNotBlank() ||
            from != null ||
            to != null

    /** 是否指定了日期区间：指定后"按月浏览"就没有意义了，月份切换让位给区间 */
    val hasDateRange: Boolean get() = from != null || to != null

    /**
     * 区间归一化：起止写反了就互换。写入侧（筛选面板）也会先归一化，
     * 这里再兜一次是为了让 [matches] 与 [queryBounds] 永远看到同一个区间。
     */
    fun normalizedRange(): Pair<LocalDate?, LocalDate?> {
        val start = from
        val end = to
        return if (start != null && end != null && start > end) end to start else start to end
    }

    /**
     * 查询用的 [from, to) 半开区间，直接命中 occurredOn 上的索引（计划 3.2：范围比较，禁止 substr 截取）。
     * 没有任何日期条件时返回 null，调用方回退到"按月浏览"。
     */
    fun queryBounds(): Pair<String, String>? {
        val (start, end) = normalizedRange()
        if (start == null && end == null) return null
        // 只填一端：另一端用自然日量的两端兜住，仍然是一次范围比较
        val fromDate = start ?: MIN_DATE
        val toDate = end ?: MAX_DATE
        return fromDate.toString() to toDate.plusDays(1).toString()
    }

    /**
     * 单行匹配。
     * @param categoryParentIds 分类 id → 父分类 id（单层树），用于"父分类命中子分类"
     */
    fun matches(
        record: TransactionRecord,
        categoryParentIds: Map<Long, Long?> = emptyMap(),
    ): Boolean {
        if (accountIds.isNotEmpty()) {
            val hit = record.accountId?.let { accountIds.contains(it) } == true ||
                record.toAccountId?.let { accountIds.contains(it) } == true
            if (!hit) return false
        }
        if (categoryIds.isNotEmpty()) {
            val categoryId = record.categoryId ?: return false
            val parentId = categoryParentIds[categoryId]
            val hit = categoryIds.contains(categoryId) ||
                (parentId != null && categoryIds.contains(parentId))
            if (!hit) return false
        }
        val amount = abs(record.amountCents)
        minAmountCents?.let { if (amount < it) return false }
        maxAmountCents?.let { if (amount > it) return false }
        if (noteKeyword.isNotBlank()) {
            val keyword = noteKeyword.trim()
            if (!record.note.orEmpty().contains(keyword, ignoreCase = true)) return false
        }
        val (start, end) = normalizedRange()
        start?.let { if (record.occurredOn < it) return false }
        end?.let { if (record.occurredOn > it) return false }
        return true
    }

    /** 批次过滤；没开条件时原样返回同一个实例，省一次遍历与一次分配 */
    fun apply(
        records: List<TransactionRecord>,
        categoryParentIds: Map<Long, Long?> = emptyMap(),
    ): List<TransactionRecord> =
        if (!isActive) records else records.filter { matches(it, categoryParentIds) }

    companion object {
        /** 只填上限时的兜底下界（早于任何可能的记账日） */
        val MIN_DATE: LocalDate = LocalDate.of(1970, 1, 1)

        /** 只填下限时的兜底上界（晚于任何可能的记账日） */
        val MAX_DATE: LocalDate = LocalDate.of(9999, 12, 31)
    }
}
