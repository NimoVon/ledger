package com.nimo.ledger.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 通栏段头（UI-UX 规范 §6.2，批 D · D-2）：灰底一条，左边标题、右边一行小字。
 *
 * 明细页的日段头（日期 + 当日支出/收入）和统计页的各段头（洞察 / 趋势 / 分类占比 / 排行）
 * 之前是两份各写一遍的 [Surface] + [Row]，内边距一个 6dp、一个 6dp 但字号写法不同；现在统一到这里。
 *
 * 故意**不**做成圆角卡片：段头是"切分整页"的横线，卡片化会让一屏看起来像贴了一堆便签。
 *
 * @param hint 右侧一行小字（「点一行看明细」「日均 / 环比 / 预算」），纯提示，不参与点击
 * @param trailing 右侧自定义内容（明细页要同时放"支出 X"和"收入 Y"，[hint] 放不下时用它）
 */
@Composable
fun LedgerSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (hint != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}
