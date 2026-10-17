# 个人记账 App UI/UX 走查报告（支撑首批 5 张效果图）

- 仓库：D:/git/ledger　App 包名：com.nimo.ledger　目标设备：Xiaomi 15S Pro（25042PN24C，Android 17 / API 37，density 3.75，arm64-v8a）
- 走查日期：2026-10-17　方法：专家走查（heuristic walkthrough）+ 源码逐行核对 + 既有真机走查记录复盘
- 权威资料：D:/AIworkspace/记账/个人记账App开发计划V2.2.md（含 V2.3 补遗，产品范围与统计口径唯一权威）、docs/UI-UX设计规范.md、docs/测试报告.md、app/src/main/java/com/nimo/ledger/ui/ 下各屏源码
- 约束遵守：不改变产品范围；不引入联网 / 账号 / 云同步 / 广告 / 遥测；不虚构用户调研数据；不写入任何真实交易数据（走查用测试分类与可撤销的测试笔）

## 0. 方法与证据等级（先读这一节）

| 等级 | 含义 | 本文用途 |
| --- | --- | --- |
| E1 源码事实 | 可 grep 到文件与行号 | 所有「现状是什么」的陈述 |
| E2 真机已实测 | docs/测试报告.md §13 / §14 有记录 | 无障碍同串、气泡遮挡、FabReserve 命中冲突、对比度实测值 |
| E3 设计推断 | 未编译、未真机、未截图 | 所有布局改法、字号缩放行为、TalkBack 动线预期 |
| E4 缺证据 | 无数据 | 真实任务完成率 / 耗时 / 错误率 / 满意度 / NPS / 崩溃率 |

本报告全部结论 = E1 + E2；凡标「建议 / 应」的都是 E3，必须在真机复核后落地。

## 1. 核心用户任务：任务流与摩擦点

优先级定义：P0 = 明确缺陷或无障碍违规，必须修；P1 = 一致性 / 体验收益高且成本低；P2 = 明显加分但有产品判断。

### 任务 A：3 次点击记一笔（冷启动 → 保存）

现状任务流（全部 E1）：

1. 任意顶层页（明细 / 统计 / 设置）右下角 ExtendedFAB「记一笔」→ app/src/main/java/com/nimo/ledger/ui/LedgerApp.kt:163-174（仅 isTopLevel 显示）、文案常量 :64 RECORD_FAB_LABEL = 记一笔
2. 进入记一笔页即自动聚焦金额框 → ui/record/RecordScreen.kt:319-322（amountFocus.requestFocus，非编辑态）
3. 输入金额 → RecordScreen.kt:389-400（label「金额」、prefix ¥、headlineSmall、KeyboardType.Decimal）
4. 点分类 chip → RecordScreen.kt:402-432（FlowRow + FilterChip，末尾恒有「+ 新建」）
5. 点 TopAppBar 右上「保存」→ RecordScreen.kt:150-171（全页唯一保存入口）

支撑口径（E1）：账户不需要人工选，effectiveAccountId = form.accountId ?: accountList.firstOrNull()?.id（ui/record/RecordViewModel.kt:272），保存时再兜一次（:362）；分类必须选，CategoryRequired（domain/validate/TransactionValidator.kt:82）；账户必填（同文件 :68-69）但被 UI 兜底。
点击计数 = FAB 1 次 + 选分类 1 次 + 保存 1 次 = 3 次点击 + 1 次金额输入，与计划 L73 的「全程 ≤3 次点击 + 一次金额输入」一致；测试报告验收判定记录的路径（底部「记一笔」→ 填金额 / 选分类 / 保存）与此吻合。
结论：核心闭环结构成立，问题不在「几次」，而在第 3 次点击的位置（见 A-1）。

