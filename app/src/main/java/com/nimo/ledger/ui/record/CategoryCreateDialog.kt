package com.nimo.ledger.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.domain.validate.CategoryValidator
import com.nimo.ledger.ui.categories.toMessage

/** 记一笔页分类区末尾的入口（附.1 R3）；按钮文字与测试共用一处，免得改了文案测试就瞎 */
internal const val CREATE_CATEGORY_LABEL = "+ 新建"

/** 内联新建对话框的 UI 测试 tag */
internal const val CREATE_CATEGORY_DIALOG_TEST_TAG = "record-category-create"

/**
 * 记一笔内联新建分类（附.1 R3）：记账记到一半发现没有合适的分类，不用退出这一笔。
 *
 * 紧凑对话框：名称 + 可选上级，**类型锁定当前收支段**（不给切，免得建到另一棵树上去）。
 * 名称规则走 [CategoryValidator]（与分类管理页同一套），重名由 Repository 对着全量分类裁决；
 * 保存成功后 ViewModel 自动选中新分类（[RecordUiState.createdCategoryId]）。
 */
@Composable
internal fun CategoryCreateDialog(
    kindLabel: String,
    parentOptions: List<CategoryEntity>,
    creating: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (name: String, parentId: Long?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf<Long?>(null) }
    var parentMenuOpen by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val parentLabel = parentOptions.firstOrNull { it.id == parentId }?.name ?: "无（一级分类）"

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        modifier = Modifier.testTag(CREATE_CATEGORY_DIALOG_TEST_TAG),
        title = { Text("新建分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "类型：$kindLabel（跟随当前收支方向，不能在这里切换）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        localError = null
                    },
                    label = { Text("分类名称") },
                    singleLine = true,
                )
                Box {
                    OutlinedButton(onClick = { parentMenuOpen = true }, enabled = !creating) {
                        Text("上级分类：$parentLabel")
                    }
                    DropdownMenu(
                        expanded = parentMenuOpen,
                        onDismissRequest = { parentMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("无（一级分类）") },
                            onClick = {
                                parentId = null
                                parentMenuOpen = false
                            },
                        )
                        parentOptions.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.name) },
                                onClick = {
                                    parentId = candidate.id
                                    parentMenuOpen = false
                                },
                            )
                        }
                    }
                }
                val shown = localError ?: errorMessage
                if (shown != null) {
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating,
                onClick = {
                    // 第一道防线：名称规则先在这里拦一次，别让用户等一个来回才知道名字不能用
                    val errors = CategoryValidator.validateName(name)
                    if (errors.isNotEmpty()) {
                        localError = errors.joinToString("；") { it.toMessage() }
                        return@TextButton
                    }
                    onCreate(name.trim(), parentId)
                },
            ) { Text(if (creating) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") }
        },
    )
}
