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
| F02 | 分类体系（支出/收入两套、预置 + 自定义、单层父子、排序、停用） | 已实现 |
| F03 | 记一笔（支出/收入，金额/分类/账户/日期/备注，任意页面可唤起，可改可删可撤销） | 已实现 |
| F04 | 转账（双账户、不影响收支统计、余额不足警示） | 已实现 |
| F05 | 流水列表（按日分组时间线、按月切换、同日稳定倒序） | 已实现 |
| F06 | 筛选与搜索（账户/分类含父子/金额区间/日期区间/备注关键词） | 已实现 |
| F07 | 月度报表（收入/支出/结余、分类排行、账户分布、环比、日均） | 已实现 |
| F08 | 备份与导出（JSON 全量导出/恢复 + 恢复前自动备份；CSV 导出 UTF-8-BOM） | 已实现 |
| F09 | 预算管理（月度总预算 / 分类预算、80% 与 100% 告警） | 已实现 |
| F10 | 统计图表（趋势折线、分类环形图、账户分布、下钻） | 已实现 |
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
./gradlew.bat --console=plain testDebugUnitTest --rerun jacocoTestReport lint assembleDebug
```

最近一次门禁（2026-10-16，改名 + 换图标后的 `clean` 构建）：**62 套 562 例，0 失败 0 错误 0 跳过**；lint 6 项、0 error；
`app/build/outputs/apk/debug/app-debug.apk` = 14,120,440 字节（13.5 MiB，计划上限 20MB）；
签名 `app/build/outputs/apk/release/app-release.apk` = 10,004,821 字节（9.5 MiB），已装到测试机并实测冷启动 150 / 219 ms。
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
| [`docs/测试报告.md`](docs/测试报告.md) | 门禁结果、562 例分布、JaCoCo 覆盖率、性能实测、打包体积、数据安全、未覆盖项 |
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
- [x] 签名包真机验证（2026-10-16）：装到测试机（`firstInstallTime 2026-10-16 10:20:18`、`versionName 1.0`、签名 v2），冷启动 219 / 150 ms，「应用信息」页显示新图标，启动日志无 `FATAL EXCEPTION`

## 已知限制

- release 未启用 minify，混淆规则待补充（当前靠 debug/release 同源构建，无额外收缩）
- 旧安装标识 `com.nimo.bookkeeping` 的包与本包（`com.nimo.ledger`）是两个应用，不能互相覆盖升级；旧包数据要迁移须先「导出 JSON」再在新包「导入 JSON（覆盖替换）」
- 签名密钥库与口令只在本机，无异地备份；丢失后已安装的包无法覆盖升级
- 测试机 MIUI 上 `adb install` 需要先开「开发者选项 → 通过 USB 安装」，否则报 `Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]`（屏幕无确认框）
- 应用图标为自适应图标（浅蓝底 + 白狗）：前景 `drawable-nodpi/ic_launcher_foreground.png`、背景 `drawable/ic_launcher_background.xml`（`#79C9FC`）、单色层 `drawable-nodpi/ic_launcher_monochrome.png`；平面主图 [`docs/icon-512.png`](docs/icon-512.png)
- 数据库仍为 v1，暂无 Migration 对象（v1→v2 迁移验证待有 v2 后进行）
- 真机 1 万笔性能与 Android 8.0（API 26）真机兼容性尚未实测；覆盖率无门禁阈值
- UI 屏幕层覆盖率偏低（SAF/系统对话框不可单测），详见 `docs/测试报告.md` §10
