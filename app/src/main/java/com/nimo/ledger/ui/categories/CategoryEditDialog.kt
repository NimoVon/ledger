package com.nimo.ledger.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.validate.CategoryDeletionBlocker
import com.nimo.ledger.domain.validate.CategoryDeletionException
import com.nimo.ledger.domain.validate.CategoryReferences
import com.nimo.ledger.domain.validate.CategoryValidationError
import com.nimo.ledger.domain.validate.CategoryValidationException
import com.nimo.ledger.domain.validate.CategoryValidator

/** 「删除」按钮的 UI 测试 tag（附.1 R3） */
internal const val CATEGORY_DELETE_TEST_TAG = "category-edit-delete"

/** 删除二次确认对话框的确认按钮 tag */
internal const val CATEGORY_DELETE_CONFIRM_TEST_TAG = "category-edit-delete-confirm"

/** 引用明细那行文字的 tag：测试断言"删除被什么挡住了" */
internal const val CATEGORY_REFERENCE_HINT_TEST_TAG = "category-edit-reference-hint"

/** 图标宫格（新建/编辑分类）的测试 tag 与预置 emoji（C-6 / F-3） */
internal const val CATEGORY_ICON_GRID_TEST_TAG = "category-icon-grid"
internal const val CATEGORY_ICON_FIELD_TEST_TAG = "category-icon-field"

/** 单颗预置图标的 tag 前缀：同一颗 emoji 在「宫格 / 输入框 / 外部状态」里会重复出现，测试必须认宫格那一颗 */
internal const val CATEGORY_ICON_PRESET_TAG_PREFIX = "category-icon-preset-"

/**
 * 预置图标（emoji）。与 [com.nimo.ledger.data.local.DefaultCategories] 保持同一套，
 * `CategoryIconPresetsTest` 会比两边的集合，漏一颗就红。
 */
internal val CATEGORY_ICON_PRESETS: List<String> = listOf(
    "🍜", "🚌", "🛍️", "🏠", "📱", "🎮", "💊", "📚", "🎁", "🔁", "📋",
    "💼", "🏆", "💻", "📈", "🧾", "↩️",
)

/**
 * 图标输入上限：最多 2 个码点（emoji + 变体选择符，比如 🛍️ 就是两个），且不切断代理对。
 *
 * 用 `String.take(n)` 会按 UTF-16 单元切，容易把 emoji 劈成半个——所以按码点走。
 */
internal fun clampCategoryIconInput(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val out = StringBuilder()
    var index = 0
    var codePoints = 0
    while (index < trimmed.length && codePoints < 2) {
        val codePoint = trimmed.codePointAt(index)
        out.appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
        codePoints++
    }
    return out.toString()
}

/** 交给仓库的图标：空串表示「不要图标」（null），否则就是宫格/输入框里的那一颗。 */
internal fun normalizedCategoryIcon(raw: String): String? = clampCategoryIconInput(raw).ifEmpty { null }

/**
 * 图标选择：预置宫格 + 手动输入。抽成独立组件是为了能被 UI 测试直接渲染——
 * `AlertDialog` 里带 `OutlinedTextField` 在 Robolectric 下永远不 idle（见 `BudgetEditDialogTest` 的注释），
 * 与 `TransactionFilterDialog` 把正文抽成 `FilterDialogBody` 是同一个套路。
 *
 * 再点一次已选的那颗 = 取消选择（宫格是单选，不是开关不了的死状态）。
 */
@Composable
internal fun CategoryIconPicker(
    icon: String,
    onIconChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "图标（可选）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CATEGORY_ICON_GRID_TEST_TAG),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CATEGORY_ICON_PRESETS.forEach { preset ->
                FilterChip(
                    selected = icon == preset,
                    onClick = { onIconChange(if (icon == preset) "" else preset) },
                    label = { Text(preset) },
                    modifier = Modifier.testTag(CATEGORY_ICON_PRESET_TAG_PREFIX + preset),
                )
            }
        }
        OutlinedTextField(
            value = icon,
            onValueChange = { onIconChange(clampCategoryIconInput(it)) },
            label = { Text("或手动输入 emoji") },
            singleLine = true,
            modifier = Modifier.testTag(CATEGORY_ICON_FIELD_TEST_TAG),
        )
    }
}

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

/** 删除被挡下的原因 → 中文提示（附.1 R3：有引用只能停用） */
internal fun CategoryDeletionBlocker.toMessage(): String = when (this) {
    CategoryDeletionBlocker.HasChildren -> "还有子分类"
    CategoryDeletionBlocker.HasTransactions -> "还有交易记录"
    CategoryDeletionBlocker.HasBudgets -> "还绑着预算"
}

/** 保存 / 删除失败 → 中文提示 */
internal fun Throwable.toCategoryMessage(): String = when (this) {
    is CategoryValidationException -> errors.joinToString("；") { it.toMessage() }
    is CategoryDeletionException ->
        "分类已被引用，不能删除（" + blockers.joinToString("、") { it.toMessage() } + "）。请改用「停用」"
    else -> message ?: "保存失败"
}

