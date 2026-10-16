package com.nimo.ledger.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.validate.CategoryValidationError
import com.nimo.ledger.domain.validate.CategoryValidationException
import com.nimo.ledger.domain.validate.CategoryValidator

/** 校验错误 → 中文提示 */
internal fun CategoryValidationError.toMessage(): String = when (this) {
    CategoryValidationError.NameBlank -> "分类名称不能为空"
    CategoryValidationError.NameTooLong -> "分类名称最多 ${CategoryValidator.MAX_NAME_LENGTH} 个字"
    CategoryValidationError.DuplicateName -> "同一层级下已经有同名分类"
    CategoryValidationError.ParentNotFound -> "所选上级分类已不存在"
    CategoryValidationError.ParentKindMismatch -> "支出与收入是两套分类树，不能跨树挂载"
    CategoryValidationError.ParentNotTopLevel -> "只支持两级分类：不能再挂到二级分类下"
    CategoryValidationError.ParentArchived -> "所选上级分类已停用，请先恢复它"
}

/** 保存失败 → 中文提示 */
internal fun Throwable.toCategoryMessage(): String = when (this) {
    is CategoryValidationException -> errors.joinToString("；") { it.toMessage() }
    else -> message ?: "保存失败"
}

/**
 * 新建 / 编辑分类（F02）。
 *
 * @param editing null 表示新建
 * @param topLevelOptions 可选的上级分类：只列一级且未停用的（过滤在页面侧完成，对话框再按 kind 收窄）
 *
 * 编辑只改名字：换父级会让既有历史流水落到另一棵树里，F02 不做跨树移动（要换就停用旧的、新建一个）。
 */
@Composable
fun CategoryEditDialog(
    editing: CategoryRow?,
    topLevelOptions: List<CategoryRow>,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, kind: CategoryKind, parentId: Long?) -> Unit,
    onToggleArchived: (() -> Unit)?,
) {
    var name by remember(editing) { mutableStateOf(editing?.name.orEmpty()) }
    var kind by remember(editing) { mutableStateOf(editing?.kind ?: CategoryKind.EXPENSE) }
    var parentId by remember(editing) { mutableStateOf(editing?.parentId) }
    var parentExpanded by remember { mutableStateOf(false) }
    var localError by remember(editing) { mutableStateOf<String?>(null) }

    // 只能挂到同一棵树的一级分类下；编辑自己时不能把自己当父级
    val parentCandidates = topLevelOptions.filter { it.kind == kind && it.id != editing?.id }
    val parentLabel = parentCandidates.firstOrNull { it.id == parentId }?.name ?: "无（一级分类）"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "新建分类" else "编辑分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editing == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryKind.entries.forEach { candidate ->
                            FilterChip(
                                selected = kind == candidate,
                                onClick = {
                                    kind = candidate
                                    // 换树就清掉上一棵树选中的父级，否则会留下跨树挂载
                                    parentId = null
                                    localError = null
                                },
                                label = { Text(candidate.displayName()) },
                            )
                        }
                    }
                } else {
                    Text(
                        text = "类型：" + kind.displayName() + "（编辑不能换树或换父级）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; localError = null },
                    label = { Text("分类名称") },
                    singleLine = true,
                )
                if (editing == null) {
                    Column {
                        OutlinedButton(onClick = { parentExpanded = true }) {
                            Text("上级分类：$parentLabel")
                        }
                        DropdownMenu(
                            expanded = parentExpanded,
                            onDismissRequest = { parentExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("无（一级分类）") },
                                onClick = { parentId = null; parentExpanded = false },
                            )
                            parentCandidates.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(candidate.name) },
                                    onClick = { parentId = candidate.id; parentExpanded = false },
                                )
                            }
                        }
                    }
                }
                val shown = localError ?: errorMessage
                if (shown != null) {
                    Text(
                        text = shown,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    // 名称先本地拦一道（空 / 过长）；重名与父级合法性要对着全量数据看，
                    // 由 Repository 判定后经 errorMessage 回显，不在这里猜
                    val errors = CategoryValidator.validateName(name)
                    if (errors.isNotEmpty()) {
                        localError = errors.joinToString("；") { it.toMessage() }
                        return@TextButton
                    }
                    onSave(
                        name.trim(),
                        if (editing == null) kind else editing.kind,
                        if (editing == null) parentId else editing.parentId,
                    )
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            Row {
                if (onToggleArchived != null) {
                    TextButton(onClick = onToggleArchived, enabled = !saving) {
                        Text(if (editing?.isArchived == true) "恢复" else "停用")
                    }
                }
                TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
            }
        },
    )
}
