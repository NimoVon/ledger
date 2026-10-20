package com.nimo.ledger.ui

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.1 补齐的 13 个功能图标（批 F · F-1）。
 *
 * 钉三件事：图标真的存在且非空、统一 24dp viewport、描边类一律 2.0f + Round 端点/拐角。
 * 不测「好不好看」——那只能靠 24 / 20 / 16dp 三档真机截图看。
 */
class LedgerIconsTest {

    private val newIcons: Map<String, ImageVector> = linkedMapOf(
       "Delete" to LedgerIcons.Delete,
       "Calendar" to LedgerIcons.Calendar,
        "Search" to LedgerIcons.Search,
        "Wallet" to LedgerIcons.Wallet,
        "Card" to LedgerIcons.Card,
        "Alert" to LedgerIcons.Alert,
        "Info" to LedgerIcons.Info,
        "Download" to LedgerIcons.Download,
        "Upload" to LedgerIcons.Upload,
   )

    /** ImageVector.paths 是 internal，只能顺着公开的 root 走一遍把 VectorPath 收集出来 */
    private fun ImageVector.leafPaths(): List<VectorPath> = (0 until root.size).flatMap { root[it].leafPaths() }

    private fun VectorNode.leafPaths(): List<VectorPath> = when (this) {
        is VectorPath -> listOf(this)
        is VectorGroup -> (0 until size).flatMap { get(it).leafPaths() }
        else -> emptyList()
    }

    @Test
    fun thereAreNineNewIcons() {
        assertEquals(9, newIcons.size)
    }

    @Test
    fun everyNewIcon_isDrawnOnA24dpViewport_andHasPaths() {
        newIcons.forEach { (label, icon) ->
            assertEquals("$label 的宽应是 24dp", 24.dp, icon.defaultWidth)
            assertEquals("$label 的高应是 24dp", 24.dp, icon.defaultHeight)
            assertEquals("$label 的 viewport 宽应是 24", 24f, icon.viewportWidth, 0.0001f)
            assertEquals("$label 的 viewport 高应是 24", 24f, icon.viewportHeight, 0.0001f)
            assertTrue("$label 至少要有一条路径", icon.leafPaths().isNotEmpty())
        }
    }

    @Test
    fun everyNewIcon_isStroke2RoundCapAndRoundJoin() {
        newIcons.forEach { (label, icon) ->
            icon.leafPaths().forEach { path ->
                assertNotNull("$label 应该是描边而不是填充", path.stroke)
                assertEquals("$label 的线宽应是 2.0f", 2f, path.strokeLineWidth, 0.0001f)
                assertEquals("$label 的端点应是 Round", StrokeCap.Round, path.strokeLineCap)
                assertEquals("$label 的拐角应是 Round", StrokeJoin.Round, path.strokeLineJoin)
            }
        }
    }

    @Test
    fun theThirteenIcons_haveUniqueNames() {
        val names = newIcons.values.map { it.name }
        assertEquals("图标名不能重复（ImageVector 的 name 会进缓存键）", names.size, names.toSet().size)
        assertTrue(names.all { it.startsWith("Ledger") })
    }

    @Test
    fun theTrendArrows_usedByTheChangeCellStillExist() {
        // 统计页环比箭头复用既有的 ArrowUp / ArrowDown（§5.1 的 TrendUp / TrendDown 落在这两个上）
        assertTrue(LedgerIcons.ArrowUp.leafPaths().isNotEmpty())
        assertTrue(LedgerIcons.ArrowDown.leafPaths().isNotEmpty())
        assertEquals(24f, LedgerIcons.ArrowUp.viewportWidth, 0.0001f)
        assertEquals(24f, LedgerIcons.ArrowDown.viewportWidth, 0.0001f)
    }
}