| 编号 | 摩擦点 | 级别 | 证据 |
| --- | --- | --- | --- |
| A-1 | 保存按钮只在 TopAppBar 右上角，页面内没有第二个保存入口；键盘弹起后它仍在屏幕顶部，大屏单手（尤其左手）够不到 | P0 | RecordScreen.kt:150-171（actions 里唯一 TextButton「保存 / 保存中…」，页面内无第二处）；键盘场景是用户真实反馈过的痛点（测试报告 §12① 表单下半被输入法遮挡 → 已加 adjustResize + imePadding） |
| A-2 | 金额框下方没有复核行，输入的是全流程唯一的数字却没有「我输对了吗」的回显；对比备注字段反而有字数计数 | P1 | RecordScreen.kt:389-400（金额框无 supportingText）vs :471-481（备注有 note.length / 200 计数） |
| A-3 | 分类 FlowRow 无高度上限，分类变多时把账户 / 日期 / 备注挤出屏幕（自建分类无数量限制） | P1 | RecordScreen.kt:402-432（FlowRow 无 heightIn / 无内部滚动）；V2.3 R3 允许任意新增分类 |
| A-4 | 转账模式与记一笔共用同一套顶栏保存（TransferScreen.kt:119-120），转账是 4 字段表单且无默认值兜底，风险高于记一笔 | P1 | ui/transfer/TransferScreen.kt:119-120 与注释 :139（宿主只管 Scaffold 与顶栏的「保存」按钮：转账页用它，记一笔页的转账分段也用它） |
| A-5 | 编辑态「删除这一笔」在表单末尾，与顶栏保存分处两端；误触风险低于设计规范描述（已有 HorizontalDivider + 说明文案），但间距数值未读数确认 | P1 | RecordScreen.kt:485-502、TransferScreen.kt:331-340（两处同款 + 文案「删除后它会立刻从流水里消失，底部提示条里可以撤销。」） |
| A-6 | 新建时若用户直接点保存会得到字段级错误才补选分类（没有任何引导提示，错误只在提交后出现） | P2 | TransactionValidator.kt:82 + RecordViewModel.kt:367-369（Invalid → errors，仅在 save 后产生） |
| A-7 | 备注无常用词 / 模板入口 | P2 | RecordScreen.kt:471-481 只有纯文本输入 |

### 任务 B：快速核对本月支出

现状任务流（E1）：明细 Tab → 顶部 MonthSwitcher（可点月份标签直选年月，MonthSwitcher.kt 的 onLabelClick 已接入，明细页 L83-88）→ MonthSummaryBar 三格（支出 / 收入 / 结余）→ 向下扫流水；点某一行进编辑，点筛选进组合筛选。

| 编号 | 摩擦点 | 级别 | 证据 |
| --- | --- | --- | --- |
| B-1 | 首屏被三行工具条吃掉约 180dp（MonthSwitcher + MonthSummaryBar + FilterBar + Divider），可见流水行数被压缩 | P1 | TransactionsScreen.kt:81-96（三行结构）；设计规范 §0.3 与 T-1 同一判断 |
| B-2 | 筛选条件摘要是多行竖排纯文字（不是 chip、不能横滚），条件一多继续长高 | P1 | TransactionsScreen.kt:179-212（chips 非空时 Column(weight 1f) + chips.forEach { Text(...) }） |
| B-3 | 日段头只有日期 + 支出/收入合计，没有「N 笔」，无法判断某天是否漏记 | P1 | TransactionsScreen.kt:214-243 DayHeader |
| B-4 | 金额列未走统一组件、无 minWidth，右边缘参差，扫读金额列对不齐 | P1 | TransactionsScreen.kt:275-280（Text(TransactionTimeline.amountText(row))，无 minWidth） |
| B-5 | 日段头非 sticky，长列表滚到中段后失去日期定位线索 | P1 | TransactionsScreen.kt:122-143（LazyColumn item(key = day-…)，非 stickyHeader） |
| B-6 | 加载态是居中转圈，无骨架，切换月份时整屏闪 | P2 | TransactionsScreen.kt:100（Box 居中 CircularProgressIndicator） |
| B-7 | 支出 / 收入只靠颜色风险已被规避（金额文本自带 +/- 符号），不要为了「简洁」去掉符号 | 约束 | ui/components/TransactionTimeline.kt 的 amountText；设计规范 §1.2 三重冗余要求 |

### 任务 C：找一笔旧流水

现状任务流（E1）：明细 Tab → 翻月份（MonthSwitcher.kt:44-46 / :67-69，contentDescription「上个月」「下个月」）或点月份标签开自绘年月选择器（YearMonthPickerDialog，年格 → 月格两步直达）→ 列表滚动；或点「筛选」按账户 / 分类 / 金额区间 / 日期区间 / 备注关键词查。

