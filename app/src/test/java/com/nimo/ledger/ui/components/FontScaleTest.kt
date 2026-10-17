package com.nimo.ledger.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 字体竖排门槛（设计规范 §3.3 / G-6 / 批 F 的 F-2）的纯函数回归：门槛只能有一个数。 */
class FontScaleTest {

    @Test
    fun normalAndMediumFontScales_stayOnOneRow() {
        assertFalse(stackAtLargeFont(1.0f))
        assertFalse(stackAtLargeFont(1.15f))
        assertFalse(stackAtLargeFont(1.3f))   // 1.3 实测横向还挤得下（真机档位「较大」）
        assertFalse(stackAtLargeFont(1.49f))
    }

    @Test
    fun fromFifteenTimesUp_itStacks() {
        assertTrue(stackAtLargeFont(1.5f))     // 真机档位「最大」的下一档，金额开始被折断
        assertTrue(stackAtLargeFont(1.8f))
        assertTrue(stackAtLargeFont(2.0f))     // 真机「特大」：结余 -¥600.00 被断成两行
    }
}
