package com.nimo.bookkeeping.ui.transactions

import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.data.local.toRecordOrNull
import com.nimo.bookkeeping.domain.model.TransactionRecord
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.stats.StatsCalculator
import com.nimo.bookkeeping.util.Money
import java.time.LocalDate
import java.time.YearMonth

/** 时间线里的一行流水：分类与账户已解析成可直接渲染的文本，UI 不再回表查询 */
data class TransactionRow(
    val id: Long,
    val type: TransactionType,
    /** 分类图标：预置分类存的是 emoji（见 DefaultCategories），转账/调整用手绘符号 */
    val categoryIcon: String,
    val categoryName: String,
    val note: String?,
    /** 所属账户；转账为「转出 → 转入」 */
    val accountLabel: String,
    val amountCents: Long,
    val occurredOn: String,
)

/** 按日分组的一段：段头显示日期与当日合计 */
data class TransactionDayGroup(
    val date: String,
    val label: String,
    /** 当日收入合计 */
    val incomeCents: Long,
    /** 当日支出合计 */
    val expenseCents: Long,
    val rows: List<TransactionRow>,
)

/**
 * 流水时间线（F05）的纯展示逻辑：分组、取名、拼标签。
 *
 * 刻意不依赖 Android / Room / Context，可在 JVM 上直接单测。
 * **不做任何金额口径计算**：所有收入/支出合计一律调用 [StatsCalculator]
 * （计划铁律：统计口径唯一实现，任何页面不得另写一套）。
 */
object TransactionTimeline {

    private const val UNKNOWN_CATEGORY = "未分类"
    private const val UNKNOWN_ACCOUNT = "未知账户"
    private const val DEFAULT_CATEGORY_ICON = "•"
    private const val TRANSFER_NAME = "转账"
    private const val TRANSFER_ICON = "⇄"
    private const val BALANCE_ADJUST_NAME = "余额调整"
    private const val BALANCE_ADJUST_ICON = "⚖"

    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /**
     * 排序铁律（计划 3.2 / V2.2 补遗 3）：occurredOn DESC, id DESC，与 DAO 的 SQL 完全一致。
     * 同日多笔按录入顺序倒序；编辑是整行替换（id 不变）、撤销是按原 id 恢复，
     * 因此两种操作之后顺序都不会跳动。
     */
    private val canonicalOrder: Comparator<TransactionEntity> =
        compareByDescending<TransactionEntity> { it.occurredOn }.thenByDescending { it.id }

    /**
     * 把一批交易切成"按日倒序、日内 id 倒序"的时间线段。
     *
     * @param categories 分类全量（含已停用），流水页从 Repository 观察而来
     * @param accounts 账户全量（含已归档），归档账户的历史流水照常显示名字
     */
    fun group(
        transactions: List<TransactionEntity>,
        categories: Map<Long, CategoryEntity> = emptyMap(),
        accounts: Map<Long, AccountEntity> = emptyMap(),
        today: LocalDate = LocalDate.now(),
    ): List<TransactionDayGroup> =
        transactions.sortedWith(canonicalOrder)
            // groupBy 保序：外层顺序即"首次出现的日期"顺序，也就是日期倒序
            .groupBy { it.occurredOn }
            .map { (date, rows) ->
                val day = runCatching { LocalDate.parse(date) }.getOrNull()
                // 本组已经是同一天，无需再传区间；口径仍由 StatsCalculator 给出
                val summary = StatsCalculator.summarize(toRecords(rows))
                TransactionDayGroup(
                    date = date,
                    label = if (day == null) date else dayLabel(day, today),
                    incomeCents = summary.incomeCents,
                    expenseCents = summary.expenseCents,
                    rows = rows.map { toRow(it, categories, accounts) },
                )
            }

    /** "2026年10月" */
    fun monthLabel(month: YearMonth): String = "${month.year}年${month.monthValue}月"

    /** 段头日期："今天" / "昨天" / "10月12日 周一" */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${date.monthValue}月${date.dayOfMonth}日 ${WEEKDAYS[date.dayOfWeek.value - 1]}"
    }

    /** 金额文本：支出带负号、收入带正号，符号落地统一交给 [Money.formatCurrency]（铁律 1） */
    fun amountText(row: TransactionRow): String = when (row.type) {
        TransactionType.EXPENSE -> "-" + Money.formatCurrency(row.amountCents)
        TransactionType.INCOME -> "+" + Money.formatCurrency(row.amountCents)
        TransactionType.TRANSFER, TransactionType.BALANCE_ADJUST ->
            Money.formatCurrency(row.amountCents)
    }

    private fun toRow(
        tx: TransactionEntity,
        categories: Map<Long, CategoryEntity>,
        accounts: Map<Long, AccountEntity>,
    ): TransactionRow {
        val category = tx.categoryId?.let { categories[it] }
        val icon: String
        val name: String
        when (tx.type) {
            TransactionType.EXPENSE, TransactionType.INCOME -> {
                icon = category?.icon?.takeIf { it.isNotBlank() } ?: DEFAULT_CATEGORY_ICON
                name = category?.name ?: UNKNOWN_CATEGORY
            }

            TransactionType.TRANSFER -> {
                icon = TRANSFER_ICON
                name = TRANSFER_NAME
            }

            TransactionType.BALANCE_ADJUST -> {
                icon = BALANCE_ADJUST_ICON
                name = BALANCE_ADJUST_NAME
            }
        }
        return TransactionRow(
            id = tx.id,
            type = tx.type,
            categoryIcon = icon,
            categoryName = name,
            note = tx.note,
            accountLabel = accountLabel(tx, accounts),
            amountCents = tx.amount,
            occurredOn = tx.occurredOn,
        )
    }

    /** 转账显示「转出 → 转入」，其余显示所属账户 */
    private fun accountLabel(
        tx: TransactionEntity,
        accounts: Map<Long, AccountEntity>,
    ): String = when (tx.type) {
        TransactionType.TRANSFER ->
            "${accountName(tx.accountId, accounts)} → ${accountName(tx.toAccountId, accounts)}"

        TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.BALANCE_ADJUST ->
            accountName(tx.accountId, accounts)
    }

    private fun accountName(id: Long?, accounts: Map<Long, AccountEntity>): String =
        id?.let { accounts[it]?.name } ?: UNKNOWN_ACCOUNT

    /**
     * 实体 → 领域模型。非法 occurredOn（只可能来自绕过校验器的直插，例如备份导入）
     * 会被丢弃而不是抛异常：这类行在时间线上照常显示，但不计入任何合计。
     * 记为 internal 是为了让 ViewModel 的月度合计复用同一份保护，避免两处口径分叉。
     */
    internal fun toRecords(rows: List<TransactionEntity>): List<TransactionRecord> =
        rows.mapNotNull { it.toRecordOrNull() }
}