| 编号 | 摩擦点 | 级别 | 证据 |
| --- | --- | --- | --- |
| C-1 | 年月选择器格子高 40dp < 44dp 触控目标（F15 违规），而它是「找旧流水」的必经步骤 | P0 | ui/components/YearMonthPickerDialog.kt:144-181 PickerCell（modifier.height(40.dp)）；设计规范 D-1 / AA-2 |
| C-2 | 明细分页没有关键词直入入口，搜索只能进筛选对话框的第 5 段（备注关键词 / 包含文字） | P1 | ui/transactions/TransactionFilterDialog.kt（label「包含文字」唯一入口）；明细页无搜索按钮，LedgerIcons.kt 也没有 Search 图标 |
| C-3 | 筛选面板已修好「先限高 420dp 再挂滚动」（正确写法有注释与 UI 测试钉住），但面板本身很深，5 个 Section 需要滚动才能看全 | P1 | TransactionFilterDialog.kt:298-321（FILTER_DIALOG_MAX_HEIGHT = 420.dp + FilterDialogBody） |
| C-4 | 年月选择器只有「取消」一个按钮，标题「选择年月」；读屏用户没有「当前选中的年月」语义提示（PickerCell 无 contentDescription） | P1 | YearMonthPickerDialog.kt:44-56、:144-181 |
| C-5 | 日期区间筛选会覆盖月份语义（注释已说明「填了日期区间就按区间查，不再看月份」），但筛选摘要里月份与区间同时可见时容易误读 | P2 | TransactionFilterDialog.kt HintText + TransactionsScreen.kt:179-212 摘要 |
| C-6 | 批量操作不做（明确不做） | — | 设计规范 T-7 P3 |

### 任务 D：设置与查看预算

现状任务流（E1）：设置 Tab（三行内联入口：账户管理 / 分类管理 / 预算管理）→ 预算管理二级页（MonthSwitcher + 总预算卡 + 分类预算列表）→ 点总预算卡或某分类行 → BudgetEditDialog 输入金额保存。

| 编号 | 摩擦点 | 级别 | 证据 |
| --- | --- | --- | --- |
| D-1 | 「数据库自检（开发用）」直接平铺在正式设置项下方，进页面还自动跑一次自检；分类页空态进一步把用户引导到「回设置页跑一次数据库自检」 | P0 | ui/settings/SettingsScreen.kt:137-140 与 :48-58（container.selfCheck 结果平铺）；ui/categories/CategoriesScreen.kt:146-150（开发者文案） |
| D-2 | 账户页 / 分类页的页内 FAB 朗读串与同名对话框标题完全相同（新建账户 / 新建分类），TalkBack 无法区分动作与对话框标题 | P0（E2 已真机确认） | AccountsScreen.kt:48 + :104/:106 与 ui/accounts/AccountEditDialog.kt:76；CategoriesScreen.kt:51 + :128/:130 与 ui/categories/CategoryEditDialog.kt:122、ui/record/CategoryCreateDialog.kt:57；测试报告 §8 已知缺口；设计规范 AA-1 已给唯一文本分离改法 |
| D-3 | 设置页三行导航用字符「›」当箭头（LedgerIcons 没有 ChevronRight 以外的箭头与 Edit / Search / Calendar / Wallet / Info 等图标） | P0 | SettingsScreen.kt L78 / L89 / L100（Text(text = 「›」, style = titleMedium)）；ui/LedgerIcons.kt 现有图标只有 12 个（List、Chart、Sliders、Back、ChevronLeft、ChevronRight、Filter、Add、Transfer、ArrowUp、ArrowDown、Scale） |
| D-4 | 预算页月份标签不可点（未传 onLabelClick），与明细页 / 统计页不一致，核对历史月预算时无法直选月份 | P1 | BudgetsScreen.kt:123-127 vs TransactionsScreen.kt:83-88、ReportsScreen.kt:76-84 |
| D-5 | 超支没有可视化：进度条 fraction 被 coerceIn(0f, 1f)，超支时全红到底，看不出超了多少 | P1 | BudgetsScreen.kt:323-355 BudgetProgressBar |
| D-6 | 分类预算行没有行尾进入指示（无 chevron），且行内两枚上移 / 下移 IconButton 挤压分类名；已停用分类与在用分类混排 | P1 | BudgetsScreen.kt:274-321；CategoriesScreen.kt:249-288（ArrowUp / ArrowDown 占行尾）、:152-177（无分组折叠） |
| D-7 | 设置页分组只用 HorizontalDivider，没有卡片边界；三条导航行没有副标题状态（如「3 个账户」），而下方导入导出行却有 title + subtitle，形态不一致 | P1 | SettingsScreen.kt:59-65 与 L68-100；ui/settings/BackupSettingsSection.kt:427-440 BackupRow(title, subtitle) |
| D-8 | 设置页左右 padding 24dp，与其余页面 16dp 不一致 | P1 | SettingsScreen.kt:59-65 |
| D-9 | 版本行「记账本 0.1.0 · 已完成 M1（数据层）」出现在页面中部且含开发里程碑信息 | P1 | SettingsScreen.kt:142-145 |
| D-10 | 主题三个选项没有预览，选中效果要靠切出去看 | P1 | SettingsScreen.kt:108-124（RadioButton + label，无预览） |

## 2. 信息架构与导航风险

