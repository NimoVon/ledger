package com.nimo.ledger.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 圆角规格守门（R4-L1）：容器类 16–24dp，细条类留在调用点 */
class LedgerShapesTest {

    @Test
    fun containerSteps_sitInsideTheBrandRangeOf16To24dp() {
        assertEquals(listOf(16, 20, 24), BRAND_SHAPE_CONTAINER_DP)
        assertTrue(BRAND_SHAPE_CONTAINER_DP.all { it in 16..24 })
        assertEquals(BRAND_SHAPE_CONTAINER_DP.sorted(), BRAND_SHAPE_CONTAINER_DP)
    }

    @Test
    fun theThreeContainerSteps_mapToMediumLargeExtraLarge() {
        assertEquals(RoundedCornerShape(16.dp), LedgerShapes.medium)
        assertEquals(RoundedCornerShape(20.dp), LedgerShapes.large)
        assertEquals(RoundedCornerShape(24.dp), LedgerShapes.extraLarge)
    }

    /** Chip / 输入框（small）与密集块（extraSmall）比容器档小，免得小控件被圆角吃掉 */
    @Test
    fun smallRoles_stayBelowTheContainerRange() {
        assertEquals(RoundedCornerShape(8.dp), LedgerShapes.extraSmall)
        assertEquals(RoundedCornerShape(12.dp), LedgerShapes.small)
        assertTrue(12 < BRAND_SHAPE_CONTAINER_DP.first())
    }

    @Test
    fun tooltipShape_usesTheSmallestContainerStep_soTrendAndDonutBubblesMatch() {
        assertEquals(RoundedCornerShape(BRAND_SHAPE_CONTAINER_DP.first().dp), TooltipShape)
        assertEquals(RoundedCornerShape(16.dp), TooltipShape)
    }
}
