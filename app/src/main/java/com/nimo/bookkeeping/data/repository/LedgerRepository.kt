package com.nimo.bookkeeping.data.repository

import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.BudgetEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.data.local.toEntity
import com.nimo.bookkeeping.domain.model.AccountRef
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.CategoryRef
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.validate.AccountValidationException
import com.nimo.bookkeeping.domain.validate.AccountValidator
import com.nimo.bookkeeping.domain.validate.TransactionValidator
import com.nimo.bookkeeping.domain.validate.TxValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 唯一数据入口。UI 只经由 Repository 读写，不直接访问 DAO（计划 4.3）。
 * 写操作三道防线：UI 校验 → TransactionValidator（这里）→ 数据库触发器。
 */
class LedgerRepository(private val db: AppDatabase) {

    private val accountDao get() = db.accountDao()
    private val categoryDao get() = db.categoryDao()
    private val txDao get() = db.transactionDao()
    private val budgetDao get() = db.budgetDao()

    // ---- 查询 ----

    fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()

    /** 只含未归档账户：记账页等"新建交易"的选择器用这个（M2 验收：归档账户不出现在记账页选择器） */
    fun observeActiveAccounts(): Flow<List<AccountEntity>> = accountDao.observeActive()

    fun observeActiveCategories(kind: CategoryKind): Flow<List<CategoryEntity>> =
        categoryDao.observeActiveByKind(kind)

    /**
     * 分类全量（含已停用）。流水页用它解析历史流水的分类名与图标：
     * 分类停用后历史流水必须照常显示，不能退化成"未分类"。
     */
    fun observeAllCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun observeTransactions(from: String, to: String): Flow<List<TransactionEntity>> =
        txDao.observeBetween(from, to)

    /**
     * 读单笔（编辑页预填用）。UI 不直接访问 DAO，读取同样经 Repository（计划 4.3）。
     * 返回 null 表示这一笔已不存在（例如别处已删除）。
     */
    suspend fun getTransaction(id: Long): TransactionEntity? = withContext(Dispatchers.IO) {
        txDao.getById(id)
    }

    fun observeBudgets(month: String): Flow<List<BudgetEntity>> = budgetDao.observeByMonth(month)

    // ---- 账户写入（F01）。余额是推导值（铁律 2），这里只写账户基础信息 ----

    /** 新建账户。名称/初始余额先过业务校验（UI 校验之外的第二道）。 */
    suspend fun createAccount(
        name: String,
        type: AccountType,
        initialBalanceCents: Long,
        includeInTotal: Boolean = true,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val errors = AccountValidator.validate(name, initialBalanceCents)
            if (errors.isNotEmpty()) throw AccountValidationException(errors)
            val now = System.currentTimeMillis()
            Result.success(
                accountDao.upsert(
                    AccountEntity(
                        name = name.trim(),
                        type = type,
                        initialBalance = initialBalanceCents,
                        includeInTotal = includeInTotal,
                        sortOrder = accountDao.nextSortOrder(),
                        createdAt = now,
                        updatedAt = now,
                    ),
                ),
            )
        } catch (e: AccountValidationException) {
            Result.failure(e)
        }
    }

    /** 编辑账户基础信息：归档状态与排序位保持原样，createdAt 不变。 */
    suspend fun updateAccount(
        id: Long,
        name: String,
        type: AccountType,
        initialBalanceCents: Long,
        includeInTotal: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val errors = AccountValidator.validate(name, initialBalanceCents)
            if (errors.isNotEmpty()) throw AccountValidationException(errors)
            val existing = accountDao.getById(id)
                ?: return@withContext Result.failure(IllegalStateException("账户不存在"))
            accountDao.upsert(
                existing.copy(
                    name = name.trim(),
                    type = type,
                    initialBalance = initialBalanceCents,
                    includeInTotal = includeInTotal,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            Result.success(Unit)
        } catch (e: AccountValidationException) {
            Result.failure(e)
        }
    }

    /** 归档 / 恢复账户。只改 isArchived，不删除历史数据。 */
    suspend fun setAccountArchived(id: Long, archived: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val existing = accountDao.getById(id)
                ?: return@withContext Result.failure(IllegalStateException("账户不存在"))
            accountDao.setArchived(existing.id, archived, System.currentTimeMillis())
            Result.success(Unit)
        }

    // ---- 交易写入：先校验后落库 ----

    private suspend fun validateOrThrow(input: TransactionInput) {
        val errors = TransactionValidator.validate(
            input = input,
            accounts = accountDao.listAll().map { AccountRef(it.id, it.isArchived) },
            categories = categoryDao.listAll().map { CategoryRef(it.id, it.kind, it.isArchived) },
        )
        if (errors.isNotEmpty()) throw TxValidationException(errors)
    }

    suspend fun addTransaction(input: TransactionInput): Result<Long> = withContext(Dispatchers.IO) {
        try {
            validateOrThrow(input)
            Result.success(txDao.insert(input.toEntity(System.currentTimeMillis())))
        } catch (e: TxValidationException) {
            Result.failure(e)
        }
    }

    /**
     * 余额是推导值（铁律 2），编辑 = 整行替换，天然不存在"重复扣款"问题。
     */
    suspend fun updateTransaction(id: Long, input: TransactionInput): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                validateOrThrow(input)
                val existing = txDao.getById(id)
                    ?: return@withContext Result.failure(IllegalStateException("交易不存在"))
                val now = System.currentTimeMillis()
                txDao.update(input.toEntity(now).copy(id = existing.id, createdAt = existing.createdAt))
                Result.success(Unit)
            } catch (e: TxValidationException) {
                Result.failure(e)
            }
        }

    /** 删除（物理）。撤销删除由 UI 层延迟物理删除策略控制（计划 9.2），超时前不会走到这里 */
    suspend fun deleteTransaction(entity: TransactionEntity): Unit = withContext(Dispatchers.IO) {
        txDao.delete(entity)
    }

    /** 撤销删除：按原 id 恢复整笔（autoGenerate 主键显式指定即可复用） */
    suspend fun restoreTransaction(entity: TransactionEntity): Long = withContext(Dispatchers.IO) {
        txDao.insert(entity)
    }

    // ---- 预算 ----

    suspend fun setBudget(month: String, categoryId: Long?, amountCents: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (amountCents <= 0) {
                return@withContext Result.failure(IllegalArgumentException("预算必须大于 0"))
            }
            val existing = budgetDao.find(month, categoryId)
            budgetDao.upsert(
                BudgetEntity(
                    id = existing?.id ?: 0L,
                    month = month,
                    categoryId = categoryId,
                    amount = amountCents,
                ),
            )
            Result.success(Unit)
        }

    suspend fun deleteBudget(id: Long): Unit = withContext(Dispatchers.IO) {
        budgetDao.deleteById(id)
    }
}
