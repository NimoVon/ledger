package com.nimo.ledger.ui.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R-8：转入账户下的「转入后 ¥x」预估值。纯显示，不进统计，缺输入就不给数。 */
class TransferBalancePreviewTest {

    @Test
    fun balancePlusAmount_isTheAfterTransferNumber() {
        assertEquals(88_900L, transferAfterBalance(balanceCents = 76_400L, amountCents = 12_500L))
    }

    @Test
    fun withoutAnAmount_thereIsNoPreview() {
        assertNull(transferAfterBalance(balanceCents = 76_400L, amountCents = null))
    }

    @Test
    fun withoutATargetAccount_thereIsNoPreview() {
        assertNull(transferAfterBalance(balanceCents = null, amountCents = 12_500L))
    }
}
