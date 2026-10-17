package com.nimo.ledger.ui.record

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.MainDispatcherRule
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.LastUsedCategory
import com.nimo.ledger.data.local.LastUsedCategoryStore
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionInput
import com.nimo.ledger.domain.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 记一笔「沿用上次分类」（批 D；P3 已由用户拍板）：按当前收支方向预选上次用过的分类，
 * 用一句行内提示说明是哪天用过；用户改选后提示必须消失，编辑旧账时不许被顶掉。
 */
@RunWith(RobolectricTestRunner::class)
class RecordLastUsedCategoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private var accountId = 0L
    private var expenseCategoryId = 0L
    private var incomeCategoryId = 0L

    /** 内存里的"上次分类"：只记最后写进来的一条，够验证读写时机 */
    private class FakeStore(private var lastUsed: LastUsedCategory? = null) : LastUsedCategoryStore {
        var saved: LastUsedCategory? = null

        override suspend fun load(): LastUsedCategory? = lastUsed

        override suspend fun save(lastUsed: LastUsedCategory) {
            saved = lastUsed
            this.lastUsed = lastUsed
        }
    }

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

    private suspend fun seed() {
        accountId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        expenseCategoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, sortOrder = 0))
        incomeCategoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "工资", kind = CategoryKind.INCOME, sortOrder = 0))
    }

    private fun newViewModel(store: LastUsedCategoryStore? = null) =
        RecordViewModel(repository, lastUsedCategoryStore = store)

    private suspend fun RecordViewModel.awaitState(
        predicate: (RecordUiState) -> Boolean,
    ): RecordUiState = uiState.first(predicate)

    private suspend fun RecordViewModel.awaitLoaded(): RecordUiState = awaitState { !it.isLoading }

    private fun daysAgo(days: Long): Long = LocalDate.now().toEpochDay() - days

    @Test
    fun withoutHistory_nothingIsPreselectedAndNoHintShows() = runTest {
        seed()
        val vm = newViewModel(FakeStore())

        vm.awaitLoaded()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.first()
        assertNull("第一次记账不该替用户瞎选分类", state.form.categoryId)
        assertNull(state.lastUsedCategoryHint)
    }

    @Test
    fun lastUsedCategory_isPreselectedWithADayCountHint() = runTest {
        seed()
        val vm = newViewModel(FakeStore(LastUsedCategory(expenseCategoryId, daysAgo(2))))

        val state = vm.awaitState { it.form.categoryId != null }

        assertEquals(expenseCategoryId, state.form.categoryId)
        assertEquals("2 天前用过「餐饮」", state.lastUsedCategoryHint)
    }

    @Test
    fun aCategoryFromTheOtherDirection_isNeitherPreselectedNorHinted() = runTest {
        seed()
        // 上次是收入「工资」，现在停在支出方向：套用过来就会写出"支出 + 工资"
        val vm = newViewModel(FakeStore(LastUsedCategory(incomeCategoryId, daysAgo(0))))

        vm.awaitLoaded()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.first()
        assertNull(state.form.categoryId)
        assertNull(state.lastUsedCategoryHint)
    }

    @Test
    fun switchingDirection_dropsTheHintAlongWithTheCategory() = runTest {
        seed()
        val vm = newViewModel(FakeStore(LastUsedCategory(expenseCategoryId, daysAgo(2))))
        assertEquals("2 天前用过「餐饮」", vm.awaitState { it.form.categoryId == expenseCategoryId }.lastUsedCategoryHint)

        vm.setType(TransactionType.INCOME)
        val switched = vm.awaitState {
            it.form.type == TransactionType.INCOME && it.categories.any { c -> c.id == incomeCategoryId }
        }

        assertNull(switched.form.categoryId)
        assertNull("换了方向还留着上周的餐饮提示，就是在骗用户", switched.lastUsedCategoryHint)
    }

    @Test
    fun pickingAnotherCategory_makesTheHintDisappear() = runTest {
        seed()
        val otherId = db.categoryDao()
            .upsert(CategoryEntity(name = "交通", kind = CategoryKind.EXPENSE, sortOrder = 1))
        val vm = newViewModel(FakeStore(LastUsedCategory(expenseCategoryId, daysAgo(1))))
        vm.awaitState { it.form.categoryId == expenseCategoryId }
        vm.awaitState { it.categories.any { c -> c.id == otherId } }

        vm.setCategory(otherId)
        val state = vm.awaitState { it.form.categoryId == otherId }

        assertNull("提示只在真的沿用上次分类时成立", state.lastUsedCategoryHint)
    }

    @Test
    fun saving_writesBackTheChosenCategoryAndToday() = runTest {
        seed()
        val store = FakeStore()
        val vm = newViewModel(store)
        vm.awaitLoaded()

        vm.setAmount("12.50")
        vm.setCategory(expenseCategoryId)
        vm.save()
        vm.awaitState { it.savedTransactionId != null }
        testScheduler.advanceUntilIdle()

        assertEquals(LastUsedCategory(expenseCategoryId, LocalDate.now().toEpochDay()), store.saved)
    }

    @Test
    fun editingAnOldTransaction_keepsItsOwnCategory() = runTest {
        seed()
        val otherId = db.categoryDao()
            .upsert(CategoryEntity(name = "交通", kind = CategoryKind.EXPENSE, sortOrder = 1))
        val transactionId = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = 1_000L,
                accountId = accountId,
                categoryId = otherId,
                occurredOn = "2026-10-05",
            ),
        ).getOrThrow()

        val store = FakeStore(LastUsedCategory(expenseCategoryId, daysAgo(0)))
        val vm = RecordViewModel(repository, editingId = transactionId, lastUsedCategoryStore = store)
        val state = vm.awaitState { it.form.categoryId != null }

        assertEquals("编辑旧账不能被上次分类顶掉", otherId, state.form.categoryId)
        assertNull(state.lastUsedCategoryHint)
        testScheduler.advanceUntilIdle()
        assertEquals(otherId, vm.uiState.first().form.categoryId)
    }

    @Test
    fun hintText_phrasesTheDayCount() {
        val today = LocalDate.of(2026, 10, 17).toEpochDay()

        assertEquals("今天用过「餐饮」", lastUsedHint(today, "餐饮", today))
        assertEquals("昨天用过「餐饮」", lastUsedHint(today - 1, "餐饮", today))
        assertEquals("3 天前用过「餐饮」", lastUsedHint(today - 3, "餐饮", today))
        // 设备时钟被拨回过（上次用得"在未来"）：不许出现"-2 天前"这种胡话
        assertEquals("今天用过「餐饮」", lastUsedHint(today + 2, "餐饮", today))
    }
}
