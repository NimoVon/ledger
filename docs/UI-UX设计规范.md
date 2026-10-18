# 记账本 · UI/UX 设计规范（小狗风 R4 完整版）

> 版本：v1.0 · 面向 **D:/git/ledger** 现有实现
> 依据：应用图标（浅蓝 #79C9FC 圆底 + 白萨摩耶 + 蓝波点领巾）、开发计划 V2.2（含附.1 R1–R4）、AGENTS.md、docs/测试报告.md §7–§14
> 定位：**落地规范 + 逐屏改进清单**，不推翻产品范围、不加依赖、不改数据铁律。所有条目都能用现有 Kotlin 2.x + Compose + Material3 实现。

---

## 0. 设计前提与不变量

### 0.1 我读过什么（规范的事实底座）

| 证据 | 位置 | 对本规范的作用 |
| --- | --- | --- |
| 图标 512 PNG | docs/icon-512.png | 提取视觉语言（圆角方形 / 纯色块 / 一笔线条 / 波点点缀） |
| 前景 PNG（空态插图复用） | app/src/main/res/drawable-nodpi/ic_launcher_foreground.png | 唯一的插画资产来源，不新增二进制资产 |
| 品牌色板 | app/src/main/java/com/nimo/ledger/ui/theme/Color.kt | 浅/深两套语义色已定，本次只做**使用规则**与少量派生 |
| 圆角 token | app/src/main/java/com/nimo/ledger/ui/theme/Shape.kt（BRAND_SHAPE_CONTAINER_DP = 16/20/24） | 所有容器圆角只能落在这三档或更小细条档 |
| 主题装配 | app/src/main/java/com/nimo/ledger/ui/theme/Theme.kt | 静态品牌色，无动态取色（守门测试 LedgerThemeSourceTest） |
| 导航骨架 | app/src/main/java/com/nimo/ledger/ui/LedgerApp.kt | 三个底栏 Tab（明细/统计/设置）+ 顶层 FAB「记一笔」+ 延迟删除 Snackbar |
| 逐屏实现 | app/src/main/java/com/nimo/ledger/ui/（record/transactions/reports/budgets/categories/accounts/settings/transfer/components） | 改进清单逐条对到文件与行号 |
| 无障碍与 WCAG 实测 | docs/测试报告.md §8、§14.1 | 对比度基线 + 已知缺口（账户/分类 FAB 朗读串） |
| 未覆盖清单 | docs/测试报告.md §10 | 第 (9) 条「统计页气泡被 FAB 遮挡」升级为本次 P0 |

### 0.2 硬不变量（任何设计提案都不得违反）

1. **产品范围不动**：F01–F15 就是全部；不引入联网、账号、云同步、广告、遥测、非必要依赖。
2. **金额是 Long 整数分**：UI 只负责「分 ↔ 字符串」，全部走 com.nimo.ledger.util.Money，不允许出现 Float/Double 金额。
3. **统计口径唯一**：任何新统计数字必须来自 domain/stats/StatsCalculator.kt；页面不得自算（余额走 domain/stats/BalanceCalculator.kt）。
4. **UI 不直接访问 DAO**：一切读写经 Repository / ViewModel。
5. **列表排序**：ORDER BY occurredOn DESC, id DESC，UI 分组用 TransactionTimeline.group（与 SQL 同序）。
6. **圆角 16–24dp** 是容器档；6–8dp 细条（进度条、占比条、柱顶）留在调用点，不升级到菱形 token。
7. **WCAG AA**：文字 4.5:1、图形/图标 3:1，浅深两套都要过（现有断言在 app/src/test/…/LedgerPaletteTest）。
8. **不用实验 API**（项目既有口径）：气泡、对话框、选择器都用稳定 API 自绘，不用 ModalBottomSheet。
9. **不新增字体文件**：字体层级只映射 Material3 默认 Typography，APK 硬上限 ≤ 20MB（当前 release 9.60 MiB）。
10. **不虚构**：本文所有「实测」数字引自 docs/测试报告.md；标注「待验证」的条目尚未跑过命令。

### 0.3 当前 UI 现状速写（改进清单的靶子）

- 明细页顶部三件套：MonthSwitcher + MonthSummaryBar（三格）+ FilterBar（OutlinedButton「筛选」+ 条件文字列 + 「清空」）→ 首屏被三行工具条吃掉约 180dp。
- 统计页 5 个 SectionHeader（本月洞察 / 收支趋势 / 分类占比 / 支出排行 / 账户支出分布），段头是 surfaceVariant 实条，与明细页日段头同款——这是好资产，应保留并抽成组件。
- 空态有 13 处调用点，但 EmptyState 里插图与标题之间**没有间距**，错误态与首次空态用的是同一张 120dp 图。
- 设置页是 one-off Row 拼的：箭头用「›」字符、自检与版本号跟真实设置项混排、没有可复用列表项。
- 对话框全是 AlertDialog 直排，标题/说明/错误/按钮四段结构各写一遍；账户页与分类页各有一个自己的 FAB。
- 预算进度条自己一份实现（8dp 高 / 4dp 圆角），统计页占比条另一份（6dp 高 / 3dp 圆角）。
- 金额列靠右但列宽随内容浮动；支出红 #B3261E 与收入蓝 #0B5A8C 相对亮度接近（6.23 : 7.00），色盲用户只能靠符号区分——目前只有明细行有 +/−。
- 分类图标是 TEXT 字段里的 emoji（如「🍜」），没有统一视觉；LedgerIcons 里没有 Edit/Delete/Search/Calendar/Wallet 等功能图标，也没有「支出/收入」方向图形。

---

## 1. 视觉语言：从图标反推的五条规则

图标给出的信息量很集中，可以直接翻译成 UI 规则：

| 图标特征 | 设计规则 | 落地处 |
| --- | --- | --- |
| 圆角方形底（squircle 感） | 容器一律大圆角；列表行不是卡片，卡片才有 16dp | Shape.kt 的 LedgerShapes（medium 16 / extraLarge 24） |
| 纯色块、无描边、无渐变、无阴影堆叠 | 平面优先；层级靠 surface 容器深浅而非阴影；阴影只给浮层（气泡、FAB、对话框） | Surface color = surfaceContainer*，气泡才用 shadowElevation = 4.dp |
| 一笔线条（微笑只有一条弧） | 图标系统 = 24dp 线性描边 2.0–2.2dp、圆头圆角、无填充膨胀 | LedgerIcons.kt 既有口径，新增图标沿用 |
| 波点 = 唯一的纹理点缀 | 纹理是「稀疏点缀」不是「填充」：一屏最多出现一处点缀，用在空态/加载/成功反馈，绝不铺满背景 | 空态插图、图表空态、Snackbar 成功态 |
| 白狗 + 淡蓝 | 白（浅色）/ 冷近黑（深色）为大面积底色，淡蓝是**强调**不是背景；一屏只有一个「蓝时刻」 | 主按钮、选中 chip、环形图中心合计、FAB |

### 1.1 一屏一个蓝时刻

淡蓝 #79C9FC 的价值在于稀有。规则：

- 每个滚动屏（明细/统计/预算/账户/分类）允许 **1 处** primaryContainer 大面积使用（选中 chip 群算 1 处）。
- FAB 不用淡蓝：浅色主题 FAB 用 primary 深蓝 #0B5A8C 配白图标（4.83:1，合规），淡蓝只做容器底（primaryContainer）与图表/进度条的语义色。
- 深色主题反过来：primary 就是 #79C9FC（对 #0E1418 为 10.22:1），淡蓝在深底上自动成为那个「蓝时刻」。

### 1.2 支出红 / 收入蓝的语义护栏

实测（docs/测试报告.md §14.1）：支出红 #B3261E 与收入蓝 #0B5A8C 对浅底分别是 6.23:1 / 7.00:1，**都过 WCAG AA**；但两者相对亮度接近，红绿色盲与低视力用户难以只靠色相区分。

因此规则升级为「颜色 + 符号 + 位置」三重冗余：

- 符号：支出 -¥12.34、收入 +¥12.34（TransactionTimeline.amountText 已实现，**必须保留**，不许为了「干净」去掉）。
- 行首形状（新增，P1）：支出行图标后用 2dp 竖条 error，收入行用 primary；或直接依赖分类图标的语义（餐饮/交通天然偏支出）。
- 位置：明细页右侧金额列固定右对齐；统计页支出永远在前（洞察/环形/排行/账户分布都是支出视角），转账与余额调整走中性色 onSurfaceVariant，不参与红蓝。
- 禁止：用红/蓝做**唯一**的选中态（选中用 primaryContainer 容器底 + onPrimaryContainer 文字）。

### 1.3 对比度基线（本次复算，供评审直接引用）

| 前台对 | 浅色 | 深色 | 阈值 | 结论 |
| --- | --- | --- | --- | --- |
| onPrimaryContainer #06304E / primaryContainer #79C9FC | **7.51** | 7.27（#CBE6FF/#0B4A73） | 4.5 | 通过 |
| onPrimaryContainer / surface | 12.98 | — | 4.5 | 通过 |
| onSurfaceVariant / surfaceVariant | **6.06** | 5.67 | 4.5 | 通过 |
| error / surface | **6.23** | 10.93 | 4.5 | 通过（金额语义色） |
| error / surfaceVariant（日段头/段头上的支出合计） | **5.22** | — | 4.5 | 通过 |
| tertiary / surface（「接近上限」） | 5.64 | 11.56 | 4.5 | 通过 |
| primary / surface（收入、「还剩 ¥x」） | 7.00 | 10.22 | 4.5 | 通过 |
| outline / surface（「未设置」弱化文字） | 4.83 | 6.48 | 4.5 | **刚好通过，不得再加透明度** |
| outlineVariant / surface（网格线，装饰） | 1.46 | 2.15 | —（装饰不进断言） | 保持，不用于承载信息 |
| primary / primaryContainer（环形图与选中块图形） | 4.40 | 5.16 | 3.0 | 通过 |

> **给实现者的一句话**：把 outline 上的 alpha 降到 0.8 就会掉到 4.5 以下。弱化文字只能换 onSurfaceVariant，不能调 alpha。

---

## 2. 色彩系统（在现有色板上加「使用规则」，不改数值）

### 2.1 角色分配表（唯一口径）

| 语义 | Light | Dark | 用在哪 | 禁止用在哪 |
| --- | --- | --- | --- | --- |
| 页面底 | surface/background #F5FAFF | #0E1418 | 全屏底色 | 卡片内 |
| 卡片/列表面板 | surfaceContainerLow #EFF6FD → surfaceContainerHigh #E1EDF8 | #161E24 → #242E36 | 分组卡片、对话框体 | 段落底色（会造成「条纹感」） |
| 段头 / 选中行底 / 气泡 | surfaceVariant #DCE7F2 | #3B4A55 | SectionHeader、DayHeader、Tooltip、DonutTooltip | 大段正文底 |
| 主强调（可点、主按钮、收入） | primary #0B5A8C | #79C9FC | FAB、主按钮、收入金额、收入图标、环形图选中、OK 状态 | 大面积底 |
| 强调容器（选中态） | primaryContainer #79C9FC + onPrimaryContainer #06304E | #0B4A73 + #CBE6FF | 选中 chip、选中年月格、环形图中心 | 文字色（#79C9FC 对浅底只有 1.70:1） |
| 支出 | error #B3261E | #FFB4AB | 支出金额、超支、删除、错误文案 | 装饰 |
| 提醒（接近上限） | tertiary #8A5A00 | #F3C57B | 预算 80% 警告、「接近上限」 | 错误（错误必须是 error） |
| 中性弱化 | onSurfaceVariant #41566A | #BECEDA | 副标题、说明、单位、轴标签 | 关键数字 |
| 描边/分隔 | outline #5C7183（强）、outlineVariant #C2D3E2（弱） | #8A9BA8 / #3E4E59 | 输入框边、分隔线、图表网格线 | 分隔线不要用 outline（太重） |

### 2.2 图表分类色（现状复核 + 一个必做修复）

分类环形图与支出排行的 10 色（app/src/main/java/com/nimo/ledger/ui/reports/CategoryDonut.kt 的 DONUT_COLORS）对浅色底 surface 的对比度实测：

| 颜色 | 对浅底 | 对深底 | 判定（图形阈值 3:1） |
| --- | --- | --- | --- |
| #B4643C | 4.14 | 4.27 | 通过 |
| #D9924A | **2.45** | 7.21 | 浅色不过 |
| #E0B457 | **1.85** | 9.57 | 浅色不过 |
| #8C9E5A | **2.80** | 6.32 | 浅色不过 |
| #5F8A72 | 3.73 | 4.74 | 通过 |
| #4F7C93 | 4.31 | 4.10 | 通过 |
| #6B6FA8 | 4.48 | 3.94 | 通过 |
| #9A6BA8 | 3.97 | 4.45 | 通过 |
| #C0576E | 4.14 | 4.27 | 通过 |
| #8A8378 | 3.57 | 4.95 | 通过 |

**修复方案（推荐，零新色、不破坏深色）**：给每段扇形与每根占比条加 **2dp 的 surface 色描边/间隙**，让形状边界本身可辨——WCAG 1.4.11 明确允许用相邻对比替代色相对比。

- 环形图：每段扇形之间留 2dp 缝（画弧时把 sweepAngle 各减 2dp 对应的角度，或先画一圈 surface 色底再叠扇形）。
- 占比条：BreakdownBar 前景右端已有圆角，补左端 2dp 的 surface 色分隔。
- 已有替代通道：环形图有直接标注（DonutCenter 合计 + 点选气泡带分类名与金额），排行行有分类名文字——**颜色从来不是唯一信息通道**，这个结论要写进代码注释，避免以后有人以为「必须换色板」。

