package com.nimo.bookkeeping.ui.accounts

import com.nimo.bookkeeping.util.Money
import kotlin.math.abs

/**
 * 余额校准（F11）表单的**纯数据**：不依赖 Android / Compose / Room，可直接 JVM 单测。
 *
 * 校准只做一件事：把账户余额调到用户填的实际值，方式是**追加**一笔 BALANCE_ADJUST，
 * 差额 = 实际 − 账面（可为负，铁律 2）。历史一行都不动（计划 F11）。
 */
sealed interface BalanceAdjustError {
    data object TargetBlank : BalanceAdjustError
    data object TargetInvalid : BalanceAdjustError
    data object TargetTooLarge : BalanceAdjustError

    /**
     * 目标本身合法，但差额绝对值超过单笔上限。余额是多笔累加出来的，可能已经比单笔上限还大，
     * 所以这个分支是真会走到的（例：账面 -¥99,999,999.99 校准到 +¥99,999,999.99）。
     */
    data object DeltaOutOfRange : BalanceAdjustError

    /** 实际与账面一致：没有变动可写。绝不插一行 amount = 0（铁律 1，数据库触发器会 ABORT） */
    data object NoChange : BalanceAdjustError
}

fun BalanceAdjustError.toMessage(): String = when (this) {
    BalanceAdjustError.TargetBlank -> "请输入实际余额"
    BalanceAdjustError.TargetInvalid -> "实际余额格式不正确（例：1000 或 1000.50；欠款可填 -1000）"
    BalanceAdjustError.TargetTooLarge ->
        "实际余额超出上限（最多 ${Money.formatCurrency(Money.MAX_AMOUNT_CENTS)}）"

    BalanceAdjustError.DeltaOutOfRange ->
        "需要调整的差额超出单笔上限（${Money.formatCurrency(Money.MAX_AMOUNT_CENTS)}），请先核对账户"

    BalanceAdjustError.NoChange -> "账面余额与实际一致，无需校准"
}

sealed interface BalanceAdjustOutcome {
    data class Valid(val targetCents: Long, val deltaCents: Long) : BalanceAdjustOutcome
    data class Invalid(val error: BalanceAdjustError) : BalanceAdjustOutcome
}

/**
 * 实际余额输入 → 校准结论。返回 [BalanceAdjustOutcome.Valid] 只代表"可以提交"：
 * 差额最终由 [com.nimo.bookkeeping.data.repository.LedgerRepository.adjustBalanceTo]
 * 在事务内用库里的全部流水重新推导（界面上那个数字可能已经过期）。
 */
fun balanceAdjustOutcome(targetText: String, currentBalanceCents: Long): BalanceAdjustOutcome {
    val text = targetText.trim()
    if (text.isEmpty()) return BalanceAdjustOutcome.Invalid(BalanceAdjustError.TargetBlank)
    if (Money.exceedsMaxAmount(text)) {
        return BalanceAdjustOutcome.Invalid(BalanceAdjustError.TargetTooLarge)
    }
    // 目标是**余额**不是金额：0（账户清零）与负数（信用卡欠款）都合法，所以走 allowingZero
    val target = Money.parseCentsAllowingZero(text)
        ?: return BalanceAdjustOutcome.Invalid(BalanceAdjustError.TargetInvalid)

    // 余额本身没有上限（多笔累加），target - current 理论上可能溢出 Long；溢出按"差额超限"处理，
    // 绝不带着一个回绕过的数字去写库。
    val delta = try {
        Math.subtractExact(target, currentBalanceCents)
    } catch (e: ArithmeticException) {
        return BalanceAdjustOutcome.Invalid(BalanceAdjustError.DeltaOutOfRange)
    }
    if (delta == 0L) return BalanceAdjustOutcome.Invalid(BalanceAdjustError.NoChange)
    if (abs(delta) > Money.MAX_AMOUNT_CENTS) {
        return BalanceAdjustOutcome.Invalid(BalanceAdjustError.DeltaOutOfRange)
    }
    return BalanceAdjustOutcome.Valid(targetCents = target, deltaCents = delta)
}

/** 提示行：把"按下按钮会多出什么"提前说清楚。非法输入时给的是错误文案。 */
fun balanceAdjustHintText(outcome: BalanceAdjustOutcome): String = when (outcome) {
    is BalanceAdjustOutcome.Valid -> "将追加一笔余额调整 " + signedAmount(outcome.deltaCents)
    is BalanceAdjustOutcome.Invalid -> outcome.error.toMessage()
}

/** 带符号的展示金额：+¥66.00 / -¥66.00（符号走 ASCII，金额格式仍只认 [Money]，铁律 1） */
fun signedAmount(cents: Long): String =
    (if (cents < 0) "-" else "+") + Money.formatCurrency(abs(cents))
