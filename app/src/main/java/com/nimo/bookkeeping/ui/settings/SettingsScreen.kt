package com.nimo.bookkeeping.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimo.bookkeeping.AppContainer
import com.nimo.bookkeeping.ui.components.FAB_BOTTOM_RESERVE
import com.nimo.bookkeeping.ui.theme.ThemePreference
import kotlinx.coroutines.launch

/**
 * 设置页（计划 4.3 ui/settings）。
 * 当前承载开发用的数据库自检（自 MainActivity 迁入），后续在此加入备份/导出与主题。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenBudgets: () -> Unit,
) {
    var lines by remember { mutableStateOf(listOf("自检运行中…")) }
    LaunchedEffect(Unit) {
        lines = try {
            container.selfCheck()
        } catch (t: Throwable) {
            listOf(
                "自检失败 ✗",
                t.javaClass.simpleName + ": " + (t.message ?: "未知错误"),
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "设置", style = MaterialTheme.typography.headlineSmall)
        HorizontalDivider()
        Text(text = "账户", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenAccounts)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "账户管理", style = MaterialTheme.typography.bodyLarge)
            Text(text = "›", style = MaterialTheme.typography.titleMedium)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenCategories)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "分类管理", style = MaterialTheme.typography.bodyLarge)
            Text(text = "›", style = MaterialTheme.typography.titleMedium)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenBudgets)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "预算管理", style = MaterialTheme.typography.bodyLarge)
            Text(text = "›", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        Text(text = "外观", style = MaterialTheme.typography.titleMedium)
        // DataStore 是真异步：先按「跟随系统」画一帧，读到盘上的真值再纠正
        val themePreference by container.themePreference.preference
            .collectAsStateWithLifecycle(initialValue = ThemePreference.SYSTEM)
        val scope = rememberCoroutineScope()
        ThemePreference.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = option == themePreference,
                        role = Role.RadioButton,
                        onClick = { scope.launch { container.themePreference.write(option) } },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == themePreference, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(
            text = "跟随系统时随手机的深浅色切换；选浅色 / 深色则一直用这一套。",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Text(text = "导入与导出", style = MaterialTheme.typography.titleMedium)
        BackupSettingsSection(
            backupRepository = container.backupRepository,
            autoBackupStore = container.autoBackupStore,
            pendingDeletion = container.pendingTransactionDeletion,
        )
        HorizontalDivider()
        Text(text = "数据库自检（开发用）", style = MaterialTheme.typography.titleMedium)
        lines.forEach { line ->
            Text(text = line, style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
        Text(
            text = "记账本 0.1.0 · 已完成 M1（数据层）",
            style = MaterialTheme.typography.bodySmall,
        )
        // 悬浮的「记一笔」会盖住页面最后一行，底部留出按钮的高度（F15）
        Spacer(Modifier.height(FAB_BOTTOM_RESERVE))
    }
}
