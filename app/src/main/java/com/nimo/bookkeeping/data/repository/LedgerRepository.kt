package com.nimo.bookkeeping.data.repository

import androidx.room.withTransaction
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
import com.nimo.bookkeeping.domain.model.MoveDirection
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.validate.AccountValidationException
import com.nimo.bookkeeping.domain.validate.AccountValidator
import com.nimo.bookkeeping.domain.validate.CategorySnapshot
import com.nimo.bookkeeping.domain.validate.CategoryValidationException
import com.nimo.bookkeeping.domain.validate.CategoryValidator
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
     * 全量流水（所有历史，不按月裁剪）：账户当前余额要的是"全部变动"。
     * 余额算法仍然只有 domain/stats/BalanceCalculator 一处（铁律 2），这里只提供输入。
     */
    fun observeAllTransactions(): Flow<List<TransactionEntity>> = txDao.observeAll()

    /**
     * 读单笔（编辑页预填用）。UI 不直接访问 DAO，读取同样经 Repository（计划 4.3）。
     * 返回 null 表示这一笔已不存在（例如别处已删除）。
     */
    suspend fun getTransaction(id: Long): TransactionEntity? = withContext(Dispatchers.IO) {
        txDao.getById(id)
    }

    /**
     * 读单个账户（含已归档）。编辑一笔历史流水时用它还原"这笔原本记在哪个账户"：
     * 归档账户不在 [observeActiveAccounts] 里，但页面得能把名字说清楚（F03 编辑归档账户）。
     */
    suspend fun getAccount(id: Long): AccountEntity? = withContext(Dispatchers.IO) {
        accountDao.getById(id)
    }

    /** 读单个分类（含已停用）。用途同 [getAccount]：编辑历史流水时要报出原本用的分类叫什么 */
    suspend fun getCategory(id: Long): CategoryEntity? = withContext(Dispatchers.IO) {
        categoryDao.getById(id)
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

    /**
     * 恢复被停用的分类，连同它的祖先链。
     *
     * 与 [setAccountArchived] 对称，但方向不对称是有意的：停用是自上而下的级联
     * （[CategoryDao.archiveWithChildren]），恢复只向上走——向下恢复会把用户单独停用过的子分类
     * 一起打开，那是替用户做决定。
     */
    suspend fun restoreCategory(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val existing = categoryDao.getById(id)
            ?: return@withContext Result.failure(IllegalStateException("分类不存在"))
        categoryDao.unarchive(existing.id)
        var parentId = existing.parentId
        while (parentId != null) {
            val parent = categoryDao.getById(parentId) ?: break
            if (!parent.isArchived) break
            categoryDao.unarchive(parent.id)
            parentId = parent.parentId
        }
        Result.success(Unit)
    }

    /**
     * 新建分类（F02）。名称、单层父子、同层重名都在 [CategoryValidator] 里判，
     * 排序排到同级末尾——新建的分类出现在列表最后一行，符合"刚加的东西在末尾"的直觉。
     */
    suspend fun createCategory(
        name: String,
        kind: CategoryKind,
        parentId: Long? = null,
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val errors = CategoryValidator.validate(
                name = name,
                kind = kind,
                parentId = parentId,
                all = categoryDao.listAll().map { it.toSnapshot() },
            )
            if (errors.isNotEmpty()) throw CategoryValidationException(errors)
            val id = db.withTransaction {
                categoryDao.insert(
                    CategoryEntity(
                        name = name.trim(),
                        kind = kind,
                        parentId = parentId,
                        // 计划 F02「排序」：新建排到同级末尾
                        sortOrder = categoryDao.maxSortOrder(kind, parentId) + 1,
                    ),
                )
            }
            Result.success(id)
        } catch (e: CategoryValidationException) {
            Result.failure(e)
        }
    }

    /**
     * 改名（F02）。只改名字：kind 与 parentId 原样保留。
     * 重名检查要排除自己（selfId），否则"把名字改成原名"会被自己挡下来。
     */
    suspend fun renameCategory(id: Long, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val existing = categoryDao.getById(id)
                ?: return@withContext Result.failure(IllegalStateException("分类不存在"))
            val errors = CategoryValidator.validate(
                name = name,
                kind = existing.kind,
                parentId = existing.parentId,
                all = categoryDao.listAll().map { it.toSnapshot() },
                selfId = existing.id,
            )
            if (errors.isNotEmpty()) throw CategoryValidationException(errors)
            categoryDao.rename(existing.id, name.trim())
            Result.success(Unit)
        } catch (e: CategoryValidationException) {
            Result.failure(e)
        }
    }

    /**
     * 停用分类（F02「停用不删除」）。级联停用它的子分类，历史流水一条不动——
     * 分类停用后旧流水的分类名照常显示，只是不能再选它记新账。
     */
    suspend fun archiveCategory(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val existing = categoryDao.getById(id)
            ?: return@withContext Result.failure(IllegalStateException("分类不存在"))
        categoryDao.archiveWithChildren(existing.id)
        Result.success(Unit)
    }

    /**
     * 同级上移 / 下移一格（F02「排序」）。
     *
     * 实现是"整组重排"而不是交换两个 sortOrder：备份导入进来的数据可能有重复的 sortOrder，
     * 交换两个相等的值等于什么都没做（界面上看起来就是按钮失灵）。整组改成 0..n-1 顺带把历史脏顺序理顺。
     * 已经在第一个（上移）或最后一个（下移）时静默不动，不报错——按钮本来就是置灰的。
     */
    suspend fun moveCategory(id: Long, direction: MoveDirection): Result<Unit> =
        withContext(Dispatchers.IO) {
            val existing = categoryDao.getById(id)
                ?: return@withContext Result.failure(IllegalStateException("分类不存在"))
            db.withTransaction {
                val ordered = categoryDao.listSiblings(existing.kind, existing.parentId).toMutableList()
                val from = ordered.indexOfFirst { it.id == id }
                val to = when (direction) {
                    MoveDirection.UP -> from - 1
                    MoveDirection.DOWN -> from + 1
                }
                if (from < 0 || to !in ordered.indices) return@withTransaction
                ordered.add(to, ordered.removeAt(from))
                ordered.forEachIndexed { index, sibling ->
                    if (sibling.sortOrder != index) categoryDao.setSortOrder(sibling.id, index)
                }
            }
            Result.success(Unit)
        }

    private fun CategoryEntity.toSnapshot() = CategorySnapshot(
        id = id,
        name = name,
        kind = kind,
        parentId = parentId,
        isArchived = isArchived,
    )

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

    /**
     * 删除（物理）。撤销删除由 UI 层延迟物理删除策略控制（计划 9.2）：
     * 倒计时内只是把该行挡在页面之外，超时才真正调用这里，因此不需要"恢复"入口。
     */
    suspend fun deleteTransaction(entity: TransactionEntity): Unit = withContext(Dispatchers.IO) {
        txDao.delete(entity)
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