总体判断：底部 3 Tab（明细 / 统计 / 设置）+ 顶层 FAB + 二级页的骨架是对的，不需要重排；风险集中在「同一个视觉元素在不同页面代表不同动作」和「入口深度」。

### 2.1 底部三 Tab（E1）
- 定义：LedgerApp.kt:89-97 TopDestination（transactions/明细 + LedgerIcons.List、reports/统计 + Chart、settings/设置 + Sliders）；渲染 :314-325（NavigationBar + NavigationBarItem，Icon contentDescription = dest.label）。
- 表单页收起底部导航：LedgerApp.kt:148（注释：避免误切页丢掉已填内容）——正确，但系统返回键 / 手势返回是否也拦一道，源码未处理，属未验证项。
- 下钻路径：统计页 onDrillDown → 写入 DrillDownRequests 脉冲 → navigate(TRANSACTIONS) 且 popUpTo(startDestination){saveState=true} + restoreState=true（LedgerApp.kt:213-222）。滚动位置是否真的恢复未验证（未写测试、未真机）；DrillDownRequests 用 MutableStateFlow + compareAndSet 一次性消费（ui/DrillDownRequests.kt），避免过期条件重套，设计正确。

### 2.2 多页 FAB 撞款（E1，P0 级 IA 风险）
- 顶层 FAB：LedgerApp.kt:163-174，仅 isTopLevel 显示，ExtendedFloatingActionButton，文案「记一笔」，semantics contentDescription = RECORD_FAB_LABEL（:64，注释说明三处悬浮按钮同一处理）。
- 账户页 FAB：AccountsScreen.kt:90-108，同款 ExtendedFAB、同位置、contentDescription「新建账户」；分类页 FAB：CategoriesScreen.kt:95-132，同款「新建分类」。
- 风险三重：① 视觉上「右下角永远是同一个按钮」，在账户页按下去的心理预期可能是「记一笔」；② 三处没有任何视觉区分（同一主容器色，见 V2.3 R4 #79C9FC）；③ 底部内容预留不一致——账户 / 分类页用 FAB_BOTTOM_RESERVE = 88.dp（ui/components/FabReserve.kt:16，注释记录了 fontScale 2.0 时最后一行「下移」按钮与悬浮按钮 bounds 相交、点下去弹出「新建分类」的真机缺陷），预算页没有 FAB 所以不预留（BudgetsScreen.kt:149-173），设置页有 FAB 所以预留（SettingsScreen.kt:147）。结果是「右下角有什么」在不同页面不稳定。
- 建议（E3）：页内 FAB 要么改成顶栏「新建」文字按钮 / 图标按钮，要么在文案与图标上与「记一笔」明确区分；无论哪种，都保留 88dp reserve 并在真机上复测 fontScale 2.0。

### 2.3 分类 / 账户入口深度（E1）
- 分类：设置 Tab → 分类管理 = 2 跳；记一笔内「+ 新建」= 0 跳直建（RecordScreen.kt:402-432 末尾 FilterChip → CategoryCreateDialog.kt:57，kind 锁定当前收支段，测试报告 §13 已真机验证保存后自动选中）。两条路径对分类的可见范围不同：记一笔只列可用分类，分类管理列全部（含已停用）。
- 账户：只有设置 Tab → 账户管理一条路（2 跳）；记一笔内的账户 chip 只能选不能建，无可用账户时记一笔显示 NoAccount 空态并指向「设置 → 账户管理」（RecordScreen.kt:227-260）。首次使用必须穿过 2 跳才能记第一笔，这是「3 次点击记一笔」在首次使用场景下的真实例外。
- 分类层级只有 24dp 缩进一个提示（CategoriesScreen.kt:249-288），且是 0 / 24 二值（产品限两级），同屏看不出「同父兄弟」的边界。

### 2.4 对话框标题朗读同串（E1 + E2，P0）
- 现状：FAB 与对话框标题都读同一个串——「新建账户」（AccountsScreen.kt:48/104/106 与 AccountEditDialog.kt:76）、「新建分类」（CategoriesScreen.kt:51/128/130 与 CategoryEditDialog.kt:122、record/CategoryCreateDialog.kt:57）。
- 对照：其余对话框标题都是名词短语，可作为统一改法的参考——「校准余额」（BalanceAdjustDialog.kt:43）、「选择年月」（YearMonthPickerDialog.kt:52）、「筛选」（TransactionFilterDialog.kt:75）、「删除分类「X」？」（CategoryEditDialog.kt:242）、「删除这条预算？」（BudgetEditDialog.kt:140）、「编辑转账 / 转账」（TransferScreen.kt:111）、「导出前请知悉」（BackupSettingsSection.kt:257）。
- 改法（唯一，来自设计规范 AA-1）：FAB contentDescription = 「新建账户」/「新建分类」，对话框标题改「账户信息」/「分类信息」；两处都要写实现注释防回归。

