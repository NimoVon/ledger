package com.nimo.bookkeeping.domain.model

import java.time.LocalDate

/**
 * 统计与余额计算的纯数据输入，不依赖 Room / Android（计划 4.3：domain/stats 必须可 JVM 单测）。
 */
data class TransactionRecord(
    val id: Long,
    val type: TransactionType,
    val amountCents: Long,
    val accountId: Long?,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val occurredOn: LocalDate,
    /** 备注：筛选（F06）的关键词匹配要读它；统计口径不读这个字段 */
    val note: String? = null,
)

/** 用户提交的尚未落库的交易。occurredOn 为 YYYY-MM-DD 文本，由校验器负责格式校验。 */
data class TransactionInput(
    val type: TransactionType,
    val amountCents: Long,
    val accountId: Long?,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val occurredOn: String,
    val note: String? = null,
)

/** 校验用账户引用 */
data class AccountRef(val id: Long, val isArchived: Boolean)

/** 校验用分类引用 */
data class CategoryRef(val id: Long, val kind: CategoryKind, val isArchived: Boolean)

/** 余额计算用账户输入 */
data class AccountBalanceInput(
    val id: Long,
    val initialBalanceCents: Long,
    val includeInTotal: Boolean,
)

/** 分类聚合用分类信息 */
data class CategoryInfo(
    val id: Long,
    val kind: CategoryKind,
    val parentId: Long?,
    val name: String,
)
