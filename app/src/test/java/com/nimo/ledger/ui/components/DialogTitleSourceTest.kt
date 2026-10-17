package com.nimo.ledger.ui.components

import com.nimo.ledger.ui.accounts.ACCOUNT_DIALOG_TITLE
import com.nimo.ledger.ui.categories.CATEGORY_DIALOG_TITLE
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AA-1 / C-1 / A-1 的回归网：页内 FAB 与对话框标题不能是同一句话。
 *
 * 这是纯语义问题——画面上看只是「新建账户」出现了两次，但读屏器会把 FAB（可点）与
 * 对话框标题（只读）念成同一个名字，用户分不清「这是按钮还是说明」。
 * 唯一解是两者文本分离：FAB 保留动词「新建账户」，对话框标题改名词「账户信息」/「分类信息」。
 *
 * 为什么不渲染 AlertDialog 做行为测试：Robolectric 下的 Dialog 窗口与 Compose 的 idling
 * 一直不稳（项目里其它对话框测试同样只渲染内容容器）。这里改为守住三件可核对的事：
 * 常量的取值、对话框源码不再出现动词标题、FAB 的 contentDescription 仍指向自己的文案。
 */
class DialogTitleSourceTest {

    private val repoRoot: File = run {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        requireNotNull(dir) { "找不到仓库根目录（settings.gradle.kts）" }
    }

    private fun source(relativePath: String): String =
        File(repoRoot, "app/src/main/java/com/nimo/ledger/" + relativePath).readText()

    private fun stripComments(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\\n]*"), " ")

    @Test
    fun dialogTitles_areNouns_notTheFabVerb() {
        assertEquals("账户信息", ACCOUNT_DIALOG_TITLE)
        assertEquals("分类信息", CATEGORY_DIALOG_TITLE)
        assertNotEquals("新建账户", ACCOUNT_DIALOG_TITLE)
        assertNotEquals("新建分类", CATEGORY_DIALOG_TITLE)
    }

    @Test
    fun accountDialog_noLongerTitlesItselfWithTheFabLabel() {
        val dialog = stripComments(source("ui/accounts/AccountEditDialog.kt"))
        assertTrue(dialog.contains("ACCOUNT_DIALOG_TITLE"))
        // 标题里不该再出现动词串（新建 / 编辑），否则与 FAB 撞名
        assertFalse(dialog.contains("\"新建账户\""))
        assertFalse(dialog.contains("\"编辑账户\""))
    }

    @Test
    fun categoryDialog_noLongerTitlesItselfWithTheFabLabel() {
        val dialog = stripComments(source("ui/categories/CategoryEditDialog.kt"))
        assertTrue(dialog.contains("CATEGORY_DIALOG_TITLE"))
        assertFalse(dialog.contains("\"新建分类\""))
        assertFalse(dialog.contains("\"编辑分类\""))
    }

    @Test
    fun theFabKeepsItsOwnVerb_andPointsAtItselfForScreenReaders() {
        val accounts = stripComments(source("ui/accounts/AccountsScreen.kt"))
        assertTrue(accounts.contains("NEW_ACCOUNT_LABEL = \"新建账户\""))
        assertTrue(accounts.contains("contentDescription = NEW_ACCOUNT_LABEL"))

        val categories = stripComments(source("ui/categories/CategoriesScreen.kt"))
        assertTrue(categories.contains("NEW_CATEGORY_LABEL = \"新建分类\""))
        assertTrue(categories.contains("contentDescription = NEW_CATEGORY_LABEL"))
    }
}
