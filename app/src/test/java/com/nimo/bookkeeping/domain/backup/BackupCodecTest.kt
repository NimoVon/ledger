package com.nimo.bookkeeping.domain.backup

import com.nimo.bookkeeping.domain.validate.TransactionValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份编解码与校验（计划 4.6 文件校验 + 6.1「CSV/JSON 解析」要有单测）。
 * 每个用例都是一份"坏备份必须被挡下"的断言：恢复是破坏性操作，宁可拒得严一点。
 */
class BackupCodecTest {

    // ---- 构造器：默认值就是一份合法数据 ----

    private fun acc(
        id: Long = 1L,
        name: String = "现金",
        type: String = "CASH",
        initialBalance: Long = 0L,
        isArchived: Boolean = false,
    ) = AccountBackup(
        id = id, name = name, type = type, initialBalance = initialBalance,
        isArchived = isArchived, createdAt = 1L, updatedAt = 1L,
    )

    private fun cat(
        id: Long = 1L,
        name: String = "餐饮",
        kind: String = "EXPENSE",
        parentId: Long? = null,
    ) = CategoryBackup(id = id, name = name, kind = kind, parentId = parentId)

    private fun tx(
        id: Long = 1L,
        type: String = "EXPENSE",
        amount: Long = 1200L,
        accountId: Long? = 1L,
        toAccountId: Long? = null,
        categoryId: Long? = 1L,
        occurredOn: String = "2026-10-13",
        note: String? = null,
    ) = TransactionBackup(
        id = id, type = type, amount = amount, accountId = accountId, toAccountId = toAccountId,
        categoryId = categoryId, occurredOn = occurredOn, note = note, createdAt = 1L, updatedAt = 1L,
    )

    private fun budget(
        id: Long = 1L,
        month: String = "2026-10",
        categoryId: Long? = null,
        amount: Long = 5000L,
    ) = BudgetBackup(id = id, month = month, categoryId = categoryId, amount = amount)

    private fun file(
        accounts: List<AccountBackup> = listOf(acc()),
        categories: List<CategoryBackup> = listOf(cat()),
        transactions: List<TransactionBackup> = listOf(tx()),
        budgets: List<BudgetBackup> = emptyList(),
    ) = BackupFile(data = BackupData(accounts, categories, transactions, budgets))

    private fun decoded(file: BackupFile): BackupDecodeResult =
        BackupCodec.decode(BackupCodec.encode(file))

    private fun errorText(file: BackupFile): String {
        val result = decoded(file)
        assertTrue("期望解码失败，实际成功", result is BackupDecodeResult.Failure)
        return (result as BackupDecodeResult.Failure).reason
    }

    private fun errorTextOfJson(text: String): String {
        val result = BackupCodec.decode(text)
        assertTrue("期望解码失败，实际成功", result is BackupDecodeResult.Failure)
        return (result as BackupDecodeResult.Failure).reason
    }

    // ---- 正常路径 ----

    @Test
    fun roundTrip_keepsEveryField() {
        val src = BackupFile(
            exportedAt = 1760000000000L,
            data = BackupData(
                accounts = listOf(
                    acc(id = 2L, name = "招行", type = "CREDIT", initialBalance = -12345L, isArchived = true),
                    acc(id = 9L, name = "余额宝", type = "ALIPAY"),
                ),
                categories = listOf(cat(id = 5L, name = "餐饮"), cat(id = 6L, name = "工资", kind = "INCOME")),
                transactions = listOf(
                    tx(id = 7L, amount = 1200L, accountId = 2L, categoryId = 5L, note = "午饭,加\"料\""),
                    tx(id = 8L, type = "TRANSFER", amount = 5000L, accountId = 2L, toAccountId = 9L, categoryId = null),
                    tx(id = 10L, type = "BALANCE_ADJUST", amount = -300L, accountId = 2L, categoryId = null),
                ),
                budgets = listOf(budget(id = 11L, categoryId = 5L, amount = 100000L)),
            ),
        )
        val result = decoded(src)
        assertTrue("期望成功", result is BackupDecodeResult.Success)
        assertEquals(src, (result as BackupDecodeResult.Success).file)
    }

    @Test
    fun emptyData_isAccepted() {
        val result = BackupCodec.decode(BackupCodec.encode(BackupFile()))
        assertTrue(result is BackupDecodeResult.Success)
    }

    @Test
    fun unknownFields_areIgnoredForForwardCompatibility() {
        val text = """
            {"app":"ledger","schemaVersion":1,"exportedAt":1,"futureTopKey":{"x":1},
             "data":{"accounts":[{"id":1,"name":"现金","type":"CASH","futureField":"v2"}],
                     "categories":[],"transactions":[],"budgets":[],"futureArray":[]}}
        """.trimIndent()
        val result = BackupCodec.decode(text)
        assertTrue((result as? BackupDecodeResult.Failure)?.reason ?: "", result is BackupDecodeResult.Success)
    }

