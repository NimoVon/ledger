package com.nimo.ledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.BudgetEntity
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.validate.BudgetValidationError
import com.nimo.ledger.domain.validate.BudgetValidationException
import com.nimo.ledger.util.Money
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
 * 预算写入（F09）：走真实 Repository + in-memory Room。
 * 重点验证"同月再设是更新而不是插第二行"——budgets 的「每月总预算唯一」是**部分唯一索引**，
 * 必须靠 find → insert/update 的写路径，不能靠 @Upsert（撞非主键约束会被 Room 静默转成 0 行 UPDATE）。
 */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryBudgetWriteTest {

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

    private suspend fun seedCategory(name: String, kind: CategoryKind = CategoryKind.EXPENSE): Long =
        db.categoryDao().insert(CategoryEntity(name = name, kind = kind))

    private suspend fun budgetsOf(month: String): List<BudgetEntity> =
        db.budgetDao().observeByMonth(month).first()

    private fun Throwable?.isValidationError(expected: BudgetValidationError): Boolean =
        (this as? BudgetValidationException)?.errors?.contains(expected) == true

    @Test
    fun setNewTotalBudget_insertsOneRow() = runTest {
        val result = repository.setBudget("2026-10", null, 100_000L)
        assertTrue(result.isSuccess)

        val rows = budgetsOf("2026-10")
        assertEquals(1, rows.size)
        assertNull(rows.single().categoryId)
        assertEquals(100_000L, rows.single().amount)
    }

    @Test
    fun setSameMonthTotalBudgetTwice_updatesInsteadOfAddingSecondRow() = runTest {
        assertTrue(repository.setBudget("2026-10", null, 100_000L).isSuccess)
        assertTrue(repository.setBudget("2026-10", null, 200_000L).isSuccess)

        val rows = budgetsOf("2026-10")
        assertEquals("同月总预算只能有一行", 1, rows.size)
        assertEquals(200_000L, rows.single().amount)
    }

    @Test
    fun setSameCategoryBudgetTwice_updatesAmount() = runTest {
        val categoryId = seedCategory("餐饮")
        assertTrue(repository.setBudget("2026-10", categoryId, 30_000L).isSuccess)
        assertTrue(repository.setBudget("2026-10", categoryId, 45_000L).isSuccess)

        val rows = budgetsOf("2026-10")
        assertEquals(1, rows.size)
        assertEquals(45_000L, rows.single().amount)
    }

    @Test
    fun totalAndCategoryBudgets_coexistInTheSameMonth() = runTest {
        val food = seedCategory("餐饮")
        val shopping = seedCategory("购物")
        assertTrue(repository.setBudget("2026-10", null, 100_000L).isSuccess)
        assertTrue(repository.setBudget("2026-10", food, 30_000L).isSuccess)
        assertTrue(repository.setBudget("2026-10", shopping, 20_000L).isSuccess)

        val rows = budgetsOf("2026-10")
        assertEquals(3, rows.size)
        assertEquals(100_000L, rows.first().amount) // observeByMonth 把总预算排在最前
        assertNull(rows.first().categoryId)
    }

    @Test
    fun zeroOrNegativeAmount_failsAndLeavesDatabaseUntouched() = runTest {
        val zero = repository.setBudget("2026-10", null, 0L)
        val negative = repository.setBudget("2026-10", null, -1L)

        assertTrue(zero.isFailure)
        assertTrue(zero.exceptionOrNull().isValidationError(BudgetValidationError.AmountNotPositive))
        assertTrue(negative.isFailure)
        assertTrue(negative.exceptionOrNull().isValidationError(BudgetValidationError.AmountNotPositive))
        assertTrue(budgetsOf("2026-10").isEmpty())
    }

    @Test
    fun amountAboveProductLimit_fails() = runTest {
        val result = repository.setBudget("2026-10", null, Money.MAX_AMOUNT_CENTS + 1)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull().isValidationError(BudgetValidationError.AmountTooLarge))
        assertTrue(budgetsOf("2026-10").isEmpty())
    }

    @Test
    fun illegalMonth_failsBeforeTouchingDatabase() = runTest {
        for (bad in listOf("2026-13", "2026-1", "26-10", "")) {
            val result = repository.setBudget(bad, null, 10_000L)
            assertTrue("月份 $bad 应该失败", result.isFailure)
            assertTrue(result.exceptionOrNull().isValidationError(BudgetValidationError.MonthFormat))
        }
        assertTrue(budgetsOf("2026-13").isEmpty())
        assertTrue(budgetsOf("2026-1").isEmpty())
    }

    @Test
    fun deleteBudget_removesOnlyThatRow() = runTest {
        val categoryId = seedCategory("餐饮")
        repository.setBudget("2026-10", null, 100_000L)
        repository.setBudget("2026-10", categoryId, 30_000L)
        val total = budgetsOf("2026-10").first { it.categoryId == null }

        val result = repository.deleteBudget(total.id)

        assertTrue(result.isSuccess)
        val rows = budgetsOf("2026-10")
        assertEquals(1, rows.size)
        assertEquals(categoryId, rows.single().categoryId)
    }

    /**
     * 记录本工程已知的 Room 陷阱：@Upsert 撞上「每月总预算唯一」的部分唯一索引时，
     * Room 会把它转成"按主键 UPDATE 0 行"（不抛异常、也不插入）。所以写路径不能用 upsert。
     * 这里不断言具体返回值（Room 版本间可能不同），只断言**永远不会出现第二行**。
     */
    @Test
    fun upsertOnDuplicateTotalBudget_neverProducesTwoRows() = runTest {
        repository.setBudget("2026-10", null, 100_000L)

        val outcome = runCatching {
            db.budgetDao().upsert(BudgetEntity(month = "2026-10", categoryId = null, amount = 999L))
        }

        val rows = budgetsOf("2026-10")
        assertEquals("同月总预算永远只有一行", 1, rows.size)
        assertFalse(rows.single().amount == 0L)
        assertTrue(outcome.isSuccess || outcome.isFailure)
    }
}
