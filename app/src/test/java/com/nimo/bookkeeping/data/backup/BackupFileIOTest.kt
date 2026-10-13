package com.nimo.bookkeeping.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份文件的字节读写（F08c）。
 *
 * 读写都要有上限：用户可能选中一个巨大的文件，读到内存里再校验就晚了。
 * 超限必须抛异常——静静地读进来才叫事故。
 */
class BackupFileIOTest {

    @Test
    fun readText_decodesUtf8() {
        val bytes = "{\"名称\":\"餐饮 ¥12.50\"}".toByteArray(Charsets.UTF_8)
        val text = BackupFileIO.readText(ByteArrayInputStream(bytes))
        assertEquals("{\"名称\":\"餐饮 ¥12.50\"}", text)
    }

    @Test
    fun readText_stripsUtf8Bom() {
        // 有些编辑器会给 JSON 加 BOM，不加处理的话解析第一步就失败
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{}".toByteArray()
        assertEquals("{}", BackupFileIO.readText(ByteArrayInputStream(bytes)))
    }

    @Test
    fun readText_atTheLimitIsAccepted_andOneByteOverThrows() {
        val limit = 16L
        val ok = ByteArray(limit.toInt()) { 'a'.code.toByte() }
        // limit 是 Long、length 是 Int：写在一起会落到 assertEquals(Object, Object) 上，16L != 16
        assertEquals(limit.toInt(), BackupFileIO.readText(ByteArrayInputStream(ok), limit).length)

        val tooBig = ByteArray(limit.toInt() + 1) { 'a'.code.toByte() }
        val error = runCatching { BackupFileIO.readText(ByteArrayInputStream(tooBig), limit) }.exceptionOrNull()
        assertTrue("超限必须抛 IOException，实际：$error", error is IOException)
    }

    @Test
    fun writeText_writesUtf8BytesAndFlushes() {
        val out = ByteArrayOutputStream()
        BackupFileIO.writeText(out, "餐饮")
        assertEquals("餐饮", out.toByteArray().toString(Charsets.UTF_8))
    }
}
