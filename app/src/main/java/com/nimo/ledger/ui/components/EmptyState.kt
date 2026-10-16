package com.nimo.ledger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nimo.ledger.R

/** 空态插图节点（测试与无障碍核对用） */
const val EMPTY_STATE_ILLUSTRATION_TEST_TAG: String = "empty-state-illustration"

/** 插图边长：图标里那只狗本身带透明留白，120dp 里可见的圆约 80dp */
private const val ILLUSTRATION_SIZE_DP = 120

/**
 * 统一空状态：小狗插图 + 标题 + 说明，居中显示（计划 4.4：空状态不能被空白页掩盖）。
 *
 * 插图复用应用图标里那只白狗（`res/drawable-nodpi/ic_launcher_foreground.png`，R4-L1
 * 「空态小狗插图（复用现有 PNG 资产）」），是**纯装饰**：contentDescription = null，
 * 读屏器只念标题与说明，不会念一张图。挤在窄条里、不适合放图的场合传 illustration = false。
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    illustration: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (illustration) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(ILLUSTRATION_SIZE_DP.dp)
                    .testTag(EMPTY_STATE_ILLUSTRATION_TEST_TAG),
            )
        }
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
