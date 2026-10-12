package com.nimo.bookkeeping.ui.record

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.MainDispatcherRule
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.validate.TransactionValidator
import com.nimo.bookkeeping.util.Dates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 记账页 ViewModel（F03）：账户/分类来源、方向切换、保存链路与失败提示。
 * 走真实 Repository + in-memory Room，验证的是端到端行为而不是桩。
 */
@RunWith(RobolectricTestRunner::class)
class RecordViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private var accountId = 0L
    private var expenseCategoryId = 0L
    private var incomeCategoryId = 0L

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

    /** 每个用例的起点：1 个可用账户 + 支出/收入各 1 个分类 */
    private suspend fun seed() {
        accountId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        expenseCategoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, sortOrder = 0))
        incomeCategoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "工资", kind = CategoryKind.INCOME, sortOrder = 0))
    }

    private fun newViewModel() = RecordViewModel(repository)

    private suspend fun RecordViewModel.awaitState(
        predicate: (RecordUiState) -> Boolean,
    ): RecordUiState = uiState.first(predicate)

    private suspend fun RecordViewModel.awaitLoaded(): RecordUiState = awaitState { !it.isLoading }

    private suspend fun transactionCount(): Int = db.transactionDao().count()

    // ---- 初始状态 ----

    @Test
    fun initialState_defaultsToTodayExpenseWithFirstActiveAccount() = runTest {
        seed()
        val vm = newViewModel()

        val state = vm.awaitLoaded()
        assertEquals(TransactionType.EXPENSE, state.form.type)
        assertEquals(Dates.today(), state.form.occurredOn)
        assertEquals("", state.form.amountText)
        assertNull(state.form.categoryId)
        assertNull(state.loadErrorMessage)
        assertEquals(listOf(accountId), state.accounts.map { it.id })
        assertEquals(listOf(expenseCategoryId), state.categories.map { it.id })
        // 账户默认选中第一个可用账户，保证"记一笔" ≤3 次点击
        assertEquals(accountId, state.effectiveAccountId)
    }

    @Test
    fun archivedAccount_isNotOfferedButKeepsHistory() = runTest {
        seed()
        val archived = repository.createAccount("已归档", AccountType.DEBIT, 0L).getOrThrow()
        repository.setAccountArchived(archived, archived = true).getOrThrow()

        val state = newViewModel().awaitLoaded()
        assertEquals(listOf(accountId), state.accounts.map { it.id })
        assertEquals(2, repository.observeAccounts().first().size)
    }

    @Test
    fun noActiveAccount_flagsEmptyState() = runTest {
        // 一个账户都没有：页面应指路到账户管理，而不是给一张存不进去的表单
        expenseCategoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, sortOrder = 0))

        val state = newViewModel().awaitLoaded()
        assertTrue(state.hasNoAccount)
        assertNull(state.effectiveAccountId)
    }

    // ---- 收支方向切换 ----

    @Test
    fun setType_switchesCategorySetAndClearsSelection() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()

        vm.setCategory(expenseCategoryId)
        assertEquals(expenseCategoryId, vm.uiState.first().form.categoryId)

        vm.setType(TransactionType.INCOME)
        val state = vm.awaitState {
            it.form.type == TransactionType.INCOME && it.categories.any { c -> c.id == incomeCategoryId }
        }
        assertEquals(listOf(incomeCategoryId), state.categories.map { it.id })
        assertNull("换方向必须清掉支出分类，避免分类与类型不符", state.form.categoryId)
    }

    // ---- 保存：成功路径 ----

    @Test
    fun save_validExpense_persistsAndEmitsSavedId() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()

        vm.setAmount("12.50")
        vm.setCategory(expenseCategoryId)
        vm.setNote("午餐")
        vm.save()

        val state = vm.awaitState { it.savedTransactionId != null }
        assertNull(state.saveErrorMessage)

        val row = db.transactionDao().getById(state.savedTransactionId!!)!!
        assertEquals(TransactionType.EXPENSE, row.type)
        assertEquals(1_250L, row.amount)
        assertEquals(accountId, row.accountId)
        assertEquals(expenseCategoryId, row.categoryId)
        assertEquals(Dates.today(), row.occurredOn)
        assertEquals("午餐", row.note)
    }

    @Test
    fun save_validIncome_persists() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()

        vm.setType(TransactionType.INCOME)
        vm.awaitState { it.categories.any { c -> c.id == incomeCategoryId } }
        vm.setAmount("5000")
        vm.setCategory(incomeCategoryId)
        vm.save()

        val state = vm.awaitState { it.savedTransactionId != null }
        val row = db.transactionDao().getById(state.savedTransactionId!!)!!
        assertEquals(TransactionType.INCOME, row.type)
        assertEquals(500_000L, row.amount)
    }

    @Test
    fun save_usesFirstActiveAccountWhenUserNeverPicked() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()

        vm.setAmount("1")
        vm.setCategory(expenseCategoryId)
        vm.save()

        val state = vm.awaitState { it.savedTransactionId != null }
        assertEquals(accountId, db.transactionDao().getById(state.savedTransactionId!!)!!.accountId)
    }

    @Test
    fun savedTransaction_appearsInTheFlowTheLedgerListObserves() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()

        val today = Dates.today()
        val tomorrow = java.time.LocalDate.parse(today).plusDays(1).toString()
        assertTrue(repository.observeTransactions(today, tomorrow).first().isEmpty())

        vm.setAmount("12.50")
        vm.setCategory(expenseCategoryId)
        vm.save()
        val state = vm.awaitState { it.savedTransactionId != null }

        // 流水页（F05）订阅的就是这个 Flow：保存后不必手动刷新即可看到新记录
        val listed = repository.observeTransactions(today, tomorrow).first()
        assertEquals(listOf(state.savedTransactionId), listed.map { it.id })
        assertEquals(1_250L, listed.single().amount)
    }

    @Test
    fun consumeSaved_clearsSignal() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.setAmount("1")
        vm.setCategory(expenseCategoryId)
        vm.save()
        vm.awaitState { it.savedTransactionId != null }

        vm.consumeSaved()
        assertNull(vm.awaitState { it.savedTransactionId == null }.savedTransactionId)
    }

    // ---- 保存：被拦住 ----

    @Test
    fun save_blankAmount_reportsErrorAndWritesNothing() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.setCategory(expenseCategoryId)
        vm.save()

        val state = vm.awaitState { it.formErrors.isNotEmpty() }
        assertTrue(state.formErrors.contains(RecordFormError.AmountBlank))
        assertEquals(0, transactionCount())
    }

    @Test
    fun save_missingCategory_reportsErrorAndWritesNothing() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.setAmount("20")
        vm.save()

        val state = vm.awaitState { it.formErrors.isNotEmpty() }
        assertEquals(listOf(RecordFormError.CategoryRequired), state.formErrors)
        assertEquals(0, transactionCount())
    }

    @Test
    fun save_invalidAmountFormat_reportsErrorAndWritesNothing() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.setAmount("1.234")
        vm.setCategory(expenseCategoryId)
        vm.save()

        val state = vm.awaitState { it.formErrors.isNotEmpty() }
        assertEquals(listOf(RecordFormError.AmountInvalid), state.formErrors)
        assertEquals(0, transactionCount())
    }

    @Test
    fun editingAfterFailedSubmit_clearsStaleErrors() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.save()
        vm.awaitState { it.formErrors.isNotEmpty() }

        vm.setAmount("20")
        assertTrue(vm.awaitState { it.formErrors.isEmpty() }.formErrors.isEmpty())
    }

    @Test
    fun save_rejectedByRepository_showsChineseMessageAndWritesNothing() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.setAmount("20")
        vm.setCategory(expenseCategoryId)
        vm.setAccount(accountId)

        // 选完之后账户被归档：UI 第一道防线看不出来，由 Repository 第二道挡住
        repository.setAccountArchived(accountId, archived = true).getOrThrow()
        vm.save()

        val state = vm.awaitState { it.saveErrorMessage != null }
        assertEquals("该账户已归档，不能再记新账", state.saveErrorMessage)
        assertEquals(0, transactionCount())
    }

    // ---- 备注上限 ----

    @Test
    fun setNote_truncatesAtLimit() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()

        vm.setNote("字".repeat(TransactionValidator.MAX_NOTE_LENGTH + 50))
        val state = vm.awaitState { it.form.note.length == TransactionValidator.MAX_NOTE_LENGTH }
        assertEquals(TransactionValidator.MAX_NOTE_LENGTH, state.form.note.length)
    }
}
