package com.nimo.bookkeeping.domain.backup

import com.nimo.bookkeeping.domain.model.AccountType
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.validate.TransactionValidator
import com.nimo.bookkeeping.util.Dates
import com.nimo.bookkeeping.util.Money
import java.time.YearMonth

/**
 * CSV 导入里被跳过的一行。
 *
 * [line] 是**文件里的物理行号**（从 1 开始，表头算第 1 行；字段内换行会让后面的行号顺延），
 * 用户可以直接照着行号去改文件。[file] 是四个文件之一（accounts.csv 等）。
 */
data class CsvRowProblem(val file: String, val line: Int, val reason: String)

/**
 * CSV 解析结果：能用的行 + 跳过的行 + 目录里缺哪些文件。
 *
 * 缺文件不算错误（允许只导一部分，比如只导 transactions.csv），由调用方在结果里提示；
 * [problems] 已按 [CsvImportParser.MAX_PROBLEMS] 折叠。
 */
data class CsvImportParse(
    val accounts: List<AccountBackup> = emptyList(),
    val categories: List<CategoryBackup> = emptyList(),
    val transactions: List<TransactionBackup> = emptyList(),
    val budgets: List<BudgetBackup> = emptyList(),
    val problems: List<CsvRowProblem> = emptyList(),
    val missingFiles: List<String> = emptyList(),
    /** 每个被接受的行在原文件里的物理行号，key = 文件名 to id（导入阶段报引用错误时要用它）。 */
    val rowLines: Map<Pair<String, Long>, Int> = emptyMap(),
) {
    val totalRows: Int get() = accounts.size + categories.size + transactions.size + budgets.size
}

/**
 * CSV 导入的解析层（F14），只做「文本 → 备份模型」的翻译与行层校验，不碰数据库。
 *
 * 表头按 [CsvExporter] 的稳定契约（英文列名）；列名匹配忽略大小写与前后空格，多出来的列忽略
 * （向前兼容：以后加列不影响旧版导入）。金额列是**元、最多两位小数**，解析回整数分。
 *
 * 校验口径与数据库触发器（data/local/SchemaHardening.kt）保持一致：宁可在这里整行跳过并报告行号，
 * 也不让一行坏数据把整批导入回滚掉。
 */
object CsvImportParser {

    /** 最多列多少条行问题（再多就折叠成一条汇总，避免结果弹窗刷屏）。 */
    const val MAX_PROBLEMS = 20

    fun parse(files: Map<String, String>): CsvImportParse {
        val problems = mutableListOf<CsvRowProblem>()
        val missing = mutableListOf<String>()
        val rowLines = mutableMapOf<Pair<String, Long>, Int>()

        fun read(name: String): String? {
            val text = files[name]
            if (text == null) missing += name
            return text
        }

        val accounts = read(CsvExporter.ACCOUNTS_FILE)?.let { parseAccounts(it, problems, rowLines) } ?: emptyList()
        val categories = read(CsvExporter.CATEGORIES_FILE)?.let { parseCategories(it, problems, rowLines) } ?: emptyList()
        val transactions =
            read(CsvExporter.TRANSACTIONS_FILE)?.let { parseTransactions(it, problems, rowLines) } ?: emptyList()
        val budgets = read(CsvExporter.BUDGETS_FILE)?.let { parseBudgets(it, problems, rowLines) } ?: emptyList()

        return CsvImportParse(
            accounts = accounts,
            categories = categories,
            transactions = transactions,
            budgets = budgets,
            problems = problems.toList(),
            missingFiles = missing,
            rowLines = rowLines,
        )
    }

    // ---------------------------------------------------------------- 单文件解析

