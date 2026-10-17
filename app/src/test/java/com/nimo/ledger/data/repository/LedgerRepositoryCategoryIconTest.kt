package com.nimo.ledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.domain.model.CategoryKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 分类图标（C-6 / 批 F 的 F-3）：新建带 emoji、改名保留、改名换图、空串清掉。
 * 走真实 Repository + in-memory Room，钉的是「icon 真的写进库、真的读得回」。
 */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryCategoryIconTest {

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

    private suspend fun iconOf(id: Long): String? = db.categoryDao().getById(id)?.icon

    @Test
    fun createCategory_storesTheEmoji() = runTest {
        val id = repository.createCategory("咖啡", CategoryKind.EXPENSE, icon = "☕").getOrThrow()

        assertEquals("☕", iconOf(id))
    }

    @Test
    fun createCategory_withoutAnIcon_storesNull() = runTest {
        val id = repository.createCategory("咖啡", CategoryKind.EXPENSE).getOrThrow()

        assertNull(iconOf(id))
    }

    @Test
    fun createCategory_withABlankIcon_storesNull() = runTest {
        val id = repository.createCategory("咖啡", CategoryKind.EXPENSE, icon = "   ").getOrThrow()

        assertNull(iconOf(id))
    }

    @Test
    fun renamingWithoutAnIcon_keepsTheOldEmoji() = runTest {
        val id = repository.createCategory("咖啡", CategoryKind.EXPENSE, icon = "☕").getOrThrow()

        repository.renameCategory(id, "咖啡饮品").getOrThrow()

        assertEquals("咖啡饮品", db.categoryDao().getById(id)?.name)
        assertEquals("☕", iconOf(id))
    }

    @Test
    fun renamingWithAnIcon_replacesIt() = runTest {
        val id = repository.createCategory("咖啡", CategoryKind.EXPENSE, icon = "☕").getOrThrow()

        repository.renameCategory(id, "咖啡", icon = "🍵").getOrThrow()

        assertEquals("🍵", iconOf(id))
    }

    @Test
    fun renamingWithABlankIcon_clearsIt() = runTest {
        val id = repository.createCategory("咖啡", CategoryKind.EXPENSE, icon = "☕").getOrThrow()

        repository.renameCategory(id, "咖啡", icon = "").getOrThrow()

        assertNull(iconOf(id))
    }
}
