# 记账本（ledger）

离线、纯手动、支持多账户余额的 Android 个人记账应用。

- 无 `INTERNET` 权限（数据不上云的物理保证），无账号体系、无遥测
- 金额全程 `Long` 整数分，杜绝浮点误差
- 账户余额由交易推导（`初始余额 + Σ变动`），不维护可变余额字段
- 转账影响双账户余额、不计入收支统计
- 数据库层触发器兜底所有硬约束（金额正负、转账双账户、分类归属）
- 无障碍（TalkBack 可读）、深色模式、预算告警、图表趋势

开发计划（产品范围 / 数据铁律 / 统计口径的唯一权威来源）：
`D:/AIworkspace/记账/个人记账App开发计划V2.2.md`

## 功能

| 编号 | 功能 | 状态 |
| --- | --- | --- |
| F01 | 账户管理（CRUD、6 种类型、初始余额、归档不删数据） | 已实现 |
| F02 | 分类体系（支出/收入两套、预置 + 自定义、单层父子、排序、停用；删除前有引用护栏、可一键补种默认分类、记一笔内联新建） | 已实现 |
| F03 | 记一笔（支出/收入，金额/分类/账户/日期/备注，任意页面可唤起，可改可删可撤销） | 已实现 |
| F04 | 转账（双账户、不影响收支统计、余额不足警示） | 已实现 |
| F05 | 流水列表（按日分组时间线、按月切换、同日稳定倒序） | 已实现 |
| F06 | 筛选与搜索（账户/分类含父子/金额区间/日期区间/备注关键词） | 已实现 |
| F07 | 报表（收入/支出/结余、分类排行、账户分布、环比、日均；时间口径 = 本月 / 近3/6/12个月 / 自然年 / 去年 / 自定义区间） | 已实现 |
| F08 | 备份与导出（JSON 全量导出/恢复 + 恢复前自动备份；CSV 导出 UTF-8-BOM） | 已实现 |
| F09 | 预算管理（月度总预算 / 分类预算、80% 与 100% 告警） | 已实现 |
| F10 | 统计图表（趋势图随范围选粒度：≤62 天逐日柱可点出当日明细、更长的范围画月度折线；分类环形图、账户分布、下钻（下钻压在统计页之上、返回回统计页；环形图点扇区出占比气泡、再点气泡下钻明细）；分类配色按分类 id 稳定映射，环形图与排行条同色） | 已实现 |
| F11 | 余额校准（生成一笔余额调整交易，不删历史） | 已实现 |
| F12 | 深色模式（跟随系统 + 手动切换） | 已实现 |
| F13 | SQLCipher 加密（条件式） | 已评估，**决定不纳入**（见 `docs/F13-SQLCipher评估.md`） |
| F14 | CSV 导入（同 id 覆盖、新 id 追加，导入前预览确认，坏行跳过并报文件名与行号） | 已实现 |
| F15 | 无障碍与体验打磨（TalkBack 可读、字体缩放、关键操作二次确认） | 已实现 |

P2（记账提醒、常用组合记忆、多账本、账单附件等）为 V2.x 选做，当前不做。

## 环境版本（2026-10-12 定版）

| 组件 | 版本 |
| --- | --- |
| Gradle | 9.8.1 |
| Android Gradle Plugin | 9.4.1（内置 Kotlin） |
| Kotlin | 2.4.21 |
| KSP | 2.3.12 |
| Room | 2.8.5 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| JDK | Android Studio 自带 JBR（`C:\Program Files\Android\Android Studio\jbr`） |
| Android SDK | `C:\Users\nimo\AppData\Local\Android\Sdk`（local.properties，勿提交） |

依赖版本集中管理在 `gradle/libs.versions.toml`；不在 `build.gradle.kts` 里写死版本号。

## 构建与测试（Windows / Git Bash）

```bash
export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
cd /d/git/ledger
./gradlew.bat --console=plain assembleDebug          # 构建 debug APK
./gradlew.bat --console=plain testDebugUnitTest      # JVM 单元测试（Robolectric）
./gradlew.bat --console=plain jacocoTestReport       # 覆盖率报告（HTML/XML）
./gradlew.bat --console=plain lint                   # 静态检查
# 一次跑全绿门禁：
./gradlew.bat --console=plain testDebugUnitTest --rerun jacocoCoverageVerification lint assembleDebug
# 覆盖率门禁：核心分层 LINE ≥ 85~95%、整体 ≥ 60%（阈值可拦人，见 docs/测试报告.md §3）
```

