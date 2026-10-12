package com.nimo.bookkeeping.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionType

/**
 * 账户。余额不落库，由 initialBalance + 交易变动推导（计划铁律 2）。
 * 账户只归档不删除，历史数据永远保留。
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType = AccountType.OTHER,
    /** 建账时的实际余额，分 */
    val initialBalance: Long = 0,
    /** 信用卡是否计入净资产（信用卡余额为负表示欠款） */
    val includeInTotal: Boolean = true,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 分类。支出/收入两套独立体系，单层父子结构。
 * 停用（归档）后不可用于新交易，历史统计保留。
 */
@Entity(
    tableName = "categories",
    indices = [Index("parentId"), Index("kind")],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    val parentId: Long? = null,
    val icon: String? = null,
    val color: String? = null,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * 交易：唯一核心实体。收支与转账共用一张表，用 type 区分。
 *
 * 金额规则（铁律 1，数据库层由 SchemaHardening 触发器兜底）：
 * - EXPENSE / INCOME / TRANSFER：amount > 0
 * - BALANCE_ADJUST：amount != 0，可正可负
 *
 * 字段约束（由触发器兜底）：
 * - EXPENSE / INCOME / TRANSFER：accountId 必填
 * - TRANSFER：toAccountId 必填且 != accountId
 * - EXPENSE / INCOME：categoryId 必填
 * - TRANSFER / BALANCE_ADJUST：categoryId 必须为 NULL
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index("occurredOn"),
        Index(value = ["type", "occurredOn"]),
        Index("accountId"),
        Index("categoryId"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TransactionType,
    /** 分。恒为整数，绝不用浮点（铁律 1） */
    val amount: Long,
    /** 支出的转出账户 / 收入的转入账户 / 转账的转出账户 */
    val accountId: Long?,
    /** 仅 TRANSFER：转入账户 */
    val toAccountId: Long? = null,
    /** 转账与余额调整为 NULL */
    val categoryId: Long? = null,
    /** ISO 8601 YYYY-MM-DD，本地自然日语义 */
    val occurredOn: String,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 预算。categoryId 为 NULL 表示当月总预算。
 * 同月唯一性：UNIQUE(month, categoryId) 对 NULL 失效，
 * 由 SchemaHardening 的部分唯一索引 idx_budget_month_total 兜底（计划：SQLite NULL 陷阱）。
 */
@Entity(
    tableName = "budgets",
    indices = [Index("month"), Index("categoryId")],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** YYYY-MM */
    val month: String,
    val categoryId: Long? = null,
    /** 分，必须 > 0（触发器兜底） */
    val amount: Long,
)