    // ---- 结构层 ----

    @Test
    fun foreignApp_isRejected() {
        val text = errorTextOfJson(
            """{"app":"other","schemaVersion":1,"data":{"accounts":[],"categories":[],"transactions":[],"budgets":[]}}""",
        )
        assertTrue(text, text.contains("不是「记账本」的备份文件"))
    }

    @Test
    fun newerSchemaVersion_isRejected() {
        val text = errorTextOfJson(
            """{"app":"ledger","schemaVersion":2,"data":{"accounts":[],"categories":[],"transactions":[],"budgets":[]}}""",
        )
        assertTrue(text, text.contains("版本过新") || text.contains("更新的版本"))
    }

    @Test
    fun missingRequiredKeys_areRejected() {
        assertTrue(errorTextOfJson("""{"schemaVersion":1,"data":{}}""").contains("缺少必需字段 app"))
        assertTrue(errorTextOfJson("""{"app":"ledger","data":{}}""").contains("缺少必需字段 schemaVersion"))
        assertTrue(errorTextOfJson("""{"app":"ledger","schemaVersion":1}""").contains("缺少必需字段 data"))
        assertTrue(
            errorTextOfJson("""{"app":"ledger","schemaVersion":1,"data":{"accounts":[]}}""")
                .contains("缺少必需字段 data.categories"),
        )
        assertTrue(
            errorTextOfJson("""{"app":"ledger","schemaVersion":1,"data":{"accounts":"x"}}""")
                .contains("必须是数组"),
        )
    }

    @Test
    fun brokenJson_isRejected() {
        assertTrue(errorTextOfJson("{这是坏的").contains("不是合法的 JSON"))
    }

    @Test
    fun nonObjectTopLevel_isRejected() {
        assertTrue(errorTextOfJson("[1,2,3]").contains("顶层必须是 JSON 对象"))
    }

    @Test
    fun missingField_isReportedByNameWithoutKotlinClassNames() {
        // 真机验证时发现的文案问题：坏文件缺字段，对话框里出现了
        // "Field 'occurredOn' is required for type with serial name 'com.nimo.bookkeeping...'"
        val text = errorTextOfJson(
            """{"app":"ledger","schemaVersion":1,"data":{"accounts":[],"categories":[],""" +
                """"transactions":[{"id":1,"type":"EXPENSE","amount":100}],"budgets":[]}}"""
        )
        assertTrue(text, text.contains("缺少必需字段"))
        assertFalse(text, text.contains("com.nimo.bookkeeping"))
        assertFalse(text, text.contains("serial name"))
    }

    @Test
    fun wrongFieldType_isReportedInPlainChinese() {
        val text = errorTextOfJson(
            """{"app":"ledger","schemaVersion":1,"data":{"accounts":[],"categories":[],"transactions":[""" +
                """{"id":"一","type":"EXPENSE","amount":100,"accountId":1,"categoryId":1,"occurredOn":"2026-10-01",""" +
                """"createdAt":0,"updatedAt":0}],"budgets":[]}}"""
        )
        assertTrue(text, text.contains("无法识别的内容") || text.contains("结构不符合本应用格式"))
    }

    // ---- 行层：金额 ----

    @Test
    fun nonPositiveAmount_isRejected() {
        assertTrue(errorText(file(transactions = listOf(tx(amount = 0L)))).contains("金额必须大于 0"))
        assertTrue(errorText(file(transactions = listOf(tx(amount = -100L)))).contains("金额必须大于 0"))
    }

    @Test
    fun outOfRangeAmount_isRejected() {
        val over = 9_999_999_999L + 1
        assertTrue(errorText(file(transactions = listOf(tx(amount = over)))).contains("超出上限"))
        assertTrue(errorText(file(accounts = listOf(acc(initialBalance = over)))).contains("初始余额超出上限"))
        assertTrue(errorText(file(budgets = listOf(budget(amount = over)))).contains("金额超出上限"))
    }

    @Test
    fun balanceAdjustWithZero_isRejected() {
        val text = errorText(
            file(transactions = listOf(tx(type = "BALANCE_ADJUST", amount = 0L, categoryId = null))),
        )
        assertTrue(text, text.contains("余额调整金额不能为 0"))
    }

    // ---- 行层：引用与规则一致性 ----

    @Test
    fun danglingReferences_areRejected() {
        assertTrue(
            errorText(file(transactions = listOf(tx(accountId = 99L))))
                .contains("引用的账户不存在：99"),
        )
        assertTrue(
            errorText(file(transactions = listOf(tx(categoryId = 99L))))
                .contains("引用的分类不存在：99"),
        )
        assertTrue(
            errorText(file(categories = listOf(cat(id = 2L, parentId = 99L))))
                .contains("父分类不存在：99"),
        )
        assertTrue(
            errorText(file(budgets = listOf(budget(categoryId = 99L))))
                .contains("引用的分类不存在：99"),
        )
    }