最近一次门禁（2026-10-20 20:15，改名 + 换图标 + 批 A（R1 月份直选 + R2 单月分析）+ 饼图可点（R2 后续）+ 批 B（R3 分类删除引用护栏 / 恢复默认分类 / 记一笔内联新建，见 [`docs/测试报告.md`](docs/测试报告.md) §13）+ 批 C（R4-L1 淡蓝品牌色板 / 移除 Android 12+ 壁纸动态取色 / 容器圆角 16–24dp / 空态小狗插图，见 §14）+ 批 D（R4 图表 10 色品牌板 / 趋势柱双序列 / 记一笔沿用上次分类，见 §15）+ 批 E（统计范围：本月 / 近 N 个月 / 自然年 / 自定义区间，趋势随粒度切换逐日柱与月折线，见 §16）+ UI 一致性收口（设计规范 §11 批 D 的 D-1 ~ D-6：`LedgerListItem` / `LedgerSectionHeader` / `LedgerProgressBar` 组件化、`EmptyState` 三色调与动作槽、`PickerCell` 44dp、账户/分类 FAB 与对话框标题文案分离，见 §17 / §18）+ 下钻返回栈修复与导航转场（统计下钻不再弹掉返回栈，转场按 Tab / 二级页 / 表单分档，见 §19）+ 三条 UI 反馈修复（记一笔输入法遮挡 / 图表 y 轴刻度与点按气泡 / 月底横轴标签过挤，见 §12）+ Tab 切换修复（下钻页卡住底栏切换、统计页点明细进不去，见 §20）+ 明细页筛选条压成一行（设计规范 §11 批 E 的 E-1：筛选按钮 + 横滚条件 chips + 清空同处一行，条高不再随条件数增长，见 §21）+ 记一笔底部保存按钮与金额复核行 + 分类 chip 区限高内滚（设计规范 §11 批 E 的 E-2 / E-3：底部 52dp 主按钮与顶栏共用同一份 `showSave` / `onSave`，金额解析成功即显示「核对：¥X」；分类区限高 168dp、可内滚、选中项自动 `bringIntoView`，见 §22）+ 金额输入全角归一化（§22 真机发现的「中文输入法把句点送成全角」：7 个金额输入框在输入口把全角数字 / 句号 / 逗号 / 负号 / 空格换成半角，`Money.parseCents` 严格口径不动，见 §23）+ 统计页气泡避开 FAB 与选中日格子加深（设计规范 §11 批 E 的 E-5 / E-6：新增 `Modifier.clearOfFloatingActionButton()`，用 `bringIntoView` 把「气泡 + 88dp」整体滚进视口，两个气泡标题行的「查看明细」文字换成 18dp 箭头；逐日柱选中格底色 0.10f → 0.14f，见 §24）+ 字体放大三处竖排（设计规范 §11 批 F 的 F-2 / 问题 G-6：竖排门槛收到 `ui/components/FontScale.kt`，明细筛选条在 ≥1.5 时让 chips 独占下一行、日段头把支出/收入挪到日期下面、预算进度条下行两段文字改竖排，见 §25）+ 接系统「减少动画」偏好（设计规范 §13 AA-7 / 批 F 的 F-4：新增 `rememberAnimationsEnabled()` 读 `animator_duration_scale` 并监听变化，倍率为 0 时四个转场直接返回 `None`，见 §26）+ 分类图标宫格（设计规范 §11 批 F 的 F-3 / 问题 C-6：新建/编辑分类对话框加 17 颗预置 emoji 宫格 + 手输框，`createCategory` / `renameCategory` 透传 `icon`、DAO 只加一条 `UPDATE ... SET icon`，见 §27）+ 13 个功能图标 + 替换 ↑↓ 字符（设计规范 §11 批 F 的 F-1 / 问题 G-7：`LedgerIcons` 补 13 个描边图标（24dp viewport / 2.0f / Round），环比箭头改由 `ArrowUp/Down` 图标画、`ReportFormatting.change` 不再拼 ↑↓，日期字段与账户行接上 `Calendar` / `Wallet` / `Card`，见 §28）+ 预算页点月份标签直接选年月（设计规范 §11 批 E 的 E-8 / 问题 B-4：`BudgetsUiState` 暴露 `month`、`BudgetsViewModel` 补 `setMonth(YearMonth)` 并让 `moveMonth` 委托给它，预算页 `MonthSwitcher` 接上 `onLabelClick` 打开通用 `YearMonthPickerDialog`，见 §29）+ 设置页卡片分组与开发项折叠（设计规范 §11 批 E 的 E-7 / ST-1 / ST-2 / ST-5：设置页收成 账户 / 外观 / 导入与导出 / 关于 四张卡片、页边距 24dp → 16dp，开发者自检进「关于」组、默认折叠、展开才跑，见 §30）+ 统计页「本月洞察」卡片化 + 5 处段头 sticky（设计规范 §11 批 E 的 E-4 / S-5 / S-2：`InsightSection` → `InsightCard`（surfaceContainerLow + 16dp 圆角 + 1dp 内缩分隔线，无总预算时不出预算达成率行），5 个段头改 `stickyHeader`，见 §31）+ 金额列统一 `LedgerAmount`（设计规范 §6.3 / 问题 G-5：明细 / 账户 / 统计三处金额收成一个组件，96dp 起右缘对齐，见 §32）+ 金额朗读中文口语（设计规范 §13 AA-5：`Money.spokenAmount` 把「支出 12 元 3 角 4 分」式文案接到 `LedgerAmount` / `MonthSummaryBar` / 明细日汇总的 `contentDescription`，见 §33）+ 装饰性图标不重复朗读且环比方向入口语（设计规范 §13 AA-3：「上升 较上月 12.5%」，见 §34）+ 表单焦点顺序显式固定（设计规范 §13 AA-6：记一笔 5 个焦点锚点 金额 → 分类 → 账户 → 日期 → 备注 → 保存，保存后不再走，见 §35）+ 图表相邻格独立朗读（设计规范 §13 AA-4：逐日柱每格一个语义节点、TalkBack 不把相邻几天拼成一句，见 §37）+ 金额朗读口径收口（设计规范 §13 AA-5 扩展：图表逐日 / 近 N 月描述、环形图合计都改 `Money.spokenAmount`，统计页带「¥」的读屏节点从 20 个降到 0；预算页金额行也换成 `LedgerAmount`，见 §38）+ 分类行排序收敛成「更多」菜单（问题 C-3：行尾一个「⋮」+ 上移/下移小菜单，2.0 字号下分类名不再被挤压，见 §39）+ 趋势图气泡移到被点柱子的上方（问题 D：柱高按金额比例算落点、夹在绘图区内，见 §40）+ 环形图逐扇区语义（问题 E：每块各有朗读与激活节点，见 §41）+ 批 B 收尾（预算页 B-1 / B-5 / B-6 / B-7，见 §45）+ S-7（日均支出补「按已过 N 天算」，见 §46）+ T-6（日段头补当日笔数，见 §47）、R-6（备注计数提醒色，见 §48）、T-5（筛选条搜索入口，见 §49）、D-5（对话框按钮文案统一，见 §50）、P3-5（关于组品牌徽章，见 §53）、ST-4（主题预览色块，见 §54）后的门禁重跑（`testDebugUnitTest --rerun` + `jacocoCoverageVerification` + `lint` + `assembleDebug` + `assembleRelease`，**未 `clean`**）：**120 套 910 例，0 失败 0 错误 0 跳过**；行覆盖 75.2%、分支 61.6%；lint 5 项、0 error（5 warning / 0 hint）；
`app/build/outputs/apk/debug/app-debug.apk` = 14,740,639 字节（14.05 MiB，计划上限 20MB；2026-10-20 21:32 构建，SHA-256 `2FA866F6…`）；
签名 `app/build/outputs/apk/release/app-release.apk` = 10,185,073 字节（9.71 MiB，2026-10-20 21:32 构建；SHA-256 `5A5F58DE…`）；2026-10-16 已装到测试机，卸载后重装再验（14:18 `Success`），冷启动首次安装 150 / 219 ms、重装后 323 / 204 / 204 ms；§13 那轮（饼图可点 + 批 B）重新构建后 21:54 覆盖安装一次（`Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留），冷启动 185 ms；批 C（R4-L1）重新构建后 23:07 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39），冷启动 215 ms；批 D 重新构建后 2026-10-17 10:14 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39），冷启动 196 ms；批 E 重新构建后 11:08 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；UI 一致性收口（§17）重新构建后 13:28 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致）；D-3 ~ D-6（§18）重新构建后 13:50 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；下钻返回栈修复 + 导航转场（§19）重新构建后 14:07 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致）；Tab 切换修复（§20）重新构建后 16:24 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；批 E · E-1（筛选条单行化，§21）重新构建后 18:47 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；批 E · E-2 / E-3（§22）重新构建后 18:58 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；金额输入全角归一化（§23）重新构建后 19:31 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；统计页气泡避让 + 选中格加深（§24）重新构建后 19:55 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；字体放大三处竖排（§25）重新构建后 20:22 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；接「减少动画」偏好（§26）重新构建后 21:03 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致）；分类图标宫格（§27）重新构建后 21:47 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致）；13 个功能图标（§28）重新构建后 22:00 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致）；批 E · E-8（预算页点月份标签选年月，§29）重新构建后 2026-10-18 17:27 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致），冷启动 189 ms；按 §29.4 走查：点标签 → 选 2026 年 → 选 9 月整页换月（已支出 ¥2,566.00 → ¥0.00）、返回键关弹窗不换月、选回 10 月数据复原、上下月箭头不受影响）；批 E · E-4（统计页洞察卡片化 + 段头 sticky，§31）重新构建后 20:10 再覆盖安装一次（`Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致），按 §31.5 走查统计页 3 张截图（洞察卡 / 分类占比段头钉在汇总条下 / 支出排行与账户分布），点「娱乐」仍能下钻「分类：娱乐」并返回统计页）；金额列统一（§32）重新构建后 20:51 再覆盖安装一次（`Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致），按 §32.4 走查明细 / 统计排行 / 账户三页金额右缘对齐）；金额朗读（§33）重新构建后 21:00 再覆盖安装一次（`Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致），按 §33.4 用 `uiautomator dump` 走查 `contentDescription`（`支出 2566 元` / `结余 负 2566 元` / `支出 788 元` / `余额调整 减少 73 元` / `142 元 5 角 5 分`）；AA-4（图表相邻格独立朗读，§37）重新构建后 21:44 再覆盖安装一次（`Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致），`uiautomator dump` 显示逐日柱 18 个独立语义节点、容器节点一条汇总描述；P3-5（关于组品牌徽章，§53）重新构建后 21:13 再覆盖安装一次（`Success`、`firstInstallTime` 仍为 14:18:39），设置页「关于」组：56dp 圆徽章 + 页脚「记账本 · 0.1.0」。
详细数据见 [`docs/测试报告.md`](docs/测试报告.md)。

首次执行 `gradlew.bat` 会下载一次 Gradle 发行包（约 140MB）。
本机 `tools/gradle-9.8.1/` 下已有解压版可直接用（已 gitignore）。

在 Android Studio 中打开：直接 Open 项目根目录，JDK 选择内嵌 JBR 即可。

## 工程结构

```
app/src/main/java/com/nimo/ledger/
├── LedgerApplication.kt        # Application + 手动 DI 容器（AppContainer）
├── MainActivity.kt             # 单 Activity + Compose 导航宿主
├── data/
│   ├── local/
│   │   ├── entity/Entities.kt  # accounts / categories / transactions / budgets
│   │   ├── dao/                # 四个 DAO
│   │   ├── AppDatabase.kt      # Room 数据库（exportSchema = true）
│   │   ├── SchemaHardening.kt  # 触发器 + 部分唯一索引（数据库层硬约束）
│   │   ├── DefaultCategories.kt# 预置分类（支出11 + 收入7）
│   │   └── Mappers.kt          # Entity <-> 领域模型
│   ├── repository/
│   │   ├── LedgerRepository.kt # 账目唯一数据入口（校验后落库）
│   │   └── BackupRepository.kt # 备份导出/恢复、CSV 导入（含导入前预览）
│   └── backup/                 # 自动备份文件 IO（AutoBackupStore / BackupFileIO）
├── domain/
│   ├── model/                  # 枚举 + 纯 Kotlin 领域模型
│   ├── stats/                  # 统计口径 / 余额 / 预算计算（纯函数，可 JVM 单测）
│   ├── filter/                 # 筛选条件与命中规则
│   ├── validate/               # 账户/分类/预算/交易校验器（第二道防线）
│   └── backup/                 # 备份模型、JSON 编解码、CSV 导出/解析
├── ui/                         # Compose 屏幕 + ViewModel（record/transfer/transactions/
│                               # reports/budgets/accounts/categories/settings/theme/components）
└── util/                       # Money（金额解析/格式化）、Dates
```

测试在 `app/src/test/java/com/nimo/ledger/`，与主源码同包分层；
`app/src/test/java/com/nimo/ledger/docs/SampleDataFilesTest.kt` 负责校验 `docs/示例数据/` 不被改坏。
Room schema JSON 在 `app/schemas/`，必须随 Git 提交（未来迁移测试的基础）。

## 数据与备份

数据库只有一个：应用私有目录下的 `ledger.db`（Room v1，`allowBackup="false"`，不参与系统云备份）。

| 操作 | 位置 | 行为 |
| --- | --- | --- |
| 导出 JSON | 设置 → 导出与导入 | 一份完整备份（账户/分类/交易/预算），金额为整数分 |
| 导入 JSON | 设置 → 导入 JSON（覆盖替换） | **整机数据被它替换**；替换前自动写一份自动备份，可回退 |
| 导出 CSV | 设置 → 导出 CSV（4 个文件） | `accounts/categories/transactions/budgets.csv`，UTF-8-BOM + CRLF，金额为元、两位小数，Excel 直开 |
| 导入 CSV | 设置 → 导入 CSV（增量合并） | 选中放着 4 个 CSV 的文件夹；**先预览「将写入多少行/跳过多少行」，确认后才写库**；同 id 覆盖、新 id 追加，本机独有数据不删；坏行跳过并报文件名与行号 |

**换手机迁移**：旧机「导出 JSON 完整备份」→ 把文件拷到新机 → 新机「导入 JSON（覆盖替换）」。
或直接拷 `ledger.db` 文件到新机应用私有目录（同 applicationId、同 schema 版本）。

> 安装标识与源码命名空间都是 `com.nimo.ledger`（2026-10-16 发布定版时从 `com.nimo.bookkeeping` 一起改来），
> 所以 `R`/`BuildConfig` 与安装包名一致；发布与签名细节见 [`docs/发布与签名.md`](docs/发布与签名.md)。

**想先试用再录入**：[`docs/示例数据/`](docs/示例数据/README.md) 下有一份合成脱敏的完整示例（1 份 JSON + 4 份 CSV），用上面的导入流程即可装进来。

## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/测试报告.md`](docs/测试报告.md) | 门禁结果、908 例分布、真机 10,000 笔验收、JaCoCo 覆盖率、性能实测、打包体积、数据安全、未覆盖项 |
| [`docs/F13-SQLCipher评估.md`](docs/F13-SQLCipher评估.md) | SQLCipher 兼容性/密钥恢复/迁移/体积评估与「不纳入」决策记录 |
| [`docs/示例数据/README.md`](docs/示例数据/README.md) | 示例备份与 CSV 的格式约定、导入用法 |
| [`docs/发布与签名.md`](docs/发布与签名.md) | 安装标识与版本规则、发布签名流程、密钥库备份与丢失后果 |