**不推荐**：为浅色单独做一套变暗的 10 色。那会让同一分类在深浅主题里颜色不同，环形图与排行条对不上号，属于用一致性换达标——不值。

### 2.3 深色主题补充规则

1. 深色下插图 PNG 是浅蓝底白狗，贴在 #0E1418 上会像一块「贴纸」。规则：**插图容器包一层 surfaceContainerHigh + 24dp 圆角或 50% 圆形裁切**，让「贴纸」变成「徽章」（见 §5.2）。
2. 深色不要用纯黑卡片：卡片一律 surfaceContainer*，靠 #161E24 vs #0E1418 的差分层级。
3. 深色下 outlineVariant 网格线只有 2.15:1；若看不清，改用 outline.copy(alpha = 0.35f)——**仅限装饰元素**，不得用于文字。

---

## 3. 字体层级（只映射，不新增字体）

### 3.1 层级表

| 用途 | M3 样式 | 字重 | 颜色 | 现存实现对应 |
| --- | --- | --- | --- | --- |
| 单笔金额（输入时） | headlineSmall | 默认（输入框自带） | onSurface | 记一笔/转账的金额框 textStyle（已有） |
| 大数字（净资产、环形图合计） | titleMedium / titleSmall | Medium/SemiBold | onSurface | AccountsScreen.NetWorthRow、DonutCenter（已有） |
| 页面内标题（「本月总预算」） | titleMedium | Medium | onSurface | BudgetsScreen（已有） |
| 分组标题（账户/外观/导入与导出） | titleMedium | Medium | onSurface | SettingsScreen（已有，保留） |
| 字段标签（「转出账户」「分类」「日期」） | titleSmall | Medium | onSurface | FieldSection（已有） |
| 列表主行（分类名、账户名） | bodyLarge | 默认 | onSurface | 各 Screen Row（已有） |
| 列表副行（账户 · 备注、类型 · 已归档） | bodySmall | 默认 | onSurfaceVariant | 各 Screen Row（已有） |
| 段头（「本月洞察」「今天」、日段头） | labelLarge | 默认 | onSurface | SectionHeader / DayHeader（已有） |
| 段头提示（「点一块看明细」） | labelSmall | 默认 | onSurfaceVariant | SectionHeader.hint（已有） |
| 辅助标注（比例、单位、轴标签） | labelSmall / labelMedium | 默认 | onSurfaceVariant | ChartYAxis、BreakdownRow（已有） |
| 底部导航标签 | labelMedium | 默认 | M3 自动 | NavigationBarItem（已有） |
| 版本号等脚注 | bodySmall | 默认 | onSurfaceVariant | SettingsScreen 尾行（已有，位置要挪，见 ST-2） |

**规则**：新增界面元素只能从这张表里挑样式。出现「这个数字该用多大字号」的争议时，答案永远是表中已有的一档——这是防止视觉碎片化最省事的办法。

### 3.2 金额排版

1. **一律走 Money.formatCurrency**（两位小数 + ¥ + 千分位），轴标签才走 Money.formatAxisLabel（万元压缩规则见 docs/测试报告.md §12）。
2. 金额列**右对齐 + 固定最小宽**（建议 96dp），保证同屏金额右边缘成一条竖线；当前浮动列宽会让 ¥9.90 与 ¥1,234.50 的左边缘参差。
3. 千分位与小数点用默认字体比例数字即可；**不引入 tabular figures / 等宽字体**（新字体文件会增加 APK，且中文混排收益低）。等宽对齐靠「右对齐 + 分行」实现。
4. 负号永远用 ASCII -，不用 −（U+2212）——Money 与现有测试都按 - 断言。

### 3.3 字号缩放（F15）与 200% 文本

- 已有：MonthSummaryBar 在 fontScale ≥ 1.5 时三格改竖排（STACK_FONT_SCALE = 1.5f）。这是正确模式，**其他横向拥挤区要照抄**。
- 需要加固的三处：FilterBar（chips 列 + 「清空」）、DayHeader（日期 + 支出 + 收入）、BudgetProgressBar 下行（「已用 ¥x / ¥y」+ 状态标签）。规则：fontScale ≥ 1.5f 时上述三处一律改 Column 竖排，间距 4–6dp。**✅ 2026-10-17 已全部完成（F-2）**：门槛收到 `ui/components/FontScale.kt`（`STACK_FONT_SCALE` / `stackAtLargeFont`），三处各读 `LocalDensity.current.fontScale`；真机 1.5 / 2.0 走查数字见 `docs/测试报告.md` §25.5。顺带修掉 E-1 遗留的「1.5 倍下 chips 被 `LazyRow` 从字形中间裁断」。
- 长数字在 200% 缩放下：DonutCenter 已有 maxLines=1 + Ellipsis（会截断金额）。改进：fontScale ≥ 1.5 时改用 titleSmall 降级 + 允许 2 行，宁可换行不要 Ellipsis 吞掉金额。

---

## 4. 间距 · 形状 · 层级

### 4.1 4dp 网格（本项目取值，不要再发明）

| 名字 | 值 | 用途 |
| --- | --- | --- |
| 微 | 2dp | 图形描边、柱子间隙、占比条端点 |
| XS | 4dp | 图标与文字、chip 之间竖距 |
| S | 6dp | 段内小分行（备注/字符计数） |
| M | 8dp | chip 横距、卡片内小分行 |
| L | 12dp | 列表行竖 padding、对话框段间 |
| XL | 16dp | **屏幕左右恒定边距**、卡片内边距、表单字段间距 |
| 2XL | 24dp | 设置页分组之间、对话框内边距 |
| 3XL | 32dp | 空态上下留白 |
| FAB 预留 | 88dp | FAB_BOTTOM_RESERVE（已有常量，所有可滚动列表统一用） |

**屏幕左右边距永远是 16dp**（SettingsScreen 用 24dp 属例外——它没有全宽分隔行；若改成卡片分组，卡片外边距要回到 16dp）。

### 4.2 圆角使用矩阵（对齐 Shape.kt）

| 元素 | 圆角 | 依据 |
| --- | --- | --- |
| 卡片 / 列表分组容器 / 气泡 / 对话框 | 16dp（shapes.medium） | Shape.kt 已定：Card 走 medium |
| 底部弹层 / 大对话框 | 24dp（shapes.extraLarge） | Shape.kt 已定 |
| chip / 输入框 / 年月格 / 小按钮 | 12dp（shapes.small） | Shape.kt 已定 |
| 圆形元素（图例圆点、插图徽章、复选框） | 50% | 视觉 |
| 进度条 / 占比条 | 4dp（预算）/ 3dp（占比） | Shape.kt 注释：细条留在调用点 |
| 柱顶 | 2dp topStart/topEnd | TrendChart 已有 |
| 日段头 / SectionHeader 实条 | 0dp（通栏） | 通栏色条是「分隔」不是「容器」 |

**新增组件一律禁止自定义 dp 圆角**：只能用 MaterialTheme.shapes.* 或本文表格里的细条值。这是防止 14dp、18dp 这类「眼估圆角」混进来的唯一有效手段。

### 4.3 层级（elevation）规则

| 层 | 做法 | 例 |
| --- | --- | --- |
| 底 | 无阴影 | 页面、列表、分组卡片 |
| 浮 1 | shadowElevation = 4.dp | DayTooltip、DonutTooltip（已有） |
| 浮 2 | M3 默认（AlertDialog、FAB） | 对话框、ExtendedFAB |

**禁止**：用 Modifier.shadow 做卡片投影（性能 + 与平面风格冲突）；卡片层级靠 surface 容器色差实现。

---

## 5. 图标与插画系统

### 5.1 图标（LedgerIcons 扩展）

现状：app/src/main/java/com/nimo/ledger/ui/LedgerIcons.kt 有 List / Chart / Sliders / Back / ChevronLeft / ChevronRight / Filter / Add / Transfer / ArrowUp / ArrowDown / Scale（2.0–2.2dp 描边、Round cap/join、24dp viewport）。

**必须补齐的功能图标**：

| 图标 | 用在哪 | 语义 |
| --- | --- | --- |
| Edit（铅笔） | 账户行、分类行、预算行右侧「编辑」入口 | 替换「点整行=编辑」的隐式交互 |
| Delete（垃圾桶） | 编辑对话框、删除确认 | 与 error 色搭配 |
| Check | 主按钮、成功反馈、选中态备选 | 状态确认 |
| Close | 对话框右上、清除条件 | 关闭 |
| Calendar | 记一笔/转账的日期字段 | 日期可点 |
| Search | 筛选面板关键词、明细页搜索 | 检索 |
| Wallet（钱包） | 账户类型：现金/储蓄卡 | 账户语义 |
| Card（卡） | 账户类型：信用卡 | 账户语义 |
| TrendUp / TrendDown | 环比 ↑↓ | **替换 ReportFormatting.change 里的 ↑↓ 字符**（字符基线在字体间不一致） |
| Alert（三角叹号） | 超支、错误态 | 警示 |
| Info | 口径说明（「转账不算收支」） | 说明 |
| Download / Upload | 导出 / 导入 | 备份区 |
| Lock | 隐私/加密说明（F13 条件项） | 安全 |

**新增图标的画法约束（照抄现有口径）**：24dp viewport；描边类 stroke 2.0f；StrokeCap.Round + StrokeJoin.Round；不画细节（一条弧能表达就不画两条）；只用 ImageVector 手绘，不引 material-icons-core（计划 4.2 规则 2）。

**分类图标策略（不改数据库）**：category.icon 是 TEXT 字段（计划 145 行：「emoji 或图标 key」）。
- **保留 emoji**：用户已建分类的 emoji 不清洗、不改写（改数据是 AGENTS.md 第 6 条禁区）。
- **补一个「预设 emoji 选择器」**（P1）：CategoryEditDialog 里把纯文本输入换成「预设网格（约 40 个常用分类 emoji，按支出/收入分两组）+ 手动输入」两用。纯 UI，不动 schema。
- **可选（P2）**：新建分类默认从预设里取，避免「🍜」与「面」两种风格混排。

### 5.2 小狗插画风格指南（只用现有 PNG）

资产：app/src/main/res/drawable-nodpi/ic_launcher_foreground.png（白狗头像，透明背景，浅蓝领巾）。**不新增、不重绘、不改文件名**（改名会让既有测试与引用断裂）。

| 场景 | 尺寸 | 容器处理 | contentDescription | 备注 |
| --- | --- | --- | --- | --- |
| 空态（首次使用/无数据） | 120dp | 圆形裁切 + 浅色底 primaryContainer.copy(alpha = 0.35f)（深色 surfaceContainerHigh） | **null**（装饰性，R4 已定） | 现有 120dp 尺寸保留 |
| 空态（筛选无结果/无权限） | 96dp | 同上但底更淡（surfaceContainerHigh） | null | 与「首次空态」区分：这是「有数据但没结果」，不该同等视觉重量 |
| 错误态 | 96dp | 圆形裁切，底 errorContainer | null | 目前错误态用同一张图，靠文案区分；换底后一眼可辨 |
| 表单空态（「还没有账户」） | 96dp | 同空态 | null | 引导去设置的场景 |
| 关于页/首次引导（若有） | 160dp | 圆形徽章 | 「记账本」（**这里要有描述**，它是品牌标识不是装饰） | 仅在确实要做引导页时使用，不新增页面 |

**硬规则**：
- 插画**只出现在空态与错误态**，不进入列表行、不做头像、不做按钮底图（一处点缀原则）。
- 深色主题必须换底（见 §2.3），否则白狗贴在近黑底上像贴纸。
- 插画与标题之间**必须有 16dp 间距**（当前 EmptyState 缺这段，见 G-3）。
- 插画不允许叠在任何金额/文字之上，不与 FAB 同区（避免遮挡）。

**可选动作（P3，需用户拍板）**：新增 drawable/brand_dog_mark.xml 作为关于页/设置页顶部的品牌标识（按图标几何用 VectorDrawable 重画线条版）。属新增资产 + 设计判断，本规范不默认执行。

---

## 6. 组件规格（可直接落地成 Composable）

### 6.1 LedgerListItem（新增，统一列表行）★ P0

**问题**：设置页/账户页/分类页/预算页各写一份 Row，右侧箭头靠「›」字符，点击目标高度不一（4dp–14dp 竖 padding 混用）。

```kotlin
@Composable
fun LedgerListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingEmoji: String? = null,                       // 分类图标
    leadingIcon: (@Composable () -> Unit)? = null,       // 功能图标
    trailingText: String? = null,
    trailingTextColor: Color = LocalContentColor.current,
    trailingContent: (@Composable () -> Unit)? = null,   // 例如校准按钮
    chevron: Boolean = false,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),  // 批 D 实装新增：给「整页已有页边距」的页面关掉行内横留白
)
```

> 实装偏差（2026-10-17，见 `docs/测试报告.md` §17）：实际比本签名单多一个末尾 `contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp)`。设置页整页已有 24dp 页边距，复用时传 `PaddingValues(horizontal = 0.dp)`，否则 24 + 16 会双重缩进。

| 属性 | 值 |
| --- | --- |
| 最小高度 | 56dp（单行）/ 72dp（双行）——≥ 44dp 触控目标（F15） |
| 竖 padding | 12dp（双行）/ 14dp（单行，与现有账户行一致） |
| 横 padding | 16dp |
| leading 区 | 固定 40dp 宽：emoji 20sp 或 24dp 图标，垂直居中 |
| title | bodyLarge / onSurface（danger = true 时 error） |
| subtitle | bodySmall / onSurfaceVariant，与 title 间距 2dp |
| trailingText | bodyLarge / Medium，右对齐（金额用 Money.formatCurrency） |
| chevron | LedgerIcons.ChevronRight 20dp，tint onSurfaceVariant，**取代「›」字符** |
| 点击涟漪 | 通栏（fillMaxWidth + clickable），不内缩 |
| 分隔线 | HorizontalDivider，start = 16.dp（有 leading 时 start = 56.dp，与文字左边缘对齐）；**末行不画** |
| 禁用态 | alpha 0.38（仅装饰）+ contentDescription 说明原因 |

