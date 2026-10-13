package com.nimo.bookkeeping.data.backup

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 恢复前的自动备份（计划 4.6「恢复前：先…生成可恢复的自动备份；备份失败则中止恢复」）。
 *
 * 落在应用私有目录，不走 SAF：恢复动作是"确认后立刻发生"的，中间再弹一次选路径，
 * 用户一点取消就变成"要不要恢复"的二义流程；而且自动备份失败必须能**确定地**中止恢复。
 * 代价是它随应用卸载消失（用户自己的手动导出才是长期备份），界面上要说清楚。
 *
 * 先写 .tmp 再改名：中途掉电最多留下一个 .tmp，不会出现"半截 JSON 被当成备份"。
 * 目录不可用（路径上是个文件、没有权限）一律抛 [IOException]，
 * 让调用方（[com.nimo.bookkeeping.data.repository.BackupRepository.restore]）中止恢复。
 */
class AutoBackupStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 自动备份目录的绝对路径，用于在界面上如实告知用户备份放在哪 */
    val directoryPath: String get() = directory.absolutePath

    /** 备份文件名：[prefix] + 时间戳 + .json，按名字排序即按时间排序 */
    fun fileFor(timestamp: Long): File = File(directory, PREFIX + timestamp + SUFFIX)

    /** 写一份自动备份并返回落地的文件；失败抛 [IOException]（调用方必须中止后续动作） */
    suspend fun write(snapshotJson: String): File = withContext(Dispatchers.IO) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建自动备份目录：" + directory.absolutePath)
        }
        if (!directory.isDirectory) {
            throw IOException("自动备份目录不可用：" + directory.absolutePath)
        }
        val target = fileFor(clock())
        val temp = File(directory, target.name + ".tmp")
        try {
            temp.writeText(snapshotJson, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                throw IOException("自动备份写入失败：" + target.name)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        target
    }

    /** 已有的自动备份，最新的在前（目录不存在或读不了时返回空表，不抛） */
    fun list(): List<File> =
        (directory.listFiles { file: File -> file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            ?: emptyArray())
            .sortedByDescending { it.name }

    /** 最近一次自动备份 */
    fun latest(): File? = list().firstOrNull()

    private companion object {
        const val PREFIX = "restore-"
        const val SUFFIX = ".json"
    }
}
