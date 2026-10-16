package com.nimo.ledger.domain.validate

import com.nimo.ledger.util.Money
import java.time.YearMonth

/**
 * 预算校验错误。页面负责把每一个错误翻成中文（见 ui/budgets/BudgetEditDialog.kt）。
 */
sealed interface BudgetValidationError {
    /** month 不是 YYYY-MM 形式的合法月份 */
    data object MonthFormat : BudgetValidationError

    /** 预算金额必须大于 0（数据库触发器与 CHECK 兜底，这里是第一道提示） */
    data object AmountNotPositive : BudgetValidationError

    /** 超过产品金额上限（与交易共用 Money.MAX_AMOUNT_CENTS） */
    data object AmountTooLarge : BudgetValidationError
}

class BudgetValidationException(
    val errors: List<BudgetValidationError>,
) : RuntimeException(errors.joinToString("; "))

/**
 * 预算校验（F09）。和 CategoryValidator 一样是纯函数：数据由调用方喂进来，这里不碰数据库、
 * 不依赖 Android/Compose/Room，因此可以直接用 JVM 单测覆盖。
 */
object BudgetValidator {

    /** YYYY-MM：四位年 + 两位月，月必须是 01..12 */
    private val MONTH_RE = Regex("""\d{4}-\d{2}""")

    fun validate(month: String, amountCents: Long): List<BudgetValidationError> = buildList {
        if (parseMonth(month) == null) add(BudgetValidationError.MonthFormat)
        when {
            amountCents <= 0L -> add(BudgetValidationError.AmountNotPositive)
            amountCents > Money.MAX_AMOUNT_CENTS -> add(BudgetValidationError.AmountTooLarge)
        }
    }

    /**
     * YYYY-MM → YearMonth。
     * 格式不合法或月份非法（如 2026-13、2026-00、2026-1）一律返回 null。
     */
    fun parseMonth(month: String): YearMonth? {
        if (!MONTH_RE.matches(month)) return null
        return runCatching { YearMonth.parse(month) }.getOrNull()
    }
}