**为什么末行不画分隔线**：图标风格是纯色块无描边，列表末尾多一条线会与容器圆角「打架」。实现：itemsIndexed 里 if (index != lastIndex) HorizontalDivider(...)。

### 6.2 LedgerSectionHeader（抽取既有实现）★ P0

**现状**：ReportsScreen.SectionHeader 与 TransactionsScreen.DayHeader 是同一款（Surface surfaceVariant + labelLarge + 右侧可选小字），但分居两个文件各写一份。

```kotlin
@Composable
fun LedgerSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,                        // 右侧小字，labelSmall / onSurfaceVariant
    trailing: (@Composable () -> Unit)? = null,  // 例如「支出 ¥x · 收入 ¥y」
)
```

| 属性 | 值 |
| --- | --- |
| 容器 | Surface(color = surfaceVariant)，**通栏、无圆角** |
| 内边距 | horizontal 16dp，vertical 6dp |
| 标题 | labelLarge / onSurface |
| hint | labelSmall / onSurfaceVariant，与标题间距 8dp，右对齐 |
| trailing | 同 hint 样式，用于金额汇总 |

**注意**：不要把段头做成圆角卡片——通栏色条是它与卡片区分开的唯一特征。

### 6.3 LedgerAmount（金额列规格）★ P1 · 已实现 2026-10-18（证据见 `docs/测试报告.md` §32）

```kotlin
@Composable
fun LedgerAmount(
    cents: Long,
    type: TransactionType?,     // null = 中性（净资产、预算额）
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    showSign: Boolean = false,  // 明细行 true；列表汇总 false
)
```

| 类型 | 颜色 | 符号 | 说明 |
| --- | --- | --- | --- |
| EXPENSE | colorScheme.error | - | 支出 |
| INCOME | colorScheme.primary | + | 收入 |
| TRANSFER / BALANCE_ADJUST | onSurfaceVariant | 无 | 中性，不抢眼（现有口径，保留） |
| null（汇总/余额） | onSurface | 无 | 净资产、预算额 |

**约束**：金额列 minWidth = 96.dp（保证右边缘成线）；maxLines = 1；**不换行**（超长金额在 200% 缩放下允许 titleSmall 降级）。

### 6.4 LedgerProgressBar（统一细条）★ P0

**问题**：预算进度条（8dp/4dp 圆角，BudgetsScreen）与占比条（6dp/3dp，ReportsScreen.BreakdownBar）两份实现；预算条没有超支分段。

```kotlin
@Composable
fun LedgerProgressBar(
    fraction: Float,                 // 0f..1f+（可 >1，表示超支）
    modifier: Modifier = Modifier,
    color: Color,                    // 由状态决定
    height: Dp = 8.dp,
    shape: Shape = RoundedCornerShape(4.dp),
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    overMarker: Boolean = false,     // 是否在 100% 处画超支刻度
)
```

| 状态 | 颜色 | 文案（调用方提供） |
| --- | --- | --- |
| NONE | outline | 「未设置」 |
| OK | primary | 「还剩 ¥x」 |
| WARNING（≥80%） | tertiary | 「已用 N%，接近上限」 |
| OVER | error | 「已超支 ¥x」 |

**超支可视化（P1）**：fraction > 1 时，轨道在 100% 处保留 2dp 的 surface 色刻度（overMarker），超出的部分继续用 error 填充但**压到 60% 高度**——一眼看出「越过刻度线的部分」。比单纯「全红到底」更能表达「超了多少」。

**无障碍**：进度条本体 contentDescription = null；信息由同行文字承载（避免 TalkBack 读一串百分比）。

**实装（2026-10-17，D-3）**：`ui/components/LedgerProgressBar.kt` 按本签名落地；另做了两件规范没写但必须做的：①`fraction` 为 `NaN` / 无穷时按 0 处理（`fillMaxWidth(NaN)` 会让 Compose 直接抛异常）；②不要给填充条加 `clearAndSetSemantics { }`——它会连调用方与本组件的 `testTag` 一起清掉（超支刻度用 `LEDGER_PROGRESS_OVER_MARKER_TEST_TAG`），而 `Box` 本身没有可聚焦语义，不加也安静。

### 6.5 LedgerDialog（对话框统一结构）★ P1

**现状**：AlertDialog 直排，标题/说明/字段/错误/按钮五段各写一遍（AccountEditDialog、CategoryEditDialog、BudgetEditDialog、CategoryCreateDialog、BalanceAdjustDialog、YearMonthPickerDialog、CsvImportConfirmDialog、删除确认）。

| 段 | 规格 |
| --- | --- |
| title | titleLarge（M3 默认），单行省略 |
| 说明 | bodySmall / onSurfaceVariant，紧跟标题（间距 4dp），**不放进 text 区第一段**（否则被滚动区吞掉） |
| 内容 | Column(verticalArrangement = spacedBy(12.dp))；字段用 OutlinedTextField（圆角走 shapes.small） |
| 错误 | bodySmall / error，**在内容之后、按钮之前**，间距 8dp；一次只显示一条（多条用「；」连接，现有口径） |
| 主按钮 | confirmButton = TextButton(「保存」/「确定」)，文案随 saving 变「保存中…」并 disabled（现有口径保留） |
| 取消 | dismissButton = TextButton(「取消」) |
| 破坏性操作 | 单独一行文字按钮（「删除」/「归档」），color = error，放在内容下方或 dismiss 左侧（沿用现有做法） |
| 二次确认 | 删除一律 AlertDialog 二次确认，标题「删除这笔记录？」/「删除分类「X」？」，正文说明后果 + 撤销窗口 |
| 宽度 | 默认（M3 max 560dp）；筛选用 heightIn(max = 420.dp) + verticalScroll（**先限高再滚动，顺序不可反**——TransactionFilterDialog 注释已记录这个真机缺陷，新对话框照抄） |
| 语义 | 对话框标题必须是**唯一**可见文本，FAB 的 contentDescription 不能与标题同串（见 AA-1） |

### 6.6 LedgerSnackbar（反馈条）

- 只用于**非阻塞结果**：延迟删除撤销（已有）、恢复默认分类结果（已有）、导入导出结果。
- 撤销型：actionLabel = 「撤销」，duration = SnackbarDuration.Indefinite（现有口径，保留）。
- 成功型：duration = Short（约 4s），文案「已删除分类「X」」这类可达性友好句子（现在就有）。
- 失败型：文案带**下一步动作**（「导出失败：目标文件夹不可写，换一个文件夹再试」），优先于「导出失败」这类无信息文案。
- 位置：贴底栏上方（Scaffold snackbarHost 已处理）；**Snackbar 不得盖住 FAB**——Scaffold 默认会把 FAB 上移，无需额外处理，但自定义 Snackbar 时要显式留 FAB_BOTTOM_RESERVE。
- 无障碍：Snackbar 文字是 live region（M3 默认），不要在文字里塞 emoji 或符号。

### 6.7 EmptyState（改造，既有组件）★ P0

现状签名（app/src/main/java/com/nimo/ledger/ui/components/EmptyState.kt）：

```kotlin
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    illustration: Boolean = true,
)
```

**改造规格（向后兼容，只加可选参数）**：

```kotlin
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    illustration: Boolean = true,
    tone: EmptyTone = EmptyTone.NEUTRAL,     // NEUTRAL / FILTERED / ERROR
    actionLabel: String? = null,             // 例如「去新建账户」
    onAction: (() -> Unit)? = null,
)

enum class EmptyTone { NEUTRAL, FILTERED, ERROR }
```

| tone | 插图尺寸 | 插图底 | 用途 |
| --- | --- | --- | --- |
| NEUTRAL | 120dp | primaryContainer.copy(alpha = 0.35f)（深色 surfaceContainerHigh） | 首次使用、无数据 |
| FILTERED | 96dp | surfaceContainerHigh | 筛选无结果 |
| ERROR | 96dp | errorContainer | 加载失败 |

**布局规格（修正当前缺陷）**：

```kotlin
Column(
    modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    if (illustration) {
        Box(Modifier.size(size).clip(CircleShape).background(illustrationBg)) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null)
        }
        Spacer(Modifier.height(16.dp))      // 当前缺失的间距
    }
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))            // 当前是 12dp 的 padding-top，统一成 8dp
    Text(message, style = MaterialTheme.typography.bodyMedium, color = onSurfaceVariant, textAlign = TextAlign.Center)
    if (actionLabel != null && onAction != null) {
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(actionLabel) }   // 主按钮 = primary 深蓝底白字
    }
}
```

**约束**：
- 插图 contentDescription = null（装饰，R4 已定），**但必须保留 testTag empty-state-illustration**（既有测试锚点，不许改）。
- 空态不铺满整屏时要给 heightIn(min = 180.dp)（ReportsScreen 已有此做法，保留）。
- 空态文案的「下一步」必须可执行；涉及开发功能的文案要挪出用户可见主路径（见 ST-1 / §9 第 10 行）。

**实装（2026-10-17，D-4）**：`EmptyState.kt` 已按本签名改造（`EmptyTone` 三档、120dp / 96dp 两档插图、16dp + 8dp 间距、`actionLabel + onAction` 才渲染按钮）；7 处「加载失败」挂 `ERROR`、明细页「没有符合条件的记录」挂 `FILTERED`，其余保持默认 `NEUTRAL`。`EMPTY_STATE_ILLUSTRATION_TEST_TAG` 与插图 `contentDescription = null` 原样保留。

### 6.8 MonthSwitcher 与 MonthSummaryBar（保留，微调）

| 组件 | 保留 | 微调 |
| --- | --- | --- |
| MonthSwitcher | 三段结构（‹ 月份 ›）、标签可点开年月选择器、IconButton 48dp | 标签右侧放一个 16dp 的 Calendar 图标（比文字提示更省空间），暗示「可点」 |
| MonthSummaryBar | 三格（支出/收入/结余）、loading 显示「—」、fontScale ≥ 1.5 竖排 | 三格数字加 minWidth，防止切换月份时数字长度变化导致整条抖动 |

**MonthSummaryBar 骨架改进（P1）**：loading 时现在是「—」，改成一格 12dp 高的圆角灰块（surfaceContainerHighest），加载完成后淡入 150ms。理由：三格数值宽度会变，骨架块避免「数字突然出现」导致版面跳动。

### 6.9 图表组件规格（沿用并加固）

| 组件 | 现状（文件） | 加固项 |
| --- | --- | --- |
| 逐日柱状图 | app/src/main/java/com/nimo/ledger/ui/reports/TrendChart.kt（160dp 高、可点格子、刻意绕过 Canvas 保无障碍） | ① 柱子间隙 1dp 保持；② 选中格底色 primary.copy(alpha=0.10f) → 0.14f（深色下 0.10 几乎看不见）→ **✅ 2026-10-17 已做**（`SELECTED_DAY_HIGHLIGHT_ALPHA = 0.14f` + `dayCellHighlight(primary)`，真机像素 (32,45,56) vs 邻居 (15,20,24)，见 `docs/测试报告.md` §24）；③ 柱顶 2dp 圆角保留 |
| 月度折线图 | 同文件 TrendCanvas | 折线 1.5dp + 圆点保留；**最后一个点用实心 4dp 圆点**强调「当前月」 |
| 分类环形图 | app/src/main/java/com/nimo/ledger/ui/reports/CategoryDonut.kt（180dp / 环宽 26dp / 起点 -90°） | ① 扇形间 2dp surface 缝（见 §2.2）；② 选中态「压暗未选中 + 选中描边 +6dp」保留；③ 洞内合计在 fontScale ≥ 1.5 时降级换行，不要 Ellipsis |
| 气泡（DayTooltip / DonutTooltip） | TrendChart.kt / CategoryDonut.kt（172dp / 通栏、16dp 圆角、浮 4dp） | **必须避开 FAB**（S-1）→ **✅ 2026-10-17 已做**：`Modifier.clearOfFloatingActionButton()`（`ui/reports/TooltipFabClearance.kt`）把「气泡 + 88dp」整体 `bringIntoView`，「查看明细」改成行尾 18dp 箭头，见 `docs/测试报告.md` §24 |
| y 轴 | ChartYAxis（52dp 宽、自绘 Layout 保中线对齐） | 保留；刻度文字与网格线中线对齐是硬要求，不许改成 Column |

---

## 7. 信息架构与导航

### 7.1 现状（不动）

```text
LedgerApp（app/src/main/java/com/nimo/ledger/ui/LedgerApp.kt）
├─ 底部导航 3 Tab：明细 transactions / 统计 reports / 设置 settings
├─ 顶层 FAB「记一笔」→ record（仅三个 Tab 显示）
└─ 二级页（自绘 TopAppBar + 返回）：
   ├─ 设置 → 账户管理 accounts / 分类管理 categories / 预算管理 budgets
   ├─ 明细行 → record?txId= 或 transfer?txId=（编辑既有流水）
   └─ 统计下钻 → transactions/drill（压在统计页之上的一层，系统返回回统计页；筛选条件经 DrillDownRequests 单次脉冲传入）
```

**结论：IA 结构是对的，不需要重排。** 三个 Tab 覆盖「看 / 分析 / 配」，FAB 覆盖「记」，二级页覆盖「管」——与开发计划 F01–F15 一一对应，且「冷启动到保存一笔 ≤ 3 次点击 + 一次金额输入」已由 FAB → record → 保存满足。

### 7.2 三条核心路径（设计要保护的最短路径）

