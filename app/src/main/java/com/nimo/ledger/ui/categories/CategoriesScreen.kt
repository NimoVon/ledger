package com.nimo.ledger.ui.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.MoveDirection
import com.nimo.ledger.domain.validate.CategoryReferences
import com.nimo.ledger.ui.LedgerIcons
import com.nimo.ledger.ui.components.EmptyState
import com.nimo.ledger.ui.components.EmptyTone
import com.nimo.ledger.ui.components.FAB_BOTTOM_RESERVE
import com.nimo.ledger.ui.components.LedgerListItem
import com.nimo.ledger.ui.components.LedgerListItemDivider
import com.nimo.ledger.ui.components.LedgerSectionHeader
import kotlinx.coroutines.launch

/** 「新建分类」按钮的文字：按钮文字与读屏名共用（F15 补语义，悬浮按钮的文字在无障碍树上读不出来） */
private const val NEW_CATEGORY_LABEL = "新建分类"

/** 「恢复默认分类」按钮（附.4）：顶栏动作，UI 测试按这个 tag 找它 */
internal const val RESTORE_DEFAULTS_TEST_TAG = "categories-restore-defaults"

/**
 * 分类管理（F02）：支出 / 收入两套独立分类树，一级 / 二级单层父子，上下移排序，停用不删除。
 *
 * 排版和账户管理页一致（列表 + 右下角新建按钮 + 点行开对话框），
 * 但多一个区别：排序不塞进对话框，而是行内每级各一对"上移 / 下移"按钮——
 * 排序是高频微调，每次开对话框太笨重。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    repository: LedgerRepository,
    onBack: () -> Unit,
) {
    val viewModel: CategoriesViewModel = viewModel(
        factory = CategoriesViewModel.factory(repository),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var dialogTarget by remember { mutableStateOf<CategoryDialogTarget?>(null) }
    var saving by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 删除前的引用明细（附.1 R3）：对话框一打开就数一遍，决定「删除」可不可点。
    // null = 还在数，页面这时不给点删除，避免"看着能点、点了才说不行"。
    val editingRow = (dialogTarget as? CategoryDialogTarget.Edit)?.row
    var editingReferences by remember { mutableStateOf<CategoryReferences?>(null) }
    LaunchedEffect(editingRow?.id) {
        editingReferences = editingRow?.let { row ->
            runCatching { viewModel.categoryReferences(row.id) }.getOrNull()
        }
    }

    fun openDialog(target: CategoryDialogTarget) {
        dialogTarget = target
        dialogError = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("分类管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = LedgerIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    // 恢复默认分类（附.4）：加法操作（按名称补种缺失的预置分类，不覆盖已有），
                    // 所以不做二次确认；补了 0 个也要说清楚，免得用户以为按钮坏了
                    TextButton(
                        onClick = {
                            scope.launch {
                                viewModel.restoreDefaultCategories()
                                    .onSuccess { count ->
                                        snackbarHostState.showSnackbar(
                                            if (count > 0) "已补种 $count 个默认分类" else "默认分类都在，无需补种",
                                        )
                                    }
                                    .onFailure { snackbarHostState.showSnackbar(it.toCategoryMessage()) }
                            }
                        },
                        modifier = Modifier.testTag(RESTORE_DEFAULTS_TEST_TAG),
                    ) { Text("恢复默认分类") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openDialog(CategoryDialogTarget.Create) },
                modifier = Modifier.semantics { contentDescription = NEW_CATEGORY_LABEL },
                icon = { Icon(imageVector = LedgerIcons.Add, contentDescription = null) },
                text = { Text(NEW_CATEGORY_LABEL) },
            )
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when {
            state.isLoading -> Box(contentModifier, Alignment.Center) { CircularProgressIndicator() }

            state.errorMessage != null -> EmptyState(
                title = "加载失败",
                message = state.errorMessage.orEmpty(),
                modifier = contentModifier,
                tone = EmptyTone.ERROR,
            )

            state.isEmpty -> EmptyState(
                title = "还没有分类",
                message = "预置分类应该在首次启动时写入。\n如果这里是空的，请回到设置页跑一次数据库自检。",
                modifier = contentModifier,
            )

            else -> LazyColumn(
                modifier = contentModifier,
                contentPadding = PaddingValues(bottom = FAB_BOTTOM_RESERVE),
            ) {
                state.sections.forEach { section ->
                    // 段头与统计页 / 明细页共用同一个组件（批 D · D-2）
                    item(key = "header-" + section.kind.name) {
                        LedgerSectionHeader(title = section.title)
                    }
                    itemsIndexed(section.rows, key = { _, row -> row.id }) { index, row ->
                        CategoryRowItem(
                            row = row,
                            onClick = { openDialog(CategoryDialogTarget.Edit(row)) },
                            onMove = { direction ->
                                scope.launch { viewModel.moveCategory(row.id, direction) }
                            },
                        )
                        // 末行不画分隔线
                        if (index != section.rows.lastIndex) {
                            LedgerListItemDivider(hasLeading = true)
                        }
                    }
                }
            }
        }
    }

    val target = dialogTarget
    if (target != null) {
        val editing = (target as? CategoryDialogTarget.Edit)?.row
        // 上级候选：一级且未停用的分类（含另一棵树的，对话框里再按 kind 收窄——切 kind 时列表要能立刻跟上）
        val topLevelOptions = state.sections.flatMap { it.rows }.filter { it.depth == 0 && !it.isArchived }
        CategoryEditDialog(
            editing = editing,
            topLevelOptions = topLevelOptions,
            saving = saving,
            errorMessage = dialogError,
            onDismiss = { if (!saving) dialogTarget = null },
            onSave = { name, kind, parentId, icon ->
                scope.launch {
                    saving = true
                    val result = if (editing == null) {
                        viewModel.createCategory(name, kind, parentId, icon).map { }
                    } else {
                        viewModel.renameCategory(editing.id, name, icon)
                    }
                    saving = false
                    result
                        .onSuccess { dialogTarget = null; dialogError = null }
                        .onFailure { dialogError = it.toCategoryMessage() }
                }
            },
            onToggleArchived = editing?.let { row ->
                {
                    scope.launch {
                        saving = true
                        val result = viewModel.setArchived(row, !row.isArchived)
                        saving = false
                        result
                            .onSuccess { dialogTarget = null }
                            .onFailure { dialogError = it.toCategoryMessage() }
                    }
                }
            },
            references = editingReferences.takeIf { editing != null },
            deleting = saving,
            // 删除（附.1 R3）：对话框里已经二次确认过，这里只管落库 + 反馈
            onDelete = editing?.let { row ->
                {
                    scope.launch {
                        saving = true
                        val result = viewModel.deleteCategory(row.id)
                        saving = false
                        result
                            .onSuccess {
                                dialogTarget = null
                                dialogError = null
                                snackbarHostState.showSnackbar("已删除分类「${row.name}」")
                            }
                            .onFailure { dialogError = it.toCategoryMessage() }
                    }
                }
            },
        )
    }
}

/** 对话框的两种用途：新建，或编辑某一行 */
private sealed interface CategoryDialogTarget {
    data object Create : CategoryDialogTarget
    data class Edit(val row: CategoryRow) : CategoryDialogTarget
}