### 2.5 其他导航风险
- 明细页筛选与统计下钻共用同一个目的地：下钻会写入筛选条件，用户返回明细后看到的是「被下钻条件筛过的列表」，摘要区（TransactionsScreen.kt:179-212）是否足以解释「我现在看的是子集」未验证（E3）。
- 延迟删除提示条：LedgerApp.kt:120-142，SnackbarDuration.Indefinite + 「撤销」，只挂最新一笔（注释说明其余几笔倒计时照旧）；提示条是否遮住底部导航未验证。

## 3. 五张效果图：各自要验证的可用性假设 + 可判定验收标准

说明：以下 5 张是按「当前摩擦点最集中、且一张图能判定多个假设」选出来的。每张图的验收标准分两栏——「图上可判定」（拿图量像素 / 数元素即可判）与「必须真机判定」（图上判不了）。

### 图 1　记一笔（含键盘弹起态与金额复核行）
要验证的假设 H1：把主保存动作放到表单底部全宽按钮后，单手可达性显著改善；金额复核行能降低「金额输错」类返工；分类区限高后后续字段不会再被挤出屏幕。
图上可判定：
1. 底部保存按钮：高度 ≥48dp（设计取 52dp），水平占满内容区（左右各 16dp 边距），文案「保存」，与顶栏「保存」在 disabled 状态与 loading 文案（保存中…）上一致；
2. 金额框下方 8–12dp 处有复核行，内容 = Money.formatCurrency(parsedCents)（整数分换算，不出现 Float/Double），样式 bodySmall / onSurfaceVariant；未解析出金额时该行不占位；
3. 分类区 maxHeight ≤168dp 且内部可垂直滚动；滚动分类区时「账户 / 日期 / 备注 / 保存」四项仍在屏内；
4. 键盘弹起态下底部保存按钮完整可见（不被 IME 遮挡），金额框与分类行至少一项可见；
5. 分类 chip 视觉高 32dp 但点击区 ≥48dp（minimumInteractiveComponentSize），支出 / 收入 / 转账分段按钮高度 ≥48dp；
6. 颜色语义：金额复核行用 onSurfaceVariant（不是 error，未出错前不报警），支出段强调色 error #B3261E、收入段 primary #0B5A8C（浅底对比度 6.23 / 7.00）。
必须真机：单手（拇指）可达率、TalkBack 焦点顺序（金额 → 分类 → 账户 → 日期 → 备注 → 保存）、键盘遮挡实测。

### 图 2　明细页首屏（工具条瘦身 + 金额列对齐 + 日段头信息）
要验证的假设 H2：工具条由三行压到两行（筛选行单行化 + 条件 chip 横滚）后，首屏可见流水行数明显增加，同时「当前筛选条件」仍能一眼读出。
图上可判定：
1. 顶部工具条总高 ≤120dp（标注逐段 dp：MonthSwitcher / MonthSummaryBar / FilterBar），对比现状约 180dp；
2. 条件摘要为圆角 chip 一行横滚（单 chip 高 ≤32dp、可横滑不换行）；无条件时该行只剩「筛选」按钮，不出现空的文字列；
3. 所有行金额走统一组件：minWidth ≥96dp、右对齐、字形一致（不出现参差右边缘）；
4. 日段头含「N 笔」，且仍带当日支出 / 收入合计；
5. 每行金额带 +/- 符号（颜色 + 符号 + 位置三重冗余），删除 / 编辑按钮不出现在行内（保持整行热区 ≥56dp）；
6. 空态与错误态用不同插图底，错误态给「重试」动作；筛选无结果文案指向「清空」；
7. 对比度：outline/surface ≥4.83（现状刚好通过，改 chip 底色时不得掉到 4.5 以下）、onSurfaceVariant/surfaceVariant ≥6.06。
必须真机：chip 横滚在 TalkBack 下的可达性、fontScale 1.5 / 2.0 时摘要行是否横排仍成立。

