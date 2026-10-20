package com.nimo.ledger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nimo.ledger.R
import com.nimo.ledger.ui.LedgerIcons
import com.nimo.ledger.ui.components.LedgerListItem
import com.nimo.ledger.ui.components.LedgerListItemDivider
import com.nimo.ledger.ui.theme.ThemePreference

/**
 * 设置页的卡片分组（UI-UX 规范 §7.4 / ST-5）。
 *
 * 改之前整页靠 [androidx.compose.material3.HorizontalDivider] 分隔，没有视觉边界；
 * 现在每组是一张 surfaceContainerLow 卡片，标题贴在卡片上方（titleSmall / onSurfaceVariant），
 * 卡片间距 24dp。页面左右边距随之从 24dp 收到 16dp（G-10），与全站一致。
 */

/** 卡片圆角：ST-5 指定 16dp */
private val CARD_CORNER = RoundedCornerShape(16.dp)

/** 分组之间的间距（ST-5：24dp） */
internal val SETTINGS_GROUP_SPACING_DP = 24.dp

/** 分组标题与卡片上缘的间距 */
private val TITLE_TO_CARD_DP = 8.dp

/** 卡片内行内水平留白（与 [LedgerListItem] 默认值一致） */
private val ROW_HORIZONTAL_DP = 16.dp

/** 徽章与「关于」组标题之间 16dp（§5.2：插画与标题必须有 16dp 间距） */
private val BRAND_TO_TITLE_DP = 16.dp

/** 分组标题：放卡片上方，titleSmall / onSurfaceVariant（ST-5） */
@Composable
fun SettingsGroupTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 一张分组卡片：surfaceContainerLow + 16dp 圆角（ST-5） */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CARD_CORNER,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

/**
 * 主题三选一的一行（跟随系统 / 浅色 / 深色）。
 *
 * 抽出来是为了放进 [SettingsCard] 里统一内边距；整行可点，读屏器按单选项播报。
 * ST-4：每行左侧 24dp 圆形色块预览（跟随系统=半深半浅、浅色=#F5FAFF、深色=#0E1418）。
 */
@Composable
fun ThemeOptionRow(
    option: ThemePreference,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = ROW_HORIZONTAL_DP, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemePreviewSwatch(option)
        Spacer(Modifier.width(12.dp))
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** ST-4 的三个预览色块 testTag（供组件测试定位） */
internal const val THEME_PREVIEW_SYSTEM_TEST_TAG = "theme-preview-system"
internal const val THEME_PREVIEW_LIGHT_TEST_TAG = "theme-preview-light"
internal const val THEME_PREVIEW_DARK_TEST_TAG = "theme-preview-dark"

private val THEME_SWATCH_SIZE = 24.dp

/** 浅色主题的页面底色（§2.3）与深色主题的页面底色 */
private val THEME_PREVIEW_LIGHT = Color(0xFFF5FAFF)
private val THEME_PREVIEW_DARK = Color(0xFF0E1418)

/** 色块本身不朗读（radio 的选中态已说明一切），所以是纯装饰 Box */
@Composable
private fun ThemePreviewSwatch(option: ThemePreference) {
    val tag = when (option) {
        ThemePreference.SYSTEM -> THEME_PREVIEW_SYSTEM_TEST_TAG
        ThemePreference.LIGHT -> THEME_PREVIEW_LIGHT_TEST_TAG
        ThemePreference.DARK -> THEME_PREVIEW_DARK_TEST_TAG
    }
    Box(
        modifier = Modifier
            .size(THEME_SWATCH_SIZE)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .testTag(tag),
    ) {
        when (option) {
            ThemePreference.SYSTEM -> Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(THEME_PREVIEW_DARK),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(THEME_PREVIEW_LIGHT),
                )
            }

            ThemePreference.LIGHT -> Box(
                Modifier
                    .fillMaxSize()
                    .background(THEME_PREVIEW_LIGHT),
            )

            ThemePreference.DARK -> Box(
                Modifier
                    .fillMaxSize()
                    .background(THEME_PREVIEW_DARK),
            )
        }
    }
}