| 路径 | 现有步数 | 设计目标 | 涉及改进项 |
| --- | --- | --- | --- |
| 记一笔（冷启动 → 保存） | 点 FAB → 输金额 → 点保存 = 3 次点击 + 1 次输入 | **不加任何步骤** | R-2、R-4 |
| 看本月花在哪（启动 → 分类明细） | 统计 Tab → 点环形图一块 → 点气泡 = 3 次点击 | 保持 3 次；**必须修好气泡被 FAB 遮挡**（否则实际点不到） | S-1 |
| 改一笔错账（明细 → 改完保存） | 明细 Tab → 点行 → 改金额 → 保存 = 4 次点击 | 保持；行点击命中整行（不缩小热区） | T-3 |

### 7.3 IA 层面的 3 个建议（都不是新增页面）

1. **账户页/分类页的 FAB 与宿主 FAB 撞名（P0）**：宿主 LedgerApp 的 FAB 是「记一笔」，AccountsScreen 与 CategoriesScreen 各自的 ExtendedFAB 是「新建账户 / 新建分类」。语义不同却同款视觉，且 docs/测试报告.md §8 已记录「朗读与对话框标题同串、无法分离」。方案见 A-1 / C-1 / AA-1。
2. ~~**统计页下钻返回要保留滚动位置（P1）**~~：点环形图/排行行 → 明细 → 返回，统计页应停在原位置而非回到顶部。**2026-10-17 已实现并真机验证**：下钻改为压在统计页之上的 `transactions/drill` 目的地（不再 `popUpTo`），返回时统计页的 `LazyListState` 由 Compose 保留，真机走查从「支出排行」位置进入、返回后仍停在排行榜而不是回到顶部（见 `docs/测试报告.md` §19.4 第 2 项）。
3. **设置页三行内联入口 → 子页卡片（P1）**：账户管理/分类管理/预算管理现在是一行文字加箭头字符。改成 LedgerListItem（chevron = true），右侧补一行副标题（账户数 / 分类数 / 本月是否已设总预算），让用户进子页前就看见状态。**是否需要计数取决于 repository 有无现成 count 查询，标待确认，不默认实施。**

### 7.4 转场（2026-10-17 已实装，`ui/LedgerNavMotion.kt`）

| 场景 | 动效 | 参数 |
| --- | --- | --- |
| 底栏 Tab 之间（明细 ↔ 统计 ↔ 设置） | `FADE` | 进入 220 ms / 退出 180 ms |
| 层级进入（Tab / 设置页 → 二级页，含统计下钻） | `SLIDE_HORIZONTAL`（从右进） | 位移 1/4 屏 |
| 层级返回（二级页 → 上一级） | `SLIDE_HORIZONTAL`（反向） | 位移 1/4 屏 |
| 表单打开（任何页 → 记一笔 / 转账） | `SLIDE_VERTICAL`（自下而上） | 位移 1/3 屏 |
| 表单返回（含从下钻页打开编辑页再返回） | `SLIDE_VERTICAL`（下落） | 位移 1/3 屏 |

判据只有一处：`navMotionOf(initialRoute, targetRoute, isPop)`——pop 时先看 `initialRoute` 是否是表单（是就 `SLIDE_VERTICAL`，不因为下层是二级页而改成横向），其余按 `PageKind { TAB, DETAIL, FORM }` 决定。缓动统一 `FastOutSlowInEasing`，**不要**在调用点另写 `tween` 时长。已知缺口：未接 Android 14 预测式返回、无拖拽返回手势。系统「减少动画」（AA-7 / 批 F 的 F-4）**已于 2026-10-17 接上**：`rememberAnimationsEnabled()` 读 `animator_duration_scale`，0 时四个转场返回 `None`（证据 `docs/测试报告.md` §26，含正/负对照）。

**Tab 切换语义（2026-10-17 修，`NavHostController.switchTab`）**：①下钻页是压在统计页之上的**一次性**结果，切到任何 Tab 前先把它丢掉（不跟 Tab 状态一起存）；②目标若是起始 Tab「明细」，用 `popBackStack(起始页, inclusive = false, saveState = true)` 而不是 `navigate + restoreState`——后者会把压在起始页之上的那段（统计页）当成起始页自己的历史整段恢复回来，表现就是「在下钻页点统计没反应」「在统计页点明细没反应」。唯一入口 `ui/LedgerRoutes.kt` 的 `NavHostController.switchTab`，底栏不再内联导航参数；判据由 `ui/LedgerDrillDownNavigationTest` 的 10 例钉住，见 `docs/测试报告.md` §20。

---

## 8. 逐屏改进清单

优先级定义：**P0 = 明确缺陷或无障碍违规，必须修**；**P1 = 一致性/体验收益高且低成本**；**P2 = 明显加分但有产品判断**；**P3 = 待拍板，不默认做**。

### 8.1 全局（G）

| ID | 优先级 | 问题 | 改法 | 涉及文件 |
| --- | --- | --- | --- | --- |
| G-1 ✅ **已修 2026-10-17（D-1 落地）** | P0 | 列表行 8 处各写一份，右侧箭头用「›」字符（字符基线随字体漂移） | 抽 `LedgerListItem`（§6.1），箭头改用 `LedgerIcons.ChevronRight` | app/src/main/java/com/nimo/ledger/ui/settings/SettingsScreen.kt、ui/accounts/AccountsScreen.kt、ui/categories/CategoriesScreen.kt、ui/budgets/BudgetsScreen.kt |
| G-2 ✅ **已修 2026-10-17（D-2 落地）** | P0 | `SectionHeader` 与 `DayHeader` 是同款实现两份 | 抽 `LedgerSectionHeader`（§6.2），两处改为调用它 | ui/reports/ReportsScreen.kt、ui/transactions/TransactionsScreen.kt |
| G-3 ✅ **已修 2026-10-17（D-4 落地）** | P0 | EmptyState 里插图与标题之间**没有间距**（Image 后直接 Text） | 加 `Spacer(16.dp)`；文案间距统一 8dp；新增 tone / actionLabel / onAction 可选参数 | app/src/main/java/com/nimo/ledger/ui/components/EmptyState.kt |
| G-4 ✅ **已修 2026-10-17（D-3 落地）** | P0 | 预算进度条（8dp/4dp 圆角）与占比条（6dp/3dp）两份实现 | 抽 `LedgerProgressBar`（§6.4） | ui/budgets/BudgetsScreen.kt、ui/reports/ReportsScreen.kt |
| G-5 ✅ **已修 2026-10-18**（`LedgerAmount` 96dp 最小宽 + 右对齐，明细 / 账户 / 统计三处接线，见 `docs/测试报告.md` §32；预算页与月份汇总条未迁移） | P1 | 金额列宽随内容浮动，右边缘不成线 | 金额统一走 `LedgerAmount`，minWidth 96dp | ui/transactions/TransactionsScreen.kt、ui/accounts/AccountsScreen.kt、ui/reports/ReportsScreen.kt |
| G-6 ✅ **已修 2026-10-17** | P1 | 字体缩放 ≥1.5 时只有 MonthSummaryBar（`STACK_FONT_SCALE = 1.5f`）做了竖排 | FilterBar / DayHeader / 预算进度条下行 三处补竖排分支（F-2：门槛收到 `FontScale.kt`，9 例测试 + 3 档真机走查，见 `docs/测试报告.md` §25） | ui/transactions/TransactionsScreen.kt、ui/budgets/BudgetsScreen.kt、ui/components/FontScale.kt |
| G-7 ✅ **已修 2026-10-17** | P1 | LedgerIcons 缺 13 个功能图标（见 §5.1） | 按现有口径补 ImageVector（24dp viewport / stroke 2.0f / Round cap+join） | app/src/main/java/com/nimo/ledger/ui/LedgerIcons.kt —— F-1 已补齐 13 个并加 `strokeIcon()`，其中 4 个已接界面（箭头 16dp / 日历 20dp / 钱包·卡 24dp），其余 10 个待 E-7 / F13 接线。证据见 `docs/测试报告.md` §28 |
| G-8 ✅ **已修 2026-10-17** | P1 | 环形图 3 色在浅色底低于图形阈值 3:1 | 扇形间加 2dp surface 缝 + 代码注释说明「颜色非唯一信息通道」 | ui/reports/CategoryDonut.kt |
| G-9 | P2 | 无统一加载骨架，一律居中转圈 | loading 改骨架块（MonthSummaryBar 三格、列表首屏），转圈只留表单页 | 各 Screen |
| G-10 ✅ **已修 2026-10-18（E-7）** | P2 | 屏幕左右边距：设置页 24dp、其余 16dp | 统一 16dp（设置页改卡片分组后自然统一） | ui/settings/SettingsScreen.kt；ui/settings/SettingsCards.kt |

### 8.2 明细页（T）—— app/src/main/java/com/nimo/ledger/ui/transactions/TransactionsScreen.kt

| ID | 优先级 | 问题 | 改法 |
| --- | --- | --- | --- |
| T-1 | P1 | 首屏被 MonthSwitcher + MonthSummaryBar + FilterBar 三行吃掉约 180dp | FilterBar 单行化：左「筛选」OutlinedButton，右侧 chips **横向滚动**（Row(horizontalScroll) 或 LazyRow），只占一行；「清空」跟在 chips 行尾 |
| T-2 | P1 | chips 现在是多行竖排文字列（Column 里每个 chip 一行 labelSmall） | 改成圆角 chip（AssistChip 或自绘 12dp 圆角 + surfaceContainerHigh 底），一行内横滚；无筛选条件时整条只剩「筛选」按钮 |
| T-3 | P1 | 行点击热区是整行（好），但没有编辑暗示 | 保留整行点击，**不新增行内编辑按钮**（会缩小热区）；把「点行=编辑」写进首用提示；删除能力留在编辑页「删除这一笔」 |
| T-4 | P1 | 金额右边缘参差 | 走 `LedgerAmount`（minWidth 96dp、类型色、带 +/- 符号） |
| T-5 | P2 | 找一笔要点两下（F06 只有筛选面板） | 「筛选」左侧加 IconButton（`LedgerIcons.Search`，contentDescription「搜索」），直接开筛选面板并聚焦关键词输入 |
| T-6 | P2 | 日段头只有合计，没有当日笔数 | DayHeader.trailing 补「N 笔」（labelSmall / onSurfaceVariant） |
| T-7 | P3 | 批量操作 | **不做**（超范围）；仅记录：若未来加多选，必须复用 PendingTransactionDeletion 的撤销语义 |

### 8.3 记一笔 / 转账（R）

涉及：app/src/main/java/com/nimo/ledger/ui/record/RecordScreen.kt、ui/transfer/TransferScreen.kt

| ID | 优先级 | 问题 | 改法 |
| --- | --- | --- | --- |
| R-1 | P1 | 表单各字段间距均匀 16dp，「金额」这个主角与备注视觉权重相同 | 金额框保持 headlineSmall；上方 8dp、下方 20dp，拉开段落；金额框聚焦时边框 primary（M3 默认已满足） |
| R-2 | P1 | 分类 chip 用 FlowRow，分类多时把账户/日期/备注挤出屏幕 | 分类区加 `heightIn(max = 168.dp)` + verticalScroll（约 3 行可见）；进页面时若选中项在可视区外，自动滚到可见 |
| R-3 | P2 | 每次记一笔都要重选分类与账户 | 默认选中「上次使用的分类 + 账户」（ViewModel 层记忆，不写库、不加 schema）；**是否算扩范围需产品确认**，故标 P2 |
| R-4 | P1 | 保存按钮在 TopAppBar 右上，大屏单手难够到 | 保留 TopAppBar「保存」，另在表单底部加全宽主按钮「保存」（Button，height 52dp）；两处同一动作、同一 disabled 逻辑（saving 时都显示「保存中…」）。这是「冷启动 3 次点击」的最稳保障 |
| R-5 | P1 | 「删除这一笔」与保存按钮同屏时易误触 | 与保存按钮之间加 Spacer(24.dp) + 上方 HorizontalDivider；文字用 error 色但**不加底色**，避免与主按钮抢视觉 |
| R-6 | P2 | 备注没有常用词 | 不做建议列表；只把 supportingText「N/200」在接近上限（≥180）时改 tertiary 色 |
| R-7 | P1 | 金额输入没有直观复核 | 金额框下 12dp 处加一行 bodySmall / onSurfaceVariant，显示 `Money.formatCurrency(parsedCents)`（仅解析成功时显示），减少输错位数 |
| R-8 | P1 | 转账两个账户 chip 没有余额方向提示 | 转出账户下已有「当前余额 ¥x」（保留）；转入账户下补「转入后 ¥x」预估值（纯显示，不进统计） |

### 8.4 统计页（S）—— app/src/main/java/com/nimo/ledger/ui/reports/ReportsScreen.kt

