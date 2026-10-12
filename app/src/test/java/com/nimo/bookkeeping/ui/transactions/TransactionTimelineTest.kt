package com.nimo.bookkeeping.ui.transactions

import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流水时间线纯逻辑（F05）：分组、排序、标签、行内容。
 * 不依赖 Android / Room，纯 JVM 单测。
 */
class TransactionTimelineTest {

    private val today = LocalDate.parse("2026-10-12")

    private val categories = mapOf(
        1L to CategoryEntity(id = 1L, name = "餐饮", kind = CategoryKind.EXPENSE, icon = "🍜"),
        2L to CategoryEntity(id = 2L, name = "工资", kind = CategoryKind.INCOME, icon = "💼"),
    )

    private val accounts = mapOf(
        10L to AccountEntity(id = 10L, name = "现金", type = AccountType.CASH, createdAt = 0, updatedAt = 0),
        20L to AccountEntity(id = 20L, name = "招行卡", type = AccountType.DEBIT, createdAt = 0, updatedAt = 0),
    )

    private fun tx(
        id: Long,
        type: TransactionType = TransactionType.EXPENSE,
        amount: Long,
        occurredOn: String,
        accountId: Long? = 10L,
        toAccountId: Long? = null,
        categoryId: Long? = 1L,
        note: String? = null,
    ) = TransactionEntity(
        id = id,
        type = type,
        amount = amount,
        accountId = accountId,
        toAccountId = toAccountId,
        categoryId = categoryId,
        occurredOn = occurredOn,
        note = note,
        createdAt = id,
        updatedAt = id,
    )

    private fun group(transactions: List<TransactionEntity>) =
        TransactionTimeline.group(transactions, categories, accounts, today)

    // ---- 分组与排序（铁律：occurredOn DESC, id DESC） ----

    @Test
    fun group_sortsDaysDescendingAndRowsByIdDescending() {
        val groups = group(
            listOf(
                tx(1, amount = 100, occurredOn = "2026-10-05"),
                tx(2, amount = 200, occurredOn = "2026-10-06"),
                tx(3, amount = 300, occurredOn = "2026-10-06"),
                tx(4, amount = 400, occurredOn = "2026-10-05"),
            ),
        )

        assertEquals(listOf("2026-10-06", "2026-10-05"), groups.map { it.date })
        // 同日多笔按录入顺序倒序：id 大的是后录入的，排前面
        assertEquals(listOf(3L, 2L), groups[0].rows.map { it.id })
        assertEquals(listOf(4L, 1L), groups[1].rows.map { it.id })
    }

    @Test
    fun group_isStableEvenIfInputIsNotSorted() {
        // 输入故意乱序：输出仍必须满足 occurredOn DESC, id DESC（编辑/撤销后不跳动）
        val groups = group(
            listOf(
                tx(2, amount = 200, occurredOn = "2026-10-05"),
                tx(9, amount = 900, occurredOn = "2026-10-07"),
                tx(1, amount = 100, occurredOn = "2026-10-05"),
                tx(7, amount = 700, occurredOn = "2026-10-07"),
            ),
        )

        assertEquals(listOf("2026-10-07", "2026-10-05"), groups.map { it.date })
        assertEquals(listOf(9L, 7L), groups[0].rows.map { it.id })
        assertEquals(listOf(2L, 1L), groups[1].rows.map { it.id })
    }

    // ---- 当日/当月合计：必须走 StatsCalculator 口径 ----

    @Test
    fun dayTotals_useStatsCalculatorSemantics_transferAndBalanceAdjustExcluded() {
        val groups = group(
            listOf(
                tx(1, type = TransactionType.EXPENSE, amount = 1_250, occurredOn = "2026-10-12"),
                tx(2, type = TransactionType.INCOME, amount = 500_000, occurredOn = "2026-10-12"),
                tx(3, type = TransactionType.TRANSFER, amount = 99_999, occurredOn = "2026-10-12", toAccountId = 20L, categoryId = null),
                tx(4, type = TransactionType.BALANCE_ADJUST, amount = -5_000, occurredOn = "2026-10-12", categoryId = null),
            ),
        )

        val day = groups.single()
        assertEquals(1_250L, day.expenseCents)
        assertEquals(500_000L, day.incomeCents)
        // 转账与余额调整照常出现在时间线里，但不进收支
        assertEquals(4, day.rows.size)
    }

