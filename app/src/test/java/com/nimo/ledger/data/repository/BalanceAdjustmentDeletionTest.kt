package com.nimo.ledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.toBalanceInput
import com.nimo.ledger.data.local.toRecordOrNull
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.stats.BalanceCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 删余额校准不能把「最新一条的实际」和账户余额撕开（用户 2026-10-20 报的 bug）。
 *
 * 校准的差额是相对**当时账面**算的：删掉前一条后，后一条的差额必须并入被删的差额，
 * 否则最后一条写着「实际 ¥1,800.00」、余额却只有 ¥1,300.00。
 */
@RunWith(RobolectricTestRunner::class)
class BalanceAdjustmentDeletionTest {

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
    fun tearDown() {
        db.close()
    }

    @Test
    fun deletingAnEarlierAdjustment_keepsTheLatestTarget() {
        val accountId = runBlocking {
            repository.createAccount("支付宝", AccountType.ALIPAY, 100_000L).getOrThrow()
        }
        val first = runBlocking { repository.adjustBalanceTo(accountId, 150_000L).getOrThrow() }
        val latest = runBlocking { repository.adjustBalanceTo(accountId, 180_000L).getOrThrow() }

        val firstRow = runBlocking { repository.getTransaction(first) }!!
        runBlocking { repository.deleteBalanceAdjustment(firstRow) }

        // 最新一条的「实际」仍是 1,800.00 元，余额必须和它一致
        assertEquals(180_000L, balanceOf(accountId))
        val merged = runBlocking { repository.getTransaction(latest) }!!
        assertEquals(80_000L, merged.amount)
        assertTrue(merged.note.orEmpty().contains("账面 ¥1,000.00"))
        assertTrue(merged.note.orEmpty().contains("实际 ¥1,800.00"))
    }

    @Test
    fun deletingTheLatestAdjustment_returnsToThePreviousTarget() {
        val accountId = runBlocking {
            repository.createAccount("支付宝", AccountType.ALIPAY, 100_000L).getOrThrow()
        }
        val first = runBlocking { repository.adjustBalanceTo(accountId, 150_000L).getOrThrow() }
        val latest = runBlocking { repository.adjustBalanceTo(accountId, 180_000L).getOrThrow() }

        val latestRow = runBlocking { repository.getTransaction(latest) }!!
        runBlocking { repository.deleteBalanceAdjustment(latestRow) }

        assertEquals(150_000L, balanceOf(accountId))
        // 前一条不需要被改写
        val firstRow = runBlocking { repository.getTransaction(first) }!!
        assertEquals(50_000L, firstRow.amount)
        assertTrue(firstRow.note.orEmpty().contains("实际 ¥1,500.00"))
    }


    @Test
    fun deletingTheFirstTwoInARow_rollsBothDiffsIntoTheThird() {
        val accountId = runBlocking {
            repository.createAccount("支付宝", AccountType.ALIPAY, 100_000L).getOrThrow()
        }
        val first = runBlocking { repository.adjustBalanceTo(accountId, 150_000L).getOrThrow() }
        val second = runBlocking { repository.adjustBalanceTo(accountId, 180_000L).getOrThrow() }
        val third = runBlocking { repository.adjustBalanceTo(accountId, 200_000L).getOrThrow() }

        runBlocking { repository.deleteBalanceAdjustment(repository.getTransaction(first)!!) }
        runBlocking { repository.deleteBalanceAdjustment(repository.getTransaction(second)!!) }

        // 第 1、2 笔的差额都并进第 3 笔：100_000 → 200_000 = +100_000
        val survivor = runBlocking { repository.getTransaction(third) }!!
        assertEquals(100_000L, survivor.amount)
        assertEquals(200_000L, balanceOf(accountId))
        assertTrue(survivor.note.orEmpty().contains("账面 ¥1,000.00"))
        assertTrue(survivor.note.orEmpty().contains("实际 ¥2,000.00"))
    }    private fun balanceOf(accountId: Long): Long = runBlocking {
        val accounts = repository.observeAccounts().first()
        val transactions = repository.observeAllTransactions().first()
        BalanceCalculator.computeBalances(
            accounts = accounts.map { it.toBalanceInput() },
            records = transactions.mapNotNull { it.toRecordOrNull() },
        )[accountId] ?: 0L
    }
}