/**
 * 「删除 / 停用」的判断依据（附.1 R3）：零引用可以删，有引用只能停用，并且要说清被什么挡住了。
 *
 * [references] 为 null = 还在数引用，这一刻「删除」按钮不给点——避免"看着能点、点了才说不行"。
 */
internal fun categoryReferenceHint(references: CategoryReferences?): String {
    if (references == null) return "正在检查引用…"
    if (references.isZeroReference) return "没有任何流水、预算或子分类：可以删除（删除不可恢复）"
    val used = buildList {
        if (references.transactionCount > 0) add("${references.transactionCount} 笔流水")
        if (references.budgetCount > 0) add("${references.budgetCount} 个预算")
        if (references.childCount > 0) add("${references.childCount} 个子分类")
    }
    return "已被使用（" + used.joinToString("、") + "），不能删除，请改用「停用」"
}

/**
 * 分类对话框标题（AA-1）：不能与页内 FAB 的「新建分类」同串；新建与编辑共用，动词留给按钮。
 */
internal const val CATEGORY_DIALOG_TITLE: String = "分类信息"

/**
 * 新建 / 编辑分类（F02），并在编辑时提供「删除」（附.1 R3）。
 *
 * @param editing null 表示新建
 * @param topLevelOptions 可选的上级分类：只列一级且未停用的（过滤在页面侧完成，对话框再按 kind 收窄）
 * @param references 该分类当前的引用明细；null = 还在数，"删除"不可点
 * @param deleting 删除进行中（与保存共用页面的 saving 闸门）
 * @param onDelete 非空表示这个分类可以走删除流程（新建时传 null，按钮不出现）
 *
 * 编辑只改名字：换父级会让既有历史流水落到另一棵树里，F02 不做跨树移动（要换就停用旧的、新建一个）。
 * 删除只用于"从未被引用过"的错误创建，必须二次确认（附.2：不可恢复）；有引用时按钮置灰并说明原因，
 * 引导用户改用「停用」——停用保留历史与统计，可恢复。
 */
@Composable
fun CategoryEditDialog(
    editing: CategoryRow?,
    topLevelOptions: List<CategoryRow>,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, kind: CategoryKind, parentId: Long?, icon: String?) -> Unit,
    onToggleArchived: (() -> Unit)?,
    references: CategoryReferences? = null,
    deleting: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember(editing) { mutableStateOf(editing?.name.orEmpty()) }
    var icon by remember(editing) { mutableStateOf(editing?.icon.orEmpty()) }
    var kind by remember(editing) { mutableStateOf(editing?.kind ?: CategoryKind.EXPENSE) }
    var parentId by remember(editing) { mutableStateOf(editing?.parentId) }
    var parentExpanded by remember { mutableStateOf(false) }
    var localError by remember(editing) { mutableStateOf<String?>(null) }
    var confirmingDelete by remember(editing) { mutableStateOf(false) }

    // 只能挂到同一棵树的一级分类下；编辑自己时不能把自己当父级
    val parentCandidates = topLevelOptions.filter { it.kind == kind && it.id != editing?.id }
    val parentLabel = parentCandidates.firstOrNull { it.id == parentId }?.name ?: "无（一级分类）"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(CATEGORY_DIALOG_TITLE) },
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
                // 图标（C-6 / F-3）：宫格点选为主，手动输入兜底
                CategoryIconPicker(icon = icon, onIconChange = { icon = it })
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
                // 引用明细（附.1 R3）：说清"能不能删、不能删是被什么挡住的"
                if (editing != null) {
                    Text(
                        text = categoryReferenceHint(references),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (references?.isZeroReference == false) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.testTag(CATEGORY_REFERENCE_HINT_TEST_TAG),
                    )
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
                enabled = !saving && !deleting,
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
                        normalizedCategoryIcon(icon),
                    )
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(
                        onClick = { confirmingDelete = true },
                        enabled = !saving && !deleting && references?.isZeroReference == true,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.testTag(CATEGORY_DELETE_TEST_TAG),
                    ) { Text(if (deleting) "删除中…" else "删除") }
                }
                if (onToggleArchived != null) {
                    TextButton(onClick = onToggleArchived, enabled = !saving && !deleting) {
                        Text(if (editing?.isArchived == true) "恢复" else "停用")
                    }
                }
                TextButton(onClick = onDismiss, enabled = !saving && !deleting) { Text("取消") }
            }
        },
    )

    // 二次确认（附.2：删除不可恢复，必须确认一次）
    if (confirmingDelete && editing != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除分类「${editing.name}」？") },
            text = { Text("删除不可恢复。这个分类没有任何流水、预算或子分类，删掉后不会再出现在选择器里。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete?.invoke()
                    },
                    enabled = !deleting,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.testTag(CATEGORY_DELETE_CONFIRM_TEST_TAG),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
        )
    }
}