| ID | 优先级 | 问题 | 改法 |
| --- | --- | --- | --- |
| S-1 | **P0** | docs/测试报告.md §10 第 (9) 条：DayTooltip / DonutTooltip 落在屏幕下沿时被 FAB 遮挡，「查看明细」点不到 | 两条独立修法，建议都做：① 气泡出现时自动滚动，使气泡底边位于 FAB 顶边之上（内容区底边减 72dp，`FAB_BOTTOM_RESERVE = 88.dp` 已定义）；② 「查看明细」文案从右下角移到标题行左侧或改成行尾 ChevronRight 图标，避开与 FAB 的右下重叠区 —— **✅ 2026-10-17 两条都做**：① 实测气泡底边 2495 + 88dp = 内容区底边 2825、比 FAB 顶边 2555 高 16dp；② 两个气泡的标题行改为行尾 18dp ChevronRight（`contentDescription` 仍为「查看明细」）。证据 `docs/测试报告.md` §24 |
| S-2 ✅ **已完成 2026-10-18（E-4 落地）** | P1 | 5 个 SectionHeader 让页面很长，找「支出排行」要滑很久 | 段头改 `stickyHeader`（LazyColumn 原生支持）；hint 保留。实装：`ReportsScreen` 加 `@OptIn(ExperimentalFoundationApi::class)`，5 处段头（insight / trend / donut / breakdown / accounts）由 `item(key = "…-header")` 换成 `stickyHeader(key = …)`；空范围分支只有趋势段头，仍用 `item`。证据 `docs/测试报告.md` §31 |
| S-3 | P1 | 环形图 3 色浅色底不达图形阈值 | 同 G-8（2dp 缝） |
| S-4 | P1 | 段头与明细页日段头同款（好资产）但实现重复 | 同 G-2 |
| S-5 ✅ **已完成 2026-10-18（E-4 落地）** | P1 | 「本月洞察」的 5 行（日均支出、支出环比、收入环比、预算达成率两行）无视觉分组 | 用一张 surfaceContainerLow + 16dp 圆角卡片包住，行间 4dp 细分隔线（start = 16dp）。实装：`InsightSection` → `InsightCard`（`Surface` + `RoundedCornerShape(16.dp)` + `padding(horizontal = 16.dp)` + `testTag("reports-insight-card")`），新增 `InsightDivider`（上下各 4dp 空 + 1dp 线，线两端缩进 16dp），卡内行竖向内边距 10dp → 8dp；无总预算时卡片只有 3 行、不出现预算达成率。证据 `docs/测试报告.md` §31 |
| S-6 | P2 | 趋势图恒为 160dp，自然年 12 个点的折线仍然偏密 | 保留 160dp；仅当 `ReportRange.CalendarYear` 且屏宽 < 360dp 时把 `CHART_HEIGHT` 提到 180dp（**需真机验证，标 P2**） |
| S-7 | P2 | 「日均支出」没说按多少天算 | 加副标题 labelSmall / onSurfaceVariant「按本月已过 N 天算」；N **必须来自 StatsCalculator 的口径，页面不得自算** |
| S-8 | P1 | 空月份只显示空态 + 趋势（好设计，保留） | 空态文案补指向动作：「点右下角「记一笔」，这里会给出收入、支出、结余与环比。」 |

### 8.5 预算页（B）—— app/src/main/java/com/nimo/ledger/ui/budgets/BudgetsScreen.kt

| ID | 优先级 | 问题 | 改法 |
| --- | --- | --- | --- |
| B-1 | P1 | TotalBudgetCard 与 CategoryBudgetRowItem 结构几乎一致却各写一份 | 抽 `BudgetRow(name, budgetCents?, spentCents, status, depth, subtitleOverride)`；总预算 = depth 0 + 固定标题 |
| B-2 | P0 | 进度条无超支可视化：fraction > 1 只是全红到底，看不出超了多少 | `LedgerProgressBar(overMarker = true)`：100% 处画 2dp 刻度，超出部分用 60% 高度填充 |
| B-3 | P1 | 「已用 ¥x / ¥y」+ 状态标签在窄屏/大字号下会挤 | fontScale ≥1.5 或宽度不足时改两行竖排 |
| B-4 | P1 | 预算页的 MonthSwitcher 标签不可点（与明细/统计不一致） | 传 `onLabelClick` 打开 YearMonthPickerDialog（组件已通用，成本约 1 行）；**2026-10-18 已修，见 `docs/测试报告.md` §29** |
| B-5 | P1 | 分类预算行没有编辑入口暗示 | 走 G-1 行尾 chevron；**不给行内删除**（误触风险），删除留在编辑对话框 |
| B-6 | P2 | 未设预算的分类混在列表里 | 顺序保持分类树；仅在顶部加一行筛选（全部 / 已设 / 未设）——P2，需产品确认 |
| B-7 | P1 | 页脚「预算只看支出…」口径说明是好资产 | 保留；文字前加 `LedgerIcons.Info`（14dp / onSurfaceVariant） |

### 8.6 账户页（A）—— app/src/main/java/com/nimo/ledger/ui/accounts/AccountsScreen.kt

| ID | 优先级 | 问题 | 改法 |
| --- | --- | --- | --- |
| A-1 | **P0** | 页内 FAB「新建账户」与宿主 FAB「记一笔」同款，朗读易混（docs/测试报告.md §8） | ① 页内 FAB 降级为 TopAppBar action（IconButton + Add，contentDescription「新建账户」）或改 SmallFloatingActionButton；② 需确认二级页内不会同时出现两个 FAB（**待验证**） |
| A-2 | P1 | 余额校准入口是「天平」图标（`LedgerIcons.Scale`），语义不清 | 保留图标，contentDescription 用「校准余额」；确认校准对话框标题含账户名（现有「校准 X 的余额」保留） |
| A-3 | P1 | 已归档账户与在用账户混排 | 在用在上；已归档折叠成一行「已归档（N）」可展开（LedgerListItem + chevron） |
| A-4 | P1 | 净资产行没有强调 | NetWorthRow 改 surfaceContainerLow 卡片 + 16dp 圆角，金额从 titleMedium 提到 titleLarge（primary/onSurface 对比度 7.00:1 合规） |
| A-5 | P2 | 账户类型没有图标 | 现金/储蓄卡/其他 → `LedgerIcons.Wallet`；信用卡 → `LedgerIcons.Card`；微信/支付宝沿用 Wallet（**不新增品牌图标，避免商标问题**） |
| A-6 | P1 | 空态没有直达动作 | EmptyState 加 `actionLabel = 「新建账户」`（G-3 的 action 参数首个使用处） |

### 8.7 设置页（ST）—— app/src/main/java/com/nimo/ledger/ui/settings/SettingsScreen.kt

| ID | 优先级 | 问题 | 改法 |
| --- | --- | --- | --- |
| ST-1 | **P0** | 「数据库自检（开发用）」直接展示给用户，与真实设置项混排 | 移入设置页最底部「关于」组并默认折叠（LedgerListItem 展开），标题「开发者选项（自检）」；或仅在 `BuildConfig.DEBUG` 时显示。**两种都给，需产品选一** |
| ST-2 | P1 | 版本行「记账本 0.1.0 · 已完成 M1（数据层）」出现在页面中部，且含开发里程碑信息 | 移到「关于」组末行，文案改「记账本 0.1.0」（bodySmall / onSurfaceVariant）；里程碑信息不进用户界面 |
| ST-3 | P0 | 「账户/分类/预算」三行用 `Text("›")` 字符箭头 | LedgerListItem(chevron = true)（同 G-1） |
| ST-4 | P1 | 主题三选项（跟随系统/浅色/深色）无预览 | 每行左侧加 24dp 圆形小色块：跟随系统=半浅半深，浅色=#F5FAFF + outline 描边，深色=#0E1418 |
| ST-5 | P1 | 分组没有视觉边界，全靠 HorizontalDivider | 改 surfaceContainerLow 卡片 + 16dp 圆角；分组标题放卡片上方（titleSmall / onSurfaceVariant）；卡片间距 24dp |
| ST-6 | P1 | 「导入与导出」说明文字很长 | 内容保留（合规与隐私需要）；长说明放进每行 subtitle，或行尾 Info 图标点开说明对话框 |
| ST-7 | P2 | 设置页搜索 | **不做**（项太少） |

### 8.8 分类页（C）—— app/src/main/java/com/nimo/ledger/ui/categories/CategoriesScreen.kt

| ID | 优先级 | 问题 | 改法 |
| --- | --- | --- | --- |
| C-1 | **P0** | 页内 FAB「新建分类」同 A-1 | 同 A-1 |
| C-2 | P1 | TopAppBar 上的「恢复默认分类」是低频且带副作用的文字按钮，且**当前没有二次确认** | 移到 TopAppBar 溢出菜单（IconButton + DropdownMenu）或页面最底行；**必须补二次确认** |
| C-3 | P1 | 上移/下移两个 IconButton 占行宽，大字号下挤压分类名 | 收敛为行尾单个排序图标 + 小菜单（上移/下移/停用）；长按拖拽成本高，标 P2 |
| C-4 | P1 | 「已停用」分类与在用混排 | 每个分组内：在用在上，已停用折叠一行（同 A-3） |
| C-5 | P1 | 分类行不显示引用数 | 行内不显示（噪音）；保留编辑对话框里的引用提示（`CATEGORY_REFERENCE_HINT_TEST_TAG`） |
| C-6 ✅ **已修 2026-10-17** | P2 | 分类 emoji 需手输，风格不一（实测更糟：对话框根本没收集过 icon，新建分类永远只有 `•`） | CategoryEditDialog 加预设 emoji 网格（与 `DefaultCategories` 同一套 17 颗），保留手动输入 —— F-3 已做：`CategoryIconPicker` + `createCategory` / `renameCategory` 透传 `icon` + DAO `SET icon`，见 `docs/测试报告.md` §27 |
| C-7 | P1 | 二级缩进 24dp 是唯一的层级提示 | 补视觉树形：子分类左侧 2dp outlineVariant 竖线；或父分类行 subtitle 显示「N 个子分类」 |

### 8.9 对话框通用（D）

| ID | 优先级 | 问题 | 改法 |
| --- | --- | --- | --- |
| D-1 | P0 | `YearMonthPickerDialog` 的年/月格高 40dp < 44dp 触控目标（F15 违规） | PickerCell height 40dp → **44dp**（shapes.small 12dp 圆角不变）；4 列在 360dp 屏下仍成立 |
| D-2 | P1 | 删除类确认文案各写一遍 | 统一模板：标题「删除…？」+ 正文说明后果 + 若走 Snackbar 撤销则补一句可撤销 |
| D-3 | P1 | 编辑对话框没有字段级错误定位 | 错误仍显示在内容之后（§6.5），同时给出错字段 `isError = true` 并在 supportingText 显示同一条消息 |
| D-4 | P1 | 筛选对话框 420dp 限高 + 滚动（已正确，注释记录了真机缺陷） | 保留，并把「先限高再挂滚动」推广为规范（§6.5） |
| D-5 | P2 | 按钮文案「确定/取消」与「保存/取消」混用 | 统一：有持久化效果用「保存」，纯选择用「确定」，都配「取消」 |

### 8.10 无障碍与合规（AA）

| ID | 优先级 | 问题 | 改法 |
| --- | --- | --- | --- |
| AA-1 | **P0** | 账户/分类页 FAB 朗读与对话框标题同串，无法分离（docs/测试报告.md §8 已知缺口） | 唯一文本分离：FAB contentDescription = 「新建账户」/「新建分类」；对话框标题由「新建账户」改为「账户信息」/「分类信息」（新建与编辑共用同一标题，不重复动词）。**两种取一，写进实现注释防回归** |
| AA-2 | P0 | PickerCell 40dp | 同 D-1 |
| AA-3 ✅ **已修 2026-10-18** | P1 | 装饰性图标未统一 contentDescription = null | 底栏图标与环比箭头已置 null；箭头携带的方向折进 `ReportFormatting.changeSpeech`（「上升 较上月 12.5%」），见 `docs/测试报告.md` §34 |
| AA-4 | P1 | 图表语义已做（`DONUT_DESCRIPTION` / `dailyDescription` / `dayDescription` / `trendDescription`），柱状图每格是独立可点 Box | 保留；确认相邻格子朗读不会被合并成长句（mergeDescendants 只在格子内部用，**待真机确认**） |
| AA-5 ✅ **已修 2026-10-18** | P1 | 金额朗读：TalkBack 会逐字读「¥」与小数点 | `Money.spokenAmount()`（元 / 角 / 分中文口语，无 ¥、无千分位、无小数点）+ `LedgerAmount` / `MonthSummaryBar` / 明细日汇总挂 `contentDescription`；证据见 `docs/测试报告.md` §33 |
| AA-6 ✅ **已修 2026-10-18** | P1 | 表单焦点顺序应为 金额 → 分类 → 账户 → 日期 → 备注 → 保存 | `focusProperties` 已显式固定 5 个锚点（金额 → 分类首 chip → 账户首 chip → 日期 → 备注 → 保存，保存之后 `FocusRequester.Cancel` 不再走）；顺序由 `RecordFocusOrderTest` 2 例锁住，设备用键盘 TAB 走查，**真机 TalkBack 实听待做**（见 `docs/测试报告.md` §35） |
| AA-7 ✅ **已修 2026-10-17** | P1 | 未处理「减少动画」偏好 | 页面过渡/骨架淡入包 LocalMotionDurationScale，或读 Settings.Global.ANIMATOR_DURATION_SCALE；**最低要求：减少动画时不播骨架淡入** —— 已读 `animator_duration_scale`（`rememberAnimationsEnabled()`）+ 四个转场 0 时返回 `None`（F-4）；骨架淡入归 G-9，届时复用同一接点。证据见 `docs/测试报告.md` §26 |
| AA-8 ✅ **已修 2026-10-18** | P1 | 200% 文本缩放 | 逐屏断言见 §10.5 与 [`docs/测试报告.md`](docs/测试报告.md) §36：`PerScreenLargeFontUiTest` 3 例 + `YearMonthPickerGridTest` 2 例；真机 2.0 走查 8 屏（分类页标题折行属 C-3，未收敛） |

---

## 9. 空态与错误态总表（全部调用点）

现状：EmptyState 在 13 处调用点生效（docs/测试报告.md §10 第 (11) 条）。下表给出「文案 + tone + 建议动作」的统一口径。

