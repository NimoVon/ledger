package com.nimo.bookkeeping.domain.model

/**
 * 交易类型。转账与余额调整不计入收支统计（计划铁律 3）。
 */
enum class TransactionType {
    EXPENSE,
    INCOME,
    TRANSFER,
    BALANCE_ADJUST,
}

enum class AccountType(val label: String) {
    CASH("现金"),
    DEBIT("储蓄卡"),
    CREDIT("信用卡"),
    WECHAT("微信"),
    ALIPAY("支付宝"),
    OTHER("其他"),
}

enum class CategoryKind {
    EXPENSE,
    INCOME,
}
