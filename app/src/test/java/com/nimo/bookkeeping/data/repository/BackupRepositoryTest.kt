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
import com.nimo.bookkeeping.domain.backup.CsvImportCounts
import com.nimo.bookkeeping.domain.backup.CsvRowProblem
import com.nimo.bookkeeping.domain.backup.TransactionBackup
import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionType
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        // 失败原因会原样显示给用户，必须与设置页入口「导入 JSON（覆盖替换）」同口径：
        // 满屏写「恢复」而入口叫「导入」，用户根本对不上自己点的是哪一个（改名残留的自动打脸）。
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("要说清是自动备份这一步挂了，实际：$message", message.contains("自动备份失败"))
        assertFalse("用户可见文案里不要再出现「恢复」，实际：$message", message.contains("恢复"))
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

    // ---- 增量导入（F14：同 id 覆盖、新 id 追加，不删本机任何行） ----

    private fun csv(header: List<String>, vararg rows: List<String>): String =
        CsvExporter.BOM + (listOf(header) + rows).joinToString("\r\n") { it.joinToString(",") } + "\r\n"

    @Test
    fun importCsv_mergesByOverwritingMatchingIdsAndAppendingNewOnes() = runTest {
        seedSample()
        val files = mapOf(
            CsvExporter.ACCOUNTS_FILE to csv(
                CsvExporter.ACCOUNTS_HEADER,
                listOf("9", "余额宝（改）", "ALIPAY", "7.77", "true", "false", "1", "1", "2"),
                listOf("20", "现金", "CASH", "88.00", "true", "false", "9", "31", "31"),
            ),
            CsvExporter.CATEGORIES_FILE to csv(
                CsvExporter.CATEGORIES_HEADER,
                listOf("5", "餐饮", "EXPENSE", "", "food", "#FF8800", "false", "1"),
                listOf("21", "交通", "EXPENSE", "5", "bus", "", "false", "3"),
            ),
            CsvExporter.TRANSACTIONS_FILE to csv(
                CsvExporter.TRANSACTIONS_HEADER,
                listOf("7", "EXPENSE", "9.99", "2", "", "5", "2026-10-13", "改过的午饭", "21", "99"),
                listOf("30", "TRANSFER", "50.00", "20", "9", "", "2026-10-14", "", "41", "41"),
            ),
            CsvExporter.BUDGETS_FILE to csv(
                CsvExporter.BUDGETS_HEADER,
                listOf("11", "2026-10", "5", "12.34"),
                listOf("31", "2026-11", "", "50.00"),
            ),
        )

        val outcome = repo.importCsv(files).getOrThrow()

        assertEquals(emptyList<CsvRowProblem>(), outcome.problems)
        assertEquals(emptyList<String>(), outcome.missingFiles)
        assertEquals(
            CsvImportCounts(accounts = 1, categories = 1, transactions = 1, budgets = 1),
            outcome.inserted,
        )
        assertEquals(
            CsvImportCounts(accounts = 1, categories = 1, transactions = 1, budgets = 1),
            outcome.updated,
        )
        assertEquals(8, outcome.writtenRows)

        // 本机原有行一行都没少
        assertEquals(3, db.accountDao().count())
        assertEquals(4, db.categoryDao().listAll().size)
        assertEquals(5, db.transactionDao().count())
        assertEquals(3, db.budgetDao().listAll().size)

        // 同 id：整行覆盖成文件里的值
        assertEquals("余额宝（改）", db.accountDao().getById(9L)?.name)
        assertEquals(777L, db.accountDao().getById(9L)?.initialBalance)
        assertEquals(999L, db.transactionDao().getById(7L)?.amount)
        assertEquals("改过的午饭", db.transactionDao().getById(7L)?.note)
        assertEquals(99L, db.transactionDao().getById(7L)?.updatedAt)

        // 新 id：追加，且能引用同一批文件里的新账户/新分类
        assertEquals(8800L, db.accountDao().getById(20L)?.initialBalance)
        assertEquals(5L, db.categoryDao().getById(21L)?.parentId)
        assertEquals(5000L, db.transactionDao().getById(30L)?.amount)
        assertEquals(9L, db.transactionDao().getById(30L)?.toAccountId)
        assertEquals(1234L, db.budgetDao().find("2026-10", 5L)?.amount)
        assertEquals(5000L, db.budgetDao().find("2026-11", null)?.amount)

        // 文件里没提到的本机行必须原样保留（含空月份总预算那份）
        assertEquals(200000L, db.budgetDao().find("2026-10", null)?.amount)
        assertEquals("招行", db.accountDao().getById(2L)?.name)
    }

    @Test
    fun importCsv_skipsRowsWhoseReferencesDoNotExistAnywhere() = runTest {
        seedSample()
        val files = mapOf(
            CsvExporter.TRANSACTIONS_FILE to csv(
                CsvExporter.TRANSACTIONS_HEADER,
                listOf("40", "EXPENSE", "5.00", "404", "", "5", "2026-10-14", "", "1", "1"),
                listOf("41", "EXPENSE", "5.00", "2", "", "999", "2026-10-14", "", "1", "1"),
                listOf("42", "EXPENSE", "5.00", "2", "", "6", "2026-10-14", "", "1", "1"),
                listOf("43", "EXPENSE", "6.00", "2", "", "5", "2026-10-14", "好的那笔", "1", "1"),
            ),
        )

        val outcome = repo.importCsv(files).getOrThrow()

        assertEquals(CsvImportCounts(transactions = 1), outcome.inserted)
        assertEquals(1, outcome.writtenRows)
        assertEquals(3, outcome.skippedRows)
        assertEquals(listOf(2, 3, 4), outcome.problems.map { it.line })
        assertTrue(outcome.problems.all { it.file == CsvExporter.TRANSACTIONS_FILE })
        assertTrue(outcome.problems[2].reason, outcome.problems[2].reason.contains("分类的收支方向"))

        assertEquals(5, db.transactionDao().count())
        assertEquals(600L, db.transactionDao().getById(43L)?.amount)
        assertEquals(null, db.transactionDao().getById(40L))
        assertEquals(null, db.transactionDao().getById(41L))
        assertEquals(null, db.transactionDao().getById(42L))
    }

    @Test
    fun importCsv_skipsABudgetThatWouldCollideOnTheSameMonthAndCategory() = runTest {
        seedSample()
        val files = mapOf(
            CsvExporter.BUDGETS_FILE to csv(
                CsvExporter.BUDGETS_HEADER,
                listOf("99", "2026-10", "5", "1.00"),
                listOf("98", "2026-11", "", "2.00"),
            ),
        )

        val outcome = repo.importCsv(files).getOrThrow()

        assertEquals(CsvImportCounts(budgets = 1), outcome.inserted)
        assertEquals(0, outcome.updated.total)
        assertEquals(1, outcome.problems.size)
        assertEquals(2, outcome.problems[0].line)
        assertTrue(outcome.problems[0].reason, outcome.problems[0].reason.contains("只能有一份"))
        assertEquals(3, db.budgetDao().listAll().size)
        assertEquals(100000L, db.budgetDao().find("2026-10", 5L)?.amount)
        assertEquals(200L, db.budgetDao().find("2026-11", null)?.amount)
    }

    @Test
    fun importCsv_withNoFilesAtAll_writesNothingAndListsTheMissingFiles() = runTest {
        seedSample()

        val outcome = repo.importCsv(emptyMap()).getOrThrow()

        assertEquals(0, outcome.writtenRows)
        assertEquals(emptyList<CsvRowProblem>(), outcome.problems)
        assertEquals(4, outcome.missingFiles.size)
        assertTrue(outcome.missingFiles.contains(CsvExporter.TRANSACTIONS_FILE))
        assertEquals(2, db.accountDao().count())
        assertEquals(4, db.transactionDao().count())
    }

    @Test
    fun importCsv_ofOurOwnExportChangesNothingAndOnlyOverwrites() = runTest {
        seedSample()
        val before = exportedData()

        val outcome = repo.importCsv(repo.exportCsvFiles()).getOrThrow()

        // 一个 id 都不新：全部走覆盖；数据逐行一致（CSV 的元 ↔ JSON 的分没有丢精度）
        assertEquals(0, outcome.inserted.total)
        assertEquals(11, outcome.updated.total)
        assertEquals(emptyList<CsvRowProblem>(), outcome.problems)
        assertEquals(before, exportedData())
        assertEquals(2, db.accountDao().count())
        assertEquals(3, db.categoryDao().listAll().size)
        assertEquals(4, db.transactionDao().count())
        assertEquals(2, db.budgetDao().listAll().size)
    }

    // ---- 导入前预览（计划 line 312：导入前提供预览/确认，预览不写库） ----

    @Test
    fun previewCsvImport_writesNothingAndPredictsWhatTheImportWillDo() = runTest {
        seedSample()
        val before = exportedData()
        val files = mapOf(
            CsvExporter.ACCOUNTS_FILE to csv(
                CsvExporter.ACCOUNTS_HEADER,
                listOf("9", "余额宝（改）", "ALIPAY", "7.77", "true", "false", "1", "1", "2"),
                listOf("20", "现金", "CASH", "88.00", "true", "false", "9", "31", "31"),
            ),
            CsvExporter.TRANSACTIONS_FILE to csv(
                CsvExporter.TRANSACTIONS_HEADER,
                listOf("7", "EXPENSE", "9.99", "2", "", "5", "2026-10-13", "改过的午饭", "21", "99"),
                listOf("40", "EXPENSE", "5.00", "404", "", "5", "2026-10-14", "", "1", "1"),
                listOf("41", "EXPENSE", "6.00", "2", "", "5", "2026-10-14", "好的那笔", "1", "1"),
            ),
        )

        val preview = repo.previewCsvImport(files).getOrThrow()

        // 预览一个字节都不许写：连"同 id 覆盖"的值也还是旧的
        assertEquals(before, exportedData())
        assertEquals("余额宝", db.accountDao().getById(9L)?.name)
        assertEquals(2, db.accountDao().count())
        assertEquals(null, db.transactionDao().getById(40L))

        // 第 3 行引用不存在的账户 → 跳过；第 4 行是可用新行 → 算新增
        assertEquals(CsvImportCounts(accounts = 1, transactions = 1), preview.inserted)
        assertEquals(CsvImportCounts(accounts = 1, transactions = 1), preview.updated)
        assertEquals(4, preview.rowsToWrite)
        assertTrue(preview.hasAnythingToWrite)
        assertEquals(1, preview.skippedRows)
        assertEquals(CsvExporter.TRANSACTIONS_FILE, preview.problems.single().file)
        assertEquals(3, preview.problems.single().line)
        assertEquals(
            setOf(CsvExporter.CATEGORIES_FILE, CsvExporter.BUDGETS_FILE),
            preview.missingFiles.toSet(),
        )

        // 确认之后真导入：结果必须与预览一字不差
        val outcome = repo.importCsv(files).getOrThrow()
        assertEquals(preview.inserted, outcome.inserted)
        assertEquals(preview.updated, outcome.updated)
        assertEquals(preview.problems, outcome.problems)
        assertEquals(preview.missingFiles, outcome.missingFiles)
        assertEquals("余额宝（改）", db.accountDao().getById(9L)?.name)
    }

    @Test
    fun previewCsvImport_ofOurOwnExport_predictsOverwritesOnly() = runTest {
        seedSample()

        val preview = repo.previewCsvImport(repo.exportCsvFiles()).getOrThrow()

        assertEquals(0, preview.inserted.total)
        assertEquals(11, preview.updated.total)
        assertEquals(11, preview.rowsToWrite)
        assertEquals(0, preview.skippedRows)
        assertEquals(emptyList<CsvRowProblem>(), preview.problems)
        assertEquals(emptyList<String>(), preview.missingFiles)
    }

    @Test
    fun previewCsvImport_withNoFiles_saysNothingToWriteAndListsWhatIsMissing() = runTest {
        seedSample()

        val preview = repo.previewCsvImport(emptyMap()).getOrThrow()

        assertFalse(preview.hasAnythingToWrite)
        assertEquals(0, preview.rowsToWrite)
        assertEquals(0, preview.skippedRows)
        assertEquals(4, preview.missingFiles.size)
    }
}