### 图 3　统计页首屏（本月洞察 + 环形图 + 气泡）
要验证的假设 H3：环形图加 2dp surface 缝后，浅色底三段即使不达 3:1 也能被区分；点最后一段扇形时气泡不再被右下角 FAB 遮挡。
图上可判定：
1. 相邻扇形之间 2dp surface 色缝（WCAG 1.4.11 允许的相邻对比替代）；环形图外径 180dp，与真机实测一致（测试报告 §13 画布 675×675px、density 3.75、环心 (721,2297)）；
2. 环心显示本月支出合计（Money.formatCurrency）；每段扇形对应图例/排行行含名称 + 百分比 + 金额，百分比与金额同源（StatsCalculator）；
3. 气泡（DayTooltip / DonutTooltip）完整落在滚动视口内，且与右下角 FAB 矩形不相交（FAB = 56dp 高、16dp 外边距）；气泡内「查看明细」文本完整可见；
4. 气泡信息结构：日期或分类名 + 「¥x · N%」+「查看明细」；
5. 五个 Section 标题在滚动时 sticky（现状 5 个 SectionHeader 都不是 sticky）；「本月洞察」为 surfaceContainerLow + 16dp 圆角卡片；
6. 空月份：120dp 小狗插图 + 标题「本月还没有记录」（装饰插图 contentDescription = null），文案给出下一步动作；
7. 「日均支出」必须标注口径「按本月已过 N 天算」，且 N 来自 StatsCalculator（不得在 UI 另算）。
必须真机：点最下沿扇形是否被 FAB 盖住（测试报告 §10(9) 真实触发过一次、样本 1）；相邻柱朗读是否被合并。

### 图 4　预算页（含超支可视化）
要验证的假设 H4：100% 处 2dp 刻度 + 超出量文字，能让「超了多少」一眼可读，而不只是「红了」。
图上可判定：
1. 进度条底槽 8dp / 圆角 4dp；已用部分 = min(fraction, 1) × 100% 宽；超支时在 100% 位置有 2dp 竖线刻度，并配「已超支 ¥x」文字；
2. 四种状态都不只靠颜色：未设置 / 还剩 ¥x / 已用 n%，接近上限 / 已超支 ¥x，文字标签齐全；状态色只在文字与填充上，标签本身保持可读对比度；
3. fontScale ≥1.5 时「已用 ¥x / ¥y」与状态标签竖排、不折行重叠；
4. 行尾有一致的进入指示（ChevronRight 图标，不是字符箭头），行内无删除按钮；
5. 月份标签可点（与明细 / 统计一致），点击开「选择年月」；
6. 页脚口径说明前有 Info 图标：「预算只看支出：转账与余额调整不算支出（与统计页同一口径）。」
必须真机：fontScale 2.0 下的实际排布、TalkBack 状态朗读是否把「已用 87%，接近上限」读成一句。

### 图 5　账户页 / 分类页（FAB 语义分离 + 列表行统一 + 空态动作）
要验证的假设 H5：文本分离后读屏用户能区分「新建」动作与「对话框标题」；列表行统一为同一行组件后视觉一致且不缩小热区。
图上可判定：
1. 页内 FAB 与对话框标题是两串不同文本（FAB「新建账户」/「新建分类」，对话框标题「账户信息」/「分类信息」）；
2. 所有列表行箭头为统一 ChevronRight 图标（24dp），行高 ≥56dp，整行可点；
3. 已归档账户 / 已停用分类与在用项视觉分离（分组或折叠），文字仍带状态词；
4. 净资产行：数值视觉权重 ≥ 标签（如 titleMedium SemiBold vs bodySmall）；
5. 空态带动作按钮（账户空态「新建账户」），分类空态文案去掉「数据库自检」这类开发流程；
6. 触控目标：IconButton ≥48dp、FilterChip 点击区 ≥48dp、PickerCell ≥44dp。
必须真机：TalkBack TTS 日志逐字确认两串分离（这是批 D 唯一必须上真机的验收项 D-6）；200% 文本缩放逐屏清单。

## 4. 真机 10 步走查脚本

环境：Xiaomi 15S Pro（Android 17 / API 37，density 3.75，屏幕 3200×1440，逻辑宽 411dp 左右）；release 包；走查前清空测试数据或用 0.01 元测试笔；截图存仓库外（AGENTS.md 第 10 条不入库）。
取证手法：截图 + logcat；注意 uiautomator dump 会压制 TalkBack（测试报告 §8），需要无障碍朗读时只看 TTS 日志。

