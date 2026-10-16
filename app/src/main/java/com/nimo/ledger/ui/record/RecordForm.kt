package com.nimo.ledger.ui.record

import com.nimo.ledger.domain.model.TransactionInput
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.validate.TransactionValidator
import com.nimo.ledger.util.Dates
import com.nimo.ledger.util.Money

/**
 * 记账页（F03）表单的**纯数据**。不依赖 Android / Compose / Room，可直接 JVM 单测。
 *
 * 三道防线里的第一道（计划 4.4）：这里的校验只是让用户尽早看到提示，
 * 第二道是 [TransactionValidator]，第三道是数据库触发器。
 */
data class RecordForm(
    val type: TransactionType = TransactionType.EXPENSE,
    /** 用户输入的金额原始文本，解析成"分"只走 [Money.parseCents]（铁律 1） */
    val amountText: String = "",
    val accountId: Long? = null,
    val categoryId: Long? = null,
    /** YYYY-MM-DD，默认今天 */
    val occurredOn: String = Dates.today(),
    val note: String = "",
) {

    /** 记账页只处理收支；转账（F04）与余额校准（P1）有各自入口，不从这里写入。 */
    val isSupportedType: Boolean
        get() = type == TransactionType.EXPENSE || type == TransactionType.INCOME

    /**
     * 切换收支方向。支出/收入是两套独立分类（铁律 3 / 边界测试：收入分类不能用于支出），
     * 所以切换时清空已选分类；金额、账户、日期、备注保留，避免用户重填。
     */
    fun withType(newType: TransactionType): RecordForm =
        if (newType == type) this else copy(type = newType, categoryId = null)

    /** 备注硬上限：超出的部分直接丢弃，且不切断代理对（emoji 不会被截成半个字符）。 */
    fun withNote(text: String): RecordForm =
        copy(note = text.truncateTo(TransactionValidator.MAX_NOTE_LENGTH))
}

/** 表单层错误（提示文案见 [toMessage]） */
sealed interface RecordFormError {
    data object UnsupportedType : RecordFormError
    data object AmountBlank : RecordFormError
    data object AmountInvalid : RecordFormError
    /** 格式合法但超过产品上限 —— 与 [AmountInvalid] 分开，别让用户去猜"哪里格式错了" */
    data object AmountTooLarge : RecordFormError
    data object AmountMustBePositive : RecordFormError
    data object AccountRequired : RecordFormError
    data object CategoryRequired : RecordFormError
    data object NoteTooLong : RecordFormError
    data object DateInvalid : RecordFormError
}

/** 表单错误 → 中文提示 */
fun RecordFormError.toMessage(): String = when (this) {
    RecordFormError.UnsupportedType -> "本页只支持支出与收入"
    RecordFormError.AmountBlank -> "请输入金额"
    RecordFormError.AmountInvalid -> "金额格式不正确（例：12.5 或 12.50，最多两位小数）"
    RecordFormError.AmountTooLarge -> "金额超出上限（单笔最多 ${Money.formatCurrency(Money.MAX_AMOUNT_CENTS)}）"
    RecordFormError.AmountMustBePositive -> "金额必须大于 0"
    RecordFormError.AccountRequired -> "请选择账户"
    RecordFormError.CategoryRequired -> "请选择分类"
    RecordFormError.NoteTooLong -> "备注最多 ${TransactionValidator.MAX_NOTE_LENGTH} 字"
    RecordFormError.DateInvalid -> "日期不正确"
}

sealed interface RecordFormOutcome {
    data class Valid(val input: TransactionInput) : RecordFormOutcome
    data class Invalid(val errors: List<RecordFormError>) : RecordFormOutcome
}

/**
 * 表单 → [TransactionInput]。
 *
 * 金额一律走 [Money.parseCents] 得到 Long 整数分，**绝不先转 Double 再计算**（铁律 1）。
 * [Money.parseCents] 允许前导负号（余额校准用），而收支金额必须 > 0，所以这里再挡一次。
 * 返回 [RecordFormOutcome.Valid] 只代表"可以提交"，最终能否落库由 Repository 与数据库触发器裁决。
 */
fun RecordForm.toOutcome(): RecordFormOutcome {
    val errors = mutableListOf<RecordFormError>()

    if (!isSupportedType) errors += RecordFormError.UnsupportedType

    // 解析失败时先分清"超上限"还是"格式不对"：两种情况给不同文案（计划 line 495）
    val amountError = if (Money.exceedsMaxAmount(amountText)) {
        RecordFormError.AmountTooLarge
    } else {
        RecordFormError.AmountInvalid
    }

    val cents: Long? = when {
        amountText.isBlank() -> {
            errors += RecordFormError.AmountBlank
            null
        }

        else -> Money.parseCents(amountText).also {
            if (it == null) errors += amountError
        }
    }
    if (cents != null && cents <= 0L) errors += RecordFormError.AmountMustBePositive

    if (accountId == null) errors += RecordFormError.AccountRequired
    if (categoryId == null) errors += RecordFormError.CategoryRequired
    if (note.length > TransactionValidator.MAX_NOTE_LENGTH) errors += RecordFormError.NoteTooLong
    if (!Dates.isValidIsoDate(occurredOn)) errors += RecordFormError.DateInvalid

    if (errors.isNotEmpty()) return RecordFormOutcome.Invalid(errors)

    val amountCents = cents
        ?: return RecordFormOutcome.Invalid(listOf(amountError))

    return RecordFormOutcome.Valid(
        TransactionInput(
            type = type,
            amountCents = amountCents,
            accountId = accountId,
            toAccountId = null,
            categoryId = categoryId,
            occurredOn = occurredOn,
            note = note.trim().ifEmpty { null },
        ),
    )
}

/** 按 UTF-16 截断到 [maxChars]，且不留下落单的高代理项（避免把 emoji 截成半个字符）。 */
internal fun String.truncateTo(maxChars: Int): String {
    if (length <= maxChars) return this
    var end = maxChars
    if (end > 0 && Character.isHighSurrogate(this[end - 1])) end -= 1
    return substring(0, end)
}
