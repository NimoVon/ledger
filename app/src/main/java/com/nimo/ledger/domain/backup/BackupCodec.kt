package com.nimo.ledger.domain.backup

import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.validate.TransactionValidator
import com.nimo.ledger.util.Dates
import com.nimo.ledger.util.Money
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs

/** 解码结果：失败时给的是**给人看的中文原因**（计划 4.6：错误信息不写文件内容进日志）。 */
sealed interface BackupDecodeResult {
    data class Success(val file: BackupFile) : BackupDecodeResult

    data class Failure(val errors: List<String>) : BackupDecodeResult {
        /** 单行摘要，给对话框/提示条用 */
        val reason: String get() = errors.joinToString("；")
    }
}

/**
 * JSON 备份的编解码与校验（计划 4.6 文件校验）。
 *
 * 校验分两层：
 * 1. **结构层**（[checkStructure]）：顶层键、app 标识、schemaVersion、data 四个数组必须存在且是同类型；
 * 2. **行层**（[validate]）：id 唯一且为正、金额范围、日期真实性、ID 引用存在（外键）、
 *    转账两端不同账户、收支必须有匹配 kind 的分类、转账/余额调整不能带分类、备注长度、同月预算重复。
 *
 * 第 2 层刻意与数据库触发器（data/local/SchemaHardening.kt）保持同一套规则：
 * 备份能过校验 = 恢复时不会撞触发器。
 */
object BackupCodec {

    /** 一次最多报几条问题（再多用户也读不完），多余的折叠成一句 */
    const val MAX_ERRORS = 8

    private val json = Json {
        ignoreUnknownKeys = true // 为未来字段留兼容空间（计划 4.6）
        prettyPrint = true
        encodeDefaults = true
    }

    private val MONTH_RE = Regex("""\d{4}-\d{2}""")

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    fun decode(text: String): BackupDecodeResult {
        val root: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (t: Throwable) {
            return BackupDecodeResult.Failure(listOf("文件不是合法的 JSON 文本（" + brief(t) + "）"))
        }
        if (root !is JsonObject) {
            return BackupDecodeResult.Failure(listOf("备份文件的顶层必须是 JSON 对象"))
        }
        val structural = checkStructure(root)
        if (structural.isNotEmpty()) return BackupDecodeResult.Failure(cap(structural))
        val file = try {
            json.decodeFromJsonElement(BackupFile.serializer(), root)
        } catch (e: MissingFieldException) {
            // 缺字段是最常见的坏文件：直接把缺哪个字段告诉用户，不暴露 Kotlin 类名
            return BackupDecodeResult.Failure(
                listOf("备份文件缺少必需字段：" + e.missingFields.joinToString("、"))
            )
        } catch (e: SerializationException) {
            return BackupDecodeResult.Failure(
                listOf("备份文件里有无法识别的内容或字段类型不对（" + brief(e) + "）")
            )
        } catch (t: Throwable) {
            return BackupDecodeResult.Failure(listOf("备份文件的结构不符合本应用格式（" + brief(t) + "）"))
        }
        val errors = validate(file)
        return if (errors.isEmpty()) BackupDecodeResult.Success(file) else BackupDecodeResult.Failure(errors)
    }

   /** 结构层：键是否存在、类型对不对、版本是否受支持 */
    private fun checkStructure(root: JsonObject): List<String> {
        val errors = mutableListOf<String>()
        val app = (root["app"] as? JsonPrimitive)?.content
        when {
            root["app"] == null -> errors += "缺少必需字段 app"
            app == null -> errors += "app 必须是字符串"
            app != BACKUP_APP_ID -> errors += "这不是「记账本」的备份文件（app=" + app + "）"
        }
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        when {
            root["schemaVersion"] == null -> errors += "缺少必需字段 schemaVersion"
            version == null -> errors += "schemaVersion 必须是整数"
            version < 1 -> errors += "schemaVersion 非法：" + version
            version > BACKUP_SCHEMA_VERSION ->
                errors += "备份文件来自更新的版本（schemaVersion=v" + version +
                    "，本应用最高支持 v" + BACKUP_SCHEMA_VERSION + "），请先升级应用"
        }
        val data = root["data"]
        when {
            data == null -> errors += "缺少必需字段 data"
            data !is JsonObject -> errors += "data 必须是 JSON 对象"
            else -> {
                for (key in REQUIRED_DATA_KEYS) {
                    val v = data[key]
                    when {
                        v == null -> errors += "缺少必需字段 data." + key
                        v !is kotlinx.serialization.json.JsonArray -> errors += "data." + key + " 必须是数组"
                    }
                }
            }
        }
        return errors
    }

    /** 行层校验入口：返回空列表表示这份备份可以安全恢复。 */
    fun validate(file: BackupFile): List<String> = validate(file.data)