    @Test
    fun dayTotals_arePerDayNotPerList() {
        val groups = group(
            listOf(
                tx(1, amount = 100, occurredOn = "2026-10-11"),
                tx(2, amount = 250, occurredOn = "2026-10-11"),
                tx(3, amount = 700, occurredOn = "2026-10-12"),
            ),
        )

        assertEquals(350L, groups.single { it.date == "2026-10-11" }.expenseCents)
        assertEquals(700L, groups.single { it.date == "2026-10-12" }.expenseCents)
    }

    // ---- 行内容：分类图标与名称、账户、备注、金额 ----

    @Test
    fun row_carriesCategoryIconNameAccountAndNote() {
        val day = group(
            listOf(tx(1, amount = 1_250, occurredOn = "2026-10-12", note = "午餐")),
        ).single()

        val row = day.rows.single()
        assertEquals(1L, row.id)
        assertEquals("🍜", row.categoryIcon)
        assertEquals("餐饮", row.categoryName)
        assertEquals("现金", row.accountLabel)
        assertEquals("午餐", row.note)
        assertEquals(1_250L, row.amountCents)
        assertEquals("2026-10-12", row.occurredOn)
    }

    @Test
    fun transferRow_showsBothAccountsAndNoCategory() {
        val row = group(
            listOf(
                tx(1, type = TransactionType.TRANSFER, amount = 50_000, occurredOn = "2026-10-12", toAccountId = 20L, categoryId = null),
            ),
        ).single().rows.single()

        assertEquals("转账", row.categoryName)
        assertEquals("现金 → 招行卡", row.accountLabel)
    }

    @Test
    fun unknownCategoryOrAccount_fallsBackInsteadOfCrashing() {
        val row = TransactionTimeline.group(
            listOf(tx(1, amount = 100, occurredOn = "2026-10-12", accountId = 999L, categoryId = 999L)),
            categories = emptyMap(),
            accounts = emptyMap(),
            today = today,
        ).single().rows.single()

        assertEquals("未分类", row.categoryName)
        assertEquals("未知账户", row.accountLabel)
        assertEquals("•", row.categoryIcon)
    }

    @Test
    fun archivedAccount_stillResolvesItsName() {
        // 归档账户不再用于新交易，但历史流水的账户名必须照常显示（M2 验收）
        val archived = mapOf(
            10L to AccountEntity(id = 10L, name = "现金", type = AccountType.CASH, isArchived = true, createdAt = 0, updatedAt = 0),
        )
        val row = TransactionTimeline.group(
            listOf(tx(1, amount = 100, occurredOn = "2026-10-12")),
            categories = categories,
            accounts = archived,
            today = today,
        ).single().rows.single()

        assertEquals("现金", row.accountLabel)
    }

    // ---- 金额文本：一律走 Money.formatCurrency（铁律 1，Long 分） ----

    @Test
    fun amountText_signsExpenseAndIncome() {
        val day = group(
            listOf(
                tx(1, type = TransactionType.EXPENSE, amount = 1_250, occurredOn = "2026-10-12"),
                tx(2, type = TransactionType.INCOME, amount = 500_000, occurredOn = "2026-10-12", categoryId = 2L),
                tx(3, type = TransactionType.BALANCE_ADJUST, amount = -5_000, occurredOn = "2026-10-12", categoryId = null),
            ),
        ).single()

        val byId = day.rows.associateBy { it.id }
        assertEquals("-¥12.50", TransactionTimeline.amountText(byId.getValue(1L)))
        assertEquals("+¥5000.00", TransactionTimeline.amountText(byId.getValue(2L)))
        assertEquals("-¥50.00", TransactionTimeline.amountText(byId.getValue(3L)))
    }

    // ---- 标签 ----

    @Test
    fun monthLabel_isChineseYearMonth() {
        assertEquals("2026年10月", TransactionTimeline.monthLabel(YearMonth.of(2026, 10)))
        assertEquals("2026年1月", TransactionTimeline.monthLabel(YearMonth.of(2026, 1)))
    }

    @Test
    fun dayLabel_marksTodayAndYesterdayAndWeekday() {
        assertEquals("今天", TransactionTimeline.dayLabel(today, today))
        assertEquals("昨天", TransactionTimeline.dayLabel(today.minusDays(1), today))
        // 2026-10-05 是周一
        assertEquals("10月5日 周一", TransactionTimeline.dayLabel(LocalDate.parse("2026-10-05"), today))
    }

    @Test
    fun group_ofEmptyList_returnsNoGroups() {
        assertTrue(group(emptyList()).isEmpty())
    }
}