/**
 * 一行分类：二级分类缩进一级。右侧一个「排序」菜单（上移 / 下移），组内已到边界时对应项置灰。
 */
@Composable
private fun CategoryRowItem(
    row: CategoryRow,
    onClick: () -> Unit,
    onMove: (MoveDirection) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    LedgerListItem(
        title = row.name,
        subtitle = if (row.isArchived) "已停用" else null,
        leadingEmoji = row.icon?.takeIf { it.isNotBlank() },
        // C-3 收敛：两个上移/下移按钮太占行宽（2.0 字号下挤压分类名）→ 行尾一个「排序」+ 小菜单
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = LedgerIcons.MoreVert,
                        contentDescription = "排序 " + row.name,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("上移") },
                        enabled = row.canMoveUp,
                        onClick = { menuOpen = false; onMove(MoveDirection.UP) },
                    )
                    DropdownMenuItem(
                        text = { Text("下移") },
                        enabled = row.canMoveDown,
                        onClick = { menuOpen = false; onMove(MoveDirection.DOWN) },
                    )
                }
            }
        },
        onClick = onClick,
        // 二级分类缩进一级：缩进放在行外层，点击涟漪跟着缩进，不会盖住父级那行
        modifier = Modifier.padding(start = if (row.depth == 0) 0.dp else 24.dp),
    )
}