    /** 行层校验（可直接对 [BackupData] 调用，便于只测校验规则而不走编解码） */
    fun validate(data: BackupData): List<String> {
        val errors = mutableListOf<String>()

        if (data.accounts.size > MAX_BACKUP_ROWS) errors += "账户数超过上限 " + MAX_BACKUP_ROWS
        if (data.categories.size > MAX_BACKUP_ROWS) errors += "分类数超过上限 " + MAX_BACKUP_ROWS
        if (data.transactions.size > MAX_BACKUP_ROWS) errors += "交易数超过上限 " + MAX_BACKUP_ROWS
        if (data.budgets.size > MAX_BACKUP_ROWS) errors += "预算数超过上限 " + MAX_BACKUP_ROWS

        val accountIds = HashSet<Long>()
        for (a in data.accounts) {
            if (a.id <= 0) errors += "账户 id 必须为正数：" + a.id else if (!accountIds.add(a.id)) errors += "账户 id 重复：" + a.id
            if (a.name.isBlank()) errors += "账户 " + a.id + " 缺少名称"
            if (AccountType.entries.none { it.name == a.type }) errors += "账户 " + a.id + " 的类型无法识别：" + a.type
            if (abs(a.initialBalance) > Money.MAX_AMOUNT_CENTS) errors += "账户 " + a.id + " 的初始余额超出上限"
        }

        val categoryIds = HashSet<Long>()
        val kinds = HashMap<Long, CategoryKind>()
        for (c in data.categories) {
            if (c.id <= 0) errors += "分类 id 必须为正数：" + c.id else if (!categoryIds.add(c.id)) errors += "分类 id 重复：" + c.id
            if (c.name.isBlank()) errors += "分类 " + c.id + " 缺少名称"
            val kind = CategoryKind.entries.firstOrNull { it.name == c.kind }
            if (kind == null) errors += "分类 " + c.id + " 的 kind 无法识别：" + c.kind else kinds[c.id] = kind
        }
        for (c in data.categories) {
            val parent = c.parentId ?: continue
            if (parent == c.id) errors += "分类 " + c.id + " 的父分类不能是它自己"
            else if (parent !in categoryIds) errors += "分类 " + c.id + " 的父分类不存在：" + parent
        }

        val budgetKeys = HashSet<String>()
        for (b in data.budgets) {
            if (b.id <= 0) errors += "预算 id 必须为正数：" + b.id
            if (b.amount <= 0) errors += "预算 " + b.id + " 的金额必须大于 0"
            if (b.amount > Money.MAX_AMOUNT_CENTS) errors += "预算 " + b.id + " 的金额超出上限"
            if (!MONTH_RE.matches(b.month)) errors += "预算 " + b.id + " 的月份格式必须是 YYYY-MM：" + b.month
            val key = b.month + "#" + b.categoryId
            if (!budgetKeys.add(key)) {
                errors += "预算重复：" + b.month + " 的" +
                    (if (b.categoryId == null) "总预算" else "分类 " + b.categoryId + " 预算") + "出现多次"
            }
            val categoryId = b.categoryId
            if (categoryId != null && categoryId !in categoryIds) {
                errors += "预算 " + b.id + " 引用的分类不存在：" + categoryId
            }
        }

        val txIds = HashSet<Long>()
        for (t in data.transactions) {
            if (t.id <= 0) errors += "交易 id 必须为正数：" + t.id
            else if (!txIds.add(t.id)) errors += "交易 id 重复：" + t.id
            val type = TransactionType.entries.firstOrNull { it.name == t.type }
            if (type == null) {
                errors += "交易 " + t.id + " 的类型无法识别：" + t.type
                continue
            }
            if (abs(t.amount) > Money.MAX_AMOUNT_CENTS) errors += "交易 " + t.id + " 的金额超出上限"
            if (type == TransactionType.BALANCE_ADJUST) {
                if (t.amount == 0L) errors += "交易 " + t.id + " 的余额调整金额不能为 0"
            } else if (t.amount <= 0L) {
                errors += "交易 " + t.id + " 的金额必须大于 0"
            }
            if (!Dates.isValidIsoDate(t.occurredOn)) errors += "交易 " + t.id + " 的日期非法：" + t.occurredOn
            if ((t.note?.length ?: 0) > TransactionValidator.MAX_NOTE_LENGTH) {
                errors += "交易 " + t.id + " 的备注超过 " + TransactionValidator.MAX_NOTE_LENGTH + " 字"
            }

            if (type == TransactionType.TRANSFER) {
                val from = t.accountId
                val to = t.toAccountId
                if (from == null || to == null) errors += "交易 " + t.id + " 的转账缺少转出或转入账户"
                else if (from == to) errors += "交易 " + t.id + " 的转出与转入必须是不同账户"
            } else if (t.accountId == null) {
                errors += "交易 " + t.id + " 缺少账户"
            }
            for (id in listOfNotNull(t.accountId, t.toAccountId)) {
                if (id !in accountIds) errors += "交易 " + t.id + " 引用的账户不存在：" + id
            }

            if (type == TransactionType.TRANSFER || type == TransactionType.BALANCE_ADJUST) {
                if (t.categoryId != null) {
                    errors += "交易 " + t.id + " 是" + (if (type == TransactionType.TRANSFER) "转账" else "余额调整") + "，不能有分类"
                }
            } else {
                val categoryId = t.categoryId
                if (categoryId == null) {
                    errors += "交易 " + t.id + " 缺少分类"
                } else {
                    val kind = kinds[categoryId]
                    if (kind == null) errors += "交易 " + t.id + " 引用的分类不存在：" + categoryId
                    else {
                        val want = if (type == TransactionType.EXPENSE) CategoryKind.EXPENSE else CategoryKind.INCOME
                        if (kind != want) {
                            errors += "交易 " + t.id + " 的分类类型不匹配（应为" +
                                (if (want == CategoryKind.EXPENSE) "支出分类" else "收入分类") + "）"
                        }
                    }
                }
            }
        }

        return cap(errors)
    }

    private fun cap(errors: List<String>): List<String> =
        if (errors.size <= MAX_ERRORS) errors
        else errors.take(MAX_ERRORS) + ("（另有 " + (errors.size - MAX_ERRORS) + " 项问题未列出）")

    private fun brief(t: Throwable): String {
        val message = t.message ?: t.javaClass.simpleName
        return message.replace('\n', ' ').take(120)
    }

    private val REQUIRED_DATA_KEYS = listOf("accounts", "categories", "transactions", "budgets")
}
