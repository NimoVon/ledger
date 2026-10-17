package com.nimo.ledger.util

import kotlin.math.abs

/**
 * 金额唯一入口：所有金额一律 Long 存"分"，绝不用 Float/Double（计划铁律 1）。
 */
object Money {

    /** 产品允许上限：¥99,999,999.99 */
    const val MAX_AMOUNT_CENTS: Long = 9_999_999_999L

    private val AMOUNT_RE = Regex("""\d{1,11}(\.\d{1,2})?""")

    /** 带千分位的输入：1,234 / 1,234.5 / 99,999,999.99（分组必须整三位，不接受 1,23 这类半截分组） */
    private val GROUPED_AMOUNT_RE = Regex("""\d{1,3}(,\d{3}){1,4}(\.\d{1,2})?""")

    /**
     * 解析用户输入金额文本为分。接受可选前导 '-'（余额校准用负数）。
     * 规则：非空、纯数字、最多两位小数、非零、绝对值不超过上限。
     * 另外接受**规格正确**的千分位分组（1,234 / 99,999,999.99），
     * 因为界面上展示的金额带千分位，用户把它复制回输入框时必须仍然可解析。
     * 返回 null 表示非法，由调用方给出提示。
     */
    fun parseCents(input: String): Long? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val magnitude = parseMagnitudeOrNull(text) ?: return null
        if (magnitude == 0L || magnitude > MAX_AMOUNT_CENTS) return null
        return if (text.startsWith("-")) -magnitude else magnitude
    }

    /**
     * 解析"元"文本为分，**允许 0 与负数**（CSV 导入用：账户初始余额可以是 0 或负数）。
     *
     * 与 [parseCents] 共用同一个格式解析器与同一个上限，唯一区别是不拒绝零值：
     * CSV/数据库都以上限为界，超上限一样返回 null。转账等"必须大于 0"的规则由调用方判断。
     */
    fun parseCentsAllowingZero(input: String): Long? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val magnitude = parseMagnitudeOrNull(text) ?: return null
        if (magnitude > MAX_AMOUNT_CENTS) return null
        return if (text.startsWith("-")) -magnitude else magnitude
    }

    /**
     * 输入文本"格式合法、但数值超过 [MAX_AMOUNT_CENTS]"（计划 line 495：金额超上限要有明确处理）。
     *
     * 与 [parseCents] 共用同一个 [parseMagnitudeOrNull]，所以两者的格式口径永远不会漂移：
     * 能解析出数值且大于上限才是 true；空、三位小数、半截千分位这类**格式**问题一律 false
     * （由 parseCents 返回 null 去走"格式不正确"文案）。零（"0"/"0.00"）不算超上限。
     */
    fun exceedsMaxAmount(input: String): Boolean {
        val magnitude = parseMagnitudeOrNull(input) ?: return false
        return magnitude > MAX_AMOUNT_CENTS
    }

    /**
     * 输入口归一化：把中文输入法常见的全角字符换成半角，返回可直接展示、也可直接喂给
     * [parseCents] 的文本。**只做字符替换，不做格式判断**——格式仍然只由 [parseCents] 判定。
     *
     * 为什么放在输入口而不是让 parse 宽容：搜狗等中文键盘会把小数点打成「。」，字段里就会显示
     * 「123。45」。只在解析端宽容的话，用户看到的仍是全角、§22 的金额复核行也会与字段对不上；
     * 归一化放到 onValueChange，字段本身就是半角，复核行、保存、CSV 三条路径口径一致。
     * 用户粘贴带千分位的展示金额（「1,234.56」）同样原样保留逗号，交给既有分组解析。
     */
    fun normalizeAmountInput(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(
                when (ch) {
                    in '０'..'９' -> '0' + (ch - '０')     // 全角数字
                    '。', '．', '｡' -> '.'                 // 句号 / 全角句点 / 半角句点
                    '，' -> ','                              // 全角逗号（千分位）
                    '－', '−', '﹣' -> '-'                   // 各类全角/数学负号
                    '\u3000' -> ' '                         // 全角空格，交给既有 trim()
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** 纯格式解析：返回金额绝对值（分），格式非法返回 null。**不做**零值与上限判断，由调用方决定。 */
    private fun parseMagnitudeOrNull(input: String): Long? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val raw = if (text.startsWith("-")) text.substring(1) else text
        if (raw.isEmpty()) return null
        val body = when {
            raw.contains(',') -> {
                if (!GROUPED_AMOUNT_RE.matches(raw)) return null
                raw.replace(",", "")
            }
            AMOUNT_RE.matches(raw) -> raw
            else -> return null
        }
        val parts = body.split('.')
        val centsDigits = parts[0] + (if (parts.size == 2) parts[1].padEnd(2, '0') else "00")
        return centsDigits.toLongOrNull()
    }

    /**
     * 分 → 元文本，两位小数，**不带千分位**：12345 -> "123.45"、-105 -> "-1.05"。
     *
     * 契约（改动前请先读）：本函数专供**输入框回填**，回填后的文本会再经 [parseCents] 解析回来
     * （见 RecordViewModel 编辑回填、AccountEditDialog 初始余额、TransactionFilterDialog 金额区间）。
     * 一旦在这里加千分位，回填文本会多出逗号——虽然 [parseCents] 已能解析规格正确的分组，
     * 但回填文本仍是"机器文本"，保持纯数字最稳。给人看的展示文本一律用 [formatCurrency]。
     */
    fun format(cents: Long): String {
        val a = abs(cents)
        return (if (cents < 0) "-" else "") + (a / 100).toString() + "." + (a % 100).toString().padStart(2, '0')
    }

    /**
     * 分 → 带货币符号与千分位的**展示**文本：12345 -> "¥123.45"、123456789 -> "¥1,234,567.89"、
     * -105 -> "-¥1.05"。所有给人看的金额都用这个（计划 9.1 示例即 "¥1,000.00" 写法）。
     */
    fun formatCurrency(cents: Long): String {
        val a = abs(cents)
        val sign = if (cents < 0) "-" else ""
        return sign + "¥" + groupThousands(a / 100) + "." + (a % 100).toString().padStart(2, '0')
    }

    /** 「万」档起点：¥10,000 = 1,000,000 分 */
    private const val WAN_CENTS: Long = 1_000_000L

    /**
     * 分 → 图表纵轴刻度文本：紧凑、不带拖尾零，量级一眼可读（统计页 y 轴优化）。
     *
     * 规则：不足 1 万元走"整数元 + 千分位"（0 -> "¥0"、60000 -> "¥600"、100000 -> "¥1,000"）；
     * 不是整元时保留两位小数（1234 -> "¥12.34"），避免刻度"看着像整数其实不是"；
     * 1 万元及以上压成"万"、最多一位小数，且**向上取**——刻度宁可略高于实际值也不能低报
     * （1_000_000 -> "¥1万"、1_010_000 -> "¥1.1万"、4_000_000 -> "¥4万"、100_000_000 -> "¥100万"）。
     * 只给图表刻度用；给人看的金额一律 [formatCurrency]（带两位小数）。
     */
    fun formatAxisLabel(cents: Long): String {
        val a = abs(cents)
        val sign = if (cents < 0) "-" else ""
        if (a >= WAN_CENTS) {
            val tenth = (a + 99_999L) / 100_000L   // 万 × 10，向上取整
            val wan = tenth / 10
            val fraction = tenth % 10
            return sign + "¥" + groupThousands(wan) + (if (fraction != 0L) ".$fraction" else "") + "万"
        }
        if (a % 100L == 0L) return sign + "¥" + groupThousands(a / 100L)
        return sign + "¥" + groupThousands(a / 100L) + "." + (a % 100L).toString().padStart(2, '0')
    }

    /** 整数部分每三位插一个逗号：1234567 -> "1,234,567"（<=3 位原样返回） */
    private fun groupThousands(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val sb = StringBuilder(digits.length + digits.length / 3)
        val lead = digits.length % 3
        if (lead > 0) sb.append(digits, 0, lead)
        var i = lead
        while (i < digits.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(digits, i, i + 3)
            i += 3
        }
        return sb.toString()
    }
}