| 步 | 操作 | 期望结果 | 判定依据 |
| --- | --- | --- | --- |
| 1 | 从桌面冷启动 App，掐表到明细页首屏出现 | ≤1.5s | 计划 L383-388 验收标准；测试报告实测 release 185–219ms（有大量余量，若超时说明真机环境劣化） |
| 2 | 在明细页点右下角「记一笔」，只观察不操作 | 自动进入记一笔；金额框已获得焦点并弹起键盘；账户 chip 已默认选中第一个可用账户；分类未选中 | RecordScreen.kt:319-322（自动聚焦）+ RecordViewModel.kt:272（effectiveAccountId 兜底）+ TransactionValidator.kt:82（分类必选） |
| 3 | 输入 0.01，直接点顶栏「保存」 | 出现字段级错误提示「分类必选」，错误定位到分类区，其余已填内容不丢 | TransactionValidator.kt:82 CategoryRequired；RecordViewModel.kt:367-369（Invalid → errors）；设计规范 D-3 节点 |
| 4 | 选任意分类后点「保存」，同时数点击 | 点击计数 = 3（FAB 1 + 分类 1 + 保存 1）+ 1 次金额输入；保存后返回上一页 | 计划 L73；测试报告验收判定同一路径 |
| 5 | 重进记一笔，键盘弹起状态下看底部 | 若已实现底部主保存按钮：完整可见不被 IME 遮挡；若仍只有顶栏保存：记录为 A-1 未修复 | 测试报告 §12①（真实用户反馈 + 已加 adjustResize/imePadding）；A-1 |
| 6 | 回明细页，核对新笔与顶部三格 | 支出增加 0.01；新笔出现在今天分组首位；同日排序稳定（occurredOn DESC, id DESC） | AGENTS.md 列表排序铁律 + 计划统计口径（数字必须来自 StatsCalculator） |
| 7 | 点月份标签 → 在年月选择器里量取格子实际高度（像素 / 3.75） | ≥44dp（即 ≥165px）；现状 40dp = 150px → 判定失败并记录 | YearMonthPickerDialog.kt:144-181；F15 触控目标要求（设计规范 D-1 / AA-2） |
| 8 | 统计 Tab → 点环形图最下方那一段扇形 → 点气泡里的「查看明细」 | 气泡完整可见、未被 FAB 遮挡，「查看明细」可点且进入明细页 | 测试报告 §10(9)（气泡落在屏幕下沿时 FAB 盖住「查看明细」，真机触发过 1 次）；设计规范 S-1 |
| 9 | 预算页 → 把某分类预算设为小于本月已花 → 观察进度条 | 出现 100% 刻度与「已超支 ¥x」，不再是「全红到底」 | BudgetsScreen.kt:323-355（coerceIn(0f,1f)、无超支刻度）；设计规范 B-2 |
| 10 | 开 TalkBack：账户页 TAB 到页内 FAB → 朗读；再打开该对话框 → 朗读标题；随后把系统字号调到 200% 复看明细页与预算页 | FAB 读「新建账户，按钮」、对话框标题读「账户信息」（两串不同）；200% 下关键信息不折行重叠、无截断 | 测试报告 §8（现状两串同串，已知缺口）；设计规范 AA-1 / AA-6 / AA-8 |

每步记录三态：通过 / 失败 / 无法判定，附截图文件名与时间戳；失败项回填到第 1 节的摩擦点编号。

## 5. 诚实标注：哪些结论缺真实用户数据支撑

本文没有任何新增的用户数据。全部结论来自源码逐行阅读（E1）与 docs/测试报告.md 记录的真机走查（E2）。具体缺口：

1. 没有用户画像、没有用户原声、没有行为数据。项目禁止遥测，也没有做过访谈 / 问卷。唯一真实用户反馈是测试报告 §12 记录的三条（装 release 真实使用后提出：输入法遮挡、柱状图无 y 轴、月底横轴拥挤）——它们是仅有的真实信号，本文第 1 节里凡涉及「用户会困惑 / 够不到」的判断都是专家走查推断，不是用户数据。
2. 没有任务完成率 / 耗时 / 错误率 / 满意度 / NPS。「3 次点击」是代码可证的结构性计数，不是实测效率；测试报告明确真机单次交互墙钟耗时三种仪器都取不到（§10(7)），所以任何「更快 / 更省力」都是设计推断。
3. 无障碍结论只有一条是真机确认的（AA-1 同串朗读，测试报告 §8）。以下均未真机验证：焦点顺序（AA-6）、金额中文口语朗读（AA-5，需新增 Money 层函数）、相邻柱朗读是否被合并（AA-4）、减少动画偏好（AA-7）、200% 文本缩放逐屏清单（AA-8）。
4. 所有布局改动建议都未经编译、未经真机、未经截图验证（设计规范 §12.2 自述）。本文第 3 节的验收标准是「设计意图的可判定化」，不等于已达标。
5. 31 天逐日图在真机上没构造过（§10(8)，当月只过 16 天），图 3 里与标签密度 / 横向滚动相关的判定属设计推断。
6. 设备覆盖只有一台（Xiaomi 15S Pro，Android 17 / API 37）；无低版本、无平板、无折叠屏、无横屏数据（Android 8 已由用户决定移出范围，§10(1)）。
7. 优先级 P0/P1/P2 是「是否明确缺陷或无障碍违规 + 改动成本」的判定，不是用户投票，也没有量化影响面。
8. FAB 遮挡气泡在真机只真实触发过一次（§10(9)），属「已复现但频率未知」，不要按「100% 必现」写进宣传或验收。
9. 涉及审美 / 品牌感的标准（小狗风、圆角 16–24dp、留白节奏）无法客观判定，只能靠设计评审，本文不给这类结论。

