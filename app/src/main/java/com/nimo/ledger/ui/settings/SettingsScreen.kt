package com.nimo.ledger.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimo.ledger.AppContainer
import com.nimo.ledger.ui.components.FAB_BOTTOM_RESERVE
import com.nimo.ledger.ui.components.LedgerListItem
import com.nimo.ledger.ui.theme.ThemePreference
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * 设置页（计划 4.3 ui/settings）。
 *
 * 结构按 UI-UX 规范 §7.4 卡片分组（ST-5）：账户 / 外观 / 导入与导出 / 关于 四组，
 * 每组是 surfaceContainerLow 卡片，页面左右边距 16dp（G-10 已随卡片分组统一）。
 *
 * ST-1：数据库自检从页面中部移入「关于」组，默认折叠；只有展开时才真正跑一次自检，
 * 结果留在本页状态里，切走再回来重新折叠（默认值就是折叠，符合「不进用户可见主路径」）。
 * ST-2：版本行是「关于」组末行，只显示「记账本 0.1.0」，里程碑文案不再出现。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenBudgets: () -> Unit,
) {
    // 默认折叠（ST-1）；rememberSaveable 让旋转屏幕时不丢展开状态，切 tab 回来仍从折叠开始
    var developerExpanded by rememberSaveable { mutableStateOf(false) }
    var selfCheckLines by remember { mutableStateOf<List<String>?>(null) }
    // §7.3 第 3 条：子页入口带状态副标题（账户数 / 分类数 / 本月是否已设总预算）
    var accountCount by remember { mutableStateOf<Int?>(null) }
    var categoryCount by remember { mutableStateOf<Int?>(null) }
    var hasTotalBudget by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        launch {
            container.repository.observeAccounts()
                .collect { accountCount = it.size }
        }
        launch {
            container.repository.observeAllCategories()
                .collect { categoryCount = it.size }
        }
        launch {
            val month = YearMonth.now().toString()
            container.repository.observeBudgets(month)
                .collect { budgets -> hasTotalBudget = budgets.any { it.categoryId == null } }
        }
    }
    LaunchedEffect(developerExpanded) {
        if (developerExpanded && selfCheckLines == null) {
            selfCheckLines = try {
                container.selfCheck()
            } catch (t: Throwable) {
                listOf(
                    "自检失败 ✗",
                    t.javaClass.simpleName + ": " + (t.message ?: "未知错误"),
                )
            }
        }
    }

    val themePreference by container.themePreference.preference
        .collectAsStateWithLifecycle(initialValue = ThemePreference.SYSTEM)
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(text = "设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        SettingsGroup(title = "账户") {
            // 卡片内的行用统一列表行默认 16dp 内边距，不再叠加页面边距
            LedgerListItem(
                title = "账户管理",
                subtitle = accountCount?.let(::accountsCountSubtitle),
                chevron = true,
                onClick = onOpenAccounts,
            )
            LedgerListItem(
                title = "分类管理",
                subtitle = categoryCount?.let(::categoriesCountSubtitle),
                chevron = true,
                onClick = onOpenCategories,
            )
            LedgerListItem(
                title = "预算管理",
                subtitle = hasTotalBudget?.let(::budgetMonthSubtitle),
                chevron = true,
                onClick = onOpenBudgets,
            )
        }
        Spacer(Modifier.height(SETTINGS_GROUP_SPACING_DP))

        SettingsGroup(title = "外观") {
            // DataStore 是真异步：先按「跟随系统」画一帧，读到盘上的真值再纠正
            ThemePreference.entries.forEach { option ->
                ThemeOptionRow(
                    option = option,
                    selected = option == themePreference,
                    onSelect = { scope.launch { container.themePreference.write(option) } },
                )
            }
            Text(
                text = "跟随系统时随手机的深浅色切换；选浅色 / 深色则一直用这一套。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(SETTINGS_GROUP_SPACING_DP))

        SettingsGroup(title = "导入与导出") {
            Column(modifier = Modifier.padding(16.dp)) {
                BackupSettingsSection(
                    backupRepository = container.backupRepository,
                    autoBackupStore = container.autoBackupStore,
                    pendingDeletion = container.pendingTransactionDeletion,
                )
            }
        }
        Spacer(Modifier.height(SETTINGS_GROUP_SPACING_DP))

        SettingsAboutGroup(
            expanded = developerExpanded,
            onToggleExpanded = { developerExpanded = !developerExpanded },
            selfCheckLines = selfCheckLines,
        )

        // 悬浮的「记一笔」会盖住页面最后一行，底部留出按钮的高度（F15）
        Spacer(Modifier.height(FAB_BOTTOM_RESERVE))
    }
}

/** §7.3 第 3 条：账户入口副标题 —— 让它进子页前就知道规模 */
internal fun accountsCountSubtitle(count: Int): String = "$count 个账户"

/** §7.3 第 3 条：分类入口副标题（含已停用，和分类页一致） */
internal fun categoriesCountSubtitle(count: Int): String = "$count 个分类"

/** §7.3 第 3 条：预算入口副标题 —— 只回答「本月有没有设总预算」 */
internal fun budgetMonthSubtitle(hasTotalBudget: Boolean): String =
    if (hasTotalBudget) "本月已设总预算" else "本月未设总预算"