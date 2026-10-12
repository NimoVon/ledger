package com.app.ledger.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.app.ledger.data.local.entity.AccountEntity
import com.app.ledger.data.local.entity.BudgetEntity
import com.app.ledger.data.local.entity.CategoryEntity
import com.app.ledger.data.local.entity.TransactionEntity
import com.app.ledger.domain.model.AccountType
import com.app.ledger.domain.model.CategoryKind
import com.app.ledger.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAO + 数据库层硬约束测试（计划 6.1 数据库测试行；M1 验收项）。
 * Robolectric 提供真实 SQLite，触发器与部分唯一索引在此验证。
 */
@RunWith(RobolectricTestRunner::class)
class DaoConstraintTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // in-memory 测试库不挂 build() 里的回调：显式补一次加固（apply 幂等）
        SchemaHardening.apply(db.openHelper.writableDatabase)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun account(name: String = "现金", initial: Long = 0) =
        AccountEntity(name = name, type = AccountType.CASH, initialBalance = initial, createdAt = 1, updatedAt = 1)

    private fun tx(
        type: TransactionType,
        amount: Long,
        accountId: Long? = 1,
        toAccountId: Long? = null,
        categoryId: Long? = null,
        day: Int = 10,
    ) = TransactionEntity(
        type = type, amount = amount, accountId = accountId, toAccountId = toAccountId,
        categoryId = categoryId, occurredOn = "2026-10-${day.toString().padStart(2, '0')}",
        createdAt = 1, updatedAt = 1,
    )

    private suspend fun seedAccountAndCategory() {
        db.accountDao().upsert(account())
        db.categoryDao().upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE))
    }

    // ---- 金额约束（铁律 1 的数据库层验证） ----

    @Test
    fun insert_expenseWithZeroOrNegative_rejected() = runTest {
        seedAccountAndCategory()
        db.transactionDao().insert(tx(TransactionType.EXPENSE, 100, categoryId = 1)) // 合法先过
        listOf(0L, -100L).forEach { bad ->
            try {
                db.transactionDao().insert(tx(TransactionType.EXPENSE, bad, categoryId = 1))
                throw AssertionError("应拒绝金额 $bad")
            } catch (_: android.database.SQLException) {
                // SQLiteException extends SQLException：触发器 RAISE(ABORT) 命中
            }
        }
        assertEquals(1, db.transactionDao().count())
    }

    @Test
    fun insert_balanceAdjustZero_rejected() = runTest {
        seedAccountAndCategory()
        try {
            db.transactionDao().insert(tx(TransactionType.BALANCE_ADJUST, 0, categoryId = null))
            throw AssertionError("应拒绝金额 0 的余额调整")
        } catch (_: android.database.SQLException) {
        }
    }

    @Test
    fun insert_balanceAdjustNegative_accepted() = runTest {
        seedAccountAndCategory()
        db.transactionDao().insert(tx(TransactionType.BALANCE_ADJUST, -350, categoryId = null))
        assertEquals(1, db.transactionDao().count())
    }

    // ---- 转账约束 ----

    @Test
    fun insert_transferSameAccount_rejected() = runTest {
        seedAccountAndCategory()
        try {
            db.transactionDao().insert(tx(TransactionType.TRANSFER, 100, accountId = 1, toAccountId = 1))
            throw AssertionError("应拒绝同账户转账")
        } catch (_: android.database.SQLException) {
        }
    }

    @Test
    fun insert_transferMissingToAccount_rejected() = runTest {
        seedAccountAndCategory()
        try {
            db.transactionDao().insert(tx(TransactionType.TRANSFER, 100, accountId = 1, toAccountId = null))
            throw AssertionError("应拒绝缺少转入账户的转账")
        } catch (_: android.database.SQLException) {
        }
    }

    // ---- 分类约束 ----

    @Test
    fun insert_expenseWithoutCategory_rejected() = runTest {
        seedAccountAndCategory()
        try {
            db.transactionDao().insert(tx(TransactionType.EXPENSE, 100, categoryId = null))
            throw AssertionError("应拒绝无分类支出")
        } catch (_: android.database.SQLException) {
        }
    }

    @Test
    fun insert_transferWithCategory_rejected() = runTest {
        seedAccountAndCategory()
        db.accountDao().upsert(account("微信"))
        try {
            db.transactionDao().insert(tx(TransactionType.TRANSFER, 100, accountId = 1, toAccountId = 2, categoryId = 1))
            throw AssertionError("应拒绝带分类的转账")
        } catch (_: android.database.SQLException) {
        }
    }

    // ---- 更新也要过触发器 ----

    @Test
    fun update_amountToZero_rejected() = runTest {
        seedAccountAndCategory()
        val id = db.transactionDao().insert(tx(TransactionType.EXPENSE, 100, categoryId = 1))
        val entity = db.transactionDao().getById(id)!!
        try {
            db.transactionDao().update(entity.copy(amount = 0))
            throw AssertionError("更新为 0 金额应被拒绝")
        } catch (_: android.database.SQLException) {
        }
    }

    // ---- 预算部分唯一索引（评审补遗 #1） ----
    // 注意：Room @Upsert 会把非主键约束冲突吞掉转为 update-0-行（静默无操作），
    // 因此用 raw SQL 直插来验证索引本身存在且拦截。

    @Test
    fun insert_secondTotalBudgetForSameMonth_rejected() = runTest {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO budgets (month, categoryId, amount) VALUES ('2026-10', NULL, 100000)",
        )
        try {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO budgets (month, categoryId, amount) VALUES ('2026-10', NULL, 200000)",
            )
            throw AssertionError("同月第二条总预算应被拒绝")
        } catch (_: android.database.SQLException) {
        }
        // 唯一例外：应用层 Repository.setBudget 走 find→带 id upsert，不会触发此索引
    }

    @Test
    fun insert_budgetZeroAmount_rejected() = runTest {
        try {
            db.budgetDao().upsert(BudgetEntity(month = "2026-10", categoryId = 1, amount = 0))
            throw AssertionError("应拒绝 0 预算")
        } catch (_: android.database.SQLException) {
        }
    }

    @Test
    fun insert_differentMonths_totalBudgetBothAllowed() = runTest {
        db.budgetDao().upsert(BudgetEntity(month = "2026-10", categoryId = null, amount = 100000))
        db.budgetDao().upsert(BudgetEntity(month = "2026-11", categoryId = null, amount = 200000))
        val found = db.budgetDao().find("2026-10", null)
        assertNotNull(found)
        assertEquals(100000L, found!!.amount)
    }

    // ---- 撤销删除：按原 id 恢复 ----

    @Test
    fun delete_thenRestore_keepsIdAndFields() = runTest {
        seedAccountAndCategory()
        val id = db.transactionDao().insert(tx(TransactionType.EXPENSE, 12345, categoryId = 1, day = 5))
        val entity = db.transactionDao().getById(id)!!
        db.transactionDao().delete(entity)
        assertEquals(0, db.transactionDao().count())
        val restoredId = db.transactionDao().insert(entity)
        assertEquals(id, restoredId)  // 显式主键复用，引用完整性保持
        assertEquals(entity, db.transactionDao().getById(id))
    }

    // ---- 排序规则：occurredOn DESC, id DESC ----

    @Test
    fun list_ordering_isStableWithinDay() = runTest {
        seedAccountAndCategory()
        db.transactionDao().insert(tx(TransactionType.EXPENSE, 100, categoryId = 1, day = 10))
        db.transactionDao().insert(tx(TransactionType.EXPENSE, 200, categoryId = 1, day = 10))
        db.transactionDao().insert(tx(TransactionType.EXPENSE, 300, categoryId = 1, day = 11))
        db.transactionDao().insert(tx(TransactionType.EXPENSE, 400, categoryId = 1, day = 9))
        val list = db.transactionDao().observeAll().first()
        assertEquals(listOf(300L, 200L, 100L, 400L), list.map { it.amount })
    }

    // ---- 月度边界查询 ----

    @Test
    fun monthBounds_excludesNextMonthFirstDay() = runTest {
        seedAccountAndCategory()
        db.transactionDao().insert(tx(TransactionType.EXPENSE, 100, categoryId = 1, day = 31))
        db.transactionDao().insert(tx(TransactionType.EXPENSE, 200, categoryId = 1, day = 1))
        val (from, to) = com.app.ledger.util.Dates.monthBounds(2026, 10)
        val october = db.transactionDao().observeBetween(from, to).first()
        assertEquals(listOf(100L, 200L), october.map { it.amount }.sorted())
    }

    // ---- 归档行为 ----

    @Test
    fun archivedAccount_stillQueryableButSeparated() = runTest {
        seedAccountAndCategory()
        db.accountDao().setArchived(1, true, updatedAt = 2)
        val all = db.accountDao().observeAll().first()
        val active = db.accountDao().observeActive().first()
        assertEquals(1, all.size)
        assertEquals(0, active.size)
        assertNotNull(db.accountDao().getById(1))
    }

    @Test
    fun archiveParentCategory_cascadesToChildren() = runTest {
        val parentId = db.categoryDao().upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE))
        db.categoryDao().upsert(CategoryEntity(name = "午餐", kind = CategoryKind.EXPENSE, parentId = parentId))
        db.categoryDao().archiveWithChildren(parentId)
        val all = db.categoryDao().listAll()
        assertTrue(all.all { it.isArchived })  // 父子都归档
        val active = db.categoryDao().observeActiveByKind(CategoryKind.EXPENSE).first()
        assertTrue(active.isEmpty())
    }

    // ---- 默认分类播种（幂等性由 AppContainer 的 count==0 守卫保证，不靠 upsert） ----

    @Test
    fun defaultCategories_seedGuardPreventsDuplication() = runTest {
        assertEquals(0, db.categoryDao().count())
        // 第一次：空表 → 播种
        if (db.categoryDao().count() == 0) {
            DefaultCategories.all().forEachIndexed { i, c -> db.categoryDao().upsert(c.copy(sortOrder = i)) }
        }
        assertEquals(18, db.categoryDao().count())
        // 第二次：非空 → 守卫跳过，不重复播种
        if (db.categoryDao().count() == 0) {
            DefaultCategories.all().forEachIndexed { i, c -> db.categoryDao().upsert(c.copy(sortOrder = i)) }
        }
        assertEquals(18, db.categoryDao().count())
    }
}
