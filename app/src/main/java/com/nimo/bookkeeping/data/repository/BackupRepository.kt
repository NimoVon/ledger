package com.nimo.bookkeeping.data.repository

import androidx.room.withTransaction
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.BudgetEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.domain.backup.AccountBackup
import com.nimo.bookkeeping.domain.backup.BackupCodec
import com.nimo.bookkeeping.domain.backup.BackupData
import com.nimo.bookkeeping.domain.backup.BackupDecodeResult
import com.nimo.bookkeeping.domain.backup.BackupFile
import com.nimo.bookkeeping.domain.backup.BudgetBackup
import com.nimo.bookkeeping.domain.backup.CategoryBackup
import com.nimo.bookkeeping.domain.backup.CsvExporter
import com.nimo.bookkeeping.domain.backup.TransactionBackup
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 恢复前的自动备份：把**覆盖前**的全量数据写成 JSON，交给调用方落到用户选定的位置。
 * 抛异常 = 自动备份失败，恢复必须整体中止（计划 4.6：自动备份失败不得继续覆盖）。
 */
typealias AutoBackupWriter = suspend (String) -> Unit

/**
 * 备份导出与恢复（计划 4.6）。UI 不碰 DAO，导出/恢复同样经 Repository（铁律 3）。
 *
 * 单位铁律：JSON 与数据库都是**整数分**；CSV 是**元**固定两位小数。两种单位不许混。
 *
 * 恢复是**替换模式**：校验（[BackupCodec.validate]）→ 自动备份当前数据 → 单事务清空并整体写入。
 * 任何一步失败都不写库：失败后原数据仍可用（计划 4.6 恢复策略）。
 */
class BackupRepository(private val db: AppDatabase) {

    private val accountDao get() = db.accountDao()
    private val categoryDao get() = db.categoryDao()
    private val txDao get() = db.transactionDao()
    private val budgetDao get() = db.budgetDao()

    // ---- 导出 ----

    /** 全量导出（含已归档账户与分类：少了它们，恢复后历史流水会缺引用）。 */
    suspend fun exportBackupFile(): BackupFile = withContext(Dispatchers.IO) { exportFile() }

