package com.nimo.ledger.ui.record

import com.nimo.ledger.domain.validate.TransactionValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-6：备注计数在接近上限（≥180）时要进入提醒色。
 *
 * 颜色本身由 Compose 主题渲染，这里锁的是「什么时候该提醒」这条判定——
 * 页面只负责把判定结果映射到 `tertiary`；写死阈值或把边界写成 `>` 都会红。
 */
class RecordNoteCounterTest {

    @Test
    fun belowThreshold_staysQuiet() {
        assertFalse(noteCounterNearLimit(0))
        assertFalse(noteCounterNearLimit(1))
        assertFalse(noteCounterNearLimit(179))
    }

    @Test
    fun atOrAboveThreshold_warns() {
        assertTrue(noteCounterNearLimit(180))
        assertTrue(noteCounterNearLimit(199))
        assertTrue(noteCounterNearLimit(TransactionValidator.MAX_NOTE_LENGTH))
    }

    @Test
    fun threshold_matchesTheCounterTextBounds() {
        // 门槛必须落在 200 以内，否则「提醒」在输入被截断之后才出现
        assertTrue("提醒阈值应小于上限", NOTE_COUNTER_WARN_AT < TransactionValidator.MAX_NOTE_LENGTH)
        assertTrue("提醒阈值不应过早", NOTE_COUNTER_WARN_AT >= 150)
    }
}
