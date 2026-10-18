package com.nimo.ledger.ui.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.util.Money
import kotlin.math.abs

/**
 * 金额列最小宽度（UI-UX 规范 §6.3）：短金额也占满这一列，右边缘才能成一条竖线。
 *
 * 96dp ≈ 在 bodyLarge（16sp）下放得下 "-¥1,234,567.89" 之外最常见的 8~10 个字符；
 * 更大的金额由内容自己撑开，[widthIn] 只是下限，不会截断。
 */
internal val LEDGER_AMOUNT_MIN_WIDTH = 96.dp

/** 金额列的测试标记：屏幕层测试可以按它数出「一列里对齐了几笔金额」。 */
internal const val LEDGER_AMOUNT_TEST_TAG = "ledger-amount"

/**
 * 全应用统一金额列（UI-UX 规范 §6.3，问题清单 G-5）。
 *
 * 改这个文件之前，明细 / 账户 / 统计三处各写一套金额排版：有的 bodyLarge + Medium、
 * 有的 titleSmall，短金额（¥1.00）比长金额（¥1,234.56）窄出十几 dp，右边缘参差；
 * 支出红 / 收入蓝 / 转账中性这套颜色也各抄一遍。现在只有这一份实现。
 *
 * 规格要点：
 * - 颜色与符号：[TransactionType.EXPENSE] 支出色、[TransactionType.INCOME] 收入色、
 *   转账与余额调整走 onSurfaceVariant（它们不参与收支，不该抢眼）；
 *   [type] 传 null 表示汇总 / 余额（净资产、预算额），用 onSurface、不带符号
 * - [showSign]：明细行传 true（支出 "-¥x"、收入 "+¥x"）；列表汇总行传 false
 * - 金额为负数时（余额调整）[Money.formatCurrency] 自己带负号，不再叠加符号
 * - 最小宽 [LEDGER_AMOUNT_MIN_WIDTH]、单行、不换行（§6.3 约束）
 */
@Composable
fun LedgerAmount(
    cents: Long,
    type: TransactionType?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    showSign: Boolean = false,
) {
    val color = when (type) {
        TransactionType.EXPENSE -> MaterialTheme.colorScheme.error
        TransactionType.INCOME -> MaterialTheme.colorScheme.primary
        TransactionType.TRANSFER, TransactionType.BALANCE_ADJUST ->
            MaterialTheme.colorScheme.onSurfaceVariant
        null -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = ledgerAmountText(cents, type, showSign),
        modifier = modifier
            .widthIn(min = LEDGER_AMOUNT_MIN_WIDTH)
            .testTag(LEDGER_AMOUNT_TEST_TAG)
            // AA-5：读屏念「支出 788 元」，不逐字念「减 ¥ 七 八 八 点 零 零」
            .semantics { contentDescription = ledgerAmountSpeech(cents, type) },
        color = color,
        style = style,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.End,
        overflow = TextOverflow.Clip,
    )
}

/**
 * 金额文本的唯一算法：符号只在 [TransactionType.EXPENSE] / [TransactionType.INCOME]
 * 且 [showSign] 为 true 时出现；负数（余额调整）交给 [Money.formatCurrency] 带号。
 *
 * 单独拆成函数是为了让单元测试不依赖 Compose 渲染就能锁住符号口径。
 */
internal fun ledgerAmountText(cents: Long, type: TransactionType?, showSign: Boolean): String = when {
    !showSign -> Money.formatCurrency(cents)
    type == TransactionType.EXPENSE -> "-" + Money.formatCurrency(cents)
    type == TransactionType.INCOME -> "+" + Money.formatCurrency(cents)
    else -> Money.formatCurrency(cents)
}

/**
 * 金额的口语文本（无障碍 AA-5）：方向词 + [Money.spokenAmount]，不出现「¥」与小数点。
 *
 * 支出 / 收入 / 转账按类型给方向词（金额取绝对值，方向已由词表达）；
 * 余额调整要区分增减（计划口径里它不参与收支统计，只改账户余额）；
 * 汇总 / 净资产（`type = null`）不加速度词，负数由 `spokenAmount` 自带「负」。
 */
internal fun ledgerAmountSpeech(cents: Long, type: TransactionType?): String = when (type) {
    TransactionType.EXPENSE -> "支出 " + Money.spokenAmount(abs(cents))
    TransactionType.INCOME -> "收入 " + Money.spokenAmount(abs(cents))
    TransactionType.TRANSFER -> "转账 " + Money.spokenAmount(abs(cents))
    TransactionType.BALANCE_ADJUST ->
        "余额调整 " + (if (cents < 0) "减少 " else "增加 ") + Money.spokenAmount(abs(cents))
    null -> Money.spokenAmount(cents)
}