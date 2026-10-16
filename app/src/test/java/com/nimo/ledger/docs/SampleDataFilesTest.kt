package com.nimo.ledger.docs

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.repository.BackupRepository
import com.nimo.ledger.data.repository.AutoBackupWriter
import com.nimo.ledger.domain.backup.BackupCodec
import com.nimo.ledger.domain.backup.BackupDecodeResult
import com.nimo.ledger.domain.backup.CsvExporter
import com.nimo.ledger.domain.backup.CsvImportParser
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 交付物校验：docs/示例数据/ 下的示例备份必须**始终**是应用能原样吃下去的合法数据。
 * 只读，不写仓库文件；文件缺失或格式漂移会直接失败，避免示例数据腐烂。
 */
@RunWith(RobolectricTestRunner::class)
class SampleDataFilesTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BackupRepository
    private val backupWrites = mutableListOf<String>()
    private val autoBackup: AutoBackupWriter = { text -> backupWrites.add(text) }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repo = BackupRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun sampleDir(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return File(dir, "docs/示例数据")
            dir = dir.parentFile
        }
        error("找不到仓库根目录（settings.gradle.kts）")
    }

    private fun csvTexts(dir: File): Map<String, String> = linkedMapOf(
        CsvExporter.ACCOUNTS_FILE to File(dir, CsvExporter.ACCOUNTS_FILE).readText(Charsets.UTF_8),
        CsvExporter.CATEGORIES_FILE to File(dir, CsvExporter.CATEGORIES_FILE).readText(Charsets.UTF_8),
        CsvExporter.TRANSACTIONS_FILE to File(dir, CsvExporter.TRANSACTIONS_FILE).readText(Charsets.UTF_8),
        CsvExporter.BUDGETS_FILE to File(dir, CsvExporter.BUDGETS_FILE).readText(Charsets.UTF_8),
    )

    @Test
    fun sampleCsvFiles_areBomCrlfAndYuan() {
        val dir = sampleDir()
        for ((name, text) in csvTexts(dir)) {
            assertTrue(name + " 必须以 UTF-8 BOM 开头", text.startsWith(CsvExporter.BOM))
            assertTrue(name + " 必须用 CRLF 换行", text.contains("\r\n"))
            assertTrue(name + " 不应出现裸 LF", !text.replace("\r\n", "").contains("\n"))
            assertTrue(name + " 的换行数应等于行数", text.trimEnd().split("\r\n").size >= 2)
        }
        // 金额是「元、两位小数」：12345 元 应写成 12345.00
        assertTrue("accounts.csv 金额应为元且固定两位小数", csvTexts(dir)[CsvExporter.ACCOUNTS_FILE]!!.contains("12345.00"))
    }

    @Test
    fun sampleJsonBackup_decodesWithExpectedCounts() {
        val json = File(sampleDir(), "ledger-backup-sample.json").readText(Charsets.UTF_8)
        val decoded = BackupCodec.decode(json)
        assertTrue("示例 JSON 必须能被 decode：" + (decoded as? BackupDecodeResult.Failure)?.reason, decoded is BackupDecodeResult.Success)
        val data = (decoded as BackupDecodeResult.Success).file.data
        assertEquals(5, data.accounts.size)
        assertEquals(20, data.categories.size)
        assertEquals(15, data.transactions.size)
        assertEquals(4, data.budgets.size)
    }

    @Test
    fun sampleCsvFiles_parseWithNoProblems() {
        val dir = sampleDir()
        val parsed = CsvImportParser.parse(csvTexts(dir))
        assertEquals(
            "示例 CSV 不应有坏行：",
            emptyList<String>(),
            parsed.problems.map { it.file + " 第 " + it.line + " 行：" + it.reason },
        )
        assertEquals("四个文件都应存在", emptyList<String>(), parsed.missingFiles)
        assertEquals(5 + 20 + 15 + 4, parsed.totalRows)
    }

    @Test
    fun sampleCsvFiles_previewAgainstEmptyDatabase_writesEveryRow() = runTest {
        val preview = repo.previewCsvImport(csvTexts(sampleDir())).getOrThrow()
        assertEquals(44, preview.rowsToWrite)
        assertEquals(0, preview.skippedRows)
        assertTrue(preview.hasAnythingToWrite)
        assertEquals(0, db.accountDao().count())
    }

    @Test
    fun sampleJsonBackup_restoresAndSurvivesRoundTrip() = runTest {
        val json = File(sampleDir(), "ledger-backup-sample.json").readText(Charsets.UTF_8)
        val expected = (BackupCodec.decode(json) as BackupDecodeResult.Success).file.data
        assertTrue(repo.restoreFromJson(json, autoBackup).getOrThrow().let { true })
        val again = (BackupCodec.decode(repo.exportJson()) as BackupDecodeResult.Success).file.data
        assertEquals(expected, again)
        assertTrue("恢复前应写一份自动备份", backupWrites.isNotEmpty())
    }
}
