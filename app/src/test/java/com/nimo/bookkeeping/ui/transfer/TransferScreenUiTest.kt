package com.nimo.bookkeeping.ui.transfer

import android.content.Context
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.nimo.bookkeeping.awaitUntil
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
 * 转账页（F04）的 UI 冒烟测试。
 *
 * 走真实 Repository + in-memory Room，验的是"页面把这一笔真的写进去了"：
 * 挑两个账户、填金额、点保存 → 库里多一行 TRANSFER（两端余额由 BalanceCalculator 推导）。
 * 余额不足只警示不拦截，也在这里钉一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TransferScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    private var cashId = 0L
    private var alipayId = 0L
    private var savedId: Long? = null

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

    private fun seedTwoAccounts() = runBlocking {
        cashId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        alipayId = repository.createAccount("支付宝", AccountType.DEBIT, 0L).getOrThrow()
    }

    private fun showTransfer() {
        composeRule.setContent {
            MaterialTheme {
                TransferScreen(
                    repository = repository,
                    onBack = {},
                    onSaved = { savedId = it },
                )
            }
        }
    }

    /** 等表单渲染出来（账户和余额都是异步读的，页面先停在加载态） */
    private fun awaitAmountField() {
        composeRule.awaitUntil {
            composeRule.onAllNodesWithTag(AMOUNT_FIELD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitTag(tag: String) {
        composeRule.awaitUntil {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitText(text: String) {
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 等库里真的多出 expected 行：保存是 ViewModel 里的协程 + Room 后台线程，顺手把主线程待办跑掉 */
    private fun awaitTransferRows(expected: Int) {
        composeRule.awaitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { db.transactionDao().count() } == expected
        }
    }

    // ---- 正常路径 ----

    @Test
    fun fillingTheForm_andSaving_writesATransferAndReturnsItsId() {
        seedTwoAccounts()
        showTransfer()
        awaitAmountField()

        composeRule.onNodeWithTag(TO_ACCOUNT_TAG_PREFIX + alipayId).performClick()
        composeRule.onNodeWithTag(AMOUNT_FIELD_TAG).performTextInput("30")
        composeRule.onNodeWithText("保存").performClick()

        awaitTransferRows(1)
        // 页面必须把 id 交回调，调用方才能返回明细页
        composeRule.awaitUntil { savedId != null }
        val saved = runBlocking { db.transactionDao().getById(savedId!!) }!!
        assertEquals(TransactionType.TRANSFER, saved.type)
        assertEquals(3_000L, saved.amount)
        assertEquals(cashId, saved.accountId)
        assertEquals(alipayId, saved.toAccountId)
        // 转账不挂分类
        assertNull(saved.categoryId)
    }

    @Test
    fun anAmountAboveTheSourceBalance_warnsButStillSaves() {
        seedTwoAccounts()
        showTransfer()
        awaitAmountField()

        composeRule.onNodeWithTag(TO_ACCOUNT_TAG_PREFIX + alipayId).performClick()
        composeRule.onNodeWithTag(AMOUNT_FIELD_TAG).performTextInput("2000")

        awaitTag(INSUFFICIENT_BALANCE_TAG)
        composeRule.onNodeWithTag(INSUFFICIENT_BALANCE_TAG).performScrollTo().assertIsDisplayed()

        // 计划 F04：警示后可继续，保存不能被拦
        composeRule.onNodeWithText("保存").performClick()
        awaitTransferRows(1)
        assertEquals(200_000L, runBlocking { repository.observeAllTransactions().first() }.single().amount)
    }

    // ---- 表单层校验：金额没填就点保存，不应该写库 ----

    @Test
    fun savingWithoutAnAmount_keepsTheDatabaseEmpty() {
        seedTwoAccounts()
        showTransfer()
        awaitAmountField()

        composeRule.onNodeWithTag(TO_ACCOUNT_TAG_PREFIX + alipayId).performClick()
        composeRule.onNodeWithText("保存").performClick()

        awaitText("请输入金额")
        assertNull(savedId)
        assertEquals(0, runBlocking { db.transactionDao().count() })
    }

    // ---- 账户不够 ----

    @Test
    fun withOneAccount_theWayOutIsShownInsteadOfTheForm() {
        runBlocking {
            cashId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        }
        showTransfer()

        awaitText("还不能转账")
        composeRule.onNodeWithText("还不能转账").assertIsDisplayed()
    }
}