| # | 位置 | 标题 | 文案 | tone | 建议 action | 现有问题 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 明细·本月无数据 | 本月还没有记录 | 点右下角「记一笔」，3 秒记下第一笔。 | NEUTRAL | 无（FAB 就在旁边） | 文案好，保留 |
| 2 | 明细·筛选无结果 | 没有符合条件的记录 | 试试放宽条件，或点上面的「清空」。 | FILTERED | 无 | 文案好；tone 改后视觉更轻，与「无数据」区分 |
| 3 | 明细·加载失败 | 加载失败 | 当前直接显示 errorMessage 原文 | ERROR | 「重试」 | **缺动作**；文案要带下一步 |
| 4 | 明细·当日明细对话框空 | 这一天没有流水。 | —（对话框内文字） | — | — | 对话框内纯文字即可，不用插图 |
| 5 | 统计·本月无数据 | 本月还没有记录 | 记一笔之后，这里会给出收入、支出、结余与环比。 | NEUTRAL | 无 | 补「点右下角记一笔」指向（S-8） |
| 6 | 统计·加载失败 | 加载失败 | errorMessage | ERROR | 「重试」 | 同 #3 |
| 7 | 统计·支出排行空 | — | 本月没有支出。转账与余额调整不算收支，所以不会出现在这里。 | —（HintRow） | — | 口径说明写得好，**原样保留** |
| 8 | 统计·账户分布空 | — | 本月没有支出，所以没有账户分布。 | —（HintRow） | — | 保留 |
| 9 | 预算·无支出分类 | 还没有支出分类 | 先去「设置 → 分类管理」建好支出分类，再回来给它们设预算。 | NEUTRAL | 「去分类管理」 | 文案给了路径，加按钮更好 |
| 10 | 分类·空（不应发生） | 还没有分类 | 预置分类应该在首次启动时写入。如果这里是空的，请回到设置页跑一次数据库自检。 | 建议改 ERROR | 「去设置」 | **这段文案是给开发者看的**，要改成用户语言：「分类数据异常，请从设置页重新导入备份，或重启应用。」（与 ST-1 配套） |
| 11 | 账户·空 | 还没有账户 | 先建一个账户（现金 / 储蓄卡 / 信用卡 / 微信 / 支付宝 / 其他），才能开始记账。 | NEUTRAL | 「新建账户」 | 好，加动作 |
| 12 | 记一笔·无可用账户 | 还没有可用账户 | 先到「设置 → 账户管理」新建一个账户，才能记一笔。（已归档的账户不会出现在这里） | NEUTRAL | 「去账户管理」 | 括号说明可移到 subtitle |
| 13 | 记一笔·不支持的类型 | 这一笔不能在这里改 | （说明该类型应去哪里编辑） | ERROR | 「返回」或「去转账页」 | 确认始终有返回路径 |
| 14 | 转账·账户不足 | 还不能转账 | 转账要在两个账户之间移动钱。先到「设置 → 账户管理」再建一个账户。 | NEUTRAL | 「去账户管理」 | 好 |

### 9.1 统一规则

1. 空态标题 ≤ 10 字，正文 ≤ 40 字、最多两行；超了就删减，**不缩字号**。
2. 错误态必须给「下一步」：重试 / 换路径 / 撤销，三者之一。
3. 空态与错误态用**不同插图底**（§5.2）：NEUTRAL 淡蓝、FILTERED 灰、ERROR 错误容器色。否则用户分不清「没数据」和「出错了」。
4. 开发相关文案（数据库自检、预置分类写入失败）一律不进用户可见主路径（ST-1、#10）。
5. 插图的 contentDescription 一律 null（装饰），但 testTag `empty-state-illustration` 必须保留（既有测试锚点）。

---

## 10. 无障碍与 WCAG AA 实施清单

### 10.1 对比度断言（扩到新元素）

现有断言见 docs/测试报告.md §14.1（浅色 5.64–16.60 / 深色 5.04–15.25）。本次设计引入的新组合及判定：

| 新增组合 | 判定 |
| --- | --- |
| 插图圆形底（primaryContainer.copy(alpha=0.35f)）上的插图 | 装饰，无文字 → 不进文字断言 |
| 骨架块（surfaceContainerHighest / surface） | 装饰 → 仅要求与背景可辨，不进断言 |
| 折叠行「已归档（N）」labelSmall / surfaceContainerLow | 用 onSurfaceVariant（浅 6.06 / 深 5.67）→ 通过 |
| 环形图 2dp surface 缝与相邻扇形 | 浅色下最小 1.85:1（#E0B457）→ **仍不达 3:1** |

**第 4 行是本次设计里唯一没做到 3:1 的地方，需要点名说清楚**：

- 事实：环形图浅色主题下 #E0B457 与 #D9924A 对底色分别为 1.85 与 2.45，即使加缝，缝隙自身也只有 1.85–2.45。
- 缓解（四条互补通道，没有一条依赖颜色）：① 缝隙让**形状边界**可辨（相邻扇形之间是 surface 缝，不直接接触）；② 环形图始终带 `DONUT_DESCRIPTION` 语义，TalkBack 可读完整数据；③ 点选后气泡显示分类名 + 金额 + 占比；④ 下方「支出排行」用**文字 + 百分比 + 金额**三重冗余列出同一份数据。
- 备选（若评审坚持数值达标）：仅浅色主题下把这 3 色调深一档（#D9924A→#B06A22、#E0B457→#8A6A10、#8C9E5A→#5F7038），代价是同一分类在深浅主题颜色不一致、环形图与排行条对不上号。**建议不换**，换色需用户拍板。

### 10.2 触控目标清单（≥44dp）

| 元素 | 现状 | 判定 |
| --- | --- | --- |
| NavigationBarItem | M3 默认 ≥48dp | 通过 |
| ExtendedFAB | 56dp 高 | 通过 |
| IconButton（返回/上下月/上移下移/校准） | M3 默认 48dp | 通过 |
| FilterChip | M3 默认 32dp 高 | **32 < 44 → 视觉高度可保留，点击区补** `Modifier.minimumInteractiveComponentSize()` |
| PickerCell（年月格） | 40dp | **违规** → D-1 改 44dp |
| 列表行 | 56–72dp | 通过 |
| FilterBar「清空」TextButton | M3 默认 40dp 高 | 边界 → 补 minimumInteractiveComponentSize() |
| 气泡整块可点 | 高 ≥44dp | 通过（2026-10-17 起也不再被 FAB 遮挡，见 S-1 / `docs/测试报告.md` §24） |
| 柱状图可点格子 | 31 天时单格约 11dp 宽 | **宽度不足属图表固有限制**；格子高 160dp 远超 44dp，且提供「点选后气泡下钻」的替代路径。作为已知限制记录，不阻塞发布 |

### 10.3 屏幕阅读器动线脚本（设计预期）

**动线 A：记一笔**
1. 「记一笔」（FAB）
2. 「金额，输入框，人民币」→ 输入
3. 「分类」→「餐饮，已选」
4. 「账户」→「现金，已选」
5. 「日期，按钮，2026年10月16日」
6. 「保存，按钮」

要求：FieldSection 的标题必须被读出（现在是普通 Text，顺序正确可接受）；若要真分组，加 `Modifier.semantics { heading() }`。

**动线 B：统计 → 下钻**
1. 「统计，标签页，已选」
2. 「本月支出分类占比，合计 ¥612.34：餐饮 ¥300.00（49%），交通 ¥200.00（33%），其余 2 个分类」
3. 点选后：「餐饮 ¥300.00 · 49%，查看明细，按钮」
4. 「明细，标签页」→ 列表只剩餐饮

第 2 步的长描述已由 donutDescription 实现（好资产）。第 3 步需真机确认气泡整块被朗读为「按钮」。

### 10.4 焦点与键盘

- 手机场景可忽略 Tab 顺序；外接键盘（平板 / DeX）要能走完表单：确认 OutlinedTextField 的 imeAction 依次 Next → Done（**现状未验证**）。
- 对话框打开时焦点落在第一个可输入字段（FocusRequester + LaunchedEffect），关闭后焦点回到触发元素——Compose AlertDialog 默认应能返回，**待真机验证**。

### 10.5 200% 文本缩放逐屏验证清单（F15 验收项）

| 屏 | 重点 | 预期做法 |
| --- | --- | --- |
| 明细 | 三行工具条是否溢出；金额是否与分类名重叠 | G-6 竖排；金额可降级 titleSmall |
| 统计 | SectionHeader 的 title + hint 是否挤在同一行溢出 | hint 换到第二行（Column） |
| 统计·环形图 | 洞内合计被 Ellipsis 截断 | 允许 2 行，不要 Ellipsis 吞掉金额 |
| 预算 | 进度条下行两段文字 | B-3 竖排 |
| 账户 | 账户名 + 类型 + 校准图标 + 余额四段同行 | 校准图标下移或余额换行 |
| 分类 | 分类名 + 上移 + 下移 三个 IconButton | C-3 收敛为单图标菜单 |
| 对话框 | 年月格 44dp 下 4 列是否溢出 | **已修 2026-10-18**：字号 ≥1.5 降到 3 列（`stackAtLargeFont`），格内文本放不下给省略号；见测试报告 §36 |
| 设置 | 主题行 RadioButton + 长文案 | 文案换行，不缩字号 |

### 10.5.1 逐屏走查结论（2026-10-18，AA-8）

- **危险操作二次确认**：删除一律二次确认（现有）；**恢复默认分类目前没有确认 → C-2 必须补**。
- **颜色不作唯一通道**：已在 §1.2 / §2.2 建立（符号 + 文字 + 位置）。
- **深色模式全页适配**（计划 L386 验收项）：新增组件必须两套主题都验；DONUT_COLORS 是主题无关常量，深色下 10 色全部 ≥3:1（见 §2.2 表）。
- **不引入新权限**：现有导出走 SAF，设计不新增任何权限申请。

---

## 11. 分批实施计划（建议）

### 批 D：一致性收口（低风险，纯 UI 重构，建议先做）

目标：把「同一件事写两遍」的组件收口；APK 体积不变、无行为变化、既有测试锚点不变。

| 任务 | 产出 | 验收 |
| --- | --- | --- |
| D-1 ✅ **已完成 2026-10-17** | 新增 `LedgerListItem`（§6.1），替换设置/账户/分类/预算四处行（实装：设置三行、账户行、分类行已换；**预算行与 D-3 的进度条一起改完**） | 现有 UI 测试全绿（89 套 764 例）；真机截图对比无布局回退，见 `docs/测试报告.md` §17.3 |
| D-2 ✅ **已完成 2026-10-17** | 新增 `LedgerSectionHeader`（§6.2），替换 ReportsScreen.SectionHeader（6 处）与 TransactionsScreen.DayHeader | 同上；另在分类页段头、预算页「分类预算」也换上 |
| D-3 ✅ **已完成 2026-10-17** | 新增 `LedgerProgressBar`（§6.4），替换预算进度条与 `ReportsScreen.BreakdownBar`；`NaN` 按 0 处理、不加 `clearAndSetSemantics`（见 §6.4 实装说明） | `LedgerProgressBarUiTest` 6 例（轨道高度 / 超支刻度出现与不出现 / `overMarker=false` / `NaN` / 无 contentDescription）；真机因账本预算全「未设置」没有渲染，如实写进 `docs/测试报告.md` §18.5 |
| D-4 ✅ **已完成 2026-10-17** | EmptyState 增 tone / actionLabel / onAction，并修 16dp 插图间距（§6.7）；7 处加载失败挂 ERROR、筛选无结果挂 FILTERED | `EmptyStateTest` 8 例（120 / 96dp、ERROR 仍可见标题说明、按钮槽只在 label + onAction 时渲染）；`EMPTY_STATE_ILLUSTRATION_TEST_TAG` 保留；真机 FILTERED 空态截图见 §18.3 |
| D-5 ✅ **已完成 2026-10-17** | PickerCell 40dp → 44dp（D-1 / AA-2） | `YearMonthPickerBodyUiTest` 断言月份格 `assertHeightIsAtLeast(44.dp)`；真机布局高度用行距 195px = 52dp = 44 + 8 反证，48dp 是 Compose 的最小触控目标扩展（见 §18.3 第 7 项） |
| D-6 ✅ **已完成 2026-10-17（TalkBack 未实测）** | 账户/分类 FAB 与对话框标题文案分离（A-1 / C-1 / AA-1）：标题改常量「账户信息」/「分类信息」，FAB 仍是「新建账户」/「新建分类」并保留为 `contentDescription` | `DialogTitleSourceTest` 4 例（纯 JUnit 源码守门 + 常量取值）；真机 `uiautomator dump` 核对标题与 FAB 文案，**TalkBack 实际播报未在真机听测**（见 `docs/测试报告.md` §18.5） |

> 批 D 状态（2026-10-17）：**D-1 ~ D-6 全部完成**。门禁 89 套 764 例、行覆盖 70.5%、真机走查净效果为零（D-1 / D-2 见 `docs/测试报告.md` §17，D-3 ~ D-6 见 §18）。执行顺序按 §12.2 第 2 条先补组件级测试再替换：D-1 / D-2 补了 `LedgerListItemUiTest` 9 例 + `LedgerSectionHeaderUiTest` 4 例，D-3 ~ D-6 又补 16 例（进度条 6、空态由 3 扩到 8、月选择器 44dp 1、标题源码守门 4）。批 E / 批 F 未开始。

回归命令（Windows）：

```bash
export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
cd /d/git/ledger
./gradlew.bat --console=plain testDebugUnitTest --rerun lint assembleDebug
```

### 批 E：逐屏打磨（中等风险，改布局不改数据）

