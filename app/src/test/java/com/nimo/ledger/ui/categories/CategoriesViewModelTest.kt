package com.nimo.ledger.ui.categories

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.MainDispatcherRule
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.CategoryKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 分类页的摊平逻辑：两棵树互相独立、一级紧跟自己的二级、上下移标志按同级位置算。
 * 这些是页面能画对的前提，用真实 Repository + in-memory Room 跑。
 */
@RunWith(RobolectricTestRunner::class)
class CategoriesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
        isArchived: Boolean = false,
    ): Long = db.categoryDao().insert(
        CategoryEntity(name = name, kind = kind, parentId = parentId, sortOrder = sortOrder, isArchived = isArchived),
    )

    private suspend fun loadedState(): CategoriesUiState =
        CategoriesViewModel(repository).uiState.first { !it.isLoading }

    @Test
    fun sections_areExpenseThenIncome_andKeepChildrenUnderTheirParent() = runTest {
        val food = seed("餐饮", sortOrder = 0)
        seed("地铁", parentId = food, sortOrder = 0)
        seed("工资", kind = CategoryKind.INCOME, sortOrder = 0)

        val state = loadedState()

        assertEquals(listOf(CategoryKind.EXPENSE, CategoryKind.INCOME), state.sections.map { it.kind })
        assertEquals(listOf("支出分类", "收入分类"), state.sections.map { it.title })
        assertEquals(listOf("餐饮", "地铁"), state.sections[0].rows.map { it.name })
        assertEquals(listOf(0, 1), state.sections[0].rows.map { it.depth })
        assertEquals(listOf("工资"), state.sections[1].rows.map { it.name })
    }

    @Test
    fun rows_followSortOrder_andFlagTheMoveEdges() = runTest {
        // 故意乱序入库：页面顺序只认 sortOrder，不认插入顺序
        seed("丙", sortOrder = 2)
        seed("甲", sortOrder = 0)
        seed("乙", sortOrder = 1)

        val rows = loadedState().sections[0].rows

        assertEquals(listOf("甲", "乙", "丙"), rows.map { it.name })
        assertEquals(listOf(false, true, true), rows.map { it.canMoveUp })
        assertEquals(listOf(true, true, false), rows.map { it.canMoveDown })
    }

    @Test
    fun moveFlags_areScopedToSiblingsNotToTheWholeSection() = runTest {
        val food = seed("餐饮", sortOrder = 0)
        seed("地铁", parentId = food, sortOrder = 0)
        seed("公交", parentId = food, sortOrder = 1)
        seed("交通", sortOrder = 1)

        val rows = loadedState().sections[0].rows

        // 一级只有「餐饮」和「交通」两个：餐饮不能上移但能下移；交通反过来
        assertEquals(listOf("餐饮", "地铁", "公交", "交通"), rows.map { it.name })
        assertEquals(listOf(false, false, true, true), rows.map { it.canMoveUp })
        assertEquals(listOf(true, true, false, false), rows.map { it.canMoveDown })
    }

    @Test
    fun archivedCategory_staysInTheListSoItCanBeRestored() = runTest {
        val food = seed("餐饮", sortOrder = 0)
        seed("地铁", parentId = food, sortOrder = 0)

        repository.archiveCategory(food).getOrThrow()

        // C-4：已停用的分类不再和在用的混排，但必须还能展开找到（否则永远没法恢复）
        val section = loadedState().sections[0]
        assertEquals(emptyList<String>(), section.rows.map { it.name })
        assertEquals(listOf("餐饮", "地铁"), section.archivedRows.map { it.name })
        assertTrue(section.archivedRows.all { it.isArchived })
    }

    @Test
    fun childWhoseParentIsMissing_isStillListed() = runTest {
        // 绕过校验直插一条孤儿（导入覆盖 / 老数据），页面不能把它藏起来
        seed("孤儿分类", parentId = 999L, sortOrder = 0)

        val rows = loadedState().sections[0].rows

        assertEquals(listOf("孤儿分类"), rows.map { it.name })
        assertEquals(1, rows[0].depth)
        assertEquals(999L, rows[0].parentId)
    }

    @Test
    fun emptyDatabase_hasTwoEmptySections() = runTest {
        val state = loadedState()

        assertEquals(2, state.sections.size)
        assertTrue(state.sections.all { it.rows.isEmpty() })
        assertTrue(state.isEmpty)
        assertFalse(state.errorMessage != null)
    }
}
