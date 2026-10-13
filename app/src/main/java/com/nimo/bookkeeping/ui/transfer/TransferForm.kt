package com.nimo.bookkeeping.ui.transfer

import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.validate.TransactionValidator
import com.nimo.bookkeeping.ui.record.truncateTo
import com.nimo.bookkeeping.util.Dates
import com.nimo.bookkeeping.util.Money

/**
 * 转账页（F04）表单的**纯数据**。不依赖 Android / Compose / Room，可直接 JVM 单测。
 *
 * 为什么不复用记账页的 [com.nimo.bookkeeping.ui.record.RecordForm]：那一套是"金额 + 一个账户 + 一个分类"，
 * 转账是"金额 + 转出账户 + 转入账户"，分类必须为空（数据库触发器也这么要求）。
 * 硬套过去就会写出半截数据（例如丢掉 toAccountId），所以这里独立成一份纯模型。
 *
 * 三道防线里的第一道（计划 4.4）：这里的校验只让用户尽早看到提示，
 * 第二道是 [TransactionValidator]，第三道是数据库触发器。
 */
data class TransferForm(
    /** 转出账户 */
    val fromAccountId: Long? = null,
    /** 转入账户 */
    val toAccountId: Long? = null,
    /** 用户输入的金额原始文本，解析成"分"只走 [Money.parseCents]（铁律 1） */
    val amountText: String = "",
    /** YYYY-MM-DD，默认今天 */
    val occurredOn: String = Dates.today(),
    val note: String = "",
) {

    /** 备注硬上限：超出的部分直接丢弃，且不切断代理对（emoji 不会被截成半个字符）。 */
    fun withNote(text: String): TransferForm =
        copy(note = text.truncateTo(TransactionValidator.MAX_NOTE_LENGTH))
}

/** 表单层错误（提示文案见 [toMessage]） */
sealed interface TransferFormError {
    data object AmountBlank : TransferFormError
    data object AmountInvalid : TransferFormError
    /** 格式合法但超过产品上限 —— 与 [AmountInvalid] 分开，别让用户去猜"哪里格式错了" */
    data object AmountTooLarge : TransferFormError
    data object AmountMustBePositive : TransferFormError
    data object FromAccountRequired : TransferFormError
    data object ToAccountRequired : TransferFormError
    data object SameAccount : TransferFormError
    data object NoteTooLong : TransferFormError
    data object DateInvalid : TransferFormError
}

/** 表单错误 → 中文提示 */
fun TransferFormError.toMessage(): String = when (this) {
    TransferFormError.AmountBlank -> "请输入金额"
    TransferFormError.AmountInvalid -> "金额格式不正确（例：12.5 或 12.50，最多两位小数）"
    TransferFormError.AmountTooLarge -> "金额超出上限（单笔最多 ${Money.formatCurrency(Money.MAX_AMOUNT_CENTS)}）"
    TransferFormError.AmountMustBePositive -> "金额必须大于 0"
    TransferFormError.FromAccountRequired -> "请选择转出账户"
    TransferFormError.ToAccountRequired -> "请选择转入账户"
    TransferFormError.SameAccount -> "转入账户不能和转出账户相同"
    TransferFormError.NoteTooLong -> "备注最多 ${TransactionValidator.MAX_NOTE_LENGTH} 字"
    TransferFormError.DateInvalid -> "日期不正确"
}

sealed interface TransferFormOutcome {
    data class Valid(val input: TransactionInput) : TransferFormOutcome
    data class Invalid(val errors: List<TransferFormError>) : TransferFormOutcome
}

/**
 * 表单 → [TransactionInput]。
 *
 * 产出的 input 恒为 TRANSFER：type 固定、categoryId 恒为 null（转账不挂分类，
 * 数据库触发器 trg_tx_validate_* 会拒掉带分类的转账）。
 * 金额一律走 [Money.parseCents] 得到 Long 整数分，**绝不先转 Double 再计算**（铁律 1）。
 */
fun TransferForm.toOutcome(): TransferFormOutcome {
    val errors = mutableListOf<TransferFormError>()

    // 解析失败时先分清"超上限"还是"格式不对"：两种情况给不同文案（计划 line 495）
    val amountError = if (Money.exceedsMaxAmount(amountText)) {
        TransferFormError.AmountTooLarge
    } else {
        TransferFormError.AmountInvalid
    }

    val cents: Long? = when {
        amountText.isBlank() -> {
            errors += TransferFormError.AmountBlank
            null
        }

        else -> Money.parseCents(amountText).also {
            if (it == null) errors += amountError
        }
    }
    if (cents != null && cents <= 0L) errors += TransferFormError.AmountMustBePositive

    if (fromAccountId == null) errors += TransferFormError.FromAccountRequired
    if (toAccountId == null) {
        errors += TransferFormError.ToAccountRequired
    } else if (fromAccountId == toAccountId) {
        errors += TransferFormError.SameAccount
    }

    if (note.length > TransactionValidator.MAX_NOTE_LENGTH) errors += TransferFormError.NoteTooLong
    if (!Dates.isValidIsoDate(occurredOn)) errors += TransferFormError.DateInvalid

    if (errors.isNotEmpty()) return TransferFormOutcome.Invalid(errors)

    val amountCents = cents
        ?: return TransferFormOutcome.Invalid(listOf(amountError))

    return TransferFormOutcome.Valid(
        TransactionInput(
            type = TransactionType.TRANSFER,
            amountCents = amountCents,
            accountId = fromAccountId,
            toAccountId = toAccountId,
            categoryId = null,
            occurredOn = occurredOn,
            note = note.trim().ifEmpty { null },
        ),
    )
}
