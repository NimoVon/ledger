# 记账本（ledger）

离线、纯手动、支持多账户余额的 Android 个人记账应用。

- 无 `INTERNET` 权限（数据不上云的物理保证），无账号体系
- 金额全程 `Long` 整数分，杜绝浮点误差
- 账户余额由交易推导（`初始余额 + Σ变动`），不维护可变余额字段
- 转账影响双账户余额、不计入收支统计
- 数据库层触发器兜底所有硬约束（金额正负、转账双账户、分类归属）

开发计划（产品范围 / 数据铁律 / 统计口径的唯一权威来源）：
`D:/AIworkspace/记账/个人记账App开发计划V2.2.md`

## 环境版本（2026-10-12 定版）

| 组件 | 版本 |
|---|---|
| Gradle | 9.8.1 |
| Android Gradle Plugin | 9.4.1（内置 Kotlin） |
| Kotlin | 2.4.21 |
| KSP | 2.3.12 |
| Room | 2.8.5 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| JDK | Android Studio 自带 JBR（`C:\Program Files\Android\Android Studio\jbr`） |
| Android SDK | `C:\Users\nimo\AppData\Local\Android\Sdk`（local.properties，勿提交） |

依赖版本集中管理在 `gradle/libs.versions.toml`。

## 构建与测试（Windows / Git Bash）

```bash
export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
cd /d/git/ledger
./gradlew.bat --console=plain assembleDebug        # 构建 debug APK
./gradlew.bat --console=plain testDebugUnitTest    # JVM 单元测试
./gradlew.bat --console=plain lint                 # 静态检查
```

首次执行 `gradlew.bat` 会下载一次 Gradle 发行包（约 140MB）。
本机 `tools/gradle-9.8.1/` 下已有解压版可直接用（已 gitignore）。

在 Android Studio 中打开：直接 Open 项目根目录，JDK 选择内嵌 JBR 即可。

## 工程结构

```
app/src/main/java/com/app/ledger/
├── LedgerApplication.kt        # 入口 + 手动 DI 容器（AppContainer）+ M0 自检
├── MainActivity.kt             # M0 自检界面（阶段2起替换为正式首页）
├── data/
│   ├── local/
│   │   ├── entity/Entities.kt  # accounts / categories / transactions / budgets
│   │   ├── dao/                # 四个 DAO
│   │   ├── AppDatabase.kt      # Room 数据库（exportSchema = true）
│   │   ├── SchemaHardening.kt  # 触发器 + 部分唯一索引（数据库层硬约束）
│   │   ├── DefaultCategories.kt# 预置分类（支出11 + 收入7）
│   │   └── Mappers.kt          # Entity <-> 领域模型
│   └── repository/LedgerRepository.kt  # 唯一数据入口（校验后落库）
├── domain/
│   ├── model/                  # 枚举 + 纯 Kotlin 领域模型
│   ├── stats/                  # 统计口径 / 余额计算（纯函数，可 JVM 单测）
│   └── validate/               # 交易校验器（第二道防线）
└── util/                       # Money（金额解析/格式化）、Dates
```

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

## 当前状态

- [x] 阶段 0：工程脚手架（M0 可编译空壳 + 自检界面）
- [x] 阶段 1（部分）：4 张表 + DAO + Repository + 统计纯函数 + JVM 单测
- [ ] 阶段 1（剩余）：DAO 层测试（Robolectric）、万级数据性能基准
- [ ] 阶段 2：记账闭环 UI
- [ ] 阶段 3：报表 ｜ 阶段 4：预算/图表 ｜ 阶段 5：打磨发布

## 已知限制

- M0 界面仅为链路自检，正式 UI 从阶段 2 开始
- 未启用 minify；release 混淆规则待补充
- 无自定义应用图标（用系统默认）
- DAO 层测试尚未接入（计划 M1 验收项）
