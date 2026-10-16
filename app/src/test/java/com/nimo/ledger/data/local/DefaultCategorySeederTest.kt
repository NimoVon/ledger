package com.nimo.ledger.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.domain.model.CategoryKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 首次启动播种（附.4）：只播一次；用户把分类删光之后**不再自动复活**（那才是"删除"）。
 * 走真实 in-memory Room + 真实 DataStore 文件，规则打的是行为。
 */
@RunWith(RobolectricTestRunner::class)
class DefaultCategorySeederTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
    }

    @After
    fun teardown() {
        db.close()
        scopes.forEach { it.cancel() }
    }

    private fun seedStore(): DefaultCategorySeedStore {
        val scope = CoroutineScope(Job() + Dispatchers.IO).also { scopes += it }
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.root, "ledger-category-seed-" + scopes.size + ".preferences_pb")
        }
        return DefaultCategorySeedStore(dataStore)
    }

    @Test
    fun aFreshInstallGetsEveryPresetCategory() = runTest {
        val store = seedStore()

        DefaultCategorySeeder.seedIfNeeded(store, db.categoryDao())

        assertEquals(DefaultCategories.all().size, db.categoryDao().count())
        // 两棵树都补齐，sortOrder 从 0 连续编号（排序键与页面口径一致）
        val all = db.categoryDao().listAll().sortedBy { it.sortOrder }
        assertEquals(
            DefaultCategories.all().map { it.kind }.toSet(),
            all.map { it.kind }.toSet(),
        )
        assertEquals(listOf(0, 1, 2), all.take(3).map { it.sortOrder })
        assertTrue(store.hasSeeded())
    }

    @Test
    fun aNonEmptyTableIsLeftAlone_butStillGetsMarked() = runTest {
        // 例如刚恢复完备份：分类该由备份说了算，不再往里塞默认项
        db.categoryDao().insert(CategoryEntity(name = "自建", kind = CategoryKind.EXPENSE, sortOrder = 0))
        val store = seedStore()

        DefaultCategorySeeder.seedIfNeeded(store, db.categoryDao())

        assertEquals(listOf("自建"), db.categoryDao().listAll().map { it.name })
        assertTrue(store.hasSeeded())
    }

    @Test
    fun wipingEveryCategoryAfterSeeding_doesNotBringThemBack() = runTest {
        val store = seedStore()
        DefaultCategorySeeder.seedIfNeeded(store, db.categoryDao())
        assertEquals(DefaultCategories.all().size, db.categoryDao().count())

        // 用户在分类管理页把分类删光（与逐条零引用删除等价）
        db.categoryDao().listAll().forEach { db.categoryDao().deleteById(it.id) }
        assertEquals(0, db.categoryDao().count())

        DefaultCategorySeeder.seedIfNeeded(store, db.categoryDao())

        assertEquals("删光就是真删光，不能再自动播种", 0, db.categoryDao().count())
    }

    @Test
    fun seedingTwiceOnAFreshInstallInsertsNothingNew() = runTest {
        val store = seedStore()

        DefaultCategorySeeder.seedIfNeeded(store, db.categoryDao())
        DefaultCategorySeeder.seedIfNeeded(store, db.categoryDao())

        assertEquals(DefaultCategories.all().size, db.categoryDao().count())
    }
}