## 关键设计决策（改代码前必读）

1. **金额**：一律 `Long` 分。解析输入先校验再转换，禁止 Double 中转。
2. **余额**：`BalanceCalculator` 唯一算法，实时聚合，不落库。余额校准 = 一笔
   `BALANCE_ADJUST` 交易（可正可负，绑定账户）。
3. **统计**：`StatsCalculator` 唯一口径。收入/支出用类型白名单
   （只认 INCOME/EXPENSE），转账与校准永不进入。
4. **约束三道防线**：UI 校验 → `TransactionValidator` → 数据库触发器
   （`SchemaHardening`）。任何 Migration 后必须重新调用 `SchemaHardening.apply()`。
5. **Room schema**：`app/schemas/` 随 Git 提交，是未来迁移测试的基础。
6. **排序**：所有流水查询 `ORDER BY occurredOn DESC, id DESC`（同日稳定）。
7. **月度查询**：`occurredOn >= from AND occurredOn < to` 范围比较，命中索引。
8. **Room `@Upsert` 陷阱**：非主键约束冲突会被 Room 转成按主键 UPDATE（0 行 = 静默无操作）；
   验证约束要用 raw SQL，写路径需感知失败时用 `@Insert`/`@Update`。
9. **UI 不直接访问 DAO**：一律经 Repository；转账在数据库事务内完成。

## 当前状态

