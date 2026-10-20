package com.nimo.ledger.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldErrorTest {

    @Test
    fun blankNameError_belongsToTheNameField() {
        assertTrue(fieldHasError("账户名称不能为空", "名称"))
        assertFalse(fieldHasError("账户名称不能为空", "余额"))
    }

    @Test
    fun amountError_belongsToTheAmountField() {
        assertTrue(fieldHasError("预算金额要大于 0", "金额"))
        assertFalse(fieldHasError("预算金额要大于 0", "名称"))
    }

    @Test
    fun nullMessage_marksNoField() {
        assertFalse(fieldHasError(null, "名称"))
        assertFalse(fieldHasError(null, "金额"))
    }

    @Test
    fun messageWithNoKeyword_marksNoField() {
        assertFalse(fieldHasError("保存失败", "名称"))
    }
}