    @Test
    fun transferRules_areEnforced() {
        val sameAccount = errorText(
            file(transactions = listOf(tx(type = "TRANSFER", accountId = 1L, toAccountId = 1L, categoryId = null))),
        )
        assertTrue(sameAccount, sameAccount.contains("转出与转入必须是不同账户"))

        val missingTo = errorText(file(transactions = listOf(tx(type = "TRANSFER", categoryId = null))))
        assertTrue(missingTo, missingTo.contains("缺少转出或转入账户"))

        val withCategory = errorText(
            file(transactions = listOf(tx(type = "TRANSFER", accountId = 1L, toAccountId = 2L, categoryId = 1L))),
        )
        assertTrue(withCategory, withCategory.contains("不能有分类"))
    }

    @Test
    fun categoryRulesMatchTheDatabaseTriggers() {
        val missing = errorText(file(transactions = listOf(tx(categoryId = null))))
        assertTrue(missing, missing.contains("缺少分类"))

        val mismatch = errorText(
            file(
                categories = listOf(cat(id = 1L, kind = "INCOME")),
                transactions = listOf(tx(type = "EXPENSE", categoryId = 1L)),
            ),
        )
        assertTrue(mismatch, mismatch.contains("分类类型不匹配"))

        val adjustWithCategory = errorText(
            file(transactions = listOf(tx(type = "BALANCE_ADJUST", amount = -100L, categoryId = 1L))),
        )
        assertTrue(adjustWithCategory, adjustWithCategory.contains("不能有分类"))
    }

    @Test
    fun unknownEnumValues_areRejected() {
        assertTrue(errorText(file(accounts = listOf(acc(type = "BITCOIN")))).contains("类型无法识别：BITCOIN"))
        assertTrue(errorText(file(categories = listOf(cat(kind = "BOTH")))).contains("kind 无法识别：BOTH"))
        assertTrue(
            errorText(file(transactions = listOf(tx(type = "REFUND")))).contains("类型无法识别：REFUND"),
        )
    }

    @Test
    fun invalidDates_areRejected() {
        assertTrue(
            errorText(file(transactions = listOf(tx(occurredOn = "2026-02-30"))))
                .contains("日期非法：2026-02-30"),
        )
        assertTrue(
            errorText(file(transactions = listOf(tx(occurredOn = "2026/10/13"))))
                .contains("日期非法"),
        )
    }

    @Test
    fun duplicateAndInvalidIds_areRejected() {
        val duplicated = errorText(
            file(
                accounts = listOf(acc(id = 1L), acc(id = 1L, name = "招行")),
                categories = listOf(cat(id = 1L)),
                transactions = listOf(tx(id = 1L, categoryId = 1L)),
            ),
        )
        assertTrue(duplicated, duplicated.contains("账户 id 重复：1"))

        val zero = errorText(file(accounts = listOf(acc(id = 0L)), transactions = emptyList()))
        assertTrue(zero, zero.contains("账户 id 必须为正数"))
    }

    @Test
    fun blankNames_areRejected() {
        assertTrue(errorText(file(accounts = listOf(acc(name = "  ")))).contains("缺少名称"))
        assertTrue(
            errorText(
                file(
                    categories = listOf(cat(name = "")),
                    transactions = listOf(tx(categoryId = 1L)),
                ),
            ).contains("缺少名称"),
        )
    }

    @Test
    fun tooLongNote_isRejected() {
        val note = "备".repeat(TransactionValidator.MAX_NOTE_LENGTH + 1)
        val text = errorText(file(transactions = listOf(tx(note = note))))
        assertTrue(text, text.contains("备注超过"))
    }

    @Test
    fun duplicateBudgets_areRejected() {
        val text = errorText(
            file(
                budgets = listOf(
                    budget(id = 1L, month = "2026-10", categoryId = null),
                    budget(id = 2L, month = "2026-10", categoryId = null),
                ),
            ),
        )
        assertTrue(text, text.contains("预算重复"))
    }

    @Test
    fun tooManyRows_isRejected() {
        val many = List(MAX_BACKUP_ROWS + 1) { acc(id = it + 1L) }
        val errors = BackupCodec.validate(BackupFile(data = BackupData(accounts = many)))
        assertTrue(errors.toString(), errors.any { it.contains("超过上限") })
    }

    @Test
    fun errorList_isCappedWithASuffix() {
        val errors = BackupCodec.validate(file(accounts = List(20) { acc(id = it + 1L, name = "") }))
        assertEquals(BackupCodec.MAX_ERRORS + 1, errors.size)
        assertTrue(errors.last(), errors.last().contains("另有 12 项问题未列出"))
    }
}