- [x] 阶段 0：工程脚手架
- [x] 阶段 1：数据库 + DAO + Repository + 统计/余额纯函数 + JVM 单测
- [x] 阶段 2：记账闭环 UI（记一笔 / 转账 / 流水列表）
- [x] 阶段 3：月度报表与筛选搜索
- [x] 阶段 4：预算、图表、深色模式、余额校准
- [x] 阶段 5：性能实测、无障碍打磨、隐私权限审计、测试报告、示例数据
- [x] 签名 release APK：`keystore/ledger-release.jks`（已 `.gitignore`）→ `assembleRelease` → `apksigner` 验签通过；流程见 [`docs/发布与签名.md`](docs/发布与签名.md)
- [x] 批 B（R3）分类治理 + 分类环形图可点（2026-10-16 晚）：删除引用护栏（有子分类/流水/预算则禁用并提示改用「停用」）、一键「恢复默认分类」补种（零缺失不写库）、记一笔内联新建分类并自动选中、环形图「点扇区出占比气泡 → 点气泡下钻明细」；均已在签名 release 包（真实账本）真机复验，证据见 `docs/测试报告.md` §13
- [x] 批 C（R4-L1 淡蓝品牌皮肤，2026-10-16 深夜）：主题从 Android 12+ 壁纸动态取色改为静态品牌色板（品牌色 `#79C9FC` 取自应用图标主色，浅/深两套 WCAG 对比度逐对断言）、容器圆角统一 16–24dp（新增形状 token）、13 处空态加小狗插图（复用图标前景 PNG，装饰性 `contentDescription = null`）；均已在签名 release 包（真实账本）真机复验，证据见 `docs/测试报告.md` §14
- [x] 批 D（R4 图表品牌化 + 记一笔沿用上次分类，2026-10-17）：环形图与排行条改用 10 色品牌板（浅深共用一套、按 `categoryId` 稳定映射，同分类跨主题同色）、扇形加 2dp 色缝；深色趋势柱由中性灰改为双序列语义色（支出 `#FFB4AB` / 收入 `#79C9FC`，效果图里「今天」= 18% 品牌蓝光环 + 柱下短杠）；记一笔进页按当前收支方向预选上次用过的分类并提示「N 天前用过「X」」（编辑旧账不预选）；门禁 84 套 708 例、真机 8 项走查净效果为零，证据见 `docs/测试报告.md` §15
- [x] 批 E（统计范围，2026-10-17）：统计页时间口径从「一个月」升级为「一段范围」——顶部标签统管本月 / 近3、6、12个月 / 今年 / 去年 / 指定月份 / 自定义区间；新增 `ReportRange` 领域模型（半开区间、`previous()` 按整段挪不重叠）与 `ReportRangeDialog`；趋势图随粒度切换「≤62 天逐日柱（点柱看当日明细）/ 更长范围月度折线」；预算行只在单月显示；环比文案与环形图读屏描述随范围改口（真机走查发现并修复 3 处口径 bug）；门禁 85 套 733 例、真机 9 项走查净效果为零，证据见 `docs/测试报告.md` §16
- [x] UI 一致性收口（设计规范 §11 批 D 的 D-1 ~ D-6，2026-10-17）：新增 `LedgerListItem`（单行 ≥56dp / 双行 ≥72dp、leading 固定 40dp 列、`chevron` 用矢量图标取代「›」字符、末行不画分隔线）、`LedgerSectionHeader`（`surfaceVariant` 通栏段头）与 `LedgerProgressBar`（fraction 可超 1，超支压到 60% 高并在 100% 处留 2dp 刻度，NaN 按 0 处理），替换设置页三行入口、账户行、分类行与段头、预算段头、统计页 6 处段头、明细日段头、预算进度条与排行占比条；`EmptyState` 增加 `EmptyTone { NEUTRAL, FILTERED, ERROR }` 与 `actionLabel` / `onAction`（插图 120dp / 96dp 两档、间距 16dp + 8dp），7 处加载失败挂 ERROR、筛选无结果挂 FILTERED；`PickerCell` 40→44dp；账户/分类对话框标题改为常量「账户信息」/「分类信息」、FAB 仍用「新建账户」/「新建分类」做 `contentDescription`；本批新补 16 例组件级测试（进度条 6、空态由 3 扩到 8、月选择器 44dp 1、标题源码守门 4）；门禁 89 套 764 例、行覆盖 70.5%、真机 8 项走查净效果为零，证据见 `docs/测试报告.md` §18
- [x] 下钻返回栈修复 + 导航转场（2026-10-17）：统计页点分类/账户/当日明细不再把返回栈弹到只剩起始页（原来系统返回会直接退出应用），改为压在统计页之上的 `transactions/drill` 目的地（`launchSingleTop`，条件经 `DrillDownRequests` 单次脉冲传入，不污染「明细」Tab 的筛选状态）；新增 `LedgerNavMotion` 按页面类型分档转场——Tab 之间 `FADE`、层级进出 `SLIDE_HORIZONTAL`、表单进出 `SLIDE_VERTICAL`（220 / 180 ms，取代 navigation-compose 默认 700 ms 无方向淡入淡出）；路由常量收敛到 `LedgerRoutes`；新增 15 例测试（转场判据 11 + 真 `NavHost` 返回栈 4）；门禁 91 套 779 例、行覆盖 70.1%，真机 8 项走查（含 `animator_duration_scale = 10` 的转场中间帧）净效果为零，证据见 `docs/测试报告.md` §19
- [x] Tab 切换修复（2026-10-17）：用户在统计页下钻后点底栏「统计」没反应——旧实现 `navigate(route){ popUpTo(起始页){ saveState }; restoreState }` 会把 `[起始页, 统计页, 下钻页]` 以起始目的地为键整段存下又整段恢复，栈顶仍是下钻页；修复收敛成 `NavHostController.switchTab(route)`（`ui/LedgerRoutes.kt`）——先丢掉一次性下钻页，目标若是起始 Tab 直接 `popBackStack(route, inclusive = false, saveState = true)`，否则沿用 `popUpTo + saveState/restoreState`。顺带修掉改动前就存在的「统计页点明细进不去」（同一根因）；新增 6 例真 `NavHost` 用例（`LedgerDrillDownNavigationTest` 4 → 10），红绿：把起始分支改回 `navigate` 时 2 例 `ComparisonFailure` 必红；门禁 91 套 785 例、行覆盖 70.2%，真机 9 项走查（主修复 + 明细往返 + 设置往返 + 返回键 + 净效果为零）净效果为零，证据见 `docs/测试报告.md` §20
- [x] 明细页筛选条压成一行（设计规范 §11 批 E 的 E-1，2026-10-17）：`FilterBar` 从「筛选按钮 + 竖排条件文字列 + 清空」改成单行——左「筛选」按钮、中间 `LazyRow` 横滚的条件 chips（12dp 圆角 `surfaceContainerHigh`、`labelSmall`、单行不省略）、行尾「清空」；无条件时整条只剩按钮。旧实现 3 条条件 56dp、6 条 104dp（每条 +16dp），新实现 1 条与 6 条等高（≤52dp）——真机无条件时首行与 2 个聚合 chip 时首行 y 坐标都是 826px，字体缩放 1.5 时也不增高。新增 7 例组件级测试（高度与条件数无关 / 横滚可达最后一条 / 单条只占一行 / 清空与打开回调）；门禁 92 套 792 例、行覆盖 70.4%，真机 9 项走查（含横滚、清空、1.5 倍字体）净效果为零，顺带修正了「首屏增加 ≥60dp」这条不成立的验收口径，证据见 `docs/测试报告.md` §21
- [x] 记一笔底部保存 + 金额复核行 + 分类区限高（设计规范 §11 批 E 的 E-2 / E-3，2026-10-17）：`RecordScreen` 把 `showSave` / `onSave` 各收敛成一份，顶栏「保存」与新增的 `Scaffold.bottomBar` → `RecordSaveBar`（52dp 主按钮，键盘弹出时在 IME 之上）共用；金额框下方解析成功即显示「核对：¥1,234.56」（12dp 间距，非法输入不显示）；分类 chip 区 `heightIn(max = 168.dp) + verticalScroll` 限高，选中项 `bringIntoView` 自动滚进可视区。红绿：删限高那例报「分类区高度 2673 px 应正好被限在 504 px（168dp）」，删 `bringIntoView` 那例报「分类199 … is not displayed」；门禁 93 套 800 例、行覆盖 70.6%，真机 10 项走查（含底部保存真写入后删除还原、fontScale 2.0 下限高与内滚、账户/日期不动）净效果为零，证据见 `docs/测试报告.md` §22
- [x] 金额输入全角归一化（2026-10-17）：§22 真机走查发现「中文输入法把句点送成全角『。』→ `Money.parseCents` 返回 null → 复核行不出现、保存走格式错误」。修法是在输入口归一化而不是放宽解析——新增 `Money.normalizeAmountInput`（全角数字 `０-９`、`。`/`．`/`｡`、`，`、`－`/`−`/`﹣`、全角空格 `U+3000` → 半角），接线 7 个金额输入框（记一笔 / 转账经 `::setAmount`，账户编辑 / 余额校准 / 预算编辑 / 明细筛选上下限就地调），`parseCents` 与 CSV/JSON 导入路径保持严格。新增 8 例测试（3 例函数级 + 2 例 ViewModel 接线 + 3 例源码级接线守门 `AmountInputSourceTest`）；红绿：把 `setAmount` 改回 `amountText = text` 时 `ComparisonFailure: expected:<123.45> but was:<１２３。４５>` 与 `必须原地归一化 expected:<1> but was:<0>` 两例必红。门禁 94 套 808 例、行覆盖 70.6%、分支 59.6%，真机 10 项走查（记一笔逐字符输入 PERIOD → 字段 `123.45`、复核行 `核对：¥123.45`、保存后删除还原、账户初始余额框 `1.5`、取消不动数据）净效果为零，证据见 `docs/测试报告.md` §23
- [x] 统计页气泡避开「记一笔」FAB + 选中日格子加深（设计规范 §11 批 E 的 E-5 / E-6，2026-10-17）：新增 `Modifier.clearOfFloatingActionButton()`（`ui/reports/TooltipFabClearance.kt`）——`BringIntoViewRequester` 把「气泡自身高度 + 88dp 保留区」当成一个矩形请求滚进视口，`DonutTooltip` / `DayTooltip` 各挂一次；两个气泡标题行右端的「查看明细」文字换成 18dp `LedgerIcons.ChevronRight`（`contentDescription` 仍是「查看明细」，语义合并后读屏照样念）；逐日柱选中格底色从 `primary.copy(alpha = 0.10f)` 提到 0.14f 并抽成 `SELECTED_DAY_HIGHLIGHT_ALPHA` / `dayCellHighlight(primary)`。新增 5 例测试（像素级避让 4 + 透明度 1）；红绿：删气泡修饰符报「等待超时（60000ms）：条件始终没成立」、常量改回 0.10f 报 `expected:<…0.14117648…> but was:<…0.101960786…>`；门禁 95 套 813 例、行覆盖 70.7%，真机 10 项走查（气泡底边 2495 + 88dp = 内容区底边 2825、比 FAB 顶边 2555 高 16dp；点最后一天弹出日明细；选中格像素 (32,45,56) vs 邻居 (15,20,24)）净效果为零，证据见 `docs/测试报告.md` §24
- [x] 字体放大到 1.5 倍以上时三处横排改竖排（设计规范 §11 批 F 的 F-2 / 问题 G-6，2026-10-17）：竖排门槛从 `MonthSummaryBar` 的私有常量收到 `ui/components/FontScale.kt`（`STACK_FONT_SCALE = 1.5f` + `stackAtLargeFont(fontScale)`，`stackSummaryCells` 改为委托），三处各读 `LocalDensity.current.fontScale` 换布局——明细筛选条 ≥1.5 时「筛选 + 清空」一行、chips 独占下一行（顺带修掉 E-1 遗留的「1.5 倍下 chip 被 LazyRow 从字形中间裁断」）、`LedgerSectionHeader` 新增 `stack` 参数让日段头把「支出/收入」挪到日期下面、预算进度条的「已用 ¥x / ¥y」与状态标签改竖排。新增 9 例测试（门槛 2 + 段头 1 + 筛选条 2 + 日段头 2 + 预算行 2，字体倍数用 `CompositionLocalProvider(LocalDensity provides Density(density, fontScale))` 覆盖）；红绿：把三处 `val stack = ...` 改成 `false` 后 3 例必红（`buttonBottom=160.0 chipsTop=32.0` / `dateBottom=130.0 totalTop=26.0` / `usedBottom=132.0 statusTop=36.0`），还原后 23 例全绿；门禁 98 套 822 例、行覆盖 71.2%，真机 3 档字体走查（1.0 / 1.5 / 2.0，含 1.5·2.0 下筛选条、日段头、预算行共 3 处、以及临时设 500 元预算再删除的还原核对）净效果为零，证据见 `docs/测试报告.md` §25
- [x] 接系统「减少动画」偏好（设计规范 §13 AA-7 / 批 F 的 F-4，2026-10-17）：新增 `ui/MotionPreference.kt`（`animationsEnabled(scale) = scale > 0f` 纯函数 + `readAnimatorScale` + `rememberAnimationsEnabled()` 读 `Settings.Global.ANIMATOR_DURATION_SCALE` 并注册 `ContentObserver`，设置里一开一关立刻生效）；`LedgerNavMotion` 四个转场各加 `animated` 参数，为 false 时直接返回 `None`（0 时长的位移仍会跳一下，所以连位移都不做）；`LedgerApp` 在 `NavHost` 前读一次、四个方向都传。新增 7 例测试（倍率阈值 2 + 读设置 1 + 组合内读值 2 + 四个转场全为 `None` 1 + 反向不为 `None` 1）；红绿：删四处守卫后 `turningAnimationsOff_*` 报 `expected:<EnterTransition.None> but was:<EnterTransition: Fade - Fade(alpha=0.0, animationSpec=…TweenSpec…)>`，还原后 7 例全绿；门禁 99 套 829 例、行覆盖 71.2%，真机做**正对照**（倍率 10，帧间差 25.87% / 34.23%，证明拍法对动画敏感）与**负对照**（倍率 0，帧间差 0.00%、三张截图字节数完全相同），两次都校验了「点前在明细、点后在统计」，证据见 `docs/测试报告.md` §26
- [x] 分类图标宫格（设计规范 §11 批 F 的 F-3 / 问题 C-6，2026-10-17）：`icon` 字段一直在数据层（默认分类都有 emoji、时间线也用它与 `leadingEmoji` 渲染），但新建/编辑分类的对话框**从来没有收集过它**，新建的分类永远只有 `•`。本批新增 `CategoryIconPicker`（17 颗预置 emoji 宫格，点一颗选中、再点取消 + 「或手动输入 emoji」输入框，输入按码点裁剪、不切代理对）；`createCategory(..., icon)` / `renameCategory(id, name, icon)`（null 保留 / 空串清除 / 非空替换三态）与 DAO 的 `UPDATE categories SET icon` 一路透传；`DefaultCategories.presetIcons()` 与宫格共用同一套。新增 17 例测试（仓库 6 + 宫格集合与裁剪 6 + 组件交互 5）；红绿：仓库两处 icon 写入与「再点取消」回退后 4 例必红（`expected:<☕> but was:<null>` 等），还原后 17 例全绿，并写明 `renamingWithABlankIcon_clearsIt` 在红态仍绿的盲区；门禁 102 套 846 例、行覆盖 71.4%，真机 5 项走查（新建分类宫格 17 颗排三行、编辑「娱乐」预填 🎮、点 🏆 保存后列表显示 `🏆 娱乐`、再改回 🎮 还原、账本 ¥2,566.00 未变），证据见 `docs/测试报告.md` §27
- [x] 13 个功能图标 + 替换 ↑↓ 字符（设计规范 §11 批 F 的 F-1 / 问题 G-7，2026-10-17）：`LedgerIcons` 补 `Edit` / `Delete` / `Check` / `Close` / `Calendar` / `Search` / `Wallet` / `Card` / `Alert` / `Info` / `Download` / `Upload` / `Lock`（统一走 `strokeIcon()`：24dp viewport、2.0f、StrokeCap/Join.Round，老图标 2.2f 不动）；环比方向改成 `ReportFormatting.changeDirection()` + `ArrowUp/Down` 图标（16dp，`contentDescription` 上升/下降），`change()` 不再拼 ↑↓；记一笔日期字段加 `Calendar`（20dp）、账户行加 `Wallet`/`Card`（24dp，信用卡用 Card）。新增 7 例测试（图标 5 + 方向与「文本不含箭头」2）并改 7 处旧断言；红绿：线宽改 2.5f 与把箭头加回文案后 8 例必红（`Edit 的线宽应是 2.0f expected:<2.0> but was:<2.5>`、`expected:<较上月 []12.5%> but was:<较上月 [↑]12.5%>` 等），还原后全绿；门禁 103 套 853 例、行覆盖 71.7%，真机三档走查（16dp 箭头 `上升 [60,1001][120,1061]`、20dp 日历、24dp 账户图标，为造出环比临时记 ¥100 后即删并核对余额复原），证据见 `docs/测试报告.md` §28
- [x] 预算页点月份标签直接选年月（设计规范 §11 批 E 的 E-8 / 问题 B-4，2026-10-18）：预算页是全应用唯一还把月份标签做成"死"的页面（明细页、统计页早已接入 `YearMonthPickerDialog`）。本轮 `BudgetsUiState` 暴露 `month`、新增 `setMonth(YearMonth)`（先清 `loadError` 再切流），`moveMonth(delta)` 改为 `setMonth(month.value.plusMonths(delta))`，`BudgetsScreen` 给 `MonthSwitcher` 传 `onLabelClick` 打开通用年月弹窗——直选与上下月共用同一条数据流；E-8 的另一半（B-2 超支刻度）在 D-3 已由 `LedgerProgressBar(overMarker = true)` 落地，本轮复核仍在位。新增 2 例测试（ViewModel 跨年直跳 5 个月 1 例 + Robolectric 接线 1 例，后者种两个月金额不同的总预算，只有整页真的换月金额才会跟着换）；红绿：拆 `onLabelClick` → 接线例 `AssertionError at BudgetsScreenMonthPickerTest.kt:97`（13 例 1 失败），`setMonth` 不切月 → 3 例红（含既有 `moveMonth` 两例）；门禁 104 套 855 例、行覆盖 72.8%、分支 60.4%（E-8 门禁时点的数字）；**真机走查已完成**（2026-10-18 17:27，release 包），证据见 `docs/测试报告.md` §29
- [x] 设置页卡片分组 + 开发项折叠（设计规范 §11 批 E 的 E-7 / ST-1 / ST-2 / ST-5，2026-10-18）：设置页原来是「横向分隔线 + 24dp 边距」的一长条，和全站的 16dp 不一致（G-10）；本轮新增 `ui/settings/SettingsCards.kt`（组标题 / 卡片 / 卡片组 / 主题单选项 / 「关于」内容 / 「关于」组六个组合），`SettingsScreen` 重写成 账户 / 外观 / 导入与导出 / 关于 四张 `surfaceContainerLow` 卡片（16dp 圆角、组间 24dp、页边距收到 16dp）；开发者自检从页面底部搬到「关于」组、默认折叠（`rememberSaveable`），展开才 `LaunchedEffect` 跑 `selfCheck()`，结果没回来时显示「自检运行中…」；版本行留在组内末行、只写「记账本 0.1.0」（ST-2：里程碑不进界面）。不做 `BuildConfig.DEBUG` 门控——验收要求「自检入口仍可用」，真机装的是 release 包。新增 5 例组件测试（`SettingsAboutSectionTest`：默认折叠不渲染输出 / 版本行无里程碑 / 点开再点折回 / 未跑时占位 / 每次点击回调一次）；红绿：展开门控写死成 `if (true)` + 版本行塞「已完成 M1（数据层）」→ 3 例红（`SettingsAboutSectionTest.kt:58` / `:67` / `:93`），还原后 5 例全绿；门禁 105 套 860 例、行覆盖 73.0%、分支 60.5%；真机 7 张截图（四组卡片与 16dp 边距、默认折叠、展开读出 user_version v1 / 触发器 4/4 ✓ / 索引已生效 ✓ / 账户 2 / 分类 18 / 交易 8 / M0 通过、再点折回、主题三选一切换与复原），证据见 `docs/测试报告.md` §30；2.0 字体补充见 §31.8
- [x] 账户页收尾（设计规范 §11 批 A 的 A-2 / A-3 / A-4 / A-5 / A-6，2026-10-19）：净资产卡片、已归档折叠、类型图标核对、校准读屏名、空态直达「新建账户」；门禁 112 套 889 例、行覆盖 74.1%、lint 0 error；真机截图 `a-01-accounts-card.png`；证据见 `docs/测试报告.md` §44
- [x] 预算页收尾（设计规范 §11 批 B 的 B-1 / B-5 / B-6 / B-7，2026-10-19）：总预算卡与分类行合并成 BudgetLine、分类行尾 ChevronRight、顶部「全部 / 已设 / 未设」筛选（默认全部、不持久化）、页脚口径说明前加 Info 图标；门禁 113 套 891 例、行覆盖 74.7%、分支 61.4%、lint 0 error；真机截图 -01 ~ -06；证据见 [docs/测试报告.md](docs/测试报告.md) §45
- [x] 统计页「本月洞察」卡片化 + 段头 sticky（设计规范 §11 批 E 的 E-4 / S-5 / S-2，2026-10-18）：统计页原来 5 个段头都是普通 `item`，往下找「支出排行」要滑很久，且「本月洞察」的 3~4 行裸奔在页面上没有分组。本轮把 `InsightSection` 改成 `InsightCard`——surfaceContainerLow + 16dp 圆角卡片（`testTag("reports-insight-card")`），行间从无分隔改成 4dp 空 + 1dp 内缩分隔线（`InsightDivider`，线两端缩进 16dp），无总预算时不出现「预算达成率」行；5 处段头（洞察 / 趋势 / 环图 / 支出排行 / 账户分布）改成 `stickyHeader`，空范围分支的趋势段头仍是 `item`。新增 3 例组件测试（卡内 4 行 / 无预算时不出达成率且仍是卡 / 滚到「账户支出分布」后「支出排行」仍 displayed）；红绿：段头改回 `item` → `AssertionError at ReportsInsightCardUiTest.kt:149`（3 例 1 失败），删卡片 `testTag` → `:117` / `:133` 两例红，还原后 3 例全绿；门禁 106 套 863 例、行覆盖 73.1%、分支 60.6%；真机 3 张截图（洞察卡 / 分类占比段头钉住 / 支出排行与账户分布），点「娱乐」仍能下钻并返回，证据见 `docs/测试报告.md` §31；1.5 / 2.0 字体补充见 §31.8；浅色 / 空月份 / 横屏见 §31.9
- [x] 日段头补「N 笔」（设计规范 §12 的 T-6，2026-10-19）：明细页每天的小标题右侧补「· N 笔」（N = 当天流水条数，转账 / 余额调整也算），大字竖排时笔数另起一行；`TransactionsDayHeaderTest` 新增 2 例（可见性与几何位置），注释掉 `DayCountText` 即红；门禁 119 套 908 例、行覆盖 74.7%、分支 61.4%，证据见 `docs/测试报告.md` §47。
- [x] 日均支出说出分母（设计规范 §13 的 S-7，2026-10-19）：统计页「本月洞察」的日均行下面补一行「按已过 N 天算」，N 直接取 `ReportsUiState.elapsedDays`（← `StatsCalculator.elapsedDays`，当月按今天几号、已结束月份按整月），页面不自算；新增 `ReportsDailyAverageHintTest` 1 例（断言 N 与统计口径一致且只出现一次），红绿把 N+1 改出来即红；门禁 114 套 892 例、行覆盖 74.7%、分支 61.4%，证据见 `docs/测试报告.md` §46。
- [x] 备注计数接近上限时变色（设计规范 §12 的 R-6，2026-10-19）：记一笔备注的 `N/200` 在 **≥180** 时改用 `tertiary` 暖色，阈值抽成 `noteCounterNearLimit(length)` 纯函数（3 例单测覆盖 0/179/180/200 边界），见 `docs/测试报告.md` §48。
- [x] 明细页筛选条加搜索入口（设计规范 §12 的 T-5，2026-10-19）：筛选条左侧 🔍（读屏名「搜索」），点它打开筛选面板并让「备注关键词」框直接拿焦点、自动弹键盘；点「筛选」按钮行为不变。2 例单测锁入口回调，真机 `uiautomator dump` 证明关键词框 `focused=true` + `mInputShown=true`，见 `docs/测试报告.md` §49。
- [x] 对话框按钮文案统一（设计规范 §8 的 D-5，2026-10-19）：有持久化效果用「保存」、纯选择用「确定」，都配「取消」——余额校准「校准」→「保存」、明细筛选与统计范围「应用」→「确定」；带输入框的 AlertDialog 在 Robolectric 下永不 idle，这项没有自动化测试，靠真机三张截图，见 `docs/测试报告.md` §50。
- [x] 关于组品牌徽章（设计规范 §5.2 可选动作 / P3-5，2026-10-20）：设置页「关于」组顶部 56dp 遮罩圆 + 84dp `ic_launcher_foreground.png`（与桌面图标同一份资产、底色 #79C9FC）、`contentDescription = "记账本"`；版本行移出卡片改页脚「记账本 · 0.1.0」。首版手绘线条矢量经用户反馈「不像应用图标」后弃用并删除。单测 6 例，红绿：摘 `testTag` + 置空描述 → 2 例红（`:113` / `:71`）；门禁 119 套 909 例、行覆盖 74.9%、`ui.settings` 26.6%；真机截图 `p35-02-about-icon.png`，证据见 `docs/测试报告.md` §53
- [x] 主题三选一加预览色块（设计规范 §8 的 ST-4，2026-10-20）：外观三行左侧各 24dp 圆（跟随系统=左深右浅 / 浅色 #F5FAFF / 深色 #0E1418，1dp outline），色块纯装饰不朗读。单测 1 例，红绿：删色块调用 → `ThemePreviewSwatchTest.kt:35` 红；门禁 120 套 910 例、行覆盖 75.2%、`ui.settings` 33.8%；真机截图 `st4-01-theme-swatches.png`，证据见 `docs/测试报告.md` §54
- [x] 加载态改骨架占位（设计规范 §13 的 G-9，2026-10-19）：新增 `SkeletonBlock`/`SkeletonList`（静态灰块，无动画——无限动画会让 Compose 测试永不 idle），明细 / 统计 / 账户 / 分类 / 预算五个内容区与当月合计三格全部改骨架，转圈只留记账、转账表单；JVM 3 例锁「三格 3 块 / 列表按行数」，冷启动太快没截到真机骨架，见 `docs/测试报告.md` §51。
- [x] 自然年窄屏趋势图抬高（设计规范 §13 的 S-6，2026-10-20）：`ReportRange.CalendarYear` 且屏宽 < 360dp 时折线区 160dp → 180dp，其余不变；纯函数 4 例锁阈值（360dp 边界算宽屏）+ 组件 2 例锁「值真的传到画布」；density override 在真机上没能触发该分支，窄屏视觉仍待真机，见 `docs/测试报告.md` §52。
- [x] 金额列统一 `LedgerAmount`（设计规范 §6.3 / 问题 G-5，2026-10-18）：明细 / 账户 / 统计三处原来各写一套金额 `Text`（字号不同、短金额右缘内缩、红蓝中性色各抄一遍）；本轮新增 `ui/components/LedgerAmount.kt`（`type` 为空时中性色、`showSign` 控制收支符号、`widthIn(min = 96.dp)` + 右对齐 + 单行不换行，文本算法拆成 `ledgerAmountText()` 纯函数），明细行删掉本地 `amountColor()`、账户行余额改走 `trailingContent`、统计的日均与排行金额一并换上。新增 7 例测试（符号口径 5 + 最小宽/可见节点 2）；红绿：删 `widthIn(min = …)` → 2 例红（`AssertionError at LedgerAmountTest.kt:76` / `:93`），还原后 7 例全绿；门禁 107 套 870 例、行覆盖 73.1%、分支 60.6%，真机 3 张截图（明细 5 笔金额同一右缘、统计排行 `¥977.00 … ¥2.00` 对齐、账户管理两账户与净资产对齐），证据见 `docs/测试报告.md` §32
- [x] 金额朗读中文口语（设计规范 §13 AA-5，2026-10-18）：`Money.spokenAmount()` 把分译成「元 / 角 / 分」口语（`12_345` → `123 元 4 角 5 分`，不带 ¥、千分位、小数点），`LedgerAmount` 挂 `contentDescription = ledgerAmountSpeech(cents, type)`（支出 / 收入 / 转账 / 余额调整 增加|减少），月份汇总条三格与明细日段头各自补语义；真机 `uiautomator dump` 读出 `支出 2566 元` / `结余 负 2566 元` / `余额调整 减少 73 元` / `142 元 5 角 5 分`，见 `docs/测试报告.md` §33
- [x] 装饰性图标不重复朗读 + 环比方向入口语（设计规范 §13 AA-3，2026-10-18）：底栏三个图标原来把「明细 / 统计 / 设置」也写进 `contentDescription`，而 `NavigationBarItem` 的 `label` 已经暴露同样的词，读屏会念两遍——现在图标置 `null`；统计页环比箭头是纯图形（方向只靠图标 + 颜色两道视觉通道），直接删描述会让读屏丢掉涨跌，所以箭头置 `null`、方向折进一句话：`ReportFormatting.changeSpeech` 产出「上升 较上月 12.5%」，持平 / 无数据不给方向词（与箭头同一 0.05% 门槛）。证据见 `docs/测试报告.md` §34（含 dump 后的底栏语义树与红绿记录）
- [x] 表单焦点顺序显式固定（设计规范 §13 AA-6，2026-10-18）：记一笔的金额 → 分类 → 账户 → 日期 → 备注 → 保存用 `focusProperties` 显式钉住（`RecordSaveBar` 之后接 `FocusRequester.Cancel`，不再往后跳），分类 / 账户为空时锚点自动回退到下一个真字段；顺序由 `RecordFocusOrderTest` 2 例锁住（红绿：删首 chip 链 1 例红于 `RecordFocusOrderTest.kt:81`），设备用键盘 TAB 走查（键盘收起、焦点离开金额），真机 TalkBack 实听仍待做——见 `docs/测试报告.md` §35
- [x] 200% 文本缩放逐屏断言（设计规范 §10.5 / 无障碍 AA-8，2026-10-18）：新增 `PerScreenLargeFontUiTest` 3 例（明细 / 统计 / 账户整屏在 `font_scale=2.0` 下渲染）与 `YearMonthPickerGridTest` 2 例（≥1.5 字号年份格降 3 列）；顺带修掉统计页段头 2.0 下 title/hint 挤同一行、年月格把「2028」静默截成「202」；真机 2.0 逐屏截图 `aa8-01`~`aa8-10`，分类页标题折行仍未收敛（C-3，见 §36.5）
- [x] 图表相邻格独立朗读（设计规范 §13 AA-4，2026-10-18）：逐日柱每格一个独立语义节点（`clickable` 自带合并边界、容器不 `mergeDescendants`），`TrendChartUiTest` 新增断言锁住「相邻几天不会被拼成一句」；真机 `uiautomator dump` 18 个独立节点 + 容器一条汇总，见 [`docs/测试报告.md`](docs/测试报告.md) §37
- [x] 签名包真机验证（2026-10-16）：装到测试机（`firstInstallTime 2026-10-16 10:20:18`、`versionName 1.0`、签名 v2），冷启动 219 / 150 ms，「应用信息」页显示新图标，启动日志无 `FATAL EXCEPTION`；14:18 卸载后重装 `Success`、冷启动 323 / 204 / 204 ms、首启播种默认分类可见

