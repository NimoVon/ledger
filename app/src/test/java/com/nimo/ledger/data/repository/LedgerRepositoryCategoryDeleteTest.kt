package com.nimo.ledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.AccountEntity
import com.nimo.ledger.data.local.entity.BudgetEntity
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.local.entity.TransactionEntity
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.validate.CategoryDeletionBlocker
import com.nimo.ledger.domain.validate.CategoryDeletionException
import com.nimo.ledger.domain.validate.CategoryReferences
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 分类删除（附.1 R3 / 附.2）：**只有零引用**的分类能删，有引用一律失败并回带原因（引导改用停用）。
 * 走真实 Repository + in-memory Room：库里没有"禁止删除被引用分类"的触发器，
 * 守卫完全靠 Repository 自己再数一遍引用——这个测试就是那道防线的证据。
 */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryCategoryDeleteTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repository = LedgerRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun seed(
        name: String,
        kind: CategoryKind = CategoryKind.EXPENSE,
        parentId: Long? = null,
        sortOrder: Int = 0,
    ): Long = db.categoryDao().insert(
        CategoryEntity(name = name, kind = kind, parentId = parentId, sortOrder = sortOrder),
    )

    private suspend fun anAccount(): Long = db.accountDao().upsert(
        AccountEntity(name = "支付宝", type = AccountType.OTHER, createdAt = 0, updatedAt = 0),
    )

    private suspend fun seedExpense(
        categoryId: Long,
        amount: Long = 100L,
        occurredOn: String = "2026-10-01",
    ): Long = db.transactionDao().insert(
        TransactionEntity(
            type = TransactionType.EXPENSE,
            amount = amount,
            accountId = anAccount(),
            categoryId = categoryId,
            occurredOn = occurredOn,
            createdAt = 0,
            updatedAt = 0,
        ),
    )

    private suspend fun seedBudget(categoryId: Long, month: String = "2026-10"): Long =
        db.budgetDao().upsert(BudgetEntity(month = month, categoryId = categoryId, amount = 10_000L))

    @Test
    fun categoryReferences_countsChildrenTransactionsAndBudgets() = runTest {
        val parent = seed("餐饮")
        seed("外卖", parentId = parent)
        seedExpense(parent)
        seedExpense(parent, amount = 200L)
        seedBudget(parent)

        val references = repository.categoryReferences(parent)

        assertEquals(CategoryReferences(childCount = 1, transactionCount = 2, budgetCount = 1), references)
        assertFalse(references.isZeroReference)
    }

    @Test
    fun deleteCategory_withNoReferences_removesTheRow() = runTest {
        val id = seed("误建的分类")

        assertTrue(repository.deleteCategory(id).isSuccess)

        assertNull(db.categoryDao().getById(id))
        // 真删（不是停用）：同名可以重新建出来
        assertTrue(repository.createCategory("误建的分类", CategoryKind.EXPENSE).isSuccess)
    }

    @Test
    fun deleteCategory_withTransactions_failsAndKeepsTheRow() = runTest {
        val id = seed("餐饮")
        seedExpense(id)

        val error = repository.deleteCategory(id).exceptionOrNull()

        assertTrue(error is CategoryDeletionException)
        assertEquals(
            listOf(CategoryDeletionBlocker.HasTransactions),
            (error as CategoryDeletionException).blockers,
        )
        // 失败不能是"删了一半"：分类与流水都在
        assertNotNull(db.categoryDao().getById(id))
        assertEquals(1, db.transactionDao().count())
    }

    @Test
    fun deleteCategory_withABudget_failsAndKeepsTheRow() = runTest {
        val id = seed("购物")
        seedBudget(id)

        val error = repository.deleteCategory(id).exceptionOrNull()

        assertTrue(error is CategoryDeletionException)
        assertEquals(listOf(CategoryDeletionBlocker.HasBudgets), (error as CategoryDeletionException).blockers)
        assertNotNull(db.categoryDao().getById(id))
    }

    @Test
    fun deleteCategory_withAChild_failsEvenWhenTheParentItselfIsUnused() = runTest {
        val parent = seed("餐饮")
        val child = seed("外卖", parentId = parent)

        val error = repository.deleteCategory(parent).exceptionOrNull()

        assertTrue(error is CategoryDeletionException)
        assertEquals(listOf(CategoryDeletionBlocker.HasChildren), (error as CategoryDeletionException).blockers)
        assertNotNull(db.categoryDao().getById(parent))
        assertNotNull(db.categoryDao().getById(child))
    }

    @Test
    fun deleteCategory_reportsEveryKindOfReferenceAtOnce() = runTest {
        val id = seed("餐饮")
        seed("外卖", parentId = id)
        seedExpense(id)
        seedBudget(id)

        val error = repository.deleteCategory(id).exceptionOrNull() as CategoryDeletionException

        assertEquals(
            listOf(
                CategoryDeletionBlocker.HasChildren,
                CategoryDeletionBlocker.HasTransactions,
                CategoryDeletionBlocker.HasBudgets,
            ),
            error.blockers,
        )
    }

    @Test
    fun deleteCategory_onAnUnknownId_fails() = runTest {
        val result = repository.deleteCategory(id = 4321L)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun deleteCategory_leavesTheOtherCategoriesAndTheirTransactionsAlone() = runTest {
        val kept = seed("餐饮", sortOrder = 0)
        val removed = seed("误建", sortOrder = 1)
        seedExpense(kept)

        assertTrue(repository.deleteCategory(removed).isSuccess)

        assertNotNull(db.categoryDao().getById(kept))
        assertEquals(1, db.transactionDao().count())
        assertEquals(listOf("餐饮"), db.categoryDao().listAll().map { it.name })
    }
}
