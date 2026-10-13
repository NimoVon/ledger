package com.nimo.bookkeeping.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.bookkeeping.data.local.AppDatabase
import com.nimo.bookkeeping.data.local.SchemaHardening
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.BudgetEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.domain.backup.AccountBackup
import com.nimo.bookkeeping.domain.backup.BackupCodec
import com.nimo.bookkeeping.domain.backup.BackupData
import com.nimo.bookkeeping.domain.backup.BackupDecodeResult
import com.nimo.bookkeeping.domain.backup.BackupFile
import com.nimo.bookkeeping.domain.backup.BudgetBackup
import com.nimo.bookkeeping.domain.backup.CategoryBackup
import com.nimo.bookkeeping.domain.backup.CsvExporter
import com.nimo.bookkeeping.domain.backup.TransactionBackup
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionType
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 备份导出/恢复（计划 4.6 恢复策略 + 4.7 集成测试「导出 → 清空 → 恢复数据一致」）。
 *
 * 数据库层的「写到一半失败要整批回滚」由 Room 的 withTransaction 结构性保证；
 * 触发器本身由 DaoConstraintTest 用真实插入路径验证（@Upsert 会掩盖约束冲突，所以那边用 @Insert）。
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BackupRepository

    private var backupWrites = mutableListOf<String>()
    private val autoBackup: AutoBackupWriter = { text -> backupWrites.add(text) }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // in-memory 测试库不挂 build() 里的回调：显式补一次加固（apply 幂等）
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repo = BackupRepository(db)
        backupWrites = mutableListOf()
    }

    @After
    fun teardown() {
        db.close()
    }

    // ---- 测试数据：两条账户（一条归档、一条不计入净资产）、三个分类（含归档子分类）、四笔交易、两条预算 ----

    private suspend fun seedSample() {
        db.accountDao().insertAll(
            listOf(
                AccountEntity(
                    id = 9L, name = "余额宝", type = AccountType.ALIPAY, initialBalance = 500L,
                    includeInTotal = true, isArchived = false, sortOrder = 1, createdAt = 1L, updatedAt = 2L,
                ),
                AccountEntity(
                    id = 2L, name = "招行", type = AccountType.CREDIT, initialBalance = -12345L,
                    includeInTotal = false, isArchived = true, sortOrder = 3, createdAt = 11L, updatedAt = 12L,
                ),
            ),
        )
        db.categoryDao().insertAll(
            listOf(
                CategoryEntity(
                    id = 5L, name = "餐饮", kind = CategoryKind.EXPENSE, parentId = null,
                    icon = "food", color = "#FF8800", isArchived = false, sortOrder = 1,
                ),
                CategoryEntity(
                    id = 7L, name = "外卖", kind = CategoryKind.EXPENSE, parentId = 5L,
                    icon = null, color = null, isArchived = true, sortOrder = 2,
                ),
                CategoryEntity(
                    id = 6L, name = "工资", kind = CategoryKind.INCOME, parentId = null,
                    icon = null, color = null, isArchived = false, sortOrder = 1,
                ),
            ),
        )
        db.transactionDao().insertAll(
            listOf(
                TransactionEntity(
                    id = 7L, type = TransactionType.EXPENSE, amount = 1200L, accountId = 2L,
                    toAccountId = null, categoryId = 5L, occurredOn = "2026-10-13", note = "午饭",
                    createdAt = 21L, updatedAt = 21L,
                ),
                TransactionEntity(
                    id = 8L, type = TransactionType.TRANSFER, amount = 5000L, accountId = 2L,
                    toAccountId = 9L, categoryId = null, occurredOn = "2026-10-12", note = null,
                    createdAt = 22L, updatedAt = 22L,
                ),
                TransactionEntity(
                    id = 10L, type = TransactionType.BALANCE_ADJUST, amount = -300L, accountId = 2L,
                    toAccountId = null, categoryId = null, occurredOn = "2026-10-11", note = null,
                    createdAt = 23L, updatedAt = 23L,
                ),
                TransactionEntity(
                    id = 11L, type = TransactionType.INCOME, amount = 8000L, accountId = 9L,
                    toAccountId = null, categoryId = 6L, occurredOn = "2026-10-10", note = "八月工资",
                    createdAt = 24L, updatedAt = 24L,
                ),
            ),
        )
        db.budgetDao().insertAll(
            listOf(
                BudgetEntity(id = 11L, month = "2026-10", categoryId = 5L, amount = 100000L),
                BudgetEntity(id = 12L, month = "2026-10", categoryId = null, amount = 200000L),
            ),
        )
    }

    /** 导出的 JSON 必须能原样解回来（含归档标记、时间戳、备注、null 字段）。 */
    private suspend fun exportedData(): BackupData =
        (BackupCodec.decode(repo.exportJson()) as BackupDecodeResult.Success).file.data

    // ---- 导出 ----

    @Test
    fun exportJson_roundTripsEveryRowAndUnit() = runTest {
        seedSample()
        val expected = BackupData(
            accounts = listOf(
                AccountBackup(
                    id = 9L, name = "余额宝", type = "ALIPAY", initialBalance = 500L,
                    includeInTotal = true, isArchived = false, sortOrder = 1, createdAt = 1L, updatedAt = 2L,
                ),
                AccountBackup(
                    id = 2L, name = "招行", type = "CREDIT", initialBalance = -12345L,
                    includeInTotal = false, isArchived = true, sortOrder = 3, createdAt = 11L, updatedAt = 12L,
                ),
            ),
            // 导出顺序沿用 DAO 的稳定排序：sortOrder ASC, id ASC（与列表页口径一致）
            categories = listOf(
                CategoryBackup(
                    id = 5L, name = "餐饮", kind = "EXPENSE", parentId = null,
                    icon = "food", color = "#FF8800", isArchived = false, sortOrder = 1,
                ),
                CategoryBackup(
                    id = 6L, name = "工资", kind = "INCOME", parentId = null,
                    icon = null, color = null, isArchived = false, sortOrder = 1,
                ),
                CategoryBackup(
                    id = 7L, name = "外卖", kind = "EXPENSE", parentId = 5L,
                    icon = null, color = null, isArchived = true, sortOrder = 2,
                ),
            ),
            transactions = listOf(
                TransactionBackup(
                    id = 7L, type = "EXPENSE", amount = 1200L, accountId = 2L, toAccountId = null,
                    categoryId = 5L, occurredOn = "2026-10-13", note = "午饭", createdAt = 21L, updatedAt = 21L,
                ),
                TransactionBackup(
                    id = 8L, type = "TRANSFER", amount = 5000L, accountId = 2L, toAccountId = 9L,
                    categoryId = null, occurredOn = "2026-10-12", note = null, createdAt = 22L, updatedAt = 22L,
                ),
                TransactionBackup(
                    id = 10L, type = "BALANCE_ADJUST", amount = -300L, accountId = 2L, toAccountId = null,
                    categoryId = null, occurredOn = "2026-10-11", note = null, createdAt = 23L, updatedAt = 23L,
                ),
                TransactionBackup(
                    id = 11L, type = "INCOME", amount = 8000L, accountId = 9L, toAccountId = null,
                    categoryId = 6L, occurredOn = "2026-10-10", note = "八月工资", createdAt = 24L, updatedAt = 24L,
                ),
            ),
            budgets = listOf(
                BudgetBackup(id = 11L, month = "2026-10", categoryId = 5L, amount = 100000L),
                BudgetBackup(id = 12L, month = "2026-10", categoryId = null, amount = 200000L),
            ),
        )
        assertEquals(expected, exportedData())
    }

    @Test
    fun exportCsv_isInYuanWhileJsonKeepsCents() = runTest {
        seedSample()
        val json = repo.exportJson()
        val csv = repo.exportCsvFiles()

        assertEquals(
            listOf(
                CsvExporter.ACCOUNTS_FILE, CsvExporter.CATEGORIES_FILE,
                CsvExporter.TRANSACTIONS_FILE, CsvExporter.BUDGETS_FILE,
            ),
            csv.keys.toList(),
        )
        csv.values.forEach { assertTrue(it.startsWith(CsvExporter.BOM)) }

        // CSV：元，两位小数；JSON：整数分。同一个 -12345 在两边长这样。
        assertTrue(csv.getValue(CsvExporter.ACCOUNTS_FILE).contains("-123.45"))
        assertTrue(json.contains("-12345"))
        assertTrue(csv.getValue(CsvExporter.TRANSACTIONS_FILE).contains("12.00"))
        assertTrue(json.contains("1200"))
        // 归档数据必须照导：少了它，恢复后的历史流水会缺引用
        assertTrue(csv.getValue(CsvExporter.CATEGORIES_FILE).contains("外卖"))
    }

    // ---- 恢复（计划 4.7：导出 → 清空 → 恢复，条目数与金额完全一致） ----

    @Test
    fun exportThenWipeThenRestore_keepsEveryRowAndAmount() = runTest {
        seedSample()
        val exported = (BackupCodec.decode(repo.exportJson()) as BackupDecodeResult.Success).file
        val amountSumBefore = db.transactionDao().listAll().sumOf { it.amount }

        // 清空：恢复一份空备份，就是产品里的「清空数据」路径（替换模式）
        assertTrue(repo.restore(BackupFile(), autoBackup).isSuccess)
        assertEquals(0, db.accountDao().count())
        assertEquals(0, db.categoryDao().listAll().size)
        assertEquals(0, db.transactionDao().count())
        assertEquals(0, db.budgetDao().listAll().size)

        assertTrue(repo.restore(exported, autoBackup).isSuccess)
        assertEquals(2, db.accountDao().count())
        assertEquals(3, db.categoryDao().listAll().size)
        assertEquals(4, db.transactionDao().count())
        assertEquals(2, db.budgetDao().listAll().size)
        assertEquals(amountSumBefore, db.transactionDao().listAll().sumOf { it.amount })

        // 逐行一致：id、归档标记、时间戳都不能变（详情页/钻取的 id 引用要稳）
        assertEquals(exported.data, exportedData())
    }

    @Test
    fun restore_replacesInsteadOfMerging() = runTest {
        seedSample()
        val small = BackupFile(
            data = BackupData(
                accounts = listOf(AccountBackup(id = 1L, name = "现金", type = "CASH")),
                categories = listOf(CategoryBackup(id = 1L, name = "餐饮", kind = "EXPENSE")),
                transactions = listOf(
                    TransactionBackup(
                        id = 1L, type = "EXPENSE", amount = 100L, accountId = 1L, categoryId = 1L,
                        occurredOn = "2026-10-13",
                    ),
                ),
            ),
        )
        assertTrue(repo.restore(small, autoBackup).isSuccess)

        assertEquals(1, db.accountDao().count())
        assertEquals(1, db.categoryDao().listAll().size)
        assertEquals(1, db.transactionDao().count())
        assertEquals(0, db.budgetDao().listAll().size)
        assertEquals("现金", db.accountDao().getById(1L)?.name)
    }

    @Test
    fun restore_writesTheCurrentDataIntoTheAutomaticBackup() = runTest {
        seedSample()
        assertTrue(repo.restore(BackupFile(), autoBackup).isSuccess)

        // 自动备份的内容必须是「被覆盖之前」的全量数据
        assertEquals(1, backupWrites.size)
        val snapshot = BackupCodec.decode(backupWrites[0])
        assertTrue(snapshot is BackupDecodeResult.Success)
        val data = (snapshot as BackupDecodeResult.Success).file.data
        assertEquals(2, data.accounts.size)
        assertEquals(4, data.transactions.size)
        assertEquals(2, data.budgets.size)
    }

    // ---- 坏输入：一个字节都不许动 ----

    @Test
    fun restore_whenAutomaticBackupFails_abortsAndLeavesDataIntact() = runTest {
        seedSample()
        val failing: AutoBackupWriter = { throw IOException("磁盘已满") }

        val result = repo.restore(BackupFile(), failing)

        assertTrue(result.isFailure)
        assertEquals(2, db.accountDao().count())
        assertEquals(3, db.categoryDao().listAll().size)
        assertEquals(4, db.transactionDao().count())
        assertEquals(2, db.budgetDao().listAll().size)
    }

    @Test
    fun restore_rejectsBrokenBackupBeforeBackingUpOrWiping() = runTest {
        seedSample()
        // 交易引用了不存在的账户 99：行层校验必须挡下
        val broken = BackupFile(
            data = BackupData(
                accounts = listOf(AccountBackup(id = 1L, name = "现金", type = "CASH")),
                categories = listOf(CategoryBackup(id = 1L, name = "餐饮", kind = "EXPENSE")),
                transactions = listOf(
                    TransactionBackup(
                        id = 1L, type = "EXPENSE", amount = 100L, accountId = 99L, categoryId = 1L,
                        occurredOn = "2026-10-13",
                    ),
                ),
            ),
        )

        val result = repo.restore(broken, autoBackup)

        assertTrue(result.isFailure)
        assertTrue(backupWrites.isEmpty())
        assertEquals(2, db.accountDao().count())
        assertEquals(4, db.transactionDao().count())
    }

    @Test
    fun restoreFromJson_rejectsTextThatIsNotOurBackup() = runTest {
        seedSample()

        assertTrue(repo.restoreFromJson("{ 这不是 JSON", autoBackup).isFailure)
        assertTrue(
            repo.restoreFromJson(
                "{\"app\":\"other\",\"schemaVersion\":1,\"data\":{}}",
                autoBackup,
            ).isFailure,
        )
        assertTrue(backupWrites.isEmpty())
        assertEquals(4, db.transactionDao().count())
        assertEquals(2, db.accountDao().count())
    }

    @Test
    fun restoreFromJson_acceptsTextExportedByTheSameVersion() = runTest {
        seedSample()
        val text = repo.exportJson()
        assertTrue(repo.restore(BackupFile(), autoBackup).isSuccess)

        assertTrue(repo.restoreFromJson(text, autoBackup).isSuccess)

        assertEquals(4, db.transactionDao().count())
        assertEquals(2, db.budgetDao().listAll().size)
        assertEquals(4, exportedData().transactions.size)
    }
}
