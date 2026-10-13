package com.nimo.bookkeeping.data.backup

import com.nimo.bookkeeping.domain.backup.AccountBackup
import com.nimo.bookkeeping.domain.backup.BACKUP_APP_ID
import com.nimo.bookkeeping.domain.backup.BACKUP_SCHEMA_VERSION
import com.nimo.bookkeeping.domain.backup.BackupCodec
import com.nimo.bookkeeping.domain.backup.BackupData
import com.nimo.bookkeeping.domain.backup.BackupDecodeResult
import com.nimo.bookkeeping.domain.backup.BackupFile
import com.nimo.bookkeeping.domain.backup.BudgetBackup
import com.nimo.bookkeeping.domain.backup.CategoryBackup
import com.nimo.bookkeeping.domain.backup.TransactionBackup
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复前自动备份（F08c）。
 *
 * 重点不是"写进去了"，而是三件事：内容是**能重新解码**的完整备份、写入失败必须抛、
 * 以及不会留下半截文件被当成备份（先 .tmp 再改名）。
 */
class AutoBackupStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("auto-backup-test").toFile()

    @Test
    fun write_producesAFileThatDecodesBackToTheSameData() = runTest {
        val dir = tempDir()
        val store = AutoBackupStore(dir) { 1_760_000_000_000L }
        val original = BackupFile(
            app = BACKUP_APP_ID,
            schemaVersion = BACKUP_SCHEMA_VERSION,
            exportedAt = 1_760_000_000_000L,
            data = BackupData(
                accounts = listOf(AccountBackup(id = 2L, name = "招行", type = "CREDIT", initialBalance = -12_345L)),
                categories = listOf(CategoryBackup(id = 5L, name = "餐饮", kind = "EXPENSE")),
                transactions = listOf(
                    TransactionBackup(
                        id = 7L, type = "EXPENSE", amount = 1_200L, accountId = 2L, categoryId = 5L,
                        occurredOn = "2026-10-13", note = "午饭",
                    ),
                ),
                budgets = listOf(BudgetBackup(id = 11L, month = "2026-10", categoryId = 5L, amount = 100_000L)),
            ),
        )

        val file = store.write(BackupCodec.encode(original))

        assertEquals("restore-1760000000000.json", file.name)
        assertEquals(dir, file.parentFile)
        val decoded = BackupCodec.decode(file.readText(Charsets.UTF_8))
        assertTrue("自动备份必须还能被读回来，实际：$decoded", decoded is BackupDecodeResult.Success)
        assertEquals(original, (decoded as BackupDecodeResult.Success).file)
    }

    @Test
    fun write_whenTheDirectoryPathIsAFile_throwsInsteadOfSilentlySucceeding() = runTest {
        val parent = tempDir()
        val blocked = File(parent, "auto-backup").apply { writeText("我不是目录") }
        val store = AutoBackupStore(blocked)

        val error = runCatching { store.write("{}") }.exceptionOrNull()
        assertTrue("目录不可用必须抛 IOException，实际：$error", error is IOException)
        assertTrue("不能破坏原本的文件", blocked.readText() == "我不是目录")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun list_andLatest_returnNewestFirst() = runTest {
        val dir = tempDir()
        var now = 1_000L
        val store = AutoBackupStore(dir) { now }

        store.write("{}")
        now = 2_000L
        store.write("{}")

        assertEquals(listOf("restore-2000.json", "restore-1000.json"), store.list().map { it.name })
        assertEquals("restore-2000.json", store.latest()?.name)
        assertNotNull(store.latest())
    }

    @Test
    fun list_onAMissingDirectory_isEmptyRatherThanThrowing() {
        assertTrue(AutoBackupStore(File(tempDir(), "nope")).list().isEmpty())
        assertEquals(null, AutoBackupStore(File(tempDir(), "nope")).latest())
    }
}
