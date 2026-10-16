package com.nimo.ledger.ui.transactions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.MainDispatcherRule
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.local.entity.TransactionEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionInput
import com.nimo.ledger.domain.model.TransactionType
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
 * 点删除后先只在内存里暂存、超时才真正落库，撤销只在窗口内有效（窗口走完这一笔就删了，
 * 同时提示条也关了——页面按 pending 驱动，两个时长是同一个数）。
 *
 * 多笔删除可以同时待撤销，各跑各的倒计时；过滤口径由 pendingIds 统一对外。
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

    /** 等所有倒计时真的走完，并且落库动作也结束（join 到那些协程） */
    private suspend fun PendingTransactionDeletion.awaitWindow() = awaitIdle()

    private fun PendingTransactionDeletion.undoableIds(): List<Long> = pending.value.map { it.id }

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

        // 界面据此立刻隐藏这一笔（列表/合计/余额都按 pendingIds 过滤），但数据库里它还在
        assertEquals(listOf(id), store.undoableIds())
        assertEquals(setOf(id), store.pendingIds.value)
        assertTrue("倒计时内数据库里这一笔必须还在", exists(id))

        store.awaitWindow()
        assertFalse("超时后才真正物理删除", exists(id))
        assertTrue("落库后过滤也要摘掉", store.pendingIds.value.isEmpty())
        assertTrue("窗口结束 = 提示条结束：不再可撤销", store.pending.value.isEmpty())
    }

    @Test
    fun schedule_secondDeletion_keepsBothUndoableOnTheirOwnCountdowns() = runTest {
        seed()
        val first = addExpense(100L, note = "第一笔")
        val second = addExpense(200L, note = "第二笔")
        val store = newStore()

        store.schedule(entityOf(first))
        store.schedule(entityOf(second))

        // 两笔各自倒计时：界面立刻都看不到，数据库里都还在（不再是"第二笔把第一笔立刻落定"）
        assertEquals(setOf(first, second), store.pendingIds.value)
        assertEquals("提示条只挂最新的一笔", listOf(second, first), store.undoableIds())
        assertTrue("倒计时内两笔都还在库里", exists(first) && exists(second))

        store.awaitWindow()
        assertFalse(exists(first))
        assertFalse(exists(second))
        assertTrue(store.pendingIds.value.isEmpty())
    }

    // ---- 撤销：只在窗口内有效，且只动点中的那一笔 ----

    @Test
    fun undo_withinWindow_cancelsTheCountdownWithoutTouchingTheDatabase() = runTest {
        seed()
        val id = addExpense(1_000L, note = "午餐")
        val original = entityOf(id)
        val store = newStore()

        store.schedule(original)
        assertTrue(store.undo(id))
        assertTrue(store.pending.value.isEmpty())
        assertTrue(store.pendingIds.value.isEmpty())

        // 取消倒计时之后不会事后补删：整笔连 updatedAt 都没被动过
        waitRealTime(undoWindowMs * 2)
        assertEquals(original, entityOf(id))
    }

    @Test
    fun undo_oneOfTwo_leavesTheOtherCountdownRunning() = runTest {
        seed()
        val first = addExpense(100L, note = "第一笔")
        val second = addExpense(200L, note = "第二笔")
        val store = newStore()

        store.schedule(entityOf(first))
        store.schedule(entityOf(second))
        assertTrue(store.undo(second))

        assertEquals(setOf(first), store.pendingIds.value)
        assertEquals(listOf(first), store.undoableIds())
        assertTrue("撤销第二笔不该动第一笔", exists(second))

        store.awaitWindow()
        assertFalse("第一笔照自己的倒计时落库", exists(first))
        assertTrue("被撤销的那一笔必须原样留着", exists(second))
        assertTrue(store.pendingIds.value.isEmpty())
    }

    @Test
    fun undo_afterTheWindow_isRefusedBecauseTheRowIsAlreadyDeleted() = runTest {
        seed()
        val other = addExpense(500L, note = "另一笔")
        val id = addExpense(1_000L, note = "午餐")
        val store = newStore()

        store.schedule(entityOf(id))
        store.awaitWindow()
        assertFalse("窗口走完就真的删了", exists(id))

        // 窗口外不再撤销：那一笔已经物理删除，提示条同时也已经关了
        assertFalse(store.undo(id))

        assertFalse(exists(id))
        assertEquals(listOf(other), db.transactionDao().observeAll().first().map { it.id })
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun undo_withoutPendingDeletion_isANoOp() = runTest {
        seed()
        val id = addExpense(1_000L)
        val store = newStore()

        assertFalse(store.undo(id))
        assertTrue(exists(id))
    }

    // ---- 提示条消失：不再可撤销，但删除照旧完成，而且这一笔仍要藏到落库为止 ----

    @Test
    fun dismiss_stopsUndoButStillHidesTheRowUntilItSettles() = runTest {
        seed()
        val id = addExpense(1_000L)
        val store = newStore()

        store.schedule(entityOf(id))
        store.dismiss(id)

        // 提示条关掉了 → 不再出现在待撤销列表里；但这一笔仍是"待物理删除"，
        // 过滤必须留着，否则它会先闪回列表、过一会儿又消失
        assertTrue(store.pending.value.isEmpty())
        assertEquals(setOf(id), store.pendingIds.value)

        store.awaitWindow()
        assertFalse(exists(id))
        assertTrue("落库后过滤也要摘掉", store.pendingIds.value.isEmpty())
    }

    // ---- 恢复备份前：整批取消，并把整笔交还调用方（失败时可原样排回去） ----

    @Test
    fun cancelAll_stopsEveryCountdownAndHandsBackTheEntities() = runTest {
        seed()
        val first = addExpense(100L, note = "第一笔")
        val second = addExpense(200L, note = "第二笔")
        val store = newStore()

        store.schedule(entityOf(first))
        store.schedule(entityOf(second))
        // 提示条被划掉的那一笔也在待删除集合里：恢复前同样必须一起取消
        store.dismiss(first)

        val cancelled = store.cancelAll()

        assertEquals(setOf(first, second), cancelled.map { it.id }.toSet())
        assertEquals(listOf(first, second), cancelled.map { it.id })
        assertTrue(store.pendingIds.value.isEmpty())
        assertTrue(store.pending.value.isEmpty())

        // 倒计时真的被取消了：等过窗口，两笔都还在库里
        store.awaitWindow()
        assertTrue(exists(first))
        assertTrue(exists(second))
    }

    @Test
    fun cancelAll_withNothingPending_isAnEmptyList() = runTest {
        seed()
        assertTrue(newStore().cancelAll().isEmpty())
    }
}