| 任务 | 产出 | 验收 |
| --- | --- | --- |
| E-1 ✅ **已完成 2026-10-17** | 明细页 FilterBar 单行化 + chips 横滚（T-1 / T-2）：`FilterBar` 改单个 `Row`（左「筛选」按钮 + 中间 `LazyRow` 条件 chips + 行尾「清空」），chip 用 12dp 圆角 + `surfaceContainerHigh` 底、`labelSmall` 单行不省略，无条件时整条只剩按钮 | ~~首屏内容区增加 ≥60dp~~ **实测不成立**：旧实现 0~3 条条件 56dp、6 条 104dp，新实现 1 条与 6 条等高（≤52dp），无条件时只省约 8dp——真实收益是「条高有界、不随条件数增长」。7 例组件级测试 + 真机 9 项走查（含横滚、清空、1.5 倍字体），见 `docs/测试报告.md` §21 |
| E-2 ✅ **已完成 2026-10-17** | 记一笔表单底部主按钮「保存」+ 金额复核行（R-4 / R-7）。实装：`showSave` / `onSave` 各收敛成一份，顶栏 `TextButton`（`record-save-top`）与 `Scaffold.bottomBar` → `RecordSaveBar`（`record-save-bottom`，52dp 按钮、键盘弹出时在 IME 之上）共用；金额框下 `AmountReviewLine`（`record-amount-review`）在 `Money.parseCents(...) > 0` 时显示「核对：¥1,234.56」，12dp 间距（外层表单 16dp 间距，故用 0dp 内层 Column 收口） | 「3 次点击记账」仍是 点按钮 → 输金额 → 点保存，底栏不新增步骤；4 例组件级测试（复核行缺失 / 千分位文案 / 可点回调 / 保存中禁用）+ 真机 4 项（无键盘贴屏底 `[667,2945][773,3020]`、键盘弹出时 `[667,1799][773,1874]` 在 IME 之上、输入 `1234567` → `核对：¥1,234,567.00`、底部按钮真写入 ¥1.00 后删除还原）。**接线本身无组件级测试**（删 `bottomBar` 那 2 例仍绿），见 `docs/测试报告.md` §22.3 / §22.7 |
| E-3 ✅ **已完成 2026-10-17** | 分类 chip 区限高 + 自动滚到选中（R-2）。实装：`CategoryChips` 用 `Box(heightIn(max = CATEGORY_CHIPS_MAX_HEIGHT_DP.dp).verticalScroll(...))` 包 `FlowRow`，上限 `CATEGORY_CHIPS_MAX_HEIGHT_DP = 168`（约 3 行）；选中那颗挂 `bringIntoViewRequester`，`LaunchedEffect(selectedCategoryId)` 里 `bringIntoView()`（首次进页沿用上次分类也生效） | 4 例组件级测试（200 颗时高度**正好** 168dp、限高后有 `ScrollBy`、3 颗时 ≤ 72dp、选中项 `assertIsDisplayed`）；红绿：删限高报「分类区高度 2673 px 应正好被限在 504 px（168dp）」，删 `bringIntoView` 报「分类199 … is not displayed」；真机 fontScale 2.0 下 chip 区内滑 `700 1750 → 700 1350` 后 娱乐/医疗 从 y1730 移到 y1320，`分类` 仍 1157、`账户` 仍 `[60,2010][256,2150]` 不动。见 `docs/测试报告.md` §22 |
| E-4 ✅ **已完成 2026-10-18** | 统计页「本月洞察」卡片化 + 段头 sticky（S-5 / S-2）。实装：`ReportsScreen` 的 `InsightSection` 改名 `InsightCard`——`Surface(color = surfaceContainerLow, shape = RoundedCornerShape(16.dp))` + `padding(horizontal = 16.dp)` + `testTag(INSIGHT_CARD_TEST_TAG = "reports-insight-card")`，卡内 `Column(padding(vertical = 4.dp))`，行序 日均 → `InsightDivider` → 环比 → （有总预算时）`InsightDivider` → 预算达成率；新增 `InsightDivider`（上下各 4dp 空 + 1dp `HorizontalDivider`，线两端缩进 16dp）；卡内三行竖向内边距 10dp → 8dp；5 处段头改 `stickyHeader`（insight / trend / donut / breakdown / accounts；空范围分支的趋势段头仍是 `item`） | 3 例 Robolectric 组件测试（`ReportsInsightCardUiTest`：设总预算后 4 行都在卡内 / 无总预算时卡里没有「预算达成率」且仍是卡 / 滚到「账户支出分布」后「支出排行」段头仍 displayed）；红绿：段头改回普通 `item` → 钉住那例 `AssertionError at ReportsInsightCardUiTest.kt:149`（3 例 1 失败），删卡片 `testTag` → 卡内两例红（`:117` / `:133`）；真机（2026-10-18 20:10，release `81a098af…`，§31.5）：洞察卡（日均 ¥142.55 / 支出环比 / 收入环比，1dp 分隔线内缩）、下滑后「分类占比」段头钉在汇总条下、再滚到「支出排行 / 账户支出分布」；点「娱乐」仍能下钻「分类：娱乐」筛选页，返回键落回统计页；1.5 / 2.0 字体补充见 §31.8（段头仍钉住、卡片不截字）；浅色 / 空月份 / 横屏见 §31.9；有数据的 31 天整月见 §31.10 |
| E-5 ✅ **已完成 2026-10-17** | 修 FAB 遮挡气泡（S-1），两条修法都做：① 新增 `Modifier.clearOfFloatingActionButton()`（`ui/reports/TooltipFabClearance.kt`）——`BringIntoViewRequester` 把「气泡自身高度 + FAB_BOTTOM_RESERVE（88dp）」当成一个矩形 `bringIntoView`，`DonutTooltip` / `DayTooltip` 各挂一次，宿主 `LazyColumn` 不用改；② 两个气泡标题行右端的「查看明细」文字换成 18dp `LedgerIcons.ChevronRight`（`contentDescription` 仍是「查看明细」，语义合并后读屏照样念） | 真机实测：环形图滚到顶时气泡整块 `[60,2300][1380,2495]`，底边 2495 + 88dp（330px）= 内容区底边 2825，比 FAB 顶边 2555 高 60px（16dp），箭头 `[1267,2334][1335,2402]` 完整可见；点最后一天 10月17日 的气泡能打开日明细；点气泡仍能下钻 `分类：人情`。**411dp 这一档由 Robolectric `@Config(w411dp-h891dp-xxhdpi)` 覆盖，真机 384dp 更窄**；0.36° 的 0.1% 扇形（弧长约 1.7px）手指点不中，**没有写成已验收**。5 例测试 + 红绿见 `docs/测试报告.md` §24 |
| E-6 ✅ **已完成 2026-10-17** | 环形图 2dp 缝（批 D 已做，`CategoryDonutArcsTest` 7 例）+ 逐日柱选中格底色 0.10f → 0.14f：抽成 `SELECTED_DAY_HIGHLIGHT_ALPHA` / `dayCellHighlight(primary)`（`ui/reports/TrendChart.kt:110` / `:113`），透明度能被单测钉住 | 截图对比 + 像素采样：选中 10月14日 后同一张截图里选中格 (1140~1170, 900) = **(32,45,56)**、隔壁 13日 (1040~1080, 900) = **(15,20,24)**；0.14 混合理论值 (30,45,56)，旧 0.10 是 (26,38,47)，实测贴 0.14。见 `docs/测试报告.md` §24.5 第 7 项 |
| E-7 ✅ **已完成 2026-10-18** | 设置页卡片分组 + 开发项折叠（ST-1 / ST-2 / ST-5）。实装：新增 `ui/settings/SettingsCards.kt`——`SettingsGroupTitle`（titleSmall / onSurfaceVariant，贴卡片上方 8dp）/ `SettingsCard`（surfaceContainerLow + 16dp 圆角）/ `SettingsGroup`（标题 + 8dp + 卡片）/ `ThemeOptionRow`（整行 `selectable`，`Role.RadioButton`）/ `SettingsAboutSection`（折叠行 + 自检输出 + 分隔线 + 版本末行）/ `SettingsAboutGroup`；`SettingsScreen.kt` 重写成 账户 / 外观 / 导入与导出 / 关于 四组，页边距 24dp → 16dp（G-10 同批修掉），组间 24dp；开发者自检进「关于」卡片、默认折叠（`rememberSaveable`），展开时才 `LaunchedEffect` 跑 `selfCheck()`，结果未回时显示「自检运行中…」；ST-1 定案为**不做 DEBUG 门控**——验收要求「自检入口仍可用」，真机装的是 release 包，门控会把它藏掉（定案理由见 `docs/测试报告.md` §30.1） | 5 例组件测试（折叠默认隐藏输出 / 版本行无里程碑文案 / 点开显示再点折回 / 未跑时「自检运行中…」/ 每次点击回调一次）；红绿：展开门控写死成 `if (true)` + 版本行塞「已完成 M1（数据层）」→ 3 例红（`SettingsAboutSectionTest.kt:58` / `:67` / `:93`），还原回绿；真机（2026-10-18 17:41，release `45e6943d…`，§30.4）：四组卡片 + 16dp 边距、自检默认折叠、展开读出 `数据库 user_version: v1` / `约束触发器: 4/4 ✓` / `预算月度唯一索引: 已生效 ✓` / `账户数: 2` / `分类数: 18` / `交易数: 8` / `M0 自检通过…`，再点折回；主题三选一切深浅色再复原正常；2.0 字体补充见 §31.8（四组卡片正常、版本行页尾可见）；浅色 / 横屏见 §31.9 |
| E-8 ✅ **已完成 2026-10-18** | 预算页 onLabelClick + 超支刻度（B-4 / B-2）。实装：`BudgetsUiState` 暴露 `month`，ViewModel 新增 `setMonth(YearMonth)`（先清 `loadError` 再切 `month` 流），`moveMonth(delta)` 改为 `setMonth(month.value.plusMonths(delta))`——上下月与直选共用一条路，与明细页 `TransactionsViewModel` 同构；`BudgetsScreen` 给 `MonthSwitcher` 传 `onLabelClick` 打开通用 `YearMonthPickerDialog`（`onSelect` → `setMonth` → 关闭）；超支刻度那一半（B-2）在 D-3 已落地，本轮复核 `LedgerProgressBar(overMarker = true)` 仍在位 | 1 例 ViewModel 单测（跨年直跳 5 个月，断言 `month` / `monthLabel` / 总预算金额都换到目标月那行）+ 1 例 Robolectric 接线测试（点标签 → 选年 → 选月，断言新标签与新金额出现、旧标签与旧金额消失）；红绿：拆 `onLabelClick` → 接线那例 `AssertionError at BudgetsScreenMonthPickerTest.kt:97`（13 例 1 失败），`setMonth` 不切月 → 3 例红（含既有 `moveMonth` 的两例）。**真机走查**（2026-10-18 17:27，release `a6be58f7…`，§29.4）：点标签 → 选 2026 年 → 选 9 月整页换月（已支出 ¥2,566.00 → ¥0.00），返回键关弹窗不换月，选回 10 月数据复原，上下月箭头不受影响 |

> 批 E 状态（2026-10-18）：**E-1 ~ E-8 全部已完成（批 E 收口）**（E-1 见 `docs/测试报告.md` §21：92 套 792 例、行覆盖 70.4%、真机 9 项；E-2 / E-3 见 §22：93 套 800 例、行覆盖 70.6%、真机 10 项；E-5 / E-6 见 §24：95 套 813 例、行覆盖 70.7%、真机 10 项；E-8 见 §29；E-7 见 §30：105 套 860 例、行覆盖 73.0%、真机 7 张截图；E-4 见 §31：106 套 863 例、行覆盖 73.1%、真机 3 张截图）。E-4 的执行要点：「段头 sticky」是把 5 处 `item` 逐一换成 `stickyHeader`，漏一处就有一段会滚走；卡片要挂 `testTag` 才能断言「这行在卡里」（Compose 测试拿不到 Surface 祖先的语义）；卡内行竖向内边距要从 10dp 收到 8dp，否则卡片整体比原实现更高、「卡片化」等于没省地方。E-7 的执行要点：折叠不是把功能藏起来——真机装 release，`BuildConfig.DEBUG` 门控会直接藏掉自检入口，所以 ST-1 选「不做门控、只做折叠」；自检要等展开才跑（`LaunchedEffect(expanded)` + 结果 null 显示「自检运行中…」），否则每次进设置页都查一遍数据库；把「关于」组抽成 `SettingsAboutGroup` 才能不碰 AppContainer 做组件测试（整屏 `SettingsScreen` 需要 Room + DataStore，JVM 组件测试起不来）。E-8 的执行要点：标签可点时不要再加「选择月份」按钮，标签本身就是入口；`setMonth` 必须先清 `loadError` 再切流，否则上个月的读取错误会留在新月份；直选与上下月共用一个 `setMonth`，否则两条路会分叉（红绿实验里把 `setMonth` 改成空操作，连既有 `moveMonth` 的两例一起红了）。E-5 的执行要点：气泡避让不要动宿主的 `LazyColumn`，把「气泡 + 88dp 预留」当成一个矩形交给 `bringIntoView` 即可，宿主不需要知道 FAB 在哪；测试里用假 FAB（右下 16dp、150 × 56dp）断言「气泡底边 ≤ 假 FAB 顶边」，但红色证据只有环形图那例——`DayTooltip` 在真实布局里贴图表顶部、够不到 FAB，那例只能守「挂上修饰符不产生副作用 + 箭头在」，不能当几何回归。E-6 的执行要点：`Color.copy(alpha =)` 是 8 位量化（0.14f 存成 36/255 ≈ 0.1412），字面量断言必须放 0.002f 容差，只有「对象相等」的比法才稳。E-2 / E-3 的执行要点：先写组件级测试再改布局的思路同样适用，但要认出「组件级测试测不到接线」——`RecordSaveBar` 的 2 例在删掉 `Scaffold.bottomBar` 后仍绿，接线只能靠真机证据，已写进 §22.7；`heightIn + verticalScroll` 的限高在 Robolectric 能量到「正好 504px」，真机则要放大字体让内容真的溢出才能看出「硬裁 + 内滚且外层不动」。E-1 的执行要点：先补组件级测试（`TransactionsFilterBarUiTest` 7 例）再改布局——Robolectric 假字体让**宽度**断言失效，只能测高度与滚动，宽度靠真机截图核对；fontScale ≥1.5 的 chip 竖排仍归 F-2 处理（当前 1.5 倍下 chip 右端会被 `LazyRow` 从字形中间裁断）。

