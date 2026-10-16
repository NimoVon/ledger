package com.nimo.ledger.data.backup

import com.nimo.ledger.domain.backup.AccountBackup
import com.nimo.ledger.domain.backup.BACKUP_APP_ID
import com.nimo.ledger.domain.backup.BACKUP_SCHEMA_VERSION
import com.nimo.ledger.domain.backup.BackupCodec
import com.nimo.ledger.domain.backup.BackupData
import com.nimo.ledger.domain.backup.BackupDecodeResult
import com.nimo.ledger.domain.backup.BackupFile
import com.nimo.ledger.domain.backup.BudgetBackup
import com.nimo.ledger.domain.backup.CategoryBackup
import com.nimo.ledger.domain.backup.TransactionBackup
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * 自动备份是"刚刚那次覆盖之前的救命副本"，不是长期存档：只留最近 MAX_KEPT_FILES 份。
     * 不清理的话，用户每导入一次就多一份 JSON，几年后目录里堆出上千个文件。
     */
    @Test
    fun write_prunesEverythingOlderThanTheDefaultLimit() = runTest {
        val dir = tempDir()
        var now = 1_000L
        val store = AutoBackupStore(dir) { now }
        repeat(AutoBackupStore.MAX_KEPT_FILES + 3) {
            now += 1_000L
            store.write("{}")
        }

        assertEquals(AutoBackupStore.MAX_KEPT_FILES, store.list().size)
        assertEquals("restore-" + now + ".json", store.latest()?.name)
        // 最旧的三份必须真的从磁盘上消失，而不是只在列表里不显示
        assertFalse(File(dir, "restore-2000.json").exists())
        assertFalse(File(dir, "restore-3000.json").exists())
        assertFalse(File(dir, "restore-4000.json").exists())
        assertTrue(File(dir, "restore-5000.json").exists())
    }

    /**
     * 时间戳位数不同时按文件名字符串排序会把 restore-9000.json 排到 restore-24000.json 前面，
     * latest() 就会指向旧备份、"只留最近 N 份"也会删错 —— 必须按解析出的时间戳排。
     */
    @Test
    fun list_sortsByRealTimestampNotByRawName() {
        val dir = tempDir()
        val oldest = File(dir, "restore-9000.json").apply { writeText("{}") }
        val newest = File(dir, "restore-24000.json").apply { writeText("{}") }
        val store = AutoBackupStore(dir)

        assertEquals(listOf(newest.name, oldest.name), store.list().map { it.name })
        assertEquals(newest.name, store.latest()?.name)
    }

    @Test
    fun write_honoursAConfiguredLimit() = runTest {
        val dir = tempDir()
        var now = 0L
        val store = AutoBackupStore(dir, maxKeptFiles = 2) { now }
        repeat(4) {
            now += 1
            store.write("{}")
        }

        assertEquals(listOf("restore-4.json", "restore-3.json"), store.list().map { it.name })
    }

    @Test
    fun latestInfo_reportsNameTimestampAndSize() = runTest {
        val dir = tempDir()
        val store = AutoBackupStore(dir) { 1_760_000_000_000L }
        store.write("{\"a\":1}")

        val info = store.latestInfo()
        assertNotNull(info)
        assertEquals("restore-1760000000000.json", info!!.name)
        assertEquals(1_760_000_000_000L, info.timestamp)
        assertEquals("{\"a\":1}".toByteArray(Charsets.UTF_8).size.toLong(), info.sizeBytes)
    }

    @Test
    fun write_leavesUnrelatedFilesAloneWhenPruning() = runTest {
        val dir = tempDir()
        val stray = File(dir, "manual.json").apply { writeText("用户自己放的") }
        val notes = File(dir, "notes.txt").apply { writeText("别的文件") }
        var now = 0L
        val store = AutoBackupStore(dir, maxKeptFiles = 2) { now }
        repeat(5) {
            now += 1
            store.write("{}")
        }

        assertEquals(2, store.list().size)
        assertTrue("清理只能动自动备份自己的文件", stray.exists())
        assertTrue(notes.exists())
    }
}
