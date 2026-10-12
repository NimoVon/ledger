# AGENTS.md — AI 编程助手项目级指令

你是这个 Android 离线个人记账 App 的协作开发者。先阅读开发计划、AGENTS.md 和现有代码，再修改。

开发计划位于：`D:/AIworkspace/记账/个人记账App开发计划V2.2.md`（产品范围、数据铁律、统计口径的唯一权威来源）。

硬性规则：
1. 不改变产品范围，不增加联网、账号、云同步、广告、遥测或非必要依赖。
2. 金额使用 Long 整数分；不得使用 Float/Double 进行金额运算。
3. 交易类型、账户余额变动、统计口径必须遵守开发计划；转账与余额调整不进入收入/支出统计。
4. UI 不直接访问 DAO；数据写入通过 Repository；转账使用数据库事务。
5. 统计/金额核心逻辑必须有单元测试；修复 bug 时先补能复现问题的测试。
6. 不擅自升级依赖、不大规模重构、不删除用户数据、不使用破坏性数据库迁移。
7. 不虚构构建或测试结果。没有实际运行命令时，明确说明尚未验证。
8. 每次只完成一个小任务；先列出计划和涉及文件，完成后汇报变更、测试命令、实际结果和剩余风险。
9. 遇到需求歧义、数据迁移风险、备份覆盖风险或安全决策时，先询问，不自行猜测。
10. 不在日志、示例、截图或提交中暴露真实交易数据、备份内容和隐私信息。

## 本工程补充约束

- 技术栈：Kotlin 2.4.21 + AGP 9.4.1（内置 Kotlin，**不要**再 apply `org.jetbrains.kotlin.android`）+ Compose + Room 2.8.5（KSP）。
- 依赖版本只改 `gradle/libs.versions.toml`，不在 build.gradle.kts 里写死版本号。
- 数据库硬约束（CHECK 规则、部分唯一索引）在 `data/local/SchemaHardening.kt`，以触发器实现；**任何 Migration 结束时必须调用 `SchemaHardening.apply(db)`**（语句幂等）。
- Room schema JSON 在 `app/schemas/`，必须随 Git 提交。
- 统计口径唯一实现在 `domain/stats/StatsCalculator.kt`，余额唯一算法在 `domain/stats/BalanceCalculator.kt`；任何页面不得另写一套算法。
- 列表排序统一 `ORDER BY occurredOn DESC, id DESC`。
- 月度/区间查询用日期范围比较（`>= from AND < to`），禁止 `substr` 截取。
- **Room @Upsert 陷阱**：非主键约束冲突（触发器/部分索引）会被 Room 捕获转为按主键 UPDATE（0 行 = 静默无操作，不抛异常）。因此：验证数据库约束必须用 raw SQL 直插；写路径需要感知约束失败时用 `@Insert`/`@Update` 而非 `@Upsert`。

## 构建与验证命令（Windows）

```bash
export JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
cd /d/git/ledger
./gradlew.bat --console=plain assembleDebug        # 构建
./gradlew.bat --console=plain testDebugUnitTest    # 单元测试
./gradlew.bat --console=plain lint                 # 静态检查
```

本地另有免下载 Gradle：`tools/gradle-9.8.1/bin/gradle.bat`（已 gitignore，仅本机使用）。
