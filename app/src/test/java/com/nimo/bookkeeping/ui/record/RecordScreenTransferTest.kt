package com.nimo.bookkeeping.ui.record

import android.content.Context
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.ui.transfer.AMOUNT_FIELD_TAG
import com.nimo.bookkeeping.ui.transfer.TO_ACCOUNT_TAG_PREFIX
import com.nimo.bookkeeping.util.Dates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 记一笔页里的「转账」分段（F04 的入口）。
 *
 * 验的是行为不是长相：切到转账 → 填金额 → 选转入账户 → 写备注 → 保存 →
 * 库里真的多一笔 TRANSFER（两个账户、没有分类、备注落库）；
 * 编辑一笔支出时不能出现转账分段（否则能把一笔支出改成转账）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class RecordScreenTransferTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repository = LedgerRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun theTransferSegment_recordsATransferWithANote() {
        var cmbId = 0L
        var alipayId = 0L
        runBlocking {
            cmbId = repository.createAccount("CMB", AccountType.CASH, 100_000L).getOrThrow()
            alipayId = repository.createAccount("支付宝", AccountType.ALIPAY, 0L).getOrThrow()
        }

        // 这里不验「保存后把新 id 交回调用方」：Compose+Robolectric 不稳定地 pump LaunchedEffect，
        // 全量跑时会偶发超时（单独跑却能过）。这条契约由 TransferViewModelTest
        // 的 savingATransfer_writesOneRowAndReportsItsId 钉住，本用例只钉「库里真的多了一笔」。
        composeRule.setContent {
            MaterialTheme {
                RecordScreen(
                    repository = repository,
                    onBack = {},
                    onSaved = {},
                )
            }
        }

        // 记一笔页默认在「支出」：三个分段都在
        awaitText("支出")
        composeRule.onNodeWithText("转账").performClick()

        // 就地换成转账表单（不换页）：金额框与转入账户是按 tag 找的，不受同名文字影响
        awaitText("转出账户")
        composeRule.onNodeWithTag(AMOUNT_FIELD_TAG).performTextInput("200")
        composeRule.onNodeWithTag(TO_ACCOUNT_TAG_PREFIX + alipayId).performClick()
        composeRule.onNodeWithText("备注").performScrollTo().performTextInput("搬家备用金")
        composeRule.onNodeWithText("保存").performClick()

        awaitRows(1)
        val row = runBlocking { repository.observeAllTransactions().first() }.single()
        assertEquals(TransactionType.TRANSFER, row.type)
        assertEquals(200_00L, row.amount)
        assertEquals(cmbId, row.accountId)
        assertEquals(alipayId, row.toAccountId)
        assertNull(row.categoryId)
        assertEquals("搬家备用金", row.note)
    }

    @Test
    fun editingAnExpense_doesNotOfferTheTransferSegment() {
        var expenseId = 0L
        runBlocking {
            val accountId = repository.createAccount("CMB", AccountType.CASH, 100_000L).getOrThrow()
            val categoryId = db.categoryDao().upsert(
                CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, icon = "🍜", sortOrder = 0),
            )
            expenseId = repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountCents = 1_000L,
                    accountId = accountId,
                    categoryId = categoryId,
                    occurredOn = Dates.today(),
                ),
            ).getOrThrow()
        }

        composeRule.setContent {
            MaterialTheme {
                RecordScreen(
                    repository = repository,
                    onBack = {},
                    onSaved = {},
                    editingId = expenseId,
                )
            }
        }

        awaitText("支出")
        composeRule.onAllNodesWithText("收入").assertCountEquals(1)
        composeRule.onAllNodesWithText("转账").assertCountEquals(0)
    }

    /** 写库是协程干的，等 UI 副作用不如直接轮询数据库（Compose+Robolectric 的坑，见 TransferScreenUiTest） */
    private fun awaitRows(expected: Int) {
        composeRule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { db.transactionDao().count() } == expected
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
