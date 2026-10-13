package com.nimo.bookkeeping.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.domain.model.CategoryKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 分类停用/恢复链路测试：停用自上而下级联，恢复只向上走。
 * 反向级联会把用户单独停用过的子分类一起打开，那是替用户做决定。
 */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryCategoryTest {

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

    private suspend fun isArchived(id: Long): Boolean = db.categoryDao().getById(id)!!.isArchived

    @Test
    fun restoreCategory_restoresAncestorsButLeavesSiblingsArchived() = runTest {
        val parent = db.categoryDao().upsert(
            CategoryEntity(name = "交通", kind = CategoryKind.EXPENSE, sortOrder = 0),
        )
        val metro = db.categoryDao().upsert(
            CategoryEntity(name = "地铁", kind = CategoryKind.EXPENSE, parentId = parent, sortOrder = 0),
        )
        val taxi = db.categoryDao().upsert(
            CategoryEntity(name = "打车", kind = CategoryKind.EXPENSE, parentId = parent, sortOrder = 1),
        )
        // 停用父分类会级联停用两个子分类
        db.categoryDao().archiveWithChildren(parent)
        assertTrue(isArchived(parent))
        assertTrue(isArchived(metro))
        assertTrue(isArchived(taxi))

        repository.restoreCategory(metro).getOrThrow()

        assertFalse("父分类要一起回来，否则是「父停用、子可选」的悬空状态", isArchived(parent))
        assertFalse(isArchived(metro))
        assertTrue("没被恢复的那个子分类不该被顺手打开", isArchived(taxi))
    }

    @Test
    fun restoreCategory_missingCategory_fails() = runTest {
        val result = repository.restoreCategory(999L)

        assertTrue(result.isFailure)
    }
}
