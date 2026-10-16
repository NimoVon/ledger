package com.nimo.ledger.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.validate.AccountValidationError
import com.nimo.ledger.domain.validate.AccountValidationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 账户写入链路测试（F01）：Repository → 校验 → DAO，真实 SQLite。 */
@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryAccountTest {

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
    fun teardown() {
        db.close()
    }

    @Test
    fun createAccount_persistsAndAppearsInList() = runTest {
        val id = repository.createAccount("招商银行", AccountType.DEBIT, 100_000L).getOrThrow()
        val all = repository.observeAccounts().first()
        assertEquals(1, all.size)
        val saved = all.single()
        assertEquals(id, saved.id)
        assertEquals("招商银行", saved.name)
        assertEquals(AccountType.DEBIT, saved.type)
        assertEquals(100_000L, saved.initialBalance)
        assertTrue(saved.includeInTotal)
        assertEquals(false, saved.isArchived)
    }

    @Test
    fun createAccount_trimsName() = runTest {
        repository.createAccount("  现金  ", AccountType.CASH, 0L).getOrThrow()
        assertEquals("现金", repository.observeAccounts().first().single().name)
    }

    @Test
    fun createAccount_blankName_failsWithValidationError() = runTest {
        val result = repository.createAccount("   ", AccountType.CASH, 0L)
        assertTrue(result.isFailure)
        val errors = (result.exceptionOrNull() as AccountValidationException).errors
        assertEquals(listOf(AccountValidationError.NameBlank), errors)
        assertEquals(0, repository.observeAccounts().first().size)
    }

    @Test
    fun createAccount_balanceOverMax_failsAndWritesNothing() = runTest {
        val result = repository.createAccount("现金", AccountType.CASH, Long.MAX_VALUE)
        assertTrue(result.isFailure)
        assertEquals(0, repository.observeAccounts().first().size)
    }

    @Test
    fun createAccount_assignsIncreasingSortOrder() = runTest {
        repository.createAccount("A", AccountType.CASH, 0L).getOrThrow()
        repository.createAccount("B", AccountType.CASH, 0L).getOrThrow()
        val orders = repository.observeAccounts().first().map { it.sortOrder }
        assertEquals(listOf(0, 1), orders.sorted())
    }

    @Test
    fun updateAccount_changesFieldsButKeepsCreatedAt() = runTest {
        val id = repository.createAccount("微信", AccountType.WECHAT, 500L).getOrThrow()
        val createdAt = repository.observeAccounts().first().single().createdAt
        repository.updateAccount(id, "微信零钱", AccountType.WECHAT, 800L, includeInTotal = false).getOrThrow()
        val saved = repository.observeAccounts().first().single()
        assertEquals(id, saved.id)
        assertEquals("微信零钱", saved.name)
        assertEquals(800L, saved.initialBalance)
        assertEquals(false, saved.includeInTotal)
        assertEquals(createdAt, saved.createdAt)
    }

    @Test
    fun updateAccount_unknownId_fails() = runTest {
        assertTrue(
            repository.updateAccount(999L, "x", AccountType.CASH, 0L, includeInTotal = true).isFailure,
        )
    }

    @Test
    fun archiveAccount_keepsRowAndHistory() = runTest {
        val id = repository.createAccount("现金", AccountType.CASH, 1_000L).getOrThrow()
        repository.setAccountArchived(id, archived = true).getOrThrow()
        val saved = repository.observeAccounts().first().single()
        assertEquals(true, saved.isArchived)
        // 归档不删除：余额推导的输入仍在
        assertEquals(1, db.accountDao().listAll().size)
        // 可恢复
        repository.setAccountArchived(id, archived = false).getOrThrow()
        assertEquals(false, repository.observeAccounts().first().single().isArchived)
    }

    @Test
    fun observeAccounts_archivedSortedAfterActive() = runTest {
        val keep = repository.createAccount("在用", AccountType.CASH, 0L).getOrThrow()
        val arch = repository.createAccount("已归档", AccountType.CASH, 0L).getOrThrow()
        repository.setAccountArchived(arch, archived = true).getOrThrow()
        val first = repository.observeAccounts().first().first()
        assertEquals(keep, first.id)
    }
}
