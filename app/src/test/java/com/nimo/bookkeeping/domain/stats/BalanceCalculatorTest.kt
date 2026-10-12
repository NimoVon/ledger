package com.nimo.bookkeeping.domain.stats

import com.nimo.bookkeeping.domain.model.AccountBalanceInput
import com.nimo.bookkeeping.domain.model.TransactionRecord
import com.nimo.bookkeeping.domain.model.TransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceCalculatorTest {

    private fun tx(
        id: Long,
        type: TransactionType,
        amount: Long,
        accountId: Long?,
        toAccountId: Long? = null,
        day: Int = 1,
    ) = TransactionRecord(
        id = id,
        type = type,
        amountCents = amount,
        accountId = accountId,
        toAccountId = toAccountId,
        categoryId = null,
        occurredOn = LocalDate.of(2026, 10, day),
    )

    // 场景与计划 9.1 完全一致：银行初始 ¥1,000.00
    @Test
    fun fullScenario_matchesPlanSection91() {
        val accounts = listOf(
            AccountBalanceInput(id = 1, initialBalanceCents = 100000, includeInTotal = true),  // 银行
            AccountBalanceInput(id = 2, initialBalanceCents = 0, includeInTotal = true),       // 现金
        )
        val records = listOf(
            tx(1, TransactionType.EXPENSE, 1250, accountId = 1, day = 1),                          // 支出 ¥12.50 → 98,750
            tx(2, TransactionType.INCOME, 20000, accountId = 1, day = 2),                          // 收入 ¥200  → 118,750
            tx(3, TransactionType.TRANSFER, 10000, accountId = 1, toAccountId = 2, day = 3),       // 转出 ¥100  → 108,750 / 现金 +10,000
            tx(4, TransactionType.BALANCE_ADJUST, -500, accountId = 1, day = 4),                   // 校准 -¥5  → 108,250
        )
        val balances = BalanceCalculator.computeBalances(accounts, records)
        assertEquals(108250L, balances[1])
        assertEquals(10000L, balances[2])
        assertEquals(118250L, BalanceCalculator.netWorthCents(balances, accounts))
    }

    @Test
    fun creditCard_negativeBalanceIsDebt() {
        val accounts = listOf(
            AccountBalanceInput(id = 1, initialBalanceCents = 0, includeInTotal = true),      // 储蓄卡
            AccountBalanceInput(id = 2, initialBalanceCents = 0, includeInTotal = true),      // 信用卡
        )
        val records = listOf(
            // 信用卡消费：用信用卡支出（余额变负）+ 还款：储蓄→信用卡转账
            tx(1, TransactionType.EXPENSE, 50000, accountId = 2),                              // 信用卡 -¥500
            tx(2, TransactionType.TRANSFER, 30000, accountId = 1, toAccountId = 2),            // 还款 ¥300
        )
        val balances = BalanceCalculator.computeBalances(accounts, records)
        assertEquals(-20000L, balances[2])  // 信用卡欠 ¥200
        assertEquals(-30000L, balances[1])  // 储蓄卡少了 ¥300
        assertEquals(-50000L, BalanceCalculator.netWorthCents(balances, accounts))
    }

    @Test
    fun netWorth_excludesFlaggedAccounts() {
        val accounts = listOf(
            AccountBalanceInput(id = 1, initialBalanceCents = 100000, includeInTotal = true),
            AccountBalanceInput(id = 2, initialBalanceCents = 999999, includeInTotal = false), // 不计入
        )
        val balances = BalanceCalculator.computeBalances(accounts, emptyList())
        assertEquals(100000L, BalanceCalculator.netWorthCents(balances, accounts))
    }

    @Test
    fun unknownAccountReference_isIgnoredSafely() {
        val accounts = listOf(AccountBalanceInput(id = 1, initialBalanceCents = 5000, includeInTotal = true))
        val records = listOf(tx(1, TransactionType.INCOME, 1000, accountId = 99)) // 幽灵账户
        val balances = BalanceCalculator.computeBalances(accounts, records)
        assertEquals(5000L, balances[1])
        assertEquals(5000L, BalanceCalculator.netWorthCents(balances, accounts))
    }

    @Test
    fun emptyState_initialBalancesOnly() {
        val accounts = listOf(
            AccountBalanceInput(id = 1, initialBalanceCents = -2500, includeInTotal = true),
            AccountBalanceInput(id = 2, initialBalanceCents = 10000, includeInTotal = true),
        )
        val balances = BalanceCalculator.computeBalances(accounts, emptyList())
        assertEquals(-2500L, balances[1])
        assertEquals(7500L, BalanceCalculator.netWorthCents(balances, accounts))
    }
}
