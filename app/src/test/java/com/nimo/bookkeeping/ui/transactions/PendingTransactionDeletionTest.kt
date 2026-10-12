package com.nimo.bookkeeping.ui.transactions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.MainDispatcherRule
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
 * 删除 + 撤销（F03，计划 9.2 / V2.2 补遗 7 延迟物理删除）。
 *
 * 走真实 Repository + in-memory Room，测的是"数据库里那一行到底什么时候消失"：
 * 点删除后先只在内存里暂存、超时才真正落库，撤销则分"取消倒计时"与"按原 id 恢复"两条路径。
 *
 * 这里的倒计时用**真实时间**（注入一个很短的窗口），不用 runTest 的虚拟时间：
 * 测试体一旦等真实 IO，虚拟时钟会自动往前跳，把倒计时提前烧掉，测出来的时序就不是用户看到的那套了。
 */
@RunWith(RobolectricTestRunner::class)
class PendingTransactionDeletionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 撤销窗口压到 300ms，够真实 IO 跑几十个来回 */
    private val undoWindowMs = 300L

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var storeScope: CoroutineScope

    private var accountId = 0L
    private var categoryId = 0L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repository = LedgerRepository(db)
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun teardown() {
        storeScope.cancel()
        db.close()
    }

    private suspend fun seed() {
        accountId = repository.createAccount("现金", AccountType.CASH, 100_000L).getOrThrow()
        categoryId = db.categoryDao()
            .upsert(CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, sortOrder = 0))
    }

    private suspend fun addExpense(amount: Long, date: String = "2026-10-12", note: String? = null): Long =
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountCents = amount,
                accountId = accountId,
                categoryId = categoryId,
                occurredOn = date,
                note = note,
            ),
        ).getOrThrow()

    /** 待删除的整笔：流水页会把这一份原样交给延迟删除策略 */
    private suspend fun entityOf(id: Long): TransactionEntity = db.transactionDao().getById(id)!!

    private suspend fun exists(id: Long): Boolean = db.transactionDao().getById(id) != null

    private fun newStore() = PendingTransactionDeletion(repository, storeScope, undoWindowMs)

    /** 等倒计时真的走完，并且落库动作也结束（join 到那个协程） */
    private suspend fun PendingTransactionDeletion.awaitWindow() = awaitIdle()

    /** 真实等一段时间：用来确认"撤销之后不会事后补删" */
    private suspend fun waitRealTime(millis: Long) {
        withContext(Dispatchers.IO) { delay(millis) }
    }

    // ---- 延迟物理删除：倒计时内数据库不动 ----

    @Test
    fun schedule_keepsTheRowInTheDatabaseUntilTheWindowExpires() = runTest {
        seed()
        val id = addExpense(1_000L)
        val store = newStore()

        store.schedule(entityOf(id))

        // 界面据此立刻隐藏这一笔，但数据库里它还在
        assertEquals(id, store.pending.value?.id)
        assertTrue("倒计时内数据库里这一笔必须还在", exists(id))

        store.awaitWindow()
        assertFalse("超时后才真正物理删除", exists(id))
    }

    @Test
    fun schedule_secondDeletion_commitsTheFirstOneImmediately() = runTest {
        seed()
        val first = addExpense(100L, note = "第一笔")
        val second = addExpense(200L, note = "第二笔")
        val store = newStore()

        store.schedule(entityOf(first))
        store.schedule(entityOf(second))

        // 同一时间只允许一笔可撤销：上一笔直接落定，新的这一笔重新开始倒计时
        assertFalse(exists(first))
        assertTrue(exists(second))
        assertEquals(second, store.pending.value?.id)

        store.awaitWindow()
        assertFalse(exists(second))
    }

    // ---- 撤销：还没落库 → 取消倒计时 ----

    @Test
    fun undo_withinWindow_cancelsTheCountdownWithoutTouchingTheDatabase() = runTest {
        seed()
        val id = addExpense(1_000L, note = "午餐")
        val original = entityOf(id)
        val store = newStore()

        store.schedule(original)
        assertTrue(store.undo())
        assertNull(store.pending.value)

        // 取消倒计时之后不会事后补删：整笔连 updatedAt 都没被动过
        waitRealTime(undoWindowMs * 2)
        assertEquals(original, entityOf(id))
    }

    @Test
    fun undo_afterWindow_restoresTheWholeRowByItsOriginalId() = runTest {
        seed()
        val other = addExpense(500L, note = "另一笔")
        val id = addExpense(1_000L, note = "午餐")
        val original = entityOf(id)
        val store = newStore()

        store.schedule(original)
        store.awaitWindow()
        assertFalse(exists(id))

        assertTrue(store.undo())

        // 按原 id 恢复：id 不变 → 列表顺序不跳动；createdAt/updatedAt 原样保留
        assertEquals(original, entityOf(id))
        assertEquals(listOf(id, other), db.transactionDao().observeAll().first().map { it.id })
        assertNull(store.pending.value)
    }

    @Test
    fun undo_withoutPendingDeletion_isANoOp() = runTest {
        seed()
        val id = addExpense(1_000L)
        val store = newStore()

        assertFalse(store.undo())
        assertTrue(exists(id))
    }

    // ---- Snackbar 消失：不再可撤销，但删除照旧完成 ----

    @Test
    fun dismiss_stopsUndoButStillDeletesAfterTheWindow() = runTest {
        seed()
        val id = addExpense(1_000L)
        val store = newStore()

        store.schedule(entityOf(id))
        store.dismiss()

        // 还没落库：状态得留着，列表才会继续隐藏它
        assertEquals(id, store.pending.value?.id)

        store.awaitWindow()
        assertFalse(exists(id))
        assertNull("落库后又没人能撤销，状态就该清掉", store.pending.value)
    }
}
