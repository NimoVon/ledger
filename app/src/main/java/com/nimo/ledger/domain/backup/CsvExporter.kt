package com.nimo.ledger.domain.backup

import com.nimo.ledger.util.Money

/**
 * CSV 导出（计划 4.6）。四条硬规则：
 *
 * 1. 每个文件都以 **UTF-8 BOM** 开头，Excel 双击直开不乱码；
 * 2. **金额列是元、固定两位小数**（[Money.format]），而 JSON 与数据库是整数分——
 *    两种单位不许混，测试里两边都钉住；
 * 3. 字段按 RFC 4180 转义：含逗号/引号/换行的值用双引号包起来、内部引号写两遍；
 * 4. 表头列名是**稳定契约**：F14 的 CSV 导入按这些列名解析，改名 = 换格式。
 *
 * 行分隔用 CRLF，同样是为了 Excel。
 */
object CsvExporter {

    const val BOM = "\uFEFF"

    private const val EOL = "\r\n"
    private const val QUOTE = '"'
    private val QUOTE_STR = QUOTE.toString()

    val ACCOUNTS_FILE = "accounts.csv"
    val CATEGORIES_FILE = "categories.csv"
    val TRANSACTIONS_FILE = "transactions.csv"
    val BUDGETS_FILE = "budgets.csv"

    val ACCOUNTS_HEADER = listOf(
        "id", "name", "type", "initial_balance", "include_in_total", "is_archived", "sort_order", "created_at", "updated_at",
    )
    val CATEGORIES_HEADER = listOf(
        "id", "name", "kind", "parent_id", "icon", "color", "is_archived", "sort_order",
    )
    val TRANSACTIONS_HEADER = listOf(
        "id", "type", "amount", "account_id", "to_account_id", "category_id", "occurred_on", "note", "created_at", "updated_at",
    )
    val BUDGETS_HEADER = listOf("id", "month", "category_id", "amount")

    /** 文件名 → 文件内容。[budgets] 目前为空（预算功能未上线），但文件照导，格式先定死。 */
    fun export(
        accounts: List<AccountBackup>,
        categories: List<CategoryBackup>,
        transactions: List<TransactionBackup>,
        budgets: List<BudgetBackup>,
    ): Map<String, String> = linkedMapOf(
        ACCOUNTS_FILE to accountsCsv(accounts),
        CATEGORIES_FILE to categoriesCsv(categories),
        TRANSACTIONS_FILE to transactionsCsv(transactions),
        BUDGETS_FILE to budgetsCsv(budgets),
    )

    fun accountsCsv(rows: List<AccountBackup>): String = buildString {
        append(BOM)
        append(line(ACCOUNTS_HEADER))
        for (a in rows) {
            append(
                line(
                    listOf(
                        text(a.id), a.name, a.type, yuan(a.initialBalance),
                        text(a.includeInTotal), text(a.isArchived), text(a.sortOrder),
                        text(a.createdAt), text(a.updatedAt),
                    ),
                ),
            )
        }
    }

    fun categoriesCsv(rows: List<CategoryBackup>): String = buildString {
        append(BOM)
        append(line(CATEGORIES_HEADER))
        for (c in rows) {
            append(
                line(
                    listOf(
                        text(c.id), c.name, c.kind, nullable(c.parentId),
                        c.icon ?: "", c.color ?: "", text(c.isArchived), text(c.sortOrder),
                    ),
                ),
            )
        }
    }

    fun transactionsCsv(rows: List<TransactionBackup>): String = buildString {
        append(BOM)
        append(line(TRANSACTIONS_HEADER))
        for (t in rows) {
            append(
                line(
                    listOf(
                        text(t.id), t.type, yuan(t.amount), nullable(t.accountId), nullable(t.toAccountId),
                        nullable(t.categoryId), t.occurredOn, t.note ?: "",
                        text(t.createdAt), text(t.updatedAt),
                    ),
                ),
            )
        }
    }

    fun budgetsCsv(rows: List<BudgetBackup>): String = buildString {
        append(BOM)
        append(line(BUDGETS_HEADER))
        for (b in rows) {
            append(line(listOf(text(b.id), b.month, nullable(b.categoryId), yuan(b.amount))))
        }
    }

    /** 分 → 元文本（两位小数、不带千分位、不带货币符号），Excel 能当数字用 */
    private fun yuan(cents: Long): String = Money.format(cents)

    private fun text(value: Long): String = value.toString()
    private fun text(value: Int): String = value.toString()
    private fun text(value: Boolean): String = value.toString()
    private fun nullable(value: Long?): String = if (value == null) "" else value.toString()

    private fun line(cells: List<String>): String = cells.joinToString(",") { cell(it) } + EOL

    private fun cell(value: String): String =
        if (value.any { it == ',' || it == QUOTE || it == '\n' || it == '\r' }) {
            QUOTE_STR + value.replace(QUOTE_STR, QUOTE_STR + QUOTE_STR) + QUOTE_STR
        } else {
            value
        }
}
