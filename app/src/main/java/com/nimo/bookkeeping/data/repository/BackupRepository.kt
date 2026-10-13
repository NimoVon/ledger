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
import com.nimo.bookkeeping.domain.backup.CsvImportCounts
import com.nimo.bookkeeping.domain.backup.CsvImportOutcome
import com.nimo.bookkeeping.domain.backup.CsvImportParse
import com.nimo.bookkeeping.domain.backup.CsvImportParser
import com.nimo.bookkeeping.domain.backup.CsvRowProblem
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

    // ---- 增量导入（F14） ----

    /**
     * CSV 增量导入（计划 line 84）。
     *
     * 语义：**同 id 覆盖成文件里的值、新 id 追加，不删除本机任何一行**（要「全新数据」请先用替换式恢复）。
     * 步骤：解析 → 与本机数据合并后校验引用与冲突 → 单事务写入。
     * 有问题的行只跳过它自己并在结果里报「文件 + 行号 + 原因」；任何一行都不许静默改写数据。
     */
    suspend fun importCsv(files: Map<String, String>): Result<CsvImportOutcome> =
        withContext(Dispatchers.IO) {
            try {
                val parsed = CsvImportParser.parse(files)
                val plan = planImport(parsed, readAll())
                if (plan.hasAnythingToWrite) {
                    db.withTransaction {
                        if (plan.accountsToInsert.isNotEmpty()) accountDao.insertAll(plan.accountsToInsert)
                        if (plan.accountsToUpdate.isNotEmpty()) accountDao.updateAll(plan.accountsToUpdate)
                        if (plan.categoriesToInsert.isNotEmpty()) {
                            val ordered = parentsFirst(
                                plan.categoriesToInsert,
                                CategoryEntity::id,
                                CategoryEntity::parentId,
                            )
                            categoryDao.insertAll(ordered)
                        }
                        if (plan.categoriesToUpdate.isNotEmpty()) categoryDao.updateAll(plan.categoriesToUpdate)
                        if (plan.transactionsToInsert.isNotEmpty()) txDao.insertAll(plan.transactionsToInsert)
                        if (plan.transactionsToUpdate.isNotEmpty()) txDao.updateAll(plan.transactionsToUpdate)
                        if (plan.budgetsToInsert.isNotEmpty()) budgetDao.insertAll(plan.budgetsToInsert)
                        if (plan.budgetsToUpdate.isNotEmpty()) budgetDao.updateAll(plan.budgetsToUpdate)
                    }
                }
                Result.success(
                    CsvImportOutcome(
                        inserted = plan.insertedCounts,
                        updated = plan.updatedCounts,
                        problems = CsvImportParser.foldProblems(parsed.problems + plan.problems),
                        missingFiles = parsed.missingFiles,
                    ),
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(
                    IllegalStateException("导入失败，本机数据未改动：" + (e.message ?: e.javaClass.simpleName), e),
                )
            }
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
                return@withContext Result.failure(IOException("无法读取当前数据，已中止导入", e))
            }

            try {
                autoBackup(snapshot)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@withContext Result.failure(
                    IOException("自动备份失败，已中止导入，数据未改动", e),
                )
            }

            try {
                replaceAll(file.data)
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(
                    IllegalStateException(
                        "导入失败，原有数据未改动：" + (e.message ?: e.javaClass.simpleName),
                        e,
                    ),
                )
            }
        }

    // ---- 内部实现 ----

    private suspend fun exportFile(): BackupFile =
        BackupFile(exportedAt = System.currentTimeMillis(), data = readAll().toBackupData())

    /**
     * 一次增量导入算出来的写入计划：哪些行是新增、哪些是覆盖、哪些必须跳过。
     */
    private class ImportPlan(
        val accountsToInsert: List<AccountEntity>,
        val accountsToUpdate: List<AccountEntity>,
        val categoriesToInsert: List<CategoryEntity>,
        val categoriesToUpdate: List<CategoryEntity>,
        val transactionsToInsert: List<TransactionEntity>,
        val transactionsToUpdate: List<TransactionEntity>,
        val budgetsToInsert: List<BudgetEntity>,
        val budgetsToUpdate: List<BudgetEntity>,
        val problems: List<CsvRowProblem>,
    ) {
        val hasAnythingToWrite: Boolean
            get() = accountsToInsert.isNotEmpty() || accountsToUpdate.isNotEmpty() ||
                categoriesToInsert.isNotEmpty() || categoriesToUpdate.isNotEmpty() ||
                transactionsToInsert.isNotEmpty() || transactionsToUpdate.isNotEmpty() ||
                budgetsToInsert.isNotEmpty() || budgetsToUpdate.isNotEmpty()

        val insertedCounts: CsvImportCounts
            get() = CsvImportCounts(
                accounts = accountsToInsert.size,
                categories = categoriesToInsert.size,
                transactions = transactionsToInsert.size,
                budgets = budgetsToInsert.size,
            )

        val updatedCounts: CsvImportCounts
            get() = CsvImportCounts(
                accounts = accountsToUpdate.size,
                categories = categoriesToUpdate.size,
                transactions = transactionsToUpdate.size,
                budgets = budgetsToUpdate.size,
            )
    }

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
            categoryDao.insertAll(
                parentsFirst(data.categories, CategoryBackup::id, CategoryBackup::parentId).map { it.toEntity() },
            )
            txDao.insertAll(data.transactions.map { it.toEntity() })
            budgetDao.insertAll(data.budgets.map { it.toEntity() })
        }
    }

    /**
     * 把「文件里的行」和「本机的行」合起来算一遍：哪些新增、哪些覆盖、哪些必须跳过。
     *
     * 引用校验看的是**合并后的集合**（本机 + 文件）：文件里新加的账户/分类，可以被同一批流水立刻引用。
     * 与数据库触发器同口径的规则（金额、账户、分类方向）已在解析层挡过一轮，这里只补「引用是否存在」。
     */
    private fun planImport(parsed: CsvImportParse, snapshot: Snapshot): ImportPlan {
        val problems = mutableListOf<CsvRowProblem>()

        val existingAccountIds = snapshot.accounts.mapTo(HashSet()) { it.id }
        val existingCategoryIds = snapshot.categories.mapTo(HashSet()) { it.id }
        val existingTransactionIds = snapshot.transactions.mapTo(HashSet()) { it.id }
        val existingBudgetIds = snapshot.budgets.mapTo(HashSet()) { it.id }

        fun lineOf(file: String, id: Long): Int = parsed.rowLines[file to id] ?: 0

        // 账户：本机没有这个 id 就新增，已有就覆盖成文件里的值
        val accountsToInsert = mutableListOf<AccountEntity>()
        val accountsToUpdate = mutableListOf<AccountEntity>()
        for (account in parsed.accounts) {
            val entity = account.toEntity()
            if (account.id in existingAccountIds) accountsToUpdate += entity else accountsToInsert += entity
        }

        // 分类：父必须存在（本机或文件里），且不能把自己接到自己的子孙下面
        val mergedParentOf = HashMap<Long, Long?>()
        snapshot.categories.forEach { mergedParentOf[it.id] = it.parentId }
        parsed.categories.forEach { mergedParentOf[it.id] = it.parentId }
        val categoriesToInsert = mutableListOf<CategoryEntity>()
        val categoriesToUpdate = mutableListOf<CategoryEntity>()
        for (category in parsed.categories) {
            val parentId = category.parentId
            if (parentId != null) {
                val reason = when {
                    parentId !in mergedParentOf.keys ->
                        "parent_id " + parentId + " 在本机数据和这个文件里都不存在"
                    wouldCreateCycle(category.id, mergedParentOf) ->
                        "parent_id 会把分类挂到自己的子孙下面"
                    else -> null
                }
                if (reason != null) {
                    problems += CsvRowProblem(
                        CsvExporter.CATEGORIES_FILE,
                        lineOf(CsvExporter.CATEGORIES_FILE, category.id),
                        reason,
                    )
                    continue
                }
            }
            val entity = category.toEntity()
            if (category.id in existingCategoryIds) categoriesToUpdate += entity else categoriesToInsert += entity
        }

        // 交易：账户/分类引用必须存在，分类的收支方向必须和交易类型一致（数据库触发器也管这条）
        val mergedCategoryKind = HashMap<Long, CategoryKind>()
        snapshot.categories.forEach { mergedCategoryKind[it.id] = it.kind }
        parsed.categories.forEach { mergedCategoryKind[it.id] = CategoryKind.valueOf(it.kind) }
        val mergedAccountIds = existingAccountIds + parsed.accounts.map { it.id }
        val transactionsToInsert = mutableListOf<TransactionEntity>()
        val transactionsToUpdate = mutableListOf<TransactionEntity>()
        for (tx in parsed.transactions) {
            val reason = when {
                tx.accountId !in mergedAccountIds ->
                    "account_id " + tx.accountId + " 在本机数据和这个文件里都不存在"
                tx.toAccountId != null && tx.toAccountId !in mergedAccountIds ->
                    "to_account_id " + tx.toAccountId + " 在本机数据和这个文件里都不存在"
                tx.categoryId != null && tx.categoryId !in mergedCategoryKind.keys ->
                    "category_id " + tx.categoryId + " 在本机数据和这个文件里都不存在"
                tx.categoryId != null && mergedCategoryKind[tx.categoryId] != kindOf(tx.type) ->
                    "分类的收支方向和交易类型不一致"
                else -> null
            }
            if (reason != null) {
                problems += CsvRowProblem(
                    CsvExporter.TRANSACTIONS_FILE,
                    lineOf(CsvExporter.TRANSACTIONS_FILE, tx.id),
                    reason,
                )
                continue
            }
            val entity = tx.toEntity()
            if (tx.id in existingTransactionIds) transactionsToUpdate += entity else transactionsToInsert += entity
        }

        // 预算：同一月份同一分类只能有一份（本机已有别的 id 占着这个键就跳过，不让触发器把整批回滚）
        val budgetKeyOwner = HashMap<Pair<String, Long?>, Long>()
        snapshot.budgets.forEach { budgetKeyOwner[it.month to it.categoryId] = it.id }
        val budgetsToInsert = mutableListOf<BudgetEntity>()
        val budgetsToUpdate = mutableListOf<BudgetEntity>()
        for (budget in parsed.budgets) {
            val owner = budgetKeyOwner[budget.month to budget.categoryId]
            val reason = when {
                owner != null && owner != budget.id ->
                    "本机已经有 " + budget.month + " 的这份预算（id " + owner + "），同一月份同一个分类只能有一份"
                budget.categoryId != null && budget.categoryId !in mergedCategoryKind.keys ->
                    "category_id " + budget.categoryId + " 在本机数据和这个文件里都不存在"
                else -> null
            }
            if (reason != null) {
                problems += CsvRowProblem(
                    CsvExporter.BUDGETS_FILE,
                    lineOf(CsvExporter.BUDGETS_FILE, budget.id),
                    reason,
                )
                continue
            }
            val entity = budget.toEntity()
            if (budget.id in existingBudgetIds) budgetsToUpdate += entity else budgetsToInsert += entity
        }

        return ImportPlan(
            accountsToInsert = accountsToInsert,
            accountsToUpdate = accountsToUpdate,
            categoriesToInsert = categoriesToInsert,
            categoriesToUpdate = categoriesToUpdate,
            transactionsToInsert = transactionsToInsert,
            transactionsToUpdate = transactionsToUpdate,
            budgetsToInsert = budgetsToInsert,
            budgetsToUpdate = budgetsToUpdate,
            problems = problems,
        )
    }

    /** 顺着 parentId 往上走，走到自己（或绕回走过的节点）说明会成环。 */
    private fun wouldCreateCycle(id: Long, parentOf: Map<Long, Long?>): Boolean {
        var cursor = parentOf[id]
        val seen = HashSet<Long>()
        while (cursor != null) {
            if (cursor == id || !seen.add(cursor)) return true
            cursor = parentOf[cursor]
        }
        return false
    }

    /** 交易类型对应的分类方向；转账/余额调整没有分类，返回 null。 */
    private fun kindOf(type: String): CategoryKind? = when (type) {
        TransactionType.EXPENSE.name -> CategoryKind.EXPENSE
        TransactionType.INCOME.name -> CategoryKind.INCOME
        else -> null
    }
    /**
     * 分类按「父在前」排序：parentId 引用同一批数据，父行先落库。
     * 现在数据库没有外键，这是为将来加外键留的后路，也顺手保证顺序稳定。
     */
    private fun <T> parentsFirst(items: List<T>, idOf: (T) -> Long, parentIdOf: (T) -> Long?): List<T> {
        val byId = items.associateBy(idOf)
        val depth = HashMap<Long, Int>()
        fun depthOf(start: T): Int {
            depth[idOf(start)]?.let { return it }
            var steps = 0
            var cursor: T? = start
            val seen = HashSet<Long>()
            while (cursor != null && seen.add(idOf(cursor))) {
                val parent = parentIdOf(cursor)?.let { byId[it] } ?: break
                steps++
                cursor = parent
            }
            depth[idOf(start)] = steps
            return steps
        }
        return items.sortedBy { depthOf(it) }
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
