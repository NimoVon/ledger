package com.nimo.ledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.DefaultCategories
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.domain.model.CategoryKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「恢复默认分类」（附.4）：按名称补种缺失的预置分类，**不覆盖已有**（自建、停用、改过名的都算已有）。
 */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryRestoreDefaultsTest {

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
        isArchived: Boolean = false,
        sortOrder: Int = 0,
    ): Long = db.categoryDao().insert(
        CategoryEntity(name = name, kind = kind, isArchived = isArchived, sortOrder = sortOrder),
    )

    @Test
    fun onAnEmptyTable_everyPresetCategoryIsInserted() = runTest {
        val restored = repository.restoreDefaultCategories().getOrThrow()

        assertEquals(DefaultCategories.all().size, restored)
        assertEquals(DefaultCategories.all().size, db.categoryDao().count())
        // 两棵树都补齐，序号从 0 连续排下来（空组 maxSortOrder = -1，所以第一个是 0）
        val all = db.categoryDao().listAll().sortedWith(compareBy({ it.kind.name }, { it.sortOrder }))
        assertEquals(
            setOf(CategoryKind.EXPENSE, CategoryKind.INCOME),
            all.map { it.kind }.toSet(),
        )
        val expenses = all.filter { it.kind == CategoryKind.EXPENSE }
        assertEquals((0 until expenses.size).toList(), expenses.map { it.sortOrder })
    }

    @Test
    fun anExistingCategoryIsNeverOverwritten() = runTest {
        // 用户手建过同名的「餐饮」，而且已经停用：恢复默认不能改它的名字/状态，也不能再补一个
        val mine = seed("餐饮", isArchived = true, sortOrder = 0)

        val restored = repository.restoreDefaultCategories().getOrThrow()

        val row = db.categoryDao().getById(mine)!!
        assertTrue("停用状态必须原样保留", row.isArchived)
        assertEquals(DefaultCategories.all().size - 1, restored)
        assertEquals(
            1,
            db.categoryDao().listAll().count { it.name == "餐饮" && it.kind == CategoryKind.EXPENSE },
        )
    }

    @Test
    fun restoredCategoriesAreAppendedAfterTheHighestExistingSortOrder() = runTest {
        seed("自建", sortOrder = 5)

        repository.restoreDefaultCategories().getOrThrow()

        val appended = db.categoryDao().listAll()
            .filter { it.kind == CategoryKind.EXPENSE && it.name != "自建" }
            .sortedBy { it.sortOrder }
        assertEquals(6, appended.first().sortOrder)
        assertEquals((6 until 6 + appended.size).toList(), appended.map { it.sortOrder })
    }

    @Test
    fun nothingIsInsertedWhenEveryPresetCategoryAlreadyExists() = runTest {
        DefaultCategories.all().forEachIndexed { index, entity ->
            db.categoryDao().insert(entity.copy(sortOrder = index))
        }

        val restored = repository.restoreDefaultCategories().getOrThrow()

        assertEquals(0, restored)
        assertEquals(DefaultCategories.all().size, db.categoryDao().count())
    }

    @Test
    fun restoringAfterTheUserDeletedEverythingBringsThemAllBack() = runTest {
        // 附.4 的关键路径：删光 → 点「恢复默认分类」→ 默认分类全部回来（这也是唯一的恢复入口）
        DefaultCategories.all().forEachIndexed { index, entity ->
            db.categoryDao().insert(entity.copy(sortOrder = index))
        }
        db.categoryDao().listAll().forEach { db.categoryDao().deleteById(it.id) }
        assertEquals(0, db.categoryDao().count())

        val restored = repository.restoreDefaultCategories().getOrThrow()

        assertEquals(DefaultCategories.all().size, restored)
        // listAll() 是 sortOrder 全局排序，两棵树的 0 号会交错；按 kind 分组比对才反映真实播种顺序
        listOf(CategoryKind.EXPENSE, CategoryKind.INCOME).forEach { kind ->
            assertEquals(
                DefaultCategories.all().filter { it.kind == kind }.map { it.name },
                db.categoryDao().listAll()
                    .filter { it.kind == kind }
                    .sortedBy { it.sortOrder }
                    .map { it.name },
            )
        }
    }
}
