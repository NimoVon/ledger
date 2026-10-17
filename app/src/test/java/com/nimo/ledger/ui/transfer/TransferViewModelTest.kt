package com.nimo.ledger.ui.transfer

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
import com.nimo.ledger.domain.stats.BalanceCalculator
import com.nimo.ledger.util.Money
import com.nimo.ledger.data.local.toBalanceInput
import com.nimo.ledger.data.local.toRecordOrNull
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
 * 转账页 ViewModel（F04）：真实 Repository + in-memory Room，验的是端到端行为。
 *
 * 两处最容易被写错的地方在这里钉住：
 * 1) 余额不足只是**警示**，保存不能被拦（计划 F04：警示，可选择继续）；
 * 2) 编辑时"够不够"的基准要把本笔已生效的扣款加回去，否则把金额改大会误报。
 */
@RunWith(RobolectricTestRunner::class)
class TransferViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private var cashId = 0L
    private var alipayId = 0L

    @Before
    fun setup() = runTest {
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

    /** 两个账户：现金 1000.00、支付宝 0.00 */
    private suspend fun seedTwoAccounts() {
        cashId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        alipayId = repository.createAccount("支付宝", AccountType.DEBIT, 0L).getOrThrow()
    }

    private fun newViewModel(editingId: Long? = null) = TransferViewModel(repository, editingId)

    private suspend fun TransferViewModel.awaitState(
        predicate: (TransferUiState) -> Boolean,
    ): TransferUiState = uiState.first(predicate)

    private suspend fun TransferViewModel.awaitLoaded(): TransferUiState =
        awaitState { !it.isLoading }

    private suspend fun newTransfer(amountCents: Long = 30_000L): Long =
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.TRANSFER,
                amountCents = amountCents,
                accountId = cashId,
                toAccountId = alipayId,
                categoryId = null,
                occurredOn = "2026-10-12",
            ),
        ).getOrThrow()

    private suspend fun balances(): Map<Long, Long> =
        BalanceCalculator.computeBalances(
            repository.observeAccounts().first().map { it.toBalanceInput() },
            repository.observeAllTransactions().first().mapNotNull { it.toRecordOrNull() },
        )

    // ---- 初始状态 ----

    @Test
    fun initialState_defaultsToTheFirstAccountAndShowsTheForm() = runTest {
        seedTwoAccounts()

        val state = newViewModel().awaitLoaded()
        assertEquals(listOf(cashId, alipayId), state.accounts.map { it.id })
        assertFalse(state.isEditing)
        assertNull(state.form.toAccountId)
        // 和记一笔同款：默认选中第一个可用账户，别让"随手转一笔"多点一步
        assertEquals(cashId, state.effectiveFromAccountId)
        assertTrue(state.isFormVisible)
        assertFalse(state.hasNotEnoughAccounts)
    }

    @Test
    fun withLessThanTwoAccounts_thePageSaysItCannotTransferYet() = runTest {
        cashId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()

        val state = newViewModel().awaitLoaded()
        assertTrue(state.hasNotEnoughAccounts)
        // 账户不够时顶栏连「保存」都不该露出
        assertFalse(state.isFormVisible)
    }

    @Test
    fun setAmount_normalizesFullWidthDigitsAndPeriod() = runTest {
        seedTwoAccounts()
        val vm = newViewModel()
        vm.awaitLoaded()

        vm.setAmount("３０。５")

        val state = vm.awaitState { it.form.amountText.isNotEmpty() }
        assertEquals("30.5", state.form.amountText)
        assertEquals(3_050L, Money.parseCents(state.form.amountText))
    }

    // ---- 校验：表单层 ----

    @Test
    fun savingWithAnEmptyAmount_reportsTheAmountErrorAndWritesNothing() = runTest {
        seedTwoAccounts()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.setToAccount(alipayId)

        vm.save()

        val state = vm.awaitState { it.formErrors.isNotEmpty() }
        assertEquals(listOf(TransferFormError.AmountBlank), state.formErrors)
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun savingWithoutADestination_reportsTheDestinationError() = runTest {
        seedTwoAccounts()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.setAmount("30")

        vm.save()

        assertEquals(
            listOf(TransferFormError.ToAccountRequired),
            vm.awaitState { it.formErrors.isNotEmpty() }.formErrors,
        )
        assertEquals(0, db.transactionDao().count())
    }

    @Test
    fun editingAField_clearsTheErrorsFromThePreviousAttempt() = runTest {
        seedTwoAccounts()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.setToAccount(alipayId)
        vm.save()
        assertEquals(listOf(TransferFormError.AmountBlank), vm.awaitState { it.formErrors.isNotEmpty() }.formErrors)

        vm.setAmount("30")

        assertTrue(vm.awaitState { it.form.amountText == "30" }.formErrors.isEmpty())
    }

    // ---- 保存：写入一行 TRANSFER ----

    @Test
    fun savingATransfer_writesOneRowAndReportsItsId() = runTest {
        seedTwoAccounts()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.setToAccount(alipayId)
        // 转 300 元 —— 30000 分
        vm.setAmount("300")

        vm.save()

        val id = vm.awaitState { it.savedTransactionId != null }.savedTransactionId!!
        val saved = db.transactionDao().getById(id)!!
        assertEquals(TransactionType.TRANSFER, saved.type)
        assertEquals(30_000L, saved.amount)
        assertEquals(cashId, saved.accountId)
        assertEquals(alipayId, saved.toAccountId)
        // 转账不挂分类
        assertNull(saved.categoryId)
        assertEquals(1, db.transactionDao().count())
        // 两端余额同时到位
        assertEquals(70_000L, balances()[cashId])
        assertEquals(30_000L, balances()[alipayId])
    }

    @Test
    fun consumingTheSavedId_clearsItSoThePageDoesNotNavigateTwice() = runTest {
        seedTwoAccounts()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.setToAccount(alipayId)
        vm.setAmount("30")
        vm.save()
        vm.awaitState { it.savedTransactionId != null }

        vm.consumeSaved()

        assertNull(vm.awaitState { it.savedTransactionId == null }.savedTransactionId)
    }

    // ---- 余额不足：只是警示，不拦保存（计划 F04）----

    @Test
    fun insufficientBalance_warnsButStillLetsTheUserSave() = runTest {
        seedTwoAccounts()
        val vm = newViewModel()
        vm.awaitLoaded()
        vm.setToAccount(alipayId)
        vm.setAmount("2000")

        val warned = vm.awaitState { it.amountCents == 200_000L }
        assertEquals(100_000L, warned.balanceBasisCents)
        assertTrue(warned.isFromBalanceInsufficient)

        vm.save()

        assertTrue(vm.awaitState { it.savedTransactionId != null }.isSaving.not())
        assertEquals(1, db.transactionDao().count())
        // 余额可以被转成负数——这正是"警示后继续"的含义，且余额是推导值
        assertEquals(-100_000L, balances()[cashId])
    }

    // ---- 编辑：基准余额要把本笔已生效的扣款加回去 ----

    @Test
    fun editingATransfer_usesTheBalanceBeforeThisTransferAsTheBasis() = runTest {
        seedTwoAccounts()
        val id = newTransfer(amountCents = 30_000L)
        val vm = newViewModel(editingId = id)

        val state = vm.awaitState { !it.isLoading && it.isEditing }
        // 表单由库里那一行还原（金额回填不分组，回填后再经 parseCents）
        assertEquals("300.00", state.form.amountText)
        assertEquals(cashId, state.form.fromAccountId)
        assertEquals(alipayId, state.form.toAccountId)
        // 当前余额已经被这笔扣过了：70_000
        assertEquals(70_000L, state.balances[cashId])
        // 但"够不够"的基准是这笔还没生效时的 100_000
        assertEquals(100_000L, state.balanceBasisCents)

        // 800.00：大于当前余额 70_000，但仍在这笔生效前的 100_000 之内 —— 不该误报警告
        vm.setAmount("800")
        assertFalse(vm.awaitState { it.form.amountText == "800" }.isFromBalanceInsufficient)

        // 1200.00：超出 100_000，才该警示
        vm.setAmount("1200")
        assertTrue(vm.awaitState { it.form.amountText == "1200" }.isFromBalanceInsufficient)
    }

    @Test
    fun savingAnEditedTransfer_keepsTheRowAndMovesBothEnds() = runTest {
        seedTwoAccounts()
        val id = newTransfer(amountCents = 30_000L)
        val vm = newViewModel(editingId = id)
        vm.awaitState { !it.isLoading && it.isEditing }

        vm.setAmount("500")
        vm.save()

        assertEquals(id, vm.awaitState { it.savedTransactionId != null }.savedTransactionId)
        assertEquals(1, db.transactionDao().count())
        assertEquals(50_000L, db.transactionDao().getById(id)!!.amount)
        // 旧的 300.00 扣款已经不存在：不是 200.00 / 800.00
        assertEquals(50_000L, balances()[cashId])
        assertEquals(50_000L, balances()[alipayId])
    }

    // ---- 编辑入口的兜底 ----

    @Test
    fun editingAnExpense_explainsThatItIsNotATransfer() = runTest {
        seedTwoAccounts()
        val categoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, sortOrder = 0))
        val expenseId = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = 1_000L,
                accountId = cashId,
                categoryId = categoryId,
                occurredOn = "2026-10-12",
            ),
        ).getOrThrow()

        val state = newViewModel(editingId = expenseId).awaitState { it.loadErrorMessage != null }
        assertEquals("这一笔不是转账，请到「记一笔」里编辑", state.loadErrorMessage)
        assertFalse(state.isFormVisible)
    }

    @Test
    fun editingAMissingTransaction_reportsItInsteadOfShowingAnEmptyForm() = runTest {
        seedTwoAccounts()

        val state = newViewModel(editingId = 9_999L).awaitState { it.loadErrorMessage != null }
        assertEquals("这笔记录不存在，可能已被删除", state.loadErrorMessage)
        assertFalse(state.isFormVisible)
    }

    @Test
    fun anArchivedSourceAccount_isFlaggedAndCanBeRestoredForEditing() = runTest {
        seedTwoAccounts()
        val id = newTransfer(amountCents = 30_000L)
        repository.setAccountArchived(cashId, archived = true).getOrThrow()

        val vm = newViewModel(editingId = id)
        val flagged = vm.awaitState { !it.isLoading && it.originalFromAccountUnavailable }
        assertEquals("现金", flagged.originalFromAccountName)

        vm.restoreOriginalAccount(cashId)

        val restored = vm.awaitState { !it.originalFromAccountUnavailable && !it.isRestoringAccount }
        assertFalse(restored.originalFromAccountUnavailable)
        assertEquals(2, repository.observeActiveAccounts().first().size)
    }
}