## 附录 A　证据索引（本文引用到的锚点）

- 计划口径：L73（3 次点击）、L383-388（验收标准）、L499（撤销删除）、附.4 / V2.3 R1–R4（年月直改、单月分析、分类增删、小狗风）。
- 源码：LedgerApp.kt（:64,:89-97,:109-116,:120-142,:148,:163-174,:186-224,:233-254,:314-325）、ui/components/FabReserve.kt:16、MonthSwitcher.kt:44-46/:67-69、MonthSummaryBar.kt:25-28、EmptyState.kt:21/:48-57、DrillDownRequests.kt、record/RecordScreen.kt（:150-171,:227-260,:319-322,:389-400,:402-432,:434-469,:471-481,:485-502）、record/RecordViewModel.kt（:272,:301,:355-393）、transfer/TransferScreen.kt（:111,:119-120,:139,:331-340）、transactions/TransactionsScreen.kt（:81-96,:100,:122-143,:179-212,:214-243,:245-282）、transactions/TransactionFilterDialog.kt（:298-321,:342-375）、reports/ReportsScreen.kt（:76-84,:121-183,:300-347,:351-373,:395-432,:436-451）、reports/DayDetailDialog.kt:54、budgets/BudgetsScreen.kt（:106-116,:123-127,:149-173,:219-256,:274-321,:323-355,:357-375）、budgets/BudgetEditDialog.kt:73/:140、settings/SettingsScreen.kt（:48-58,:59-65,:68-100,:108-124,:137-140,:142-145,:147）、settings/BackupSettingsSection.kt（:55-64,:202-221,:257,:289,:319,:370,:427-440）、accounts/AccountsScreen.kt（:47-48,:90-108,:113-127,:208-221,:223-260）、accounts/AccountEditDialog.kt:76、accounts/BalanceAdjustDialog.kt:43、categories/CategoriesScreen.kt（:50-51,:95-132,:146-150,:152-177,:249-288）、categories/CategoryEditDialog.kt:122/:242、record/CategoryCreateDialog.kt:57、components/YearMonthPickerDialog.kt（:44-56,:63-129,:131-141,:144-181）、LedgerIcons.kt（12 个图标 + 缺失清单）、domain/validate/TransactionValidator.kt:55-99。
- 文档：docs/UI-UX设计规范.md（§0.2 不变量、§0.3 现状、§1.2/§1.3 对比度、§2.2 图表 10 色、§7 信息架构、§8 优先级、§9 空态表、§10.2 触控目标、§10.3 动线、§11 分批、§12.2 诚实边界）；docs/测试报告.md（§6/§7 安全与对账、§8 无障碍、§10 未覆盖 11 条、§12 三条真实反馈、§13 饼图走查、§14 深色与空态走查）。

## 附录 B　建议的落地顺序（不改产品范围）

1. 先做「无争议的缺陷修复」并全部上真机：D-2（同串朗读，批 D-6）、C-1（40dp 触控目标）、D-1（开发用自检移出用户可见主路径）、D-3（字符箭头换图标）。这四项都不涉及产品判断。
2. 再做「一致性与效率」：A-1（底部主保存）、B-1（工具条瘦身）、B-3/B-4（日段头笔数 + 金额列对齐）、D-5（超支可视化）、D-4（预算页月份标签可点）。
3. 最后是设计稿层面的统一：LedgerListItem / LedgerSectionHeader / LedgerProgressBar / EmptyState tone 四个组件收口，以及 LedgerIcons 补齐（ChevronRight 之外的 Edit / Delete / Search / Calendar / Wallet / Info）。
4. 5 张效果图按第 3 节的判定栏自检，能判的当场判；凡标「必须真机」的项，进第 4 节的 10 步脚本一起跑。

---
走查人：UX 研究员 Agent　日期：2026-10-17　下一步：把第 3 节的效果图验收标准交给设计执行，把第 4 节脚本交给真机走查，把缺证据清单（第 5 节）作为本轮结论的不确定性边界一并交付。