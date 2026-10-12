package com.nimo.bookkeeping.data.repository

import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.BudgetEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.data.local.toEntity
import com.nimo.bookkeeping.domain.model.AccountRef
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.CategoryRef
import com.nimo.bookkeeping.domain.model.TransactionInput
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

    fun observeActiveCategories(kind: CategoryKind): Flow<List<CategoryEntity>> =
        categoryDao.observeActiveByKind(kind)

    fun observeTransactions(from: String, to: String): Flow<List<TransactionEntity>> =
        txDao.observeBetween(from, to)

    fun observeBudgets(month: String): Flow<List<BudgetEntity>> = budgetDao.observeByMonth(month)

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
