package com.nimo.ledger.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvImportParserTest {

    private val accounts = listOf(
        AccountBackup(
            id = 1,
            name = "CMB",
            type = "DEBIT",
            initialBalance = 123400,
            includeInTotal = true,
            isArchived = false,
            sortOrder = 0,
            createdAt = 1000L,
            updatedAt = 2000L,
        ),
        AccountBackup(
            id = 2,
            name = "Alipay",
            type = "ALIPAY",
            initialBalance = 0,
            includeInTotal = false,
            isArchived = true,
            sortOrder = 1,
            createdAt = 0L,
            updatedAt = 0L,
        ),
    )

    private val categories = listOf(
        CategoryBackup(
            id = 6,
            name = "Entertainment",
            kind = "EXPENSE",
            parentId = null,
            icon = "🎮",
            color = null,
            isArchived = false,
            sortOrder = 0,
        ),
        CategoryBackup(
            id = 7,
            name = "Cinema",
            kind = "EXPENSE",
            parentId = 6,
            icon = null,
            color = "#FF0000",
            isArchived = true,
            sortOrder = 1,
        ),
    )

    private val transactions = listOf(
        TransactionBackup(
            id = 2,
            type = "EXPENSE",
            amount = 60000,
            accountId = 1,
            toAccountId = null,
            categoryId = 6,
            occurredOn = "2026-10-12",
            note = null,
            createdAt = 0L,
            updatedAt = 0L,
        ),
        TransactionBackup(
            id = 3,
            type = "TRANSFER",
            amount = 20000,
            accountId = 1,
            toAccountId = 2,
            categoryId = null,
            occurredOn = "2026-10-13",
            note = "lunch, with \"quotes\" and\nsecond line",
            createdAt = 0L,
            updatedAt = 0L,
        ),
    )

    private val budgets = listOf(
        BudgetBackup(id = 9, month = "2026-10", categoryId = null, amount = 500000),
        BudgetBackup(id = 10, month = "2026-11", categoryId = 6, amount = 100000),
    )

    private fun text(header: List<String>, vararg rows: List<String>): String =
        CsvExporter.BOM + (listOf(header) + rows).joinToString("\r\n") { it.joinToString(",") } + "\r\n"

    @Test
    fun roundTrip_exportThenParse_keepsEveryField() {
        val files = CsvExporter.export(accounts, categories, transactions, budgets)

        val parsed = CsvImportParser.parse(files)

        assertEquals(emptyList<CsvRowProblem>(), parsed.problems)
        assertEquals(emptyList<String>(), parsed.missingFiles)
        assertEquals(accounts, parsed.accounts)
        assertEquals(categories, parsed.categories)
        assertEquals(transactions, parsed.transactions)
        assertEquals(budgets, parsed.budgets)
        assertEquals(8, parsed.totalRows)
    }

    @Test
    fun englishContentAndLowercaseEnumNames_areAcceptedAndKeptAsIs() {
        val body = text(
            CsvExporter.TRANSACTIONS_HEADER,
            listOf("2", "expense", "12.50", "1", "", "6", "2026-10-12", "lunch with Alice", "0", "0"),
        )

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.TRANSACTIONS_FILE to body))

        assertEquals(emptyList<CsvRowProblem>(), parsed.problems)
        val row = parsed.transactions.single()
        assertEquals("EXPENSE", row.type)
        assertEquals(1250L, row.amount)
        assertEquals("lunch with Alice", row.note)
    }

    @Test
    fun unknownEnglishType_skipsOnlyThatRowAndReportsItsPhysicalLine() {
        val body = text(
            CsvExporter.TRANSACTIONS_HEADER,
            listOf("4", "EXPENSE", "12.50", "1", "", "6", "2026-10-12", "", "0", "0"),
            listOf("5", "MOVIE", "3.00", "1", "", "6", "2026-10-12", "", "0", "0"),
            listOf("6", "income", "5.00", "1", "", "6", "2026-10-12", "", "0", "0"),
        )

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.TRANSACTIONS_FILE to body))

        assertEquals(listOf(4L, 6L), parsed.transactions.map { it.id })
        val problem = parsed.problems.single()
        assertEquals(CsvExporter.TRANSACTIONS_FILE, problem.file)
        assertEquals(3, problem.line)
        assertTrue(problem.reason, problem.reason.contains("MOVIE"))
    }

    @Test
    fun missingRequiredColumn_skipsThatFileOnlyAndKeepsParsingTheOthers() {
        val files = CsvExporter.export(accounts, categories, transactions, budgets).toMutableMap()
        files[CsvExporter.TRANSACTIONS_FILE] =
            CsvExporter.BOM +
                "id,type,amount,account_id,to_account_id,category_id,occurred_on,note,created_at\r\n" +
                "2,EXPENSE,12.50,1,,6,2026-10-12,,0\r\n"

        val parsed = CsvImportParser.parse(files)

        assertEquals(emptyList<TransactionBackup>(), parsed.transactions)
        assertEquals(2, parsed.accounts.size)
        assertEquals(2, parsed.categories.size)
        val problem = parsed.problems.single()
        assertEquals(1, problem.line)
        assertTrue(problem.reason, problem.reason.contains("updated_at"))
    }

    @Test
    fun quotedNewlineInsideANote_doesNotShiftTheNextRowsLineNumber() {
        val body = CsvExporter.BOM +
            CsvExporter.TRANSACTIONS_HEADER.joinToString(",") + "\r\n" +
            "2,EXPENSE,12.50,1,,6,2026-10-12,\"first\nsecond\",0,0\r\n" +
            "bogus,EXPENSE,1.00,1,,6,2026-10-12,,0,0\r\n"

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.TRANSACTIONS_FILE to body))

        assertEquals("first\nsecond", parsed.transactions.single().note)
        assertEquals(4, parsed.problems.single().line)
    }

    @Test
    fun lfOnlyFileWithBlankLines_isAccepted() {
        val body = CsvExporter.BOM +
            CsvExporter.ACCOUNTS_HEADER.joinToString(",") + "\n" +
            "1,CMB,DEBIT,100.00,true,false,0,0,0\n" +
            "\n"

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.ACCOUNTS_FILE to body))

        assertEquals(emptyList<CsvRowProblem>(), parsed.problems)
        assertEquals(10000L, parsed.accounts.single().initialBalance)
    }

    @Test
    fun emptyFile_isReportedAtLineOne() {
        val parsed = CsvImportParser.parse(mapOf(CsvExporter.ACCOUNTS_FILE to CsvExporter.BOM))

        val problem = parsed.problems.single()
        assertEquals(1, problem.line)
        assertTrue(problem.reason, problem.reason.contains("空的"))
    }

    @Test
    fun illegalAmounts_areSkippedWithTheirOwnReasons() {
        val body = text(
            CsvExporter.TRANSACTIONS_HEADER,
            listOf("2", "EXPENSE", "abc", "1", "", "6", "2026-10-12", "", "0", "0"),
            listOf("3", "EXPENSE", "0.00", "1", "", "6", "2026-10-12", "", "0", "0"),
            listOf("4", "TRANSFER", "-1.00", "1", "2", "", "2026-10-12", "", "0", "0"),
            listOf("5", "EXPENSE", "12.50", "1", "", "6", "2026-10-12", "", "0", "0"),
        )

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.TRANSACTIONS_FILE to body))

        assertEquals(listOf(5L), parsed.transactions.map { it.id })
        assertEquals(listOf(2, 3, 4), parsed.problems.map { it.line })
        assertTrue(parsed.problems[0].reason, parsed.problems[0].reason.contains("不是合法的金额"))
        assertTrue(parsed.problems[1].reason, parsed.problems[1].reason.contains("必须大于 0"))
        assertTrue(parsed.problems[2].reason, parsed.problems[2].reason.contains("必须大于 0"))
    }

    @Test
    fun transferAndBalanceAdjustRules_matchTheDatabaseTriggers() {
        val body = text(
            CsvExporter.TRANSACTIONS_HEADER,
            listOf("2", "TRANSFER", "1.00", "1", "1", "", "2026-10-12", "", "0", "0"),
            listOf("3", "TRANSFER", "1.00", "1", "", "", "2026-10-12", "", "0", "0"),
            listOf("4", "TRANSFER", "1.00", "1", "2", "6", "2026-10-12", "", "0", "0"),
            listOf("5", "BALANCE_ADJUST", "0.00", "1", "", "", "2026-10-12", "", "0", "0"),
            listOf("6", "EXPENSE", "1.00", "1", "", "", "2026-10-12", "", "0", "0"),
            listOf("7", "BALANCE_ADJUST", "-6.00", "1", "", "", "2026-10-12", "", "0", "0"),
        )

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.TRANSACTIONS_FILE to body))

        assertEquals(listOf(7L), parsed.transactions.map { it.id })
        assertEquals(-600L, parsed.transactions.single().amount)
        assertEquals(listOf(2, 3, 4, 5, 6), parsed.problems.map { it.line })
        assertTrue(parsed.problems[0].reason, parsed.problems[0].reason.contains("不能相同"))
        assertTrue(parsed.problems[1].reason, parsed.problems[1].reason.contains("to_account_id"))
        assertTrue(parsed.problems[2].reason, parsed.problems[2].reason.contains("不能有 category_id"))
        assertTrue(parsed.problems[3].reason, parsed.problems[3].reason.contains("不能为 0"))
        assertTrue(parsed.problems[4].reason, parsed.problems[4].reason.contains("必须有 category_id"))
    }

    @Test
    fun accountZeroAndNegativeBalances_areAcceptedAndDuplicateIdsAreSkipped() {
        val body = text(
            CsvExporter.ACCOUNTS_HEADER,
            listOf("1", "CMB", "DEBIT", "0.00", "true", "false", "0", "0", "0"),
            listOf("2", "Credit", "CREDIT", "-1234.56", "true", "false", "1", "0", "0"),
            listOf("2", "Dup", "CASH", "1.00", "true", "false", "2", "0", "0"),
        )

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.ACCOUNTS_FILE to body))

        assertEquals(listOf(1L, 2L), parsed.accounts.map { it.id })
        assertEquals(0L, parsed.accounts[0].initialBalance)
        assertEquals(-123456L, parsed.accounts[1].initialBalance)
        val problem = parsed.problems.single()
        assertEquals(4, problem.line)
        assertTrue(problem.reason, problem.reason.contains("重复"))
    }

    @Test
    fun badIdsAndBlankNames_areSkippedWithLineNumbers() {
        val body = text(
            CsvExporter.ACCOUNTS_HEADER,
            listOf("0", "Zero", "DEBIT", "1.00", "true", "false", "0", "0", "0"),
            listOf("2", "", "DEBIT", "1.00", "true", "false", "0", "0", "0"),
        )

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.ACCOUNTS_FILE to body))

        assertEquals(emptyList<AccountBackup>(), parsed.accounts)
        assertEquals(listOf(2, 3), parsed.problems.map { it.line })
        assertTrue(parsed.problems[0].reason, parsed.problems[0].reason.contains("id"))
        assertTrue(parsed.problems[1].reason, parsed.problems[1].reason.contains("name"))
    }

    @Test
    fun badMonthAndDuplicateBudgetKey_areSkipped() {
        val body = text(
            CsvExporter.BUDGETS_HEADER,
            listOf("1", "2026-13", "", "500.00"),
            listOf("2", "2026-10", "", "500.00"),
            listOf("3", "2026-10", "", "600.00"),
            listOf("4", "2026-10", "6", "100.00"),
        )

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.BUDGETS_FILE to body))

        assertEquals(listOf(2L, 4L), parsed.budgets.map { it.id })
        assertEquals(listOf(2, 4), parsed.problems.map { it.line })
        assertTrue(parsed.problems[0].reason, parsed.problems[0].reason.contains("月份"))
        assertTrue(parsed.problems[1].reason, parsed.problems[1].reason.contains("只能有一份"))
    }

    @Test
    fun extraUnknownColumns_areIgnored() {
        val body = CsvExporter.BOM +
            (CsvExporter.ACCOUNTS_HEADER + "future_column").joinToString(",") + "\r\n" +
            "1,CMB,DEBIT,100.00,true,false,0,0,0,whatever\r\n"

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.ACCOUNTS_FILE to body))

        assertEquals(emptyList<CsvRowProblem>(), parsed.problems)
        assertEquals(1L, parsed.accounts.single().id)
    }

    @Test
    fun missingFiles_areReportedButNotFatal() {
        val all = CsvExporter.export(accounts, categories, transactions, budgets)

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.TRANSACTIONS_FILE to all.getValue(CsvExporter.TRANSACTIONS_FILE)))

        assertEquals(
            listOf(CsvExporter.ACCOUNTS_FILE, CsvExporter.CATEGORIES_FILE, CsvExporter.BUDGETS_FILE),
            parsed.missingFiles,
        )
        assertEquals(2, parsed.transactions.size)
        assertEquals(emptyList<CsvRowProblem>(), parsed.problems)
    }

    @Test
    fun everyProblem_isKeptWhenParsingAndFoldProblemsCollapsesTheListForDisplay() {
        val rows = (1..25).map { index ->
            listOf(index.toString(), "MOVIE", "1.00", "1", "", "6", "2026-10-12", "", "0", "0")
        }
        val body = text(CsvExporter.TRANSACTIONS_HEADER, *rows.toTypedArray())

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.TRANSACTIONS_FILE to body))

        // 解析层不折叠：25 行坏数据 = 25 条问题（表头占第 1 行，数据行 2..26）
        assertEquals(25, parsed.problems.size)
        assertEquals((2..26).toList(), parsed.problems.map { it.line })

        // 折叠是显示层的事：导入流程会把「引用不存在」这类问题追加进来，再统一折叠一次
        val folded = CsvImportParser.foldProblems(parsed.problems)

        assertEquals(CsvImportParser.MAX_PROBLEMS + 1, folded.size)
        val summary = folded.last()
        assertEquals(0, summary.line)
        assertTrue(summary.reason, summary.reason.contains("另有 5 项问题未列出"))
    }

    @Test
    fun rowLines_rememberWhichPhysicalLineEachAcceptedRowCameFrom() {
        val body = text(
            CsvExporter.TRANSACTIONS_HEADER,
            listOf("4", "EXPENSE", "3.00", "1", "", "6", "2026-10-12", "", "0", "0"),
            listOf("5", "INCOME", "12.50", "1", "", "7", "2026-10-12", "", "0", "0"),
        )

        val parsed = CsvImportParser.parse(mapOf(CsvExporter.TRANSACTIONS_FILE to body))

        assertEquals(2, parsed.transactions.size)
        assertEquals(
            mapOf(
                (CsvExporter.TRANSACTIONS_FILE to 4L) to 2,
                (CsvExporter.TRANSACTIONS_FILE to 5L) to 3,
            ),
            parsed.rowLines,
        )
    }
}
