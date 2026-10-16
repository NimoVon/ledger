package com.nimo.ledger.ui.accounts

import com.nimo.ledger.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 余额校准表单（F11）的纯逻辑：解析、差额推导、错误分支、提示文案。
 * 不依赖 Android / Compose，跑在 JVM 上（金额边界本身由 MoneyTest 覆盖）。
 */
class BalanceAdjustFormTest {

    private fun valid(text: String, current: Long): BalanceAdjustOutcome.Valid =
        balanceAdjustOutcome(text, current) as BalanceAdjustOutcome.Valid

    private fun error(text: String, current: Long): BalanceAdjustError =
        (balanceAdjustOutcome(text, current) as BalanceAdjustOutcome.Invalid).error

    @Test
    fun blankInput_asksForTheActualBalance() {
        assertEquals(BalanceAdjustError.TargetBlank, error("", 100_000L))
        assertEquals(BalanceAdjustError.TargetBlank, error("   ", 100_000L))
    }

    @Test
    fun malformedInput_isRejected() {
        assertEquals(BalanceAdjustError.TargetInvalid, error("abc", 100_000L))
        assertEquals(BalanceAdjustError.TargetInvalid, error("1.2.3", 100_000L))
        // 分以下精度直接拒绝（与记账页同一套 Money 口径）
        assertEquals(BalanceAdjustError.TargetInvalid, error("12.345", 100_000L))
    }

    @Test
    fun actualBalanceBeyondTheCeiling_isRejected() {
        // ¥100,000,000.00 超过单账户上限 ¥99,999,999.99
        assertEquals(BalanceAdjustError.TargetTooLarge, error("100000000.00", 0L))
    }

    @Test
    fun positiveDelta_isTheDifferenceToWrite() {
        // 账面 ¥1,000.00 → 实际 ¥1,660.00，追加 +¥660.00
        val outcome = valid("1660.00", 100_000L)
        assertEquals(166_000L, outcome.targetCents)
        assertEquals(66_000L, outcome.deltaCents)
    }

    @Test
    fun negativeDelta_isAllowed() {
        // 花多了：账面 ¥11,000.00 → 实际 ¥10,000.00
        assertEquals(-100_000L, valid("10000", 1_100_000L).deltaCents)
    }

    @Test
    fun zeroAndNegativeTargets_areAllowed() {
        // 账户清零：目标 0 是合法的"余额"，差额是负数（铁律 1 只管金额不能为 0，不管余额）
        assertEquals(-100_000L, valid("0", 100_000L).deltaCents)
        // 信用卡欠款：目标是负余额
        assertEquals(-50_000L, valid("-500", 0L).deltaCents)
    }

    @Test
    fun deltaBeyondOneRow_isRejected() {
        // 余额是多笔累加出来的，可以比单笔上限还大：−¥99,999,999.99 校准到 +¥99,999,999.99
        assertEquals(
            BalanceAdjustError.DeltaOutOfRange,
            error("99999999.99", -Money.MAX_AMOUNT_CENTS),
        )
    }

    @Test
    fun nothingToDo_whenActualEqualsBook() {
        assertEquals(BalanceAdjustError.NoChange, error("1000.00", 100_000L))
        // 前后空格不该被当成"有变化"
        assertEquals(BalanceAdjustError.NoChange, error(" 1000 ", 100_000L))
        // 双零：账面 0 也没得校准（不会去写一行 amount = 0 的交易）
        assertEquals(BalanceAdjustError.NoChange, error("0", 0L))
    }

    @Test
    fun hintText_saysWhatWillBeAppended() {
        assertEquals("将追加一笔余额调整 +¥660.00", balanceAdjustHintText(valid("1660.00", 100_000L)))
        assertEquals("将追加一笔余额调整 -¥1,000.00", balanceAdjustHintText(valid("10000", 1_100_000L)))
        assertEquals(
            BalanceAdjustError.NoChange.toMessage(),
            balanceAdjustHintText(balanceAdjustOutcome("1000", 100_000L)),
        )
    }

    @Test
    fun signedAmount_keepsTheSignOutsideTheCurrency() {
        assertEquals("+¥0.01", signedAmount(1L))
        assertEquals("-¥0.01", signedAmount(-1L))
        assertEquals("+¥99,999,999.99", signedAmount(Money.MAX_AMOUNT_CENTS))
    }

    @Test
    fun everyError_hasAReadableMessage() {
        // sealed interface 没有 entries，逐项写清楚：新增错误分支时必须同步这个列表
        listOf(
            BalanceAdjustError.TargetBlank,
            BalanceAdjustError.TargetInvalid,
            BalanceAdjustError.TargetTooLarge,
            BalanceAdjustError.DeltaOutOfRange,
            BalanceAdjustError.NoChange,
        ).forEach { assertTrue(it.toMessage().isNotBlank()) }
    }
}
