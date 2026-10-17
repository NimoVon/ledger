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

最近一次门禁（2026-10-17 19:31，改名 + 换图标 + 批 A（R1 月份直选 + R2 单月分析）+ 饼图可点（R2 后续）+ 批 B（R3 分类删除引用护栏 / 恢复默认分类 / 记一笔内联新建，见 [`docs/测试报告.md`](docs/测试报告.md) §13）+ 批 C（R4-L1 淡蓝品牌色板 / 移除 Android 12+ 壁纸动态取色 / 容器圆角 16–24dp / 空态小狗插图，见 §14）+ 批 D（R4 图表 10 色品牌板 / 趋势柱双序列 / 记一笔沿用上次分类，见 §15）+ 批 E（统计范围：本月 / 近 N 个月 / 自然年 / 自定义区间，趋势随粒度切换逐日柱与月折线，见 §16）+ UI 一致性收口（设计规范 §11 批 D 的 D-1 ~ D-6：`LedgerListItem` / `LedgerSectionHeader` / `LedgerProgressBar` 组件化、`EmptyState` 三色调与动作槽、`PickerCell` 44dp、账户/分类 FAB 与对话框标题文案分离，见 §17 / §18）+ 下钻返回栈修复与导航转场（统计下钻不再弹掉返回栈，转场按 Tab / 二级页 / 表单分档，见 §19）+ 三条 UI 反馈修复（记一笔输入法遮挡 / 图表 y 轴刻度与点按气泡 / 月底横轴标签过挤，见 §12）+ Tab 切换修复（下钻页卡住底栏切换、统计页点明细进不去，见 §20）+ 明细页筛选条压成一行（设计规范 §11 批 E 的 E-1：筛选按钮 + 横滚条件 chips + 清空同处一行，条高不再随条件数增长，见 §21）+ 记一笔底部保存按钮与金额复核行 + 分类 chip 区限高内滚（设计规范 §11 批 E 的 E-2 / E-3：底部 52dp 主按钮与顶栏共用同一份 `showSave` / `onSave`，金额解析成功即显示「核对：¥X」；分类区限高 168dp、可内滚、选中项自动 `bringIntoView`，见 §22）+ 金额输入全角归一化（§22 真机发现的「中文输入法把句点送成全角」：7 个金额输入框在输入口把全角数字 / 句号 / 逗号 / 负号 / 空格换成半角，`Money.parseCents` 严格口径不动，见 §23）后的 `clean` 构建）：**94 套 808 例，0 失败 0 错误 0 跳过**；行覆盖 70.6%、分支 59.6%；lint 5 项、0 error（5 warning / 0 hint）；
`app/build/outputs/apk/debug/app-debug.apk` = 14,701,716 字节（14.02 MiB，计划上限 20MB）；
签名 `app/build/outputs/apk/release/app-release.apk` = 10,119,537 字节（9.65 MiB）；2026-10-16 已装到测试机，卸载后重装再验（14:18 `Success`），冷启动首次安装 150 / 219 ms、重装后 323 / 204 / 204 ms；§13 那轮（饼图可点 + 批 B）重新构建后 21:54 覆盖安装一次（`Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留），冷启动 185 ms；批 C（R4-L1）重新构建后 23:07 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39），冷启动 215 ms；批 D 重新构建后 2026-10-17 10:14 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39），冷启动 196 ms；批 E 重新构建后 11:08 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；UI 一致性收口（§17）重新构建后 13:28 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致）；D-3 ~ D-6（§18）重新构建后 13:50 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；下钻返回栈修复 + 导航转场（§19）重新构建后 14:07 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，设备内 `base.apk` 的 SHA-256 与本地包逐位一致）；Tab 切换修复（§20）重新构建后 16:24 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；批 E · E-1（筛选条单行化，§21）重新构建后 18:47 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；批 E · E-2 / E-3（§22）重新构建后 18:58 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）；金额输入全角归一化（§23）重新构建后 19:31 再覆盖安装一次（同样 `Success`、`firstInstallTime` 仍为 14:18:39，用户账本数据保留）。
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
| [`docs/测试报告.md`](docs/测试报告.md) | 门禁结果、808 例分布、真机 10,000 笔验收、JaCoCo 覆盖率、性能实测、打包体积、数据安全、未覆盖项 |
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
- 统计页分类环形图的占比气泡若落在屏幕下沿，右下角「记一笔」FAB 会遮住气泡右端的「查看明细」文案（整行仍可点，点左侧或继续上滑即可）；彻底修法留待下一批 UI 打磨（`docs/测试报告.md` §13）
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
- 金额输入归一化（2026-10-17 §23）：归一化只做字符替换（全角数字 / 句号 / 逗号 / 负号 / 全角空格 → 半角），**不处理格式**——金额里出现「¥」「元」或多于一个小数点仍非法；CSV / JSON 导入路径不放宽（外部数据不做隐式纠正，该路径仍走严格 `parseCents`）；`AmountInputSourceTest` 是源码级守门（只证明「有调用」，且只检查表里列出的文件，新增金额输入框要手动补 `amountFieldFiles` 表）；「输入法把 PERIOD 送成全角」只有真机能复现，Robolectric 验不到。另：真机自动化里一次性 `input text "123.45"` 曾出现前缀 `99`（`99123.45`），逐字符输入正常，未复现定性。详见 `docs/测试报告.md` §23.7
- 设置页用 `contentPadding = PaddingValues(horizontal = 0.dp)` 关掉行内水平留白，是为了不叠加整页 24dp 页边距，其余页面用默认 16dp；分类页父级分类靠外层 24dp padding 右移，分隔线仍按 56dp 缩进，子级分隔线会比父级短一截（有意区分层级）；行内 `IconButton` 最小触控目标 48dp，在 56dp 单行里上下只剩 4dp 余量；空态插图 NEUTRAL 120dp、FILTERED / ERROR 96dp 两档是刻意区分。详见 `docs/测试报告.md` §17.5 与 §18.5
