package com.nimo.ledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val SKELETON_LIST_TAG = "skeleton-list"
internal const val SKELETON_ROW_TAG = "skeleton-row"
internal const val SKELETON_BLOCK_TAG = "skeleton-block"

/**
 * 一块静态灰条。**故意不做闪动/呼吸动画**：无限动画会让 Compose 永远拿不到 idle，
 * 测试就会像 §50.3 那样卡到 60 秒超时。骨架屏的价值在「形状先占位」，不在动效。
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    width: Dp? = null,
    shape: Shape = RoundedCornerShape(4.dp),
) {
    val sized = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    Box(
        modifier = sized
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .testTag(SKELETON_BLOCK_TAG),
    )
}

/**
 * 列表首屏骨架：分类圆点 + 两行文字 + 右侧金额，形状与 [com.nimo.ledger.ui.transactions.TransactionTimeline]
 * 的行一致，加载完成时行高不会跳。
 */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 6) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(SKELETON_LIST_TAG),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        repeat(rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SKELETON_ROW_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(modifier = Modifier.size(28.dp), height = 28.dp, width = 28.dp, shape = CircleShape)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBlock(height = 13.dp)
                    SkeletonBlock(height = 10.dp)
                }
                Spacer(Modifier.width(16.dp))
                SkeletonBlock(height = 14.dp, width = 72.dp)
            }
        }
    }
}
