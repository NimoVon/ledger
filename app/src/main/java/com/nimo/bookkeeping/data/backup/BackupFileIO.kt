package com.nimo.bookkeeping.data.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * 备份文件的字节层读写（F08c）：SAF 拿到的只是 [InputStream]/[OutputStream]，
 * 不假定有真实路径（计划 4.6 文件权限）。
 *
 * 读取必须有上限：用户可能选中一个几百 MB 的文件，读进内存再校验就晚了；
 * 超限直接抛 [IOException]，由调用方把错误如实报给用户。
 * 顺带吞掉开头可能存在的 UTF-8 BOM——有些编辑器写 JSON 会带上它，
 * 不处理的话解析第一步就报「不是合法的备份文件」，用户看不懂为什么。
 */
object BackupFileIO {

    /** 备份文件大小上限：远大于任何真实账目，只用来挡住"选错文件" */
    const val MAX_BACKUP_BYTES: Long = 64L * 1024L * 1024L

    /** UTF-8 读入全部文本（去 BOM），超过 [maxBytes] 抛 [IOException]。不关闭输入流。 */
    fun readText(input: InputStream, maxBytes: Long = MAX_BACKUP_BYTES): String {
        val buffer = ByteArray(8 * 1024)
        val sink = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IOException("备份文件超过上限 " + (maxBytes / 1024L / 1024L) + " MB")
            }
            sink.write(buffer, 0, read)
        }
        return stripBom(String(sink.toByteArray(), StandardCharsets.UTF_8))
    }

    /** UTF-8 写入并 flush；不关闭输出流。CSV 的 BOM 由内容自带，这里不额外加 */
    fun writeText(output: OutputStream, text: String) {
        output.write(text.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun stripBom(text: String): String =
        if (text.startsWith('\uFEFF')) text.substring(1) else text
}
