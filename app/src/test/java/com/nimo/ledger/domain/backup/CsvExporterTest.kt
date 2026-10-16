package com.nimo.ledger.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 导出（计划 4.6）：BOM、元/分两种单位、RFC 4180 转义、表头列名稳定性。
 * 表头断言故意写死字符串——改列名会让 F14 的导入错位，必须在这里被挡住。
 */
class CsvExporterTest {

    private fun acc(id: Long = 1L, name: String = "现金", type: String = "CASH", initialBalance: Long = 123456L) =
        AccountBackup(id = id, name = name, type = type, initialBalance = initialBalance)

    private fun cat(id: Long = 1L, name: String = "餐饮", kind: String = "EXPENSE", parentId: Long? = null) =
        CategoryBackup(id = id, name = name, kind = kind, parentId = parentId)

    private fun tx(
        id: Long = 1L,
        type: String = "EXPENSE",
        amount: Long = 1200L,
        accountId: Long? = 1L,
        toAccountId: Long? = null,
        categoryId: Long? = 1L,
        note: String? = null,
    ) = TransactionBackup(
        id = id, type = type, amount = amount, accountId = accountId, toAccountId = toAccountId,
        categoryId = categoryId, occurredOn = "2026-10-13", note = note,
    )

    /** 去掉 BOM 后按 CRLF 切行（不含引号内换行的用例才用它） */
    private fun lines(csv: String): List<String> =
        csv.removePrefix(CsvExporter.BOM).split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun accountsCsv_hasBomHeaderAndYuanAmount() {
        val csv = CsvExporter.accountsCsv(listOf(acc(initialBalance = 123456L)))
        assertTrue(csv.startsWith("\uFEFF"))
        assertEquals(
            listOf(
                "id,name,type,initial_balance,include_in_total,is_archived,sort_order,created_at,updated_at",
                "1,现金,CASH,1234.56,true,false,0,0,0",
            ),
            lines(csv),
        )
    }

    @Test
    fun transactionsCsv_moneyIsYuanWhileJsonKeepsCents() {
        val row = tx(amount = 1200L)
        val csvLines = lines(CsvExporter.transactionsCsv(listOf(row)))
        assertEquals(
            "id,type,amount,account_id,to_account_id,category_id,occurred_on,note,created_at,updated_at",
            csvLines[0],
        )
        assertEquals("1,EXPENSE,12.00,1,,1,2026-10-13,,0,0", csvLines[1])

        // JSON 侧仍是整数分：同一条数据 1200 分 == CSV 里的 12.00 元
        val json = BackupCodec.encode(BackupFile(data = BackupData(transactions = listOf(row))))
        assertTrue(json, json.contains("1200"))
        assertTrue(json, !json.contains("12.00"))
    }

    @Test
    fun transactionsCsv_escapesCommaQuoteAndNewline() {
        val note = "他说\"好\",然后\n走了"
        val csv = CsvExporter.transactionsCsv(listOf(tx(note = note)))
        val expectedCell = "\"" + note.replace("\"", "\"\"") + "\""
        assertTrue(csv, csv.contains(expectedCell))
    }

    @Test
    fun categoriesCsv_nullParentAndIconAreEmptyFields() {
        val csvLines = lines(CsvExporter.categoriesCsv(listOf(cat(parentId = null))))
        assertEquals("id,name,kind,parent_id,icon,color,is_archived,sort_order", csvLines[0])
        assertEquals("1,餐饮,EXPENSE,,,,false,0", csvLines[1])
    }

    @Test
    fun budgetsCsv_headersAndYuanAmount() {
        val csvLines = lines(
            CsvExporter.budgetsCsv(listOf(BudgetBackup(id = 3L, month = "2026-10", categoryId = null, amount = 100000L))),
        )
        assertEquals("id,month,category_id,amount", csvLines[0])
        assertEquals("3,2026-10,,1000.00", csvLines[1])
    }

    @Test
    fun negativeAmount_keepsMinusSign() {
        val csvLines = lines(
            CsvExporter.transactionsCsv(listOf(tx(type = "BALANCE_ADJUST", amount = -60000L, categoryId = null))),
        )
        assertEquals("1,BALANCE_ADJUST,-600.00,1,,,2026-10-13,,0,0", csvLines[1])
    }

    @Test
    fun export_returnsFourNamedFilesEachWithBom() {
        val files = CsvExporter.export(listOf(acc()), listOf(cat()), listOf(tx()), emptyList())
        assertEquals(
            listOf("accounts.csv", "categories.csv", "transactions.csv", "budgets.csv"),
            files.keys.toList(),
        )
        files.forEach { (name, content) -> assertTrue(name, content.startsWith(CsvExporter.BOM)) }
    }
}
