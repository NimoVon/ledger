package com.nimo.bookkeeping.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.MoveDirection
import com.nimo.bookkeeping.domain.validate.CategoryValidationError
import com.nimo.bookkeeping.domain.validate.CategoryValidationException
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
 * 分类写入（F02）：新建排到同级末尾、改名校验、停用级联、上下移排序。
 * 走真实 Repository + in-memory Room，规则打的是端到端的行为。
 */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryCategoryWriteTest {

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

    private suspend fun expenses(): List<CategoryEntity> =
        db.categoryDao().listAll().filter { it.kind == CategoryKind.EXPENSE }

    private suspend fun row(id: Long): CategoryEntity = db.categoryDao().getById(id)!!

    private fun Throwable?.isDuplicateNameError(): Boolean =
        (this as? CategoryValidationException)?.errors?.contains(CategoryValidationError.DuplicateName) == true

    @Test
    fun createCategory_appendsToEndOfSiblings() = runTest {
        seed("餐饮", sortOrder = 0)
        seed("购物", sortOrder = 1)

        val id = repository.createCategory("娱乐", CategoryKind.EXPENSE).getOrThrow()

        assertEquals(2, row(id).sortOrder)
        assertEquals(listOf("餐饮", "购物", "娱乐"), expenses().sortedBy { it.sortOrder }.map { it.name })
    }

    @Test
    fun createCategory_appendsInsideTheChosenParentNotAtTopLevel() = runTest {
        val parent = seed("交通", sortOrder = 0)
        seed("地铁", parentId = parent, sortOrder = 0)

        val id = repository.createCategory("打车", CategoryKind.EXPENSE, parentId = parent).getOrThrow()

        assertEquals(parent, row(id).parentId)
        assertEquals(1, row(id).sortOrder)
    }

    @Test
    fun createCategory_rejectsDuplicateNameInSameLevel() = runTest {
        seed("餐饮", sortOrder = 0)

        val result = repository.createCategory("餐饮", CategoryKind.EXPENSE)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull().isDuplicateNameError())
        assertEquals(1, db.categoryDao().count())
    }

    @Test
    fun createCategory_allowsSameNameInTheOtherTree() = runTest {
        seed("餐饮", kind = CategoryKind.EXPENSE)

        // 支出树有「餐饮」，收入树再建一个不算重名
        val id = repository.createCategory("餐饮", CategoryKind.INCOME).getOrThrow()

        assertEquals(CategoryKind.INCOME, row(id).kind)
    }

    @Test
    fun createCategory_rejectsThirdLevel() = runTest {
        val parent = seed("交通", sortOrder = 0)
        val child = seed("地铁", parentId = parent, sortOrder = 0)

        val result = repository.createCategory("地铁补票", CategoryKind.EXPENSE, parentId = child)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull() is CategoryValidationException &&
                (result.exceptionOrNull() as CategoryValidationException)
                    .errors.contains(CategoryValidationError.ParentNotTopLevel),
        )
        assertEquals(2, db.categoryDao().count())
    }

    @Test
    fun createCategory_rejectsArchivedParent() = runTest {
        val parent = seed("交通", sortOrder = 0)
        repository.archiveCategory(parent).getOrThrow()

        val result = repository.createCategory("打车", CategoryKind.EXPENSE, parentId = parent)

        assertTrue(result.isFailure)
    }

    @Test
    fun renameCategory_keepsParentKindAndOrder() = runTest {
        val parent = seed("交通", sortOrder = 3)
        val child = seed("地铁", parentId = parent, sortOrder = 7)

        repository.renameCategory(child, " 磁悬浮 ").getOrThrow()

        val updated = row(child)
        assertEquals("磁悬浮", updated.name)
        assertEquals(parent, updated.parentId)
        assertEquals(7, updated.sortOrder)
    }

    @Test
    fun renameCategory_allowsRenamingToItsOwnName_butNotToASiblings() = runTest {
        seed("餐饮", sortOrder = 0)
        val shopping = seed("购物", sortOrder = 1)

        // 自己叫自己：不算重名
        assertTrue(repository.renameCategory(shopping, "购物").isSuccess)
        // 撞到兄弟：拦下来，且库里没被改动
        val result = repository.renameCategory(shopping, "餐饮")
        assertTrue(result.isFailure)
        assertEquals("购物", row(shopping).name)
    }

    @Test
    fun archiveCategory_cascadesToChildrenOnly() = runTest {
        val parent = seed("交通", sortOrder = 0)
        val child = seed("地铁", parentId = parent, sortOrder = 0)

        repository.archiveCategory(parent).getOrThrow()

        assertTrue(row(parent).isArchived)
        assertTrue("停用父分类要级联停用子分类，否则会出现「父停用、子可选」", row(child).isArchived)
        // 停用不删除：行还在，只是标记
        assertEquals(2, db.categoryDao().count())
    }

    @Test
    fun moveCategory_upSwapsWithThePreviousSibling() = runTest {
        seed("餐饮", sortOrder = 0)
        val shopping = seed("购物", sortOrder = 1)
        seed("娱乐", sortOrder = 2)

        repository.moveCategory(shopping, MoveDirection.UP).getOrThrow()

        assertEquals(listOf("购物", "餐饮", "娱乐"), expenses().sortedBy { it.sortOrder }.map { it.name })
        assertEquals(listOf(0, 1, 2), expenses().sortedBy { it.sortOrder }.map { it.sortOrder })
    }

    @Test
    fun moveCategory_downSwapsWithTheNextSibling() = runTest {
        val food = seed("餐饮", sortOrder = 0)
        seed("购物", sortOrder = 1)

        repository.moveCategory(food, MoveDirection.DOWN).getOrThrow()

        assertEquals(listOf("购物", "餐饮"), expenses().sortedBy { it.sortOrder }.map { it.name })
    }

    @Test
    fun moveCategory_atTheEdge_doesNothing() = runTest {
        val first = seed("餐饮", sortOrder = 0)
        val last = seed("购物", sortOrder = 1)

        repository.moveCategory(first, MoveDirection.UP).getOrThrow()
        repository.moveCategory(last, MoveDirection.DOWN).getOrThrow()

        assertEquals(listOf("餐饮", "购物"), expenses().sortedBy { it.sortOrder }.map { it.name })
    }

    @Test
    fun moveCategory_onlyTouchesItsOwnSiblings() = runTest {
        val transport = seed("交通", sortOrder = 0)
        val food = seed("餐饮", sortOrder = 1)
        seed("地铁", parentId = transport, sortOrder = 0)
        val bus = seed("公交", parentId = transport, sortOrder = 1)

        repository.moveCategory(bus, MoveDirection.UP).getOrThrow()

        // 子分类内部换了顺序，一级分类的顺序一个字都没动
        assertEquals(0, row(transport).sortOrder)
        assertEquals(1, row(food).sortOrder)
        assertEquals(
            listOf("公交", "地铁"),
            db.categoryDao().listSiblings(CategoryKind.EXPENSE, transport).map { it.name },
        )
    }

    @Test
    fun moveCategory_reindexesDuplicateSortOrders() = runTest {
        // 备份/CSV 导入可能带进重复的 sortOrder：交换两个相等的值等于没动，
        // 因此实现是整组重排（顺带把历史脏顺序理顺）
        val a = seed("甲", sortOrder = 0)
        val b = seed("乙", sortOrder = 0)
        val c = seed("丙", sortOrder = 0)

        repository.moveCategory(c, MoveDirection.UP).getOrThrow()

        assertEquals(listOf(0, 1, 2), listOf(row(a), row(c), row(b)).map { it.sortOrder })
        assertEquals(
            listOf("甲", "丙", "乙"),
            db.categoryDao().listSiblings(CategoryKind.EXPENSE, null).map { it.name },
        )
    }

    @Test
    fun moveCategory_missingCategory_fails() = runTest {
        assertTrue(repository.moveCategory(999L, MoveDirection.UP).isFailure)
    }

    @Test
    fun createCategory_archivedSiblingStillBlocksTheSameName() = runTest {
        val food = seed("餐饮", sortOrder = 0)
        repository.archiveCategory(food).getOrThrow()

        // 停用的兄弟也算重名：恢复之后就会撞上
        val result = repository.createCategory("餐饮", CategoryKind.EXPENSE)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull().isDuplicateNameError())
        assertNull(row(food).parentId)
    }

    @Test
    fun restoreCategory_afterArchive_keepsNameAndOrder() = runTest {
        val parent = seed("交通", sortOrder = 0)
        val child = seed("地铁", parentId = parent, sortOrder = 5)
        repository.archiveCategory(parent).getOrThrow()

        repository.restoreCategory(child).getOrThrow()

        val restored = row(child)
        assertFalse(restored.isArchived)
        assertEquals("地铁", restored.name)
        assertEquals(5, restored.sortOrder)
    }
}
