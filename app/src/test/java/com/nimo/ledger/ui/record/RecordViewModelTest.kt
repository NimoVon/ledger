package com.nimo.ledger.ui.record

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.MainDispatcherRule
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionInput
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.validate.TransactionValidator
import com.nimo.ledger.util.Dates
import com.nimo.ledger.util.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun setAmount_normalizesFullWidthDigitsAndPeriodFromTheChineseKeyboard() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()

        // 真机实测（§22.5）：中文输入法把 "123.45" 打成 "123。45"，复核行不出现、保存报格式错。
        // 归一化必须发生在输入口——字段本身就得是半角，不能只在解析时宽容。
        vm.setAmount("１２３。４５")
        vm.setCategory(expenseCategoryId)
        vm.setAccount(accountId)

        val state = vm.awaitState { it.form.amountText.isNotEmpty() }
        assertEquals("123.45", state.form.amountText)
        // 校验与复核行走的是同一条 parseCents：字段归一化后表单整体可提交
        val outcome = state.form.toOutcome()
        val errors = (outcome as? RecordFormOutcome.Invalid)?.errors
        assertTrue("归一化后不该再有校验错误，实际：$errors", outcome is RecordFormOutcome.Valid)
        assertEquals(12_345L, (outcome as RecordFormOutcome.Valid).input.amountCents)
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

    // ---- 编辑模式（点流水行进来）：预填整笔、整行替换、删除入口 ----

    private suspend fun existingExpense(
        amount: Long = 1_250L,
        date: String = "2026-10-05",
        note: String? = "午餐",
        account: Long = accountId,
    ): Long = repository.addTransaction(
        TransactionInput(
            type = TransactionType.EXPENSE,
            amountCents = amount,
            accountId = account,
            categoryId = expenseCategoryId,
            occurredOn = date,
            note = note,
        ),
    ).getOrThrow()

    private fun editingViewModel(id: Long) = RecordViewModel(repository, id)

    @Test
    fun editMode_prefillsEveryFieldFromTheExistingRow() = runTest {
        seed()
        val id = existingExpense()

        val state = editingViewModel(id).awaitLoaded()

        assertTrue(state.isEditing)
        assertEquals(id, state.editingTransaction?.id)
        assertEquals(TransactionType.EXPENSE, state.form.type)
        // 分 → 元文本：整数运算，不经过 Float/Double
        assertEquals("12.50", state.form.amountText)
        assertEquals(accountId, state.form.accountId)
        assertEquals(expenseCategoryId, state.form.categoryId)
        assertEquals("2026-10-05", state.form.occurredOn)
        assertEquals("午餐", state.form.note)
        assertEquals(accountId, state.effectiveAccountId)
        assertFalse(state.originalAccountUnavailable)
        assertFalse(state.originalCategoryUnavailable)
    }

    @Test
    fun editMode_save_replacesTheRowKeepingIdAndCreatedAt() = runTest {
        seed()
        val id = existingExpense()
        val original = db.transactionDao().getById(id)!!
        val vm = editingViewModel(id)
        vm.awaitLoaded()

        vm.setAmount("99.99")
        vm.setNote("改过了")
        vm.save()

        val state = vm.awaitState { it.savedTransactionId != null }
        assertEquals("编辑不该多出一条记录", 1, transactionCount())
        assertEquals(id, state.savedTransactionId)
        assertEquals(id, state.editingTransaction?.id)

        val updated = db.transactionDao().getById(id)!!
        assertEquals(9_999L, updated.amount)
        assertEquals("改过了", updated.note)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(original.occurredOn, updated.occurredOn)
        assertEquals(original.type, updated.type)
    }

    @Test
    fun editMode_doesNotSilentlyMoveTheRowToAnotherAccount() = runTest {
        seed()
        val other = repository.createAccount("招行卡", AccountType.DEBIT, 0L).getOrThrow()
        val id = existingExpense()
        // 记录还在，但它的账户被归档了：历史照常显示，该账户不再出现在选择器里（M2 验收）
        repository.setAccountArchived(accountId, archived = true).getOrThrow()

        val vm = editingViewModel(id)
        val state = vm.awaitLoaded()
        assertEquals(accountId, state.form.accountId)
        assertEquals(accountId, state.effectiveAccountId)
        assertFalse("归档账户不能出现在记账页选择器", state.accounts.any { it.id == accountId })
        assertTrue("原账户不可用要明确提示，别让用户存不进去还找不到原因", state.originalAccountUnavailable)

        // 直接保存被第二道防线挡住，且不会"顺手"改到别的账户上
        vm.save()
        val blocked = vm.awaitState { it.saveErrorMessage != null }
        assertEquals("该账户已归档，不能再记新账", blocked.saveErrorMessage)
        assertEquals(accountId, db.transactionDao().getById(id)!!.accountId)

        // 重新选一个可用账户就能存下去：不是死胡同
        vm.setAccount(other)
        vm.save()
        vm.awaitState { it.savedTransactionId != null }
        assertEquals(other, db.transactionDao().getById(id)!!.accountId)
    }

    @Test
    fun editMode_archivedAccount_isNamedSoTheUserKnowsWhatIsBeingEdited() = runTest {
        seed()
        val id = existingExpense()
        repository.setAccountArchived(accountId, archived = true).getOrThrow()

        val state = editingViewModel(id).awaitLoaded()

        assertTrue(state.originalAccountUnavailable)
        // 归档账户不在选择器里，但页面必须能说出这笔记在哪个账户上
        assertEquals("现金", state.originalAccountName)
        // "现金"是当时唯一的账户：accounts 为空，但编辑模式绝不能被"还没有可用账户"拦掉，
        // 否则表单（连带"恢复账户并编辑"入口）永远不出现——真机验证时踩到过这个坑
        assertTrue(state.accounts.isEmpty())
        assertFalse(state.hasNoAccount)
    }

    @Test
    fun editMode_restoreOriginalAccount_keepsTheRowWhereItWasAndUnblocksSaving() = runTest {
        seed()
        val id = existingExpense()
        repository.setAccountArchived(accountId, archived = true).getOrThrow()
        val vm = editingViewModel(id)
        vm.awaitLoaded()

        vm.setAmount("66.00")
        vm.restoreOriginalAccount()

        // 账户回到选择器并弹回原值选中、提示消失——不必把这一笔挪到别的账户。
        // 等待条件必须和下面的断言同口径：只等"账户回到列表"会在负载下抢在
        // submit.isRestoringAccount 复位之前返回，于是后面的 assertFalse 假失败。
        val restored = vm.awaitState {
            !it.isRestoringAccount && !it.originalAccountUnavailable &&
                it.accounts.any { account -> account.id == accountId }
        }
        assertEquals(accountId, restored.effectiveAccountId)
        assertNull(restored.originalAccountName)
        assertFalse(restored.isRestoringAccount)
        assertFalse("账户本身要真的被恢复", db.accountDao().getById(accountId)!!.isArchived)

        vm.save()
        vm.awaitState { it.savedTransactionId != null }
        val saved = db.transactionDao().getById(id)!!
        assertEquals("归属不该被改掉", accountId, saved.accountId)
        assertEquals(6_600L, saved.amount)
        assertEquals(1, transactionCount())
    }

    @Test
    fun editMode_archivedCategory_isNamedSoTheUserKnowsWhichOneIsOut() = runTest {
        seed()
        val id = existingExpense()
        db.categoryDao().archiveWithChildren(expenseCategoryId)

        val state = editingViewModel(id).awaitLoaded()

        assertTrue(state.originalCategoryUnavailable)
        // 停用分类不在选择器里，但页面得说得出这一笔原本用的是哪个分类
        assertEquals("餐饮", state.originalCategoryName)
    }

    @Test
    fun editMode_restoreOriginalCategory_keepsTheRowWhereItWasAndUnblocksSaving() = runTest {
        seed()
        val id = existingExpense()
        db.categoryDao().archiveWithChildren(expenseCategoryId)
        val vm = editingViewModel(id)
        vm.awaitLoaded()

        vm.restoreOriginalCategory()

        // 分类回到选择器、原值重新选中、提示消失——不必把这一笔挪到别的分类上。
        // 同上：分类热流先回列表、isRestoringCategory 还没复位的那一帧不能算"好了"。
        val restored = vm.awaitState {
            !it.isRestoringCategory && !it.originalCategoryUnavailable &&
                it.categories.any { c -> c.id == expenseCategoryId }
        }
        assertEquals(expenseCategoryId, restored.form.categoryId)
        assertNull(restored.originalCategoryName)
        assertFalse(restored.isRestoringCategory)
        assertFalse("分类要真的被写回未停用", db.categoryDao().getById(expenseCategoryId)!!.isArchived)

        vm.save()
        vm.awaitState { it.savedTransactionId != null }
        val saved = db.transactionDao().getById(id)!!
        assertEquals("分类不该被改掉", expenseCategoryId, saved.categoryId)
        assertEquals(1, transactionCount())
    }

    @Test
    fun editMode_archivedCategory_isFlaggedForReselection() = runTest {
        seed()
        val id = existingExpense()
        val otherCategory = db.categoryDao()
            .upsert(CategoryEntity(name = "交通", kind = CategoryKind.EXPENSE, sortOrder = 1))
        db.categoryDao().archiveWithChildren(expenseCategoryId)

        val vm = editingViewModel(id)
        val state = vm.awaitState { it.originalCategoryUnavailable }
        assertEquals(expenseCategoryId, state.form.categoryId)
        assertFalse(state.categories.any { it.id == expenseCategoryId })

        vm.save()
        val blocked = vm.awaitState { it.saveErrorMessage != null }
        assertEquals("该分类已停用，请重新选择", blocked.saveErrorMessage)

        vm.setCategory(otherCategory)
        vm.save()
        vm.awaitState { it.savedTransactionId != null }
        assertEquals(otherCategory, db.transactionDao().getById(id)!!.categoryId)
    }

    @Test
    fun editMode_deleteRequest_carriesTheWholeRowAndWritesNothing() = runTest {
        seed()
        val id = existingExpense(note = "午餐")
        val vm = editingViewModel(id)
        vm.awaitLoaded()

        vm.requestDelete()

        val state = vm.awaitState { it.deleteRequested != null }
        // 整笔都要交出去：延迟删除超时后要按这一份原样落库
        assertEquals(id, state.deleteRequested?.id)
        assertEquals(TransactionType.EXPENSE, state.deleteRequested?.type)
        assertEquals(1_250L, state.deleteRequested?.amount)
        assertEquals(accountId, state.deleteRequested?.accountId)
        assertEquals(expenseCategoryId, state.deleteRequested?.categoryId)
        assertEquals("2026-10-05", state.deleteRequested?.occurredOn)
        assertEquals("午餐", state.deleteRequested?.note)
        // 页面只交出去，不自己删：物理删除交给 PendingTransactionDeletion（计划 9.2）
        assertEquals(1, transactionCount())

        vm.consumeDeleteRequest()
        assertNull(vm.awaitState { it.deleteRequested == null }.deleteRequested)
    }

    @Test
    fun newRecordMode_offersNoDeleteEntryAndDoesNothingOnRequest() = runTest {
        seed()
        val vm = newViewModel()
        val state = vm.awaitLoaded()

        assertFalse(state.isEditing)
        assertNull(state.editingTransaction)
        assertNull(state.deleteRequested)

        // 没有编辑对象时点删除是空操作，不会误删任何东西
        vm.requestDelete()
        assertNull(vm.awaitState { !it.isLoading }.deleteRequested)
        assertEquals(0, transactionCount())
    }

    @Test
    fun editMode_missingRow_reportsAReadableError() = runTest {
        seed()

        val state = editingViewModel(999_999L).awaitLoaded()

        assertEquals("这笔记录不存在，可能已被删除", state.loadErrorMessage)
        assertNull(state.editingTransaction)
        assertFalse(state.isEditingUnsupportedType)
    }

    @Test
    fun editMode_transferAndBalanceAdjust_areNotEditableOnThisPage() = runTest {
        seed()
        val cardId = repository.createAccount("招行卡", AccountType.DEBIT, 0L).getOrThrow()
        val transferId = repository.addTransaction(
            TransactionInput(
                type = TransactionType.TRANSFER,
                amountCents = 10_000L,
                accountId = accountId,
                toAccountId = cardId,
                occurredOn = "2026-10-05",
            ),
        ).getOrThrow()
        val adjustId = repository.addTransaction(
            TransactionInput(
                type = TransactionType.BALANCE_ADJUST,
                amountCents = -500L,
                accountId = accountId,
                occurredOn = "2026-10-05",
            ),
        ).getOrThrow()

        // 转账（F04）与余额调整（P1）有各自入口：硬套收支表单会写出半截数据
        assertTrue(editingViewModel(transferId).awaitLoaded().isEditingUnsupportedType)
        assertTrue(editingViewModel(adjustId).awaitLoaded().isEditingUnsupportedType)
    }

    // ---- 页面该画哪一屏：唯一的展示决策点（防止"空状态把整张表单顶掉"复发）----

    @Test
    fun screen_isLoadingBeforeAnythingIsRead() {
        // 组合流还没发出第一帧：只该转圈，不能先闪一屏"还没有可用账户"
        assertEquals(RecordScreenState.Loading, RecordUiState().screen)
    }

    @Test
    fun screen_isFormOnANormalRecordPage() = runTest {
        seed()

        assertEquals(RecordScreenState.Form, newViewModel().awaitLoaded().screen)
    }

    @Test
    fun screen_isNoAccountWhenThereIsNoUsableAccount() = runTest {
        expenseCategoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, sortOrder = 0))

        assertEquals(RecordScreenState.NoAccount, newViewModel().awaitLoaded().screen)
    }

    @Test
    fun screen_staysOnTheFormWhenEditingARowOnAnArchivedAccount() = runTest {
        // 真机踩过的坑：唯一账户被归档后编辑这一笔，整张表单（连同"恢复账户并编辑"）被空状态顶掉，
        // 用户没有任何出路。编辑模式必须落在 Form。
        seed()
        val id = existingExpense(amount = 60_000L)
        repository.setAccountArchived(accountId, archived = true).getOrThrow()

        val state = editingViewModel(id).awaitLoaded()

        assertTrue(state.accounts.isEmpty())
        assertEquals(RecordScreenState.Form, state.screen)
    }

    @Test
    fun screen_isLoadErrorWhenTheEditedRowIsGone() = runTest {
        seed()

        assertEquals(RecordScreenState.LoadError, editingViewModel(999_999L).awaitLoaded().screen)
    }

    @Test
    fun screen_isUnsupportedTypeForTransferAndBalanceAdjust() = runTest {
        seed()
        val cardId = repository.createAccount("招行卡", AccountType.DEBIT, 0L).getOrThrow()
        val transferId = repository.addTransaction(
            TransactionInput(
                type = TransactionType.TRANSFER,
                amountCents = 10_000L,
                accountId = accountId,
                toAccountId = cardId,
                occurredOn = "2026-10-05",
            ),
        ).getOrThrow()

        assertEquals(
            RecordScreenState.UnsupportedType,
            editingViewModel(transferId).awaitLoaded().screen,
        )
    }

    // ---- 内联新建分类（附.1 R3）：记账记到一半也能加分类 ----

    @Test
    fun createCategory_addsItToTheCurrentTreeAndSelectsIt() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()

        vm.createCategory("咖啡")

        // 建好就选中：用户是"记这一笔时缺分类"，不该再让他回去点一下
        val state = vm.awaitState { it.createdCategoryId != null && it.form.categoryId == it.createdCategoryId }
        val created = db.categoryDao().getById(state.createdCategoryId!!)!!
        assertEquals("咖啡", created.name)
        assertEquals(CategoryKind.EXPENSE, created.kind)
        assertNull(created.parentId)
        assertNull(state.categoryCreateErrorMessage)
        assertFalse(state.isCreatingCategory)
        // 新分类立刻出现在选择器里（分类流是 Room 推的，等它到位再断言）
        val withNewCategory = vm.awaitState { it.categories.any { row -> row.id == created.id } }
        assertEquals(
            listOf(expenseCategoryId, created.id),
            withNewCategory.categories.map { it.id }.sorted(),
        )
    }

    @Test
    fun createCategory_followsTheCurrentDirection() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()

        vm.setType(TransactionType.INCOME)
        vm.createCategory("年终奖")

        val state = vm.awaitState { it.createdCategoryId != null }
        assertEquals(CategoryKind.INCOME, db.categoryDao().getById(state.createdCategoryId!!)!!.kind)
    }

    @Test
    fun createCategory_withADuplicateName_reportsItAndKeepsTheFormAsIs() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()

        vm.createCategory("餐饮")

        val state = vm.awaitState { it.categoryCreateErrorMessage != null }
        assertEquals("同一层级下已经有同名分类", state.categoryCreateErrorMessage)
        assertNull(state.createdCategoryId)
        assertNull(state.form.categoryId)
        assertEquals(2, db.categoryDao().count())
    }

    @Test
    fun consumeCreatedCategory_clearsTheOneShotSignalAndTheError() = runTest {
        seed()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.createCategory("咖啡")
        val created = vm.awaitState { it.createdCategoryId != null }
        assertNull(created.categoryCreateErrorMessage)

        vm.consumeCreatedCategory()

        val cleared = vm.awaitState { it.createdCategoryId == null && it.categoryCreateErrorMessage == null }
        // 选中的分类是表单状态，不跟着一次性信号一起清
        assertEquals(created.form.categoryId, cleared.form.categoryId)
    }
}