## 已知限制

- release 未启用 minify，混淆规则待补充（当前靠 debug/release 同源构建，无额外收缩）
- 旧安装标识 `com.nimo.bookkeeping` 的包与本包（`com.nimo.ledger`）是两个应用，不能互相覆盖升级；旧包数据要迁移须先「导出 JSON」再在新包「导入 JSON（覆盖替换）」
- 签名密钥库与口令只在本机、不在版本库（`.gitignore`）；使用者已自行完成异地备份（2026-10-16 确认），仓库不保存备份
- 测试机 MIUI 上 `adb install` 需要先开「开发者选项 → 通过 USB 安装」，否则报 `Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]`（屏幕无确认框）；2026-10-16 14:18 开启该开关后重装 `Success`，验证通过
- 应用图标为自适应图标（浅蓝底 + 白狗）：前景 `drawable-nodpi/ic_launcher_foreground.png`、背景 `drawable/ic_launcher_background.xml`（`#79C9FC`）、单色层 `drawable-nodpi/ic_launcher_monochrome.png`；平面主图 [`docs/icon-512.png`](docs/icon-512.png)
- 数据库仍为 v1，暂无 Migration 对象（v1→v2 迁移验证待有 v2 后进行）
- 真机 1 万笔性能已实测（2026-10-16：10,000 笔种子库冷启动 610 / 593 / 595 ms，页面汇总与库真值逐分一致；见 docs/测试报告.md §4.2）；Android 8.0（API 26）低版本真机验证已按用户决定移出验收范围（2026-10-16），`minSdk 26` 声明保留
- UI 屏幕层覆盖率偏低（SAF/系统对话框不可单测），详见 `docs/测试报告.md` §10
- 统计页分类环形图的占比气泡以前落在屏幕下沿时会被右下角「记一笔」FAB 压住右端的「查看明细」（`docs/测试报告.md` §13 记的老账）；2026-10-17 批 E 的 E-5 已修——气泡出现时把「气泡 + 88dp」整体滚进视口（真机实测气泡底边 2495 + 88dp = 内容区底边 2825，比 FAB 顶边 2555 高 16dp），「查看明细」文字换成行尾箭头（见 `docs/测试报告.md` §24）。同类仍未解决的两条：0.1% 那种 0.36° 的扇形手指点不中（验收按「最后可点的扇形」执行）、FAB 依旧会盖住环形图本身的右下弧（要点那几段得先滚动），见 §24.7
- 主题自 2026-10-16 批 C 起固定为淡蓝品牌色板，**不再跟随系统壁纸动态取色**（这是本批的交付目标）；系统深色而应用内选「浅色」时状态栏图标会转为深色，`enableEdgeToEdge()` 跟随应用内偏好，符合预期
- 批 C 的 debug APK 比上一版大 328,914 字节：逐条目对比只有约 3.5 KB 来自代码，其余是 APK 容器内条目对齐填充（同轮签名 release 体积未变）；详见 `docs/测试报告.md` §14.3
- 「沿用上次分类」的偏好存在本地 DataStore `ledger-last-category`（键 `last_category_id` / `last_category_used_epoch_day`）；删除流水不会回滚该偏好（记录的是「上次记过哪类」，不是「账本里还有没有」），属设计如此
- 批 D 的 debug APK 比批 C 大 2,032 字节、签名 release 大 16,384 字节（恰好一格 16 KiB 容器对齐填充，代码侧只新增色板与偏好存储两个文件）；详见 `docs/测试报告.md` §15.3
- 统计范围（批 E）不持久化：进程被杀/退出应用后回到「本月」；从统计页点分类/账户/当日明细进的是**压栈的下钻页**（2026-10-17 §19 起不再弹掉返回栈），系统返回键落回统计页；在下钻页点底栏「明细」会落到**未筛选**的明细 Tab（下钻条件只属于那一层）；自定义区间上限 3660 天，>62 天按月聚合（首尾可能是「部分月」）；选「今年」时未来月补 0 不截断（横轴固定 1–12 月）；预算行只在单月范围出现。详见 `docs/测试报告.md` §16.5
- UI 一致性收口（设计规范 §11 批 D）已做完 D-1 ~ D-6，但有三处只能靠单测/源码守门：①真实账本里 7 个分类预算与总预算全是「未设置」，`LedgerProgressBar` 的超支刻度与 60% 压缩在真机没有渲染（单测覆盖 `fraction = 1.5` / `0.8` / `NaN`）；②`EmptyState` 的 ERROR 色调需要「加载失败」才能触发，真机复现不了（单测覆盖 96dp 插图与按钮槽）；③D-6 只做了 `uiautomator dump` 文案核对 + 源码守门，没有在真机上用 TalkBack 实测播报。
- 导航转场没有接 Android 14 的预测式返回，也不读系统「减少动画」偏好（规范 AA-7 / 批 F 的 F-4 未做）：应用内转场是自写 `tween`（进入 220 ms / 退出 180 ms），只受系统 `animator_duration_scale` 影响；拖拽返回（drag-to-go-back）未实现，返回键/返回箭头之外没有手势。详见 `docs/测试报告.md` §19.6
- Tab 切换语义（2026-10-17 §20）：下钻页是**一次性**页面，不参与 Tab 状态记忆——从下钻页切到任何 Tab 都会把它丢掉（切回原 Tab 时回到那个 Tab 自己的历史，而不是下钻页）；「回起始 Tab」用 `popBackStack(起始页, inclusive = false, saveState = true)` 而不是 `navigate + restoreState`（后者会把压在起始页上的页面整段恢复回来）。这条依赖 navigation-compose 当前的 `restoreState` 语义，升级该依赖后必须重跑 `LedgerDrillDownNavigationTest` 那 10 例。详见 `docs/测试报告.md` §20.6
- 明细页筛选条（2026-10-17 §21）：条件 chips 是「聚合摘要」而不是一条件一 chip，条件多时同一 chip 内文字也会被裁，要横向滚动才能看全；字体缩放 ≥1.5 时 chip 右端会被 `LazyRow` 边界从字形中间裁断（不是省略号），按规范 G-6 / 批 F 的 F-2 应由「≥1.5 竖排」解决，本批未做；横滚没有渐隐或箭头提示，chip 也没有「共 N 个条件」的读屏语义。设计规范里「首屏内容区增加 ≥60dp」这条验收实测不成立（无条件时 56 → 约 48dp，只省 8dp），本批的收益是「条高有界」，详见 `docs/测试报告.md` §21.6
- 记一笔的底部保存与分类区限高（2026-10-17 §22）：底部「保存」的接线（`Scaffold.bottomBar` + `showSave`）只有真机证据，组件测试直接测 `RecordSaveBar`，删掉接线也不会红；分类区 168dp 上限在字体缩放 2.0 下只能看三行、第 4 行被从字形中间硬裁（没有省略号/渐隐）；金额复核行只在解析成功且 > 0 时出现，非法输入不提示；**中文输入法会把句点送成全角「。」导致 `Money.parseCents` 返回 null**（既有输入侧问题，2026-10-17 真机发现，同日已在 §23 修掉）。详见 `docs/测试报告.md` §22.7
- 功能图标（2026-10-17 §28）：13 个图里有 10 个（Edit/Delete/Check/Close/Search/Alert/Info/Download/Upload/Lock）**画好、有测试、但还没有宿主界面**，E-7 已把设置页重排成卡片分组，但**没有给它们接界面**；（F13 见下一条）；E-8 接的是预算页月份标签，也没动这些图标；三档真机证据用的是三颗不同图标（16dp 箭头 / 20dp 日历 / 24dp 钱包·卡），不是同一颗的三种尺寸；16dp 下线条占比 8.3% 偏轻，无像素级验证；账户类型只二分（信用卡→Card，其余→Wallet）；箭头带「上升/下降」语义，读屏会念出来。详见 `docs/测试报告.md` §28.7
- F13 SQLCipher 加密（条件式，**2026-10-16 决策：不纳入，维持挂起**）：评估报告在 [docs/F13-SQLCipher评估.md](docs/F13-SQLCipher评估.md)——编译 / 打包 / 16 KB page / 权限都实测通过，真正的成本在密钥管理与存量库迁移（Keystore 密钥不可导出，卸载 / 清数据 / 换机都可能让库永久不可读；按官方示例接线会让 271 例测试崩；四 ABI 体积 +7.71 MB；Community Edition 要求在界面做归属声明）。当前防护：应用私有目录 + 无 INTERNET + `allowBackup="false"` + JSON/CSV 备份；再评估触发条件见该报告 §0；代码里没有 SQLCipher 依赖，spike 已还原。`Lock` 图标因此没有宿主界面（见上一条）。详见 `docs/测试报告.md` §6 与 §28.7
- 统计页补充走查（2026-10-18 §31.8 / §31.9 / §31.10）：1.5 / 2.0 字体、浅色主题、空月份、横屏都已走查；「有数据的 31 天整月」用临时 CSV 数据走查后已原样还原（备份 → 增量导入 31 笔 2026-01 → 截图 → 覆盖替换回滚，`data` 字段逐字段相同），测试文件夹已删；横屏下卡片/列表按全宽铺开（无双栏 / 最大宽度），FAB 遮挡比竖屏更明显；18 天数据时「左右滑动看整月」提示出现但可滚距离仅约 4dp（微交互噪音，未修）。详见 `docs/测试报告.md` §31.9 / §31.10
- 金额列统一（2026-10-18 §32）：明细 / 账户 / 统计三页已走 `LedgerAmount`；预算页的「预算额」与月份汇总条三格仍是各自排版未迁移；Robolectric 字宽不可用，长金额撑开只能靠真机截图；`overflow = Clip`（分屏 + 200% 字体 + 最大金额不省略，直接裁）。详见 `docs/测试报告.md` §32.5
- 分类图标宫格（2026-10-17 §27）：宫格与默认分类靠 `CategoryIconPresetsTest` 比集合同步（两处维护点）；手输只保留 2 个码点，组合型 emoji 会被截断；记一笔内联新建分类仍不带图标；对话框在 fontScale ≥ 1.5 下没有滚动，宫格可能把「保存」挤出可视区；改图标不是乐观更新；真机的 ASCII 名字要在关掉输入法后用 `input text` 注入（默认中文输入法会把后续字符吃进拼音合成区，只留首字符；`ime disable` → 输入 → `ime enable` 是可用配方，见 §27.6 第 4 项），emoji 手输仍无真机证据、宫格点选是唯一有真机证据的入口。详见 `docs/测试报告.md` §27.7
- 「减少动画」偏好（2026-10-17 §26）：只读 `animator_duration_scale` 一个键（无障碍「移除动画」会同时置 0，正常路径够用；只改 window scale 不感知）；`ContentObserver` 只在组合期注册，后台被重建后会重读一次；转场 `None` 仍是一次重组，只是没有中间帧；真机证据是静态截帧比较而非录屏，能证「没有中间帧」、不能证「完全不绘制」；G-9 的骨架淡入还没做，AA-7 的「最低要求」目前只体现在转场上，`rememberAnimationsEnabled()` 已留作接点。详见 `docs/测试报告.md` §26.7
- 字体放大竖排（2026-10-17 §25）：门槛按「字体倍数」而不是「剩余宽度」判，极窄机型 + 1.0 字体仍可能挤；预算行的真机证据靠临时设 500 元预算再删除换来（UI 层核对回到「未设置」，但没有库级逐字节比对）；debug 包体积比上一版少 105,480 字节未定位（release +16,384 正常）；2.0 倍下明细首屏只剩 1 行多流水，属字体放大的固有代价。详见 `docs/测试报告.md` §25.7
- 金额输入归一化（2026-10-17 §23）：归一化只做字符替换（全角数字 / 句号 / 逗号 / 负号 / 全角空格 → 半角），**不处理格式**——金额里出现「¥」「元」或多于一个小数点仍非法；CSV / JSON 导入路径不放宽（外部数据不做隐式纠正，该路径仍走严格 `parseCents`）；`AmountInputSourceTest` 是源码级守门（只证明「有调用」，且只检查表里列出的文件，新增金额输入框要手动补 `amountFieldFiles` 表）；「输入法把 PERIOD 送成全角」只有真机能复现，Robolectric 验不到。另：真机自动化里一次性 `input text "123.45"` 曾出现前缀 `99`（`99123.45`），逐字符输入正常，未复现定性。详见 `docs/测试报告.md` §23.7
- 设置页用 `contentPadding = PaddingValues(horizontal = 0.dp)` 关掉行内水平留白，是为了不叠加整页 16dp 页边距（G-10 在 E-7 统一为 16dp，此前是 24dp），其余页面用默认 16dp；分类页父级分类靠外层 24dp padding 右移，分隔线仍按 56dp 缩进，子级分隔线会比父级短一截（有意区分层级）；行内 `IconButton` 最小触控目标 48dp，在 56dp 单行里上下只剩 4dp 余量；空态插图 NEUTRAL 120dp、FILTERED / ERROR 96dp 两档是刻意区分。详见 `docs/测试报告.md` §17.5 与 §18.5
- release APK **不是逐字节可复现**：同源代码两次构建体积同为 10,152,305 字节，但 SHA-256 分别是 `f3a0ddfe…`（21:43，设备安装的那一次）与 `c194038d…`（21:51 重建，签名块带时间戳）；本 README 与测试报告记的是设备上那一份。详见 `docs/测试报告.md` §37.5