/**
 * 「关于」组的内容（ST-1 / ST-2），由调用方放进 [SettingsCard]。
 *
 * - 开发者自检默认折叠，标题「开发者选项（自检）」；展开后才显示自检结果，所以
 *   [selfCheckLines] 为 null 表示「还没跑」，显示「自检运行中…」；
 * - 版本行是组内末行，只留「记账本 0.1.0」（bodySmall / onSurfaceVariant），
 *   里程碑信息（M1 数据层）不进用户界面。
 *
 * 纯展示组件：展开状态与自检结果都由调用方持有，所以可以直接在 JVM 上测。
 */
@Composable
fun SettingsAboutSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    selfCheckLines: List<String>?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LedgerListItem(
            title = "开发者选项（自检）",
            subtitle = "数据库约束与索引自检",
            trailingContent = {
                Icon(
                    imageVector = LedgerIcons.ChevronRight,
                    contentDescription = if (expanded) "已展开" else "已折叠",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 90f else 0f),
                )
            },
            onClick = onToggleExpanded,
        )
        if (expanded) {
            Column(
                modifier = Modifier.padding(
                    start = ROW_HORIZONTAL_DP,
                    end = ROW_HORIZONTAL_DP,
                    bottom = 12.dp,
                ),
            ) {
                val lines = selfCheckLines
                if (lines == null) {
                    Text(text = "自检运行中…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    lines.forEach { line ->
                        Text(text = line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        LedgerListItemDivider(hasLeading = false)

    }
}

/** 组标题 + 卡片 + 组间距：把 ST-5 的排版收成一个可复用组合，避免每处手写三个间距 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsGroupTitle(title)
        Spacer(Modifier.height(TITLE_TO_CARD_DP))
        SettingsCard(content = content)
    }
}

/** 品牌徽章（P3-5）：只在「关于」组出现，读屏念「记账本」——它是品牌标识，不是装饰 */
internal const val BRAND_MARK_TEST_TAG = "settings-brand-mark"

/** 徽章尺寸：56dp 圆底 + 28dp 线条狗，比空态插画克制，不抢卡片内容 */
private val BRAND_MARK_SIZE = 56.dp
private val BRAND_MARK_IMAGE_SIZE = 84.dp

/** 桌面图标底色（取自 ic_launcher_background.xml：「前景位图背景方块均色 #79C9FC」） */
private val BRAND_MARK_BACKGROUND = androidx.compose.ui.graphics.Color(0xFF79C9FC)

/** 「关于」组：品牌徽章 + 组标题 + 卡片 + 开发者自检 + 版本行（ST-1 / ST-2 / P3-5 的完整落点） */
@Composable
fun SettingsAboutGroup(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    selfCheckLines: List<String>?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // 直接复用桌面图标资产（P3-5 改版）：前景 PNG 是自带浅蓝底的完整图标，
            // 遮罩圆按 108dp 画布里 72dp 安全区裁切 —— 画 84dp 图、圆 56dp，狗头正好占满圆圈，
            // 与系统桌面上的图标观感一致；不再手绘矢量（用户反馈线条狗不像）。
            Box(
                modifier = Modifier
                    .size(BRAND_MARK_SIZE)
                    .clip(CircleShape)
                    .background(BRAND_MARK_BACKGROUND)
                    .testTag(BRAND_MARK_TEST_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "记账本",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(BRAND_MARK_IMAGE_SIZE),
                )
            }
        }
        Spacer(Modifier.height(BRAND_TO_TITLE_DP))
        SettingsGroup(title = "关于", modifier = modifier) {
            SettingsAboutSection(
                expanded = expanded,
                onToggleExpanded = onToggleExpanded,
                selfCheckLines = selfCheckLines,
            )
        }
        Spacer(Modifier.height(8.dp))
        SettingsVersionLine()
    }
}

/** 版本行移出卡片（§7.4：版本是页脚说明，不是卡片里的一项设置） */
private const val ABOUT_VERSION_TEXT = "记账本 · 0.1.0"

@Composable
private fun SettingsVersionLine() {
    Text(
        text = ABOUT_VERSION_TEXT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_HORIZONTAL_DP, vertical = 12.dp),
    )
}
