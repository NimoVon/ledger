package com.nimo.ledger.domain.backup

import kotlinx.serialization.Serializable

/** 备份文件标识：解码时校验，防止把别的 app 的 JSON 当成本应用备份（计划 4.6 文件校验）。 */
const val BACKUP_APP_ID = "ledger"

/**
 * 备份格式版本。**只增不改**：新增可选字段不动版本号；字段语义变化才 +1（计划 4.6 版本迁移）。
 * 解码时版本比本常量新 → 明确拒绝，不做"尽力解析"。
 */
const val BACKUP_SCHEMA_VERSION = 1

/** 单表行数上限，防超大数组拖垮内存（计划 4.6「数据量上限」）。10 万笔够个人记账十年。 */
const val MAX_BACKUP_ROWS = 100_000

/**
 * 备份文件顶层结构（计划 4.6）：
 *
 *     { "app": "ledger", "schemaVersion": 1, "exportedAt": 1760000000000,
 *       "data": { "accounts": [], "categories": [], "transactions": [], "budgets": [] } }
 *
 * 枚举字段一律用 **String** 而不是枚举类型：kotlinx 的枚举解码失败只会抛
 * SerializationException，用户看不懂；存成字符串后由 [BackupCodec.validate] 给出中文原因。
 * 所有金额字段一律是**整数分**（铁律 1）——与 CSV 的"元"是两种单位，不许混（计划 4.6）。
 */
@Serializable
data class BackupFile(
    val app: String = BACKUP_APP_ID,
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAt: Long = 0L,
    val data: BackupData = BackupData(),
)

@Serializable
data class BackupData(
    val accounts: List<AccountBackup> = emptyList(),
    val categories: List<CategoryBackup> = emptyList(),
    val transactions: List<TransactionBackup> = emptyList(),
    val budgets: List<BudgetBackup> = emptyList(),
)

@Serializable
data class AccountBackup(
    val id: Long,
    val name: String,
    val type: String,
    val initialBalance: Long = 0L,
    val includeInTotal: Boolean = true,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class CategoryBackup(
    val id: Long,
    val name: String,
    val kind: String,
    val parentId: Long? = null,
    val icon: String? = null,
    val color: String? = null,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
)

@Serializable
data class TransactionBackup(
    val id: Long,
    val type: String,
    /** 分。EXPENSE/INCOME/TRANSFER 必须 > 0，BALANCE_ADJUST 可负但不能为 0（铁律 1） */
    val amount: Long,
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    /** ISO 8601 YYYY-MM-DD */
    val occurredOn: String,
    val note: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class BudgetBackup(
    val id: Long,
    /** YYYY-MM */
    val month: String,
    /** NULL = 当月总预算 */
    val categoryId: Long? = null,
    /** 分，必须 > 0 */
    val amount: Long,
)
