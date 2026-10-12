package com.nimo.bookkeeping.domain.stats

import com.nimo.bookkeeping.domain.model.AccountBalanceInput
import com.nimo.bookkeeping.domain.model.TransactionRecord
import com.nimo.bookkeeping.domain.model.TransactionType

/**
 * 账户余额唯一算法（计划铁律 2）：
 * 当前余额 = initial_balance + Σ(交易变动)，纯函数推导，不在 accounts 表存可变余额。
 *
 * 变动约定：
 * - EXPENSE：account -amount
 * - INCOME：account +amount
 * - TRANSFER：转出账户 -amount，转入账户 +amount
 * - BALANCE_ADJUST：account +amount（amount 自带符号，可正可负）
 */
object BalanceCalculator {

    fun computeBalances(
        accounts: List<AccountBalanceInput>,
        records: List<TransactionRecord>,
    ): Map<Long, Long> {
        val balances = HashMap<Long, Long>(accounts.size * 2)
        accounts.forEach { balances[it.id] = it.initialBalanceCents }

        fun apply(accountId: Long?, delta: Long) {
            if (accountId == null) return
            // 引用不存在的账户（理论不可能：账户只归档不删除）时安全忽略
            if (!balances.containsKey(accountId)) return
            balances[accountId] = balances.getValue(accountId) + delta
        }

        for (r in records) {
            when (r.type) {
                TransactionType.EXPENSE -> apply(r.accountId, -r.amountCents)
                TransactionType.INCOME -> apply(r.accountId, r.amountCents)
                TransactionType.TRANSFER -> {
                    apply(r.accountId, -r.amountCents)
                    apply(r.toAccountId, r.amountCents)
                }
                TransactionType.BALANCE_ADJUST -> apply(r.accountId, r.amountCents)
            }
        }
        return balances
    }

    /** 净资产 = Σ(纳入统计账户的余额)；信用卡欠款为负（计划 3.4） */
    fun netWorthCents(
        balances: Map<Long, Long>,
        accounts: List<AccountBalanceInput>,
    ): Long = accounts.asSequence()
        .filter { it.includeInTotal }
        .sumOf { balances[it.id] ?: 0L }
}