    private fun parseAccounts(
        text: String,
        problems: MutableList<CsvRowProblem>,
        lines: MutableMap<Pair<String, Long>, Int>,
    ): List<AccountBackup> {
        val file = CsvExporter.ACCOUNTS_FILE
        val table = readTable(file, text, CsvExporter.ACCOUNTS_HEADER, problems) ?: return emptyList()
        val rows = mutableListOf<AccountBackup>()
        val seen = mutableSetOf<Long>()
        for ((line, cells) in table.records) {
            val r = RowReader(table.columns, cells)
            val id = r.id()
            val name = r.cell("name").also { if (it.isEmpty()) r.fail("name 不能为空") }
            val type = r.enumName("type", AccountType.entries.map { it.name })
            val initialBalance = r.cents("initial_balance")
            val includeInTotal = r.optionalBool("include_in_total", default = true)
            val isArchived = r.optionalBool("is_archived")
            val sortOrder = r.optionalInt("sort_order")
            val createdAt = r.timestamp("created_at")
            val updatedAt = r.timestamp("updated_at")
            val problem = r.takeProblem()
            if (problem != null) {
                problems += CsvRowProblem(file, line, problem)
                continue
            }
            if (id == null || type == null || initialBalance == null) continue
            if (!seen.add(id)) {
                problems += CsvRowProblem(file, line, "id $id 在文件里重复出现")
                continue
            }
            lines[file to id] = line
            rows += AccountBackup(
                id = id,
                name = name,
                type = type,
                initialBalance = initialBalance,
                includeInTotal = includeInTotal,
                isArchived = isArchived,
                sortOrder = sortOrder,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }
        return rows
    }

    private fun parseCategories(
        text: String,
        problems: MutableList<CsvRowProblem>,
        lines: MutableMap<Pair<String, Long>, Int>,
    ): List<CategoryBackup> {
        val file = CsvExporter.CATEGORIES_FILE
        val table = readTable(file, text, CsvExporter.CATEGORIES_HEADER, problems) ?: return emptyList()
        val rows = mutableListOf<CategoryBackup>()
        val seen = mutableSetOf<Long>()
        for ((line, cells) in table.records) {
            val r = RowReader(table.columns, cells)
            val id = r.id()
            val name = r.cell("name").also { if (it.isEmpty()) r.fail("name 不能为空") }
            val kind = r.enumName("kind", CategoryKind.entries.map { it.name })
            val parentId = r.optionalLong("parent_id")
            val icon = r.cell("icon").ifEmpty { null }
            val color = r.cell("color").ifEmpty { null }
            val isArchived = r.optionalBool("is_archived")
            val sortOrder = r.optionalInt("sort_order")
            if (id != null && parentId == id) r.fail("parent_id 不能是自己")
            val problem = r.takeProblem()
            if (problem != null) {
                problems += CsvRowProblem(file, line, problem)
                continue
            }
            if (id == null || kind == null) continue
            if (!seen.add(id)) {
                problems += CsvRowProblem(file, line, "id $id 在文件里重复出现")
                continue
            }
            lines[file to id] = line
            rows += CategoryBackup(
                id = id,
                name = name,
                kind = kind,
                parentId = parentId,
                icon = icon,
                color = color,
                isArchived = isArchived,
                sortOrder = sortOrder,
            )
        }
        return rows
    }

    private fun parseTransactions(
        text: String,
        problems: MutableList<CsvRowProblem>,
        lines: MutableMap<Pair<String, Long>, Int>,
    ): List<TransactionBackup> {
        val file = CsvExporter.TRANSACTIONS_FILE
        val table = readTable(file, text, CsvExporter.TRANSACTIONS_HEADER, problems) ?: return emptyList()
        val rows = mutableListOf<TransactionBackup>()
        val seen = mutableSetOf<Long>()
        for ((line, cells) in table.records) {
            val r = RowReader(table.columns, cells)
            val id = r.id()
            val type = r.enumName("type", TransactionType.entries.map { it.name })
            val amount = r.cents("amount")
            val accountId = r.optionalLong("account_id")
            val toAccountId = r.optionalLong("to_account_id")
            val categoryId = r.optionalLong("category_id")
            val occurredOn = r.isoDate("occurred_on")
            val note = r.note()
            val createdAt = r.timestamp("created_at")
            val updatedAt = r.timestamp("updated_at")
            when (type) {
                "TRANSFER" -> if (amount != null && amount <= 0L) r.fail("转账金额必须大于 0")
                "BALANCE_ADJUST" -> if (amount != null && amount == 0L) r.fail("余额调整金额不能为 0")
                "EXPENSE", "INCOME" -> if (amount != null && amount <= 0L) r.fail("收支金额必须大于 0")
            }
            if (type != null && accountId == null) r.fail("account_id 不能为空")
            when (type) {
                "TRANSFER" -> {
                    if (toAccountId == null) r.fail("转账必须有 to_account_id")
                    if (accountId != null && accountId == toAccountId) r.fail("转出账户与转入账户不能相同")
                    if (categoryId != null) r.fail("转账不能有 category_id")
                }
                "BALANCE_ADJUST" -> if (categoryId != null) r.fail("余额调整不能有 category_id")
                "EXPENSE", "INCOME" -> if (categoryId == null) r.fail("收支必须有 category_id")
            }
            val problem = r.takeProblem()
            if (problem != null) {
                problems += CsvRowProblem(file, line, problem)
                continue
            }
            if (id == null || type == null || amount == null || accountId == null) continue
            if (!seen.add(id)) {
                problems += CsvRowProblem(file, line, "id $id 在文件里重复出现")
                continue
            }
            lines[file to id] = line
            rows += TransactionBackup(
                id = id,
                type = type,
                amount = amount,
                accountId = accountId,
                toAccountId = toAccountId,
                categoryId = categoryId,
                occurredOn = occurredOn ?: Dates.today(),
                note = note,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }
        return rows
    }

    private fun parseBudgets(
        text: String,
        problems: MutableList<CsvRowProblem>,
        lines: MutableMap<Pair<String, Long>, Int>,
    ): List<BudgetBackup> {
        val file = CsvExporter.BUDGETS_FILE
        val table = readTable(file, text, CsvExporter.BUDGETS_HEADER, problems) ?: return emptyList()
        val rows = mutableListOf<BudgetBackup>()
        val seenIds = mutableSetOf<Long>()
        val seenKeys = mutableSetOf<Pair<String, Long?>>()
        for ((line, cells) in table.records) {
            val r = RowReader(table.columns, cells)
            val id = r.id()
            val month = r.month("month")
            val categoryId = r.optionalLong("category_id")
            val amount = r.cents("amount")
            if (amount != null && amount <= 0L) r.fail("预算金额必须大于 0")
            val problem = r.takeProblem()
            if (problem != null) {
                problems += CsvRowProblem(file, line, problem)
                continue
            }
            if (id == null || month == null || amount == null) continue
            if (!seenIds.add(id)) {
                problems += CsvRowProblem(file, line, "id $id 在文件里重复出现")
                continue
            }
            if (!seenKeys.add(month to categoryId)) {
                problems += CsvRowProblem(file, line, "同一个月同一个分类的预算只能有一份（" + month + "）")
                continue
            }
            lines[file to id] = line
            rows += BudgetBackup(id = id, month = month, categoryId = categoryId, amount = amount)
        }
        return rows
    }

    // ---------------------------------------------------------------- 表头与记录切分

    private class Table(val columns: Map<String, Int>, val records: List<Pair<Int, List<String>>>)

    /**
     * 读取一个 CSV 文件的表头与数据行。缺必需列时整份文件跳过（记一条文件级问题，行号=表头行）。
     */
    private fun readTable(
        file: String,
        text: String,
        required: List<String>,
        problems: MutableList<CsvRowProblem>,
    ): Table? {
        val records = readRecords(text).filter { (_, cells) -> cells.any { it.isNotBlank() } }
        if (records.isEmpty()) {
            problems += CsvRowProblem(file, 1, "文件是空的（至少要有表头）")
            return null
        }
        val first = records.first()
        val headerLine = first.first
        val header = first.second
        val columns = header.mapIndexedNotNull { index, name ->
            val key = name.trim().lowercase()
            if (key.isEmpty()) null else key to index
        }.toMap()
        val missing = required.filter { it !in columns }
        if (missing.isNotEmpty()) {
            problems += CsvRowProblem(file, headerLine, "表头缺少列：" + missing.joinToString("、"))
            return null
        }
        return Table(columns, records.drop(1))
    }

    /**
     * RFC 4180 记录切分：去 BOM、支持引号包裹、内部 "" 转义、字段内换行、CRLF/LF。
     *
     * 返回「该记录起始的物理行号 to 各字段原文」，行号用于给用户报位置（字段内换行会把后面的行号顺延）。
     */
    private fun readRecords(text: String): List<Pair<Int, List<String>>> {
        val body = text.removePrefix(CsvExporter.BOM)
        val records = mutableListOf<Pair<Int, List<String>>>()
        var cells = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var line = 1
        var recordLine = 1
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < body.length && body[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> {
                        if (c == '\n') line++
                        field.append(c)
                    }
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        cells += field.toString()
                        field.clear()
                    }
                    '\r' -> Unit
                    '\n' -> {
                        cells += field.toString()
                        field.clear()
                        records += recordLine to cells.toList()
                        cells = mutableListOf()
                        line++
                        recordLine = line
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || cells.isNotEmpty()) {
            cells += field.toString()
            records += recordLine to cells.toList()
        }
        return records
    }

    // ---------------------------------------------------------------- 字段读取

    private class RowReader(private val columns: Map<String, Int>, private val cells: List<String>) {

        private var problem: String? = null

        fun fail(reason: String) {
            if (problem == null) problem = reason
        }

        fun takeProblem(): String? = problem

        private fun raw(name: String): String = columns[name]?.let { cells.getOrNull(it) } ?: ""

        fun cell(name: String): String = raw(name).trim()

        fun id(): Long? {
            val text = cell("id")
            val value = text.toLongOrNull()
            if (value == null || value <= 0L) fail("id「" + text + "」不是有效的正整数 ID")
            return value
        }

        /** 可空引用列：空字符串 = 没有（null）。 */
        fun optionalLong(name: String): Long? {
            val text = cell(name)
            if (text.isEmpty()) return null
            val value = text.toLongOrNull()
            if (value == null || value <= 0L) {
                fail(name + "「" + text + "」不是有效的正整数 ID")
                return null
            }
            return value
        }

        fun optionalInt(name: String, default: Int = 0): Int {
            val text = cell(name)
            if (text.isEmpty()) return default
            val value = text.toIntOrNull()
            if (value == null) {
                fail(name + "「" + text + "」不是整数")
                return default
            }
            return value
        }

        fun optionalBool(name: String, default: Boolean = false): Boolean {
            val text = cell(name)
            if (text.isEmpty()) return default
            return when (text.lowercase()) {
                "true" -> true
                "false" -> false
                else -> {
                    fail(name + "「" + text + "」只能是 true 或 false")
                    default
                }
            }
        }

        /** 金额列：元、最多两位小数，允许 0 与负数（引用与上下限由调用方判断）。 */
        fun cents(name: String): Long? {
            val text = cell(name)
            val value = Money.parseCentsAllowingZero(text)
            if (value == null) {
                fail(
                    name + "「" + text + "」不是合法的金额（元，最多两位小数，且不超过 " +
                        Money.formatCurrency(Money.MAX_AMOUNT_CENTS) + "）",
                )
            }
            return value
        }

        fun enumName(name: String, allowed: List<String>): String? {
            val text = cell(name)
            if (text.isEmpty()) {
                fail(name + " 不能为空")
                return null
            }
            val hit = allowed.firstOrNull { it.equals(text, ignoreCase = true) }
            if (hit == null) {
                fail(name + "「" + text + "」不是可识别的值（可用：" + allowed.joinToString("/") + "）")
            }
            return hit
        }

        fun isoDate(name: String): String? {
            val text = cell(name)
            if (text.isEmpty()) {
                fail(name + " 不能为空")
                return null
            }
            if (!Dates.isValidIsoDate(text)) {
                fail(name + "「" + text + "」不是有效日期（YYYY-MM-DD）")
                return null
            }
            return text
        }

        fun month(name: String): String? {
            val text = cell(name)
            val parsed = runCatching { YearMonth.parse(text) }.getOrNull()
            if (parsed == null) {
                fail(name + "「" + text + "」不是有效月份（YYYY-MM）")
                return null
            }
            return parsed.toString()
        }

        /** 备注保留原文（不 trim），空字符串视为没有备注。 */
        fun note(): String? {
            val text = raw("note")
            if (text.length > TransactionValidator.MAX_NOTE_LENGTH) {
                fail("note 太长（最多 " + TransactionValidator.MAX_NOTE_LENGTH + " 字）")
                return null
            }
            return text.ifEmpty { null }
        }

        fun timestamp(name: String, default: Long = 0L): Long {
            val text = cell(name)
            if (text.isEmpty()) return default
            val value = text.toLongOrNull()
            if (value == null) {
                fail(name + "「" + text + "」不是毫秒时间戳")
                return default
            }
            return value
        }
    }

    // ---------------------------------------------------------------- 问题条数折叠

    /**
     * 把问题列表折叠到 [MAX_PROBLEMS] 条（解析本身**不折叠**，因为导入流程还会往后面追加
     * 「引用不存在」这类问题；两处必须用同一套折叠口径，所以放在这里给两边共用）。
     */
    fun foldProblems(problems: List<CsvRowProblem>): List<CsvRowProblem> {
        if (problems.size <= MAX_PROBLEMS) return problems
        val rest = problems.size - MAX_PROBLEMS
        return problems.take(MAX_PROBLEMS) + CsvRowProblem("（更多）", 0, "另有 " + rest + " 项问题未列出")
    }
}
