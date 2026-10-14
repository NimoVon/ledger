package com.nimo.bookkeeping.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nimo.bookkeeping.AppContainer

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
    }
}