### 批 F：图标与字体缩放收口

| 任务 | 产出 | 验收 |
| --- | --- | --- |
| F-1 ✅ **图标已补齐 2026-10-17，接线部分完成** | 补 13 个 LedgerIcons（§5.1），替换「›」与「↑↓」字符。实装：13 个描边图标（`strokeIcon()`：24dp / 2.0f / Round）+ 环比改 `ChangeDirection` + `ArrowUp/Down` 图标（16dp，`contentDescription` 上升/下降）+ 记一笔 `Calendar`（20dp）+ 账户行 `Wallet`/`Card`（24dp）；「›」在 D-1 时已换 `ChevronRight`，`↑↓` 本批清零 | 图标 5 例测试（存在 / 24dp viewport / 2.0f+Round / 名字唯一 / 趋势箭头）+ 方向与「文本不含箭头」2 例 + 7 处旧断言更新；真机三档：16dp 箭头 `上升 [60,1001][120,1061]`（60px÷3.75）、20dp 日历、24dp 账户图标。**诚实缺口**：三档是三颗不同图标而非同一颗的三种尺寸；Edit/Delete/Check/Close/Search/Alert/Info/Download/Upload/Lock 仍无宿主界面，见 `docs/测试报告.md` §28.7 |
| F-2 ✅ **已完成 2026-10-17** | fontScale ≥1.5 三处竖排（G-6）。实装：门槛收到 `ui/components/FontScale.kt`（`STACK_FONT_SCALE = 1.5f` + `stackAtLargeFont`，`MonthSummaryBar.stackSummaryCells` 改为委托）；`FilterBar` ≥1.5 时「筛选 + 清空」一行、chips 独占下一行；`LedgerSectionHeader` 新增 `stack` 参数，`DayHeader` 据此把支出/收入竖排；`BudgetProgressBar` 两段文字改竖排 | `@Config(qualifiers = "w411dp-h891dp-xxhdpi")` + `CompositionLocalProvider(LocalDensity provides Density(density, fontScale))` 的 9 例组件测试（1.0 与 2.0 各一组几何断言），真机 1440×3200（384dp 宽）跑 1.0 / 1.5 / 2.0 三档：1.5 下 chips 从 x98 起独占整行、日段头「支出 ¥774.00」在日期下面、预算行「已超支 ¥274.00」在「已用 …」下面；2.0 下「分类：餐饮」完整显示（E-1 遗留的裁字已消失）。红绿：三处 `val stack = ...` 改 `false` 时 3 例必红（详见 `docs/测试报告.md` §25.3） |
| F-3 ✅ **已完成 2026-10-17** | 分类预设 emoji 网格（C-6）。实装：新增 `CategoryIconPicker`（17 颗预置 emoji 的 `FlowRow` 宫格 + 「或手动输入 emoji」输入框，按码点裁剪、不切代理对）；`createCategory(..., icon)` / `renameCategory(id, name, icon)` 三态（null 保留 / 空串清除 / 非空替换）与 `CategoryDao.setIcon`（只 `UPDATE` 一列，不动表结构）；`DefaultCategories.presetIcons()` 与宫格共用同一套 | 17 例测试（仓库 6 + 宫格集合与裁剪 6 + 组件交互 5，`CategoryIconPicker` 单独渲染——`AlertDialog` + `OutlinedTextField` 在 Robolectric 下永不 idle）；真机：新建对话框 17 颗排三行（`图标（可选）` `[210,1211]`，🍜`[270,1347]` … ↩️`[471,1887]`）、编辑「娱乐」预填 🎮、点 🏆 保存后列表显示 `🏆 [88,1783] + 娱乐`、再改回 🎮 还原，账本 ¥2,566.00 未变。见 `docs/测试报告.md` §27 |
| F-4 ✅ **已完成 2026-10-17** | 「减少动画」偏好（AA-7）。实装：新增 `ui/MotionPreference.kt`——`animationsEnabled(scale) = scale > 0f`、`readAnimatorScale()`、`rememberAnimationsEnabled()`（读 `Settings.Global.ANIMATOR_DURATION_SCALE` + `ContentObserver`，设置里一开一关立刻生效）；`LedgerNavMotion` 四个转场加 `animated` 参数，false 时返回 `None`；`LedgerApp` 在 `NavHost` 前读一次并传给四个方向 | 7 例组件/纯函数测试（阈值 2 + 读设置 1 + 组合读值 2 + 4 路由 × 4 方向全为 `None` 1 + 反向 1）；真机**正/负对照**（同一套「明细→统计」操作、设备上连拍 3 张再 pull）：倍率 10 时帧间差 25.87% / 34.23%（拍法对动画敏感），倍率 0 时 0.00% 且三张截图字节数完全相同；两次都校验「点前明细、点后统计」。见 `docs/测试报告.md` §26 |
| F-5 ✅ **已完成 2026-10-17** | 金额输入全角归一化（来源是 §22 真机走查的输入侧发现，原计划单独成批）：新增 `Money.normalizeAmountInput`，在 **输入口**把全角数字 `０-９`、句号 `。` / `．` / `｡`、逗号 `，`、负号 `－` / `−` / `﹣`、全角空格 `U+3000` 换成半角；`Money.parseCents` 与 CSV/JSON 导入的严格口径不动。接线 7 个 `KeyboardType.Decimal` 输入框（记一笔 / 转账经 `::setAmount`，账户编辑 / 余额校准 / 预算编辑 / 明细筛选上下限就地调） | 8 例测试（函数级 3 + ViewModel 接线 2 + 源码级守门 `AmountInputSourceTest` 3）；真机逐字符输 `1` `2` `3` + PERIOD + `4` `5` → 字段 `123.45`、复核行 `核对：¥123.45`（修复前同一套按键得「123。 45」且复核行不出现），第二个输入口（账户初始余额）读到 `1.5`，取消后净资产 -¥2,539.00 逐分未变。见 `docs/测试报告.md` §23 |

> 批 F 状态（2026-10-17）：**F-1（13 个功能图标）图标已补齐、接线部分完成**——证据见 `docs/测试报告.md` §28（103 套 853 例、行覆盖 71.7%、真机三档 16/20/24dp）；**F-3（分类图标宫格）已完成**——证据见 `docs/测试报告.md` §27（102 套 846 例、行覆盖 71.4%、真机 5 项含改图标再还原）；**F-4（接系统「减少动画」偏好）已完成**——证据见 `docs/测试报告.md` §26（99 套 829 例、行覆盖 71.2%、真机正负对照：倍率 10 帧间差 25.87% / 34.23%，倍率 0 帧间差 0.00%）；**F-2（fontScale ≥1.5 三处竖排）已完成**——证据见 `docs/测试报告.md` §25（98 套 822 例、行覆盖 71.2%、真机 3 档字体走查净效果为零）；**F-5（金额输入全角归一化）已完成**——它不在 §11 的原始批次里，来源是 §22 真机走查发现的输入侧问题，趁手修掉并单列成 F-5；证据见 `docs/测试报告.md` §23（94 套 808 例、行覆盖 70.6%、真机 10 项走查净效果为零）。**F-1 图标已补齐、10 个图标待接线**（见 §28）。F-5 的执行要点：①先判断「归一化放输入口还是放解析」，选输入口——字段本身就得是半角，否则 §22 的「核对：¥…」复核行与字段对不上，用户看着全角还能保存更迷惑；②`parseCents` 严格口径与 CSV/JSON 导入路径一律不动；③7 个输入口靠源码级守门 `AmountInputSourceTest` 兜底，但那只网只认表里列出的文件，新增金额输入框要手动补 `amountFieldFiles`。

### 批 P3：待拍板项（不默认做）

| 项 | 内容 | 为什么要拍板 |
| --- | --- | --- |
| P3-1 | 记一笔默认沿用上次分类/账户（R-3） | 是否算「扩大范围」需产品判断；实现只需 ViewModel 记忆，不写库 |
| P3-2 | 明细页搜索图标直入关键词（T-5） | 改变筛选面板的进入路径 |
| P3-3 | 预算列表筛选（全部/已设/未设，B-6） | 新增交互 |
| P3-4 | 环形图浅色 3 色替换（§10.1 备选） | 破坏两主题色一致性，**建议不做** |
| P3-5 | 品牌标识 VectorDrawable（§5.2 可选动作） | 新增资产 + 设计判断 |
| P3-6 | 金额中文口语朗读（AA-5） | 需新增 Money 层函数，触及金额核心模块 |

---

## 12. 交付物索引与验证声明

### 12.1 要求 → 章节对照

| 要求 | 章节 |
| --- | --- |
| 视觉语言 | §1（五条规则 + 一屏一个蓝时刻 + 语义护栏） |
| 色板 | §2（角色分配表 + 图表色复核 + 深色补充） |
| 字体层级 | §3（11 档映射表 + 金额排版 + 200% 缩放） |
| 间距 | §4.1（4dp 网格 9 档） |
| 圆角 | §4.2（使用矩阵，对齐 Shape.kt） |
| 图标与插图风格 | §5（13 个待补图标口径 + 插画 5 场景规格） |
| 空态与错误态 | §6.7 + §9（14 行总表 + 5 条统一规则） |
| 逐屏改进点 | §8（G/T/R/S/B/A/ST/C/D/AA 共 70+ 条，带优先级） |
| 优先级 | §8 表头定义 + §11 分批计划（批 D/E/F + P3） |
| 信息架构 | §7 |
| 组件规格 | §6（9 个组件，含 Kotlin 签名与尺寸表） |
| 无障碍 | §10（对比度 + 触控 + 朗读动线 + 焦点 + 200% + 合规） |

### 12.2 验证状态（诚实边界）

**已验证（有实测数据或源码依据）**：
- 对比度数值：§1.3、§2.2、§10.1 全部由 WCAG 相对亮度公式**本次复算**得出；既有断言组取自 docs/测试报告.md §14.1。
- 源码事实：所有文件路径、组件签名、常量值、文案均来自本仓库 app/src/main/java/com/nimo/ledger/ui/** 的逐文件阅读。
- 已知缺陷：FAB 遮挡气泡（docs/测试报告.md §10 第 (9) 条）、FAB 朗读与对话框标题同串（§8）、PickerCell 40dp（源码）、EmptyState 插图无间距（源码）。

**未验证（本规范中标注「待验证 / 待确认」的项）**：
1. 所有布局改动**没有**经过编译、真机或截图验证——本文是设计规范，不是已落地的实现。
2. 屏幕层测试覆盖率低（docs/测试报告.md §10 第 (4) 条：ui.settings 10.7%），批 D/E 的组件替换**缺少测试网**，重构前应先补组件级测试。**批 D 的 D-1 ~ D-6 已按此执行**（D-1 / D-2 先写 13 例组件级测试再替换，见 docs/测试报告.md §17.1；D-3 ~ D-6 又补 16 例，见 §18.1）。
3. 31 天逐日图真机密度未构造（§10 第 (8) 条）——S-6 的窄屏加高建议依赖这条，**未验证**。
4. TalkBack 动线脚本（§10.3）是设计预期，未在真机走过。
5. 分类 emoji 预设（C-6）、默认沿用上次分类（R-3）、设置页计数副标题（§7.3 第 3 条）涉及产品判断或数据接口确认，等待拍板。
6. 200% 文本缩放逐屏清单（§10.5）是待执行计划，未执行。

### 12.3 一句话给实现者

> 图标教了三件事：**大圆角、纯色块、一处点缀**。守住这三点，再把「写了 8 遍的列表行」收成 1 个组件、把两个 FAB 的名字分清、把挡住气泡的悬浮按钮挪开——这三件事做完，界面完成度会有质的变化，而产品范围一行都不用改。

---

**UI 设计师**：DSH UI 设计师 Agent
**设计系统日期**：2026-10-16
**实施状态（2026-10-18）**：另有一项不在 §11 批次里的导航收口已完成——统计下钻不再弹掉返回栈、转场按 Tab / 二级页 / 表单分档（§7.4，证据见 `docs/测试报告.md` §19）。§11 的批 D 已全部完成——D-1（`LedgerListItem`）/ D-2（`LedgerSectionHeader`）证据见 [`docs/测试报告.md`](测试报告.md) §17，D-3（`LedgerProgressBar`）/ D-4（`EmptyState` 三色调）/ D-5（`PickerCell` 44dp）/ D-6（FAB 与对话框标题文案分离）见 §18；批 E 的 E-1 ~ E-3 见 §21 / §22，E-5（气泡避开 FAB）/ E-6（选中格底色 0.14f）见 §24，E-8（预算页点月份标签直选年月，2026-10-18）见 §29，E-7（设置页卡片分组 + 开发项折叠，2026-10-18）见 §30，E-4（统计页洞察卡片化 + 段头 sticky，2026-10-18）见 §31；批 F 的 F-2（fontScale ≥1.5 三处竖排）见 §25，F-4（接「减少动画」偏好）见 §26，F-3（分类图标宫格）见 §27，F-1（13 个功能图标）见 §28；金额输入全角归一化（§11 之外、由 §22 真机走查引出的 F-5）见 §23；问题 G-5（金额列统一 `LedgerAmount`，2026-10-18）见 §32；问题 AA-3（装饰性图标不重复朗读 + 环比方向入口语，2026-10-18）见 §34；问题 AA-6（表单焦点顺序显式固定，2026-10-18）见 §35；批 E 全部收口（E-1 ~ E-8），F-1 余下的 10 个图标仍无宿主界面（E-7 只重排了设置页，没有给它们接界面）
**QA 流程**：批 D/E/F 每批结束跑 testDebugUnitTest --rerun + lint + assembleDebug，并为新组件补截图断言

