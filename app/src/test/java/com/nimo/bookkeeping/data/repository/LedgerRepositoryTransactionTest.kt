package com.nimo.bookkeeping.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.validate.TxValidationError
import com.nimo.bookkeeping.domain.validate.TxValidationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 记账写入链路（F03）：Repository → TransactionValidator → DAO → 触发器，真实 SQLite。
 * 同时覆盖 M2 验收项「归档账户不出现在记账页账户选择器」所依赖的查询。
 */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryTransactionTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private var expenseCategoryId = 0L
    private var incomeCategoryId = 0L

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repository = LedgerRepository(db)

        expenseCategoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, sortOrder = 0))
        incomeCategoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "工资", kind = CategoryKind.INCOME, sortOrder = 0))
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun newAccount(name: String = "现金"): Long =
        repository.createAccount(name, AccountType.CASH, 0L).getOrThrow()

    private fun input(
        type: TransactionType = TransactionType.EXPENSE,
        amountCents: Long = 1_250L,
        accountId: Long?,
        categoryId: Long? = null,
        occurredOn: String = "2026-10-12",
        note: String? = null,
    ) = TransactionInput(
        type = type,
        amountCents = amountCents,
        accountId = accountId,
        categoryId = categoryId,
        occurredOn = occurredOn,
        note = note,
    )

    private suspend fun errorsOf(input: TransactionInput): List<TxValidationError> {
        val result = repository.addTransaction(input)
        assertTrue("期望写入失败，实际成功", result.isFailure)
        return (result.exceptionOrNull() as TxValidationException).errors
    }

    // ---- 正常写入 ----

    @Test
    fun addExpense_persistsAllFields() = runTest {
        val accountId = newAccount()
        val id = repository.addTransaction(
            input(accountId = accountId, categoryId = expenseCategoryId, note = "午餐"),
        ).getOrThrow()

        val saved = db.transactionDao().getById(id)!!
        assertEquals(TransactionType.EXPENSE, saved.type)
        assertEquals(1_250L, saved.amount)
        assertEquals(accountId, saved.accountId)
        assertEquals(expenseCategoryId, saved.categoryId)
        assertEquals("2026-10-12", saved.occurredOn)
        assertEquals("午餐", saved.note)
        // 记账页不写转入账户；转账（F04）才用 toAccountId
        assertEquals(null, saved.toAccountId)
    }

    @Test
    fun addIncome_persistsWithIncomeCategory() = runTest {
        val accountId = newAccount()
        val id = repository.addTransaction(
            input(
                type = TransactionType.INCOME,
                amountCents = 500_000L,
                accountId = accountId,
                categoryId = incomeCategoryId,
            ),
        ).getOrThrow()
        assertEquals(500_000L, db.transactionDao().getById(id)!!.amount)
    }

    @Test
    fun blankNote_isStoredAsNull() = runTest {
        val accountId = newAccount()
        val id = repository.addTransaction(
            input(accountId = accountId, categoryId = expenseCategoryId, note = "   "),
        ).getOrThrow()
        assertEquals(null, db.transactionDao().getById(id)!!.note)
    }

    // ---- 被拒绝的写入：必须一行都不落库 ----

    @Test
    fun archivedAccount_isRejectedAndWritesNothing() = runTest {
        val accountId = newAccount()
        repository.setAccountArchived(accountId, archived = true).getOrThrow()

        assertEquals(
            listOf(TxValidationError.AccountArchived),
            errorsOf(input(accountId = accountId, categoryId = expenseCategoryId)),
        )
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun categoryKindMismatch_isRejected() = runTest {
        val accountId = newAccount()
        // 收入分类不能用于支出（计划 9.2）
        assertEquals(
            listOf(TxValidationError.CategoryKindMismatch),
            errorsOf(input(accountId = accountId, categoryId = incomeCategoryId)),
        )
        // 支出分类不能用于收入
        assertEquals(
            listOf(TxValidationError.CategoryKindMismatch),
            errorsOf(
                input(
                    type = TransactionType.INCOME,
                    accountId = accountId,
                    categoryId = expenseCategoryId,
                ),
            ),
        )
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun missingCategory_isRejected() = runTest {
        val accountId = newAccount()
        assertEquals(
            listOf(TxValidationError.CategoryRequired),
            errorsOf(input(accountId = accountId, categoryId = null)),
        )
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun missingAccount_isRejected() = runTest {
        assertEquals(
            listOf(TxValidationError.AccountRequired),
            errorsOf(input(accountId = null, categoryId = expenseCategoryId)),
        )
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun zeroAndNegativeAmount_areRejected() = runTest {
        val accountId = newAccount()
        assertEquals(
            listOf(TxValidationError.AmountMustBePositive),
            errorsOf(input(amountCents = 0L, accountId = accountId, categoryId = expenseCategoryId)),
        )
        assertEquals(
            listOf(TxValidationError.AmountMustBePositive),
            errorsOf(input(amountCents = -1L, accountId = accountId, categoryId = expenseCategoryId)),
        )
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun amountOverProductLimit_isRejected() = runTest {
        val accountId = newAccount()
        assertEquals(
            listOf(TxValidationError.AmountOutOfRange),
            errorsOf(
                input(
                    amountCents = 10_000_000_000L,
                    accountId = accountId,
                    categoryId = expenseCategoryId,
                ),
            ),
        )
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun noteOver200Chars_isRejected() = runTest {
        val accountId = newAccount()
        assertEquals(
            listOf(TxValidationError.NoteTooLong),
            errorsOf(
                input(
                    accountId = accountId,
                    categoryId = expenseCategoryId,
                    note = "字".repeat(201),
                ),
            ),
        )
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun impossibleDate_isRejected() = runTest {
        val accountId = newAccount()
        assertEquals(
            listOf(TxValidationError.DateInvalid),
            errorsOf(
                input(
                    accountId = accountId,
                    categoryId = expenseCategoryId,
                    occurredOn = "2026-02-30",
                ),
            ),
        )
        assertEquals(0, db.transactionDao().count())
    }

    // ---- 编辑 / 删除 / 撤销（F03 的修改与删除入口）----

    @Test
    fun getTransaction_readsOneRowAndIsNullWhenMissing() = runTest {
        val accountId = newAccount()
        val id = repository.addTransaction(
            input(accountId = accountId, categoryId = expenseCategoryId, note = "午餐"),
        ).getOrThrow()

        assertEquals(id, repository.getTransaction(id)?.id)
        assertNull(repository.getTransaction(id + 1))
    }

    @Test
    fun updateTransaction_replacesTheRowKeepingIdAndCreatedAt() = runTest {
        val accountId = newAccount()
        val id = repository.addTransaction(
            input(accountId = accountId, categoryId = expenseCategoryId, note = "午餐"),
        ).getOrThrow()
        val original = db.transactionDao().getById(id)!!

        repository.updateTransaction(
            id,
            input(amountCents = 9_900L, accountId = accountId, categoryId = expenseCategoryId, note = "改过了"),
        ).getOrThrow()

        val updated = db.transactionDao().getById(id)!!
        assertEquals(id, updated.id)
        assertEquals(9_900L, updated.amount)
        assertEquals("改过了", updated.note)
        // 整行替换：id 与 createdAt 保持原样，顺序因此不跳动
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(1, db.transactionDao().count())
    }

    @Test
    fun updateTransaction_onMissingRow_failsWithoutInserting() = runTest {
        val accountId = newAccount()

        val result = repository.updateTransaction(
            424_242L,
            input(accountId = accountId, categoryId = expenseCategoryId),
        )

        assertTrue(result.isFailure)
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun updateTransaction_rejectedByValidator_keepsTheOldRowUntouched() = runTest {
        val accountId = newAccount()
        val id = repository.addTransaction(
            input(accountId = accountId, categoryId = expenseCategoryId),
        ).getOrThrow()

        val result = repository.updateTransaction(
            id,
            input(amountCents = 0L, accountId = accountId, categoryId = expenseCategoryId),
        )

        assertTrue(result.isFailure)
        assertEquals(1_250L, db.transactionDao().getById(id)!!.amount)
    }

    @Test
    fun deleteThenRestore_roundTripsTheSameRow() = runTest {
        val accountId = newAccount()
        val id = repository.addTransaction(
            input(accountId = accountId, categoryId = expenseCategoryId, note = "午餐"),
        ).getOrThrow()
        val original = db.transactionDao().getById(id)!!

        repository.deleteTransaction(original)
        assertNull(db.transactionDao().getById(id))

        // 撤销删除：按原 id 整笔恢复（含 createdAt/updatedAt）
        assertEquals(id, repository.restoreTransaction(original))
        assertEquals(original, db.transactionDao().getById(id))
    }

    // ---- 选择器数据源（M2 验收：归档的不出现）----

    @Test
    fun observeActiveAccounts_excludesArchivedButKeepsHistory() = runTest {
        val active = newAccount("在用")
        val archived = newAccount("已归档")
        repository.setAccountArchived(archived, archived = true).getOrThrow()

        val selectable = repository.observeActiveAccounts().first()
        assertEquals(listOf(active), selectable.map { it.id })
        // 归档只是不进选择器，数据仍在（历史流水照常显示）
        assertEquals(2, db.accountDao().listAll().size)
        assertEquals(2, repository.observeAccounts().first().size)
    }

    @Test
    fun observeActiveCategories_excludesArchived() = runTest {
        val archivedId = db.categoryDao()
            .upsert(CategoryEntity(name = "旧分类", kind = CategoryKind.EXPENSE, sortOrder = 9))
        db.categoryDao().archiveWithChildren(archivedId)

        val selectable = repository.observeActiveCategories(CategoryKind.EXPENSE).first()
        assertFalse(selectable.any { it.id == archivedId })
        assertTrue(selectable.any { it.id == expenseCategoryId })
        // 收入分类不会混进支出的选择器
        assertTrue(selectable.all { it.kind == CategoryKind.EXPENSE })
    }
}