    /** 导出为 JSON 文本（计划 4.6 手动备份文件）。 */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) { BackupCodec.encode(exportFile()) }

    /** 导出四个 CSV 文件（文件名 → 内容）。金额是元，表头是稳定契约（F14 导入按此解析）。 */
    suspend fun exportCsvFiles(): Map<String, String> = withContext(Dispatchers.IO) {
        val data = readAll().toBackupData()
        CsvExporter.export(
            accounts = data.accounts,
            categories = data.categories,
            transactions = data.transactions,
            budgets = data.budgets,
        )
    }

    // ---- 恢复 ----

    /** 解析 → 校验 → 恢复。文本不是本应用备份时直接失败，不碰数据库。 */
    suspend fun restoreFromJson(text: String, autoBackup: AutoBackupWriter): Result<Unit> =
        when (val decoded = BackupCodec.decode(text)) {
            is BackupDecodeResult.Failure -> Result.failure(IllegalArgumentException(decoded.reason))
            is BackupDecodeResult.Success -> restore(decoded.file, autoBackup)
        }

    /**
     * 替换模式恢复。
     *
     * 顺序不能倒：
     * 1. 先校验：坏备份连自动备份都不该触发，更不该清数据；
     * 2. 再自动备份：写失败就中止（[autoBackup] 抛异常 = Result.failure，数据库一个字节都没动）；
     * 3. 最后 [replaceAll] 在同一个事务里清空 + 写入，中途失败自动回滚。
     */
    suspend fun restore(file: BackupFile, autoBackup: AutoBackupWriter): Result<Unit> =
        withContext(Dispatchers.IO) {
            val errors = BackupCodec.validate(file)
            if (errors.isNotEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("备份文件有问题：" + errors.joinToString("；")),
                )
            }

            val snapshot = try {
                BackupCodec.encode(exportFile())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@withContext Result.failure(IOException("无法读取当前数据，已中止恢复", e))
            }

            try {
                autoBackup(snapshot)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@withContext Result.failure(
                    IOException("自动备份失败，已中止恢复，数据未改动", e),
                )
            }

            try {
                replaceAll(file.data)
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(
                    IllegalStateException(
                        "恢复失败，原有数据未改动：" + (e.message ?: e.javaClass.simpleName),
                        e,
                    ),
                )
            }
        }

    // ---- 内部实现 ----

    private suspend fun exportFile(): BackupFile =
        BackupFile(exportedAt = System.currentTimeMillis(), data = readAll().toBackupData())

    private class Snapshot(
        val accounts: List<AccountEntity>,
        val categories: List<CategoryEntity>,
        val transactions: List<TransactionEntity>,
        val budgets: List<BudgetEntity>,
    )

    private suspend fun readAll(): Snapshot = Snapshot(
        accounts = accountDao.listAll(),
        categories = categoryDao.listAll(),
        transactions = txDao.listAll(),
        budgets = budgetDao.listAll(),
    )

    /** 单事务替换：清空 → 写入。Room 的 withTransaction 出错会整批回滚，不会留下半份数据。 */
    private suspend fun replaceAll(data: BackupData) {
        db.withTransaction {
            txDao.deleteAll()
            budgetDao.deleteAll()
            categoryDao.deleteAll()
            accountDao.deleteAll()

            accountDao.insertAll(data.accounts.map { it.toEntity() })
            categoryDao.insertAll(parentsFirst(data.categories).map { it.toEntity() })
            txDao.insertAll(data.transactions.map { it.toEntity() })
            budgetDao.insertAll(data.budgets.map { it.toEntity() })
        }
    }

    /**
     * 分类按「父在前」排序：parentId 引用同一批数据，父行先落库。
     * 现在数据库没有外键，这是为将来加外键留的后路，也顺手保证顺序稳定。
     */
    private fun parentsFirst(categories: List<CategoryBackup>): List<CategoryBackup> {
        val byId = categories.associateBy { it.id }
        val depth = HashMap<Long, Int>()
        fun depthOf(start: CategoryBackup): Int {
            depth[start.id]?.let { return it }
            var steps = 0
            var cursor: CategoryBackup? = start
            val seen = HashSet<Long>()
            while (cursor != null && seen.add(cursor.id)) {
                val parent = cursor.parentId?.let { byId[it] } ?: break
                steps++
                cursor = parent
            }
            depth[start.id] = steps
            return steps
        }
        return categories.sortedBy { depthOf(it) }
    }

    private fun Snapshot.toBackupData(): BackupData = BackupData(
        accounts = accounts.map { it.toBackup() },
        categories = categories.map { it.toBackup() },
        transactions = transactions.map { it.toBackup() },
        budgets = budgets.map { it.toBackup() },
    )

    // ---- 实体 ↔ 备份模型映射（枚举存 name 字符串，金额原样整数分） ----

    private fun AccountEntity.toBackup() = AccountBackup(
        id = id,
        name = name,
        type = type.name,
        initialBalance = initialBalance,
        includeInTotal = includeInTotal,
        isArchived = isArchived,
        sortOrder = sortOrder,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun AccountBackup.toEntity() = AccountEntity(
        id = id,
        name = name,
        type = AccountType.entries.firstOrNull { it.name == type } ?: error("未知账户类型：" + type),
        initialBalance = initialBalance,
        includeInTotal = includeInTotal,
        isArchived = isArchived,
        sortOrder = sortOrder,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun CategoryEntity.toBackup() = CategoryBackup(
        id = id,
        name = name,
        kind = kind.name,
        parentId = parentId,
        icon = icon,
        color = color,
        isArchived = isArchived,
        sortOrder = sortOrder,
    )

    private fun CategoryBackup.toEntity() = CategoryEntity(
        id = id,
        name = name,
        kind = CategoryKind.entries.firstOrNull { it.name == kind } ?: error("未知分类类型：" + kind),
        parentId = parentId,
        icon = icon,
        color = color,
        isArchived = isArchived,
        sortOrder = sortOrder,
    )

    private fun TransactionEntity.toBackup() = TransactionBackup(
        id = id,
        type = type.name,
        amount = amount,
        accountId = accountId,
        toAccountId = toAccountId,
        categoryId = categoryId,
        occurredOn = occurredOn,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun TransactionBackup.toEntity() = TransactionEntity(
        id = id,
        type = TransactionType.entries.firstOrNull { it.name == type }
            ?: error("未知交易类型：" + type),
        amount = amount,
        accountId = accountId,
        toAccountId = toAccountId,
        categoryId = categoryId,
        occurredOn = occurredOn,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun BudgetEntity.toBackup() = BudgetBackup(
        id = id,
        month = month,
        categoryId = categoryId,
        amount = amount,
    )

    private fun BudgetBackup.toEntity() = BudgetEntity(
        id = id,
        month = month,
        categoryId = categoryId,
        amount = amount,
    )
}
