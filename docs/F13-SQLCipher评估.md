# F13 SQLCipher 加密（条件式）评估报告

> 依据：开发计划 V2.2 第 83、247、252、298、300、342 行。
> 结论先行：**技术上可用（编译/打包/16 KB/权限全部实测通过），但真正成本不在接入，在密钥管理与存量库迁移。建议按“条件式”继续挂起，等用户明确威胁模型后再启动。**
> 本报告数字均为本机实测；未实测项在「待真机/待验证清单」单列，不作为已证事实。

## 0. 决策记录（2026-10-16，用户拍板）

**决策：不纳入 SQLCipher，维持条件式挂起（即第 8 节的「路 A」）。**

用户给出的理由（原话）：「不用密钥，也没有云端需求，数据只在本地」。

- 不引入任何密钥/口令机制 → 不存在「密钥丢失即数据永久不可读」的风险。
- 无云端需求 → 不存在「备份上云被读取」的威胁；`allowBackup="false"` 已把系统自动备份关掉。
- 数据只在本机 → SQLCipher 只剩「防 root / 物理镜像 / 维修人员 / 提权恶意软件」这一条威胁，不在用户的威胁模型内。

**当前实际防护（已实测，见 §11）**：应用私有目录 + 合并 Manifest 无 INTERNET 权限 + `allowBackup="false"` + JSON 完整备份/恢复 + CSV 四文件导出（用户可自行把数据搬走）。

**再评估触发条件**（命中任一条再启动第 8 节的路 B）：

1. 用户明确要求防 root / 物理镜像 / 设备维修期间的读取；
2. App 要存比记账更敏感的数据；
3. 出现真实的数据外泄事件反馈；
4. 引入云同步（当前明确不做）。

## 1. 一句话结论

| 维度 | 实测结论 | 判定 |
| --- | --- | --- |
| 与 Room 2.8.5 / AGP 9.4.1 / KSP 编译 | `assembleDebug` BUILD SUCCESSFUL in 46s | 通过 |
| Android API 覆盖 | AAR `minSdkVersion=23` + `minCompileSdk=36`，本工程 minSdk 26 / compileSdk 37 | 通过 |
| 16 KB page size 设备 | 四个 .so 的 ELF `PT_LOAD p_align=16384`；APK 内 lib 条目 zip 数据偏移 `% 16384 == 0` | 通过 |
| 新权限 / 合并 Manifest | 只有原本的 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION，**没有 INTERNET** | 通过 |
| 单元测试 | 只入 classpath 不接线：61 套 557 例全绿；接线后 557 例里 **271 例崩** | 需先做测试接缝 |
| APK 体积 | 四 ABI 全量 14.2 MB → 22.0 MB（+7.71 MB / +54%）；仅 arm64 16.3 MB（+2.06 MB） | 可接受但显著 |
| 存量明文库迁移 | 需要一次性重建加密库 + 原子替换 + 失败回滚 | 有风险，需专项验证 |
| 密钥丢失与恢复 | Keystore 密钥不可导出；卸载/清除数据/换机/生物录入变更都可能让库永久不可读 | **最大风险项，尚无方案** |
| 许可证 | Community Edition 为 BSD-style，**要求在使用界面做归属声明**，当前 App 无「关于/开源许可」入口 | 需新增界面 |

## 2. 制品事实（不是博客转述）

- 制品：`net.zetetic:sqlcipher-android`，最新 **4.19.1**（maven-metadata `lastUpdated=20260929`；版本序列 4.5.2→…→4.19.1）。旧制品 `net.zetetic:android-database-sqlcipher` 已弃用并冻结在 4.5.4，官方迁移文档指向新制品 → **维护活跃，选新制品**。
- AAR 4.19.1 = 4,016,379 字节（17 个条目）；classes.jar 68 个类，只含 `net/zetetic/...`，不 shadow `android.*`。
- native .so 未压缩尺寸：arm64-v8a 2,100,672 / armeabi-v7a 1,047,124 / x86 2,244,880 / x86_64 2,231,848，合计 **7,624,524** 字节。
- POM：`kotlin-stdlib 2.2.10`（compile）+ `androidx.sqlite:sqlite 2.7.0`（runtime）。
- API：`SupportOpenHelperFactory` 实现 `androidx.sqlite.db.SupportSQLiteOpenHelper$Factory`；另有 driver 形态类 `net.zetetic.database.sqlcipher.driver.SQLCipherDriver`（**在 `driver` 子包，不在 `sqlcipher` 包**）。
- 加密默认值：AES-256-CBC、4096 字节页、每页随机 IV、每页 HMAC-SHA512、PBKDF2-HMAC-SHA512 默认 256,000 迭代（`PRAGMA kdf_iter` 可调）、文件头 16 字节随机 salt。WAL/journal 页同样加密，**其他临时文件不加密** → 需把 temp store 放内存。

## 3. 编译与打包 spike（临时改动，已还原）

临时改动 3 个文件（`gradle/libs.versions.toml`、`app/build.gradle.kts`、`app/src/main/java/com/nimo/ledger/data/local/AppDatabase.kt`，diff 存 `C:/Users/nimo/AppData/Local/Temp/ledger-verify/sqlcipher/f13_spike.patch`），测完已 `git checkout --` 全部还原，工作树干净（HEAD `15c6859`）。

- 编译：`assembleDebug` **BUILD SUCCESSFUL in 46s**；依赖解析把 `androidx.sqlite:sqlite 2.6.2` 顶到 `2.7.0`，无冲突。
- 体积：
  - 四 ABI 全量：14,253,939 → **21,962,293**（+7,708,354，+54.1%）。APK 内 lib 条目全部 `method=0`（未压缩存储），所以增量 ≈ .so 未压缩尺寸之和，`libsqlcipher.so` 四份 7,624,524 字节是主项。
  - 仅 arm64-v8a：**16,308,971**（同会话基线 +2,055,032）；按同算法 arm64+armeabi-v7a 约 +3,102,156。
- 权限：`aapt2 badging` 的 native-code 仍为四个 ABI，uses-permission 未增加 → 计划第 298 行的 INTERNET 检查通过。
- 基线噪声：同一 commit 两次构建的 debug APK 相差 38,255 字节（0.27%），属打包非确定性（与 `core.autocrlf=true` 导致的 LF→CRLF 物化及 debug 打包差异有关）。**体积增量必须同会话对比，或直接用 .so 尺寸**，不要拿跨会话两次 APK 相减。

## 4. 单元测试：必须预留「测试接缝」

| 状态 | 结果 |
| --- | --- |
| 依赖只进 classpath，不接线 | 61 套 **557 例 0 失败** |
| 按官方示例接线（`System.loadLibrary` 放在 AppDatabase companion） | 557 例中 **271 例失败** |

失败只有两种消息：

- 251 例 `java.lang.NoClassDefFoundError: Could not initialize class com.nimo.ledger.AppContainer`
- 20 例 `java.lang.UnsatisfiedLinkError: no sqlcipher in java.library.path: ...`

根因：`System.loadLibrary("sqlcipher")` 在类初始化时就执行，而 Robolectric 测试经 `app/src/main/java/com/nimo/ledger/LedgerApplication.kt:33` 的 `class AppContainer` 走到 `AppDatabase.build(context)`（`LedgerApplication.kt:35`），JVM 上没有原生库 → 整条链路崩。

**结论：纳入 SQLCipher 前必须先给生产库构建加接缝**（例如 `build(context, factory: SupportSQLiteOpenHelper.Factory?)` 参数化，测试传内存工厂；或把加密工厂收进可替换的 provider），否则整套单测不可用。

## 5. 迁移与 SchemaHardening

- 现有库：`app/src/main/java/com/nimo/ledger/data/local/AppDatabase.kt:42` 常量 `NAME = "ledger.db"`，:44-47 唯一打开点，无 `addMigrations`、无 `fallbackToDestructiveMigration`，`@Database(version = 1, exportSchema = true)`。
- 加密是**存储层**替换，表结构与 schema 版本不变 → Room 的 identity hash（`room_master_table`）随库一起被加密，对新老代码都是透明的；`SchemaHardening.apply(db)`（触发器、幂等语句）在 `HardeningCallback.onCreate` 里照常执行，不冲突。
- **真正的迁移风险**：已有用户的 `ledger.db` 是明文。首启加密版本必须：读取明文库 → 建加密库 → 全量搬运 → 校验行数/余额 → 原子替换（保留 `.bak` 直到校验通过）→ 失败回滚。计划第 247 行明确要求这一步单独验证，且禁止破坏性迁移。
- 另需确认：`PRAGMA temp_store` / `SQLITE_TEMP_STORE` 强制内存（否则临时文件明文落盘，安全性自相矛盾）。

## 6. 密钥丢失与恢复（最大风险）

SQLCipher 的密钥必须来自某处，而每一条来源都有丢失场景：

| 密钥来源 | 优点 | 丢失/失效场景 | 后果 |
| --- | --- | --- | --- |
| Android Keystore 随机密钥（不导出） | 用户无感，最省事 | 卸载重装、清除应用数据、换机、系统升级/Keymaster 重置 | 库**永久不可读**（Keystore 条目随应用 UID 消失） |
| Keystore + 生物识别绑定 | 多一道本地防护 | 新增指纹/人脸录入时默认失效（可用 `setInvalidatedByBiometricEnrollment(false)` 关闭） | 解锁失败 → 需回退路径 |
| 用户自设口令（PBKDF2 派生） | 可跨设备/重装恢复 | 用户忘记口令 | 库不可读（需明示“没有找回手段”） |
| 口令 + Keystore 包裹（快解） | 兼顾体验与可恢复 | 口令忘记且 Keystore 丢失 | 同上 |

官方文档（developer.android.com/privacy-and-security/keystore）能证实的是：只用生物识别凭据的密钥在**新增生物特征录入时默认失效**，可用 `setInvalidatedByBiometricEnrollment(false)` 关闭；`canAuthenticate()` 判断凭据是否就绪；StrongBox 走 `setIsStrongBoxBacked()`。**该页没有说明卸载/清除数据/换机后密钥是否保留、能否随备份迁移** → 这批行为只能真机验证，不可当作已证事实。

对照当前工程：`AndroidManifest.xml:8 android:allowBackup="false"` 已禁用系统自动备份，所以「云备份泄漏数据库」这条威胁本来就不存在；SQLCipher 实际防的是 **root / 物理镜像 / 维修人员 / 提权恶意软件**读取应用私有目录。是否值得为这条威胁付出「密钥丢失 = 数据全丢」的代价、以及是否要求用户额外记住口令，**是产品决策，不是技术决策**，需要用户拍板。

## 7. 许可证与合规

- Community Edition 为 **BSD-style**，官方要求在使用界面/材料中做归属与许可声明，且位置必须用户可访问。
- 现状：`ui/settings` 下 grep「关于|开源|许可」**零命中**，仓库里也没有 `docs/` 这类可放声明的入口 → 纳入即需要新增「关于/开源许可」界面（或设置页内一节），否则不合规。
- 计划第 252 行要求说明「是否能用标准库实现」：**不能**。Android 平台没有内置 SQLite 加密；文件级 `EncryptedFile` 面向小文件顺序读写，不适合 SQLite 的随机页访问。

## 8. 两条路

### 路 A（推荐）：保持条件式，暂不纳入 —— **已选，见 §0**

- 现状已满足计划要求：应用私有目录隔离 + `allowBackup=false` + JSON 备份/恢复 + CSV 导出（用户可自行把数据搬走）。
- 把本报告的实测数字与「再评估触发条件」记录在案：**用户明确要求防 root/物理提取**、或 **App 要存更敏感的数据**、或 **有用户反馈设备被维修/越狱后数据外泄**。
- 代价：0 行代码、0 字节体积、0 密钥风险；换来的是把这 4~6 人日和一类永久丢数据风险挡在门外。

### 路 B：纳入（需要以下前置条件全部完成）

| 阶段 | 工作 | 验收 |
| --- | --- | --- |
| B1 依赖与接线 | `libs.versions.toml` 加 `sqlcipher = "4.19.1"`；`AppDatabase.build` 参数化加测试接缝；`SupportOpenHelperFactory` 接线；temp store 内存化 | 557 例全绿（含新接线测试）+ 真机首启可读写 |
| B2 密钥方案 | Keystore 随机密钥或口令派生（二选一，需用户决策）+ 失败回退 + 用户知情文案 | 真机验证重装/清除数据/换机的实际行为；明确告知「丢失即不可恢复」 |
| B3 存量库迁移 | 明文→密文一次性重建 + 行数/余额校验 + `.bak` 回滚 | 真机用现网数据跑通，中断/失败可回滚，`SchemaHardening` 触发器仍在 |
| B4 合规界面 | 设置页新增「关于/开源许可」并声明 SQLCipher BSD 归属 | 界面上可见、可打开 |
| B5 体积决策 | 决定 ABI 策略（四 ABI 22.0 MB / 仅 arm64 16.3 MB / arm64+armv7 ≈ 17.4 MB） | 用户确认体积可接受 |

工作量估计：B1 约 1 人日，B2 约 1-2 人日，B3 约 1-2 人日，B4 约 0.5 人日 → **合计约 4~6 人日**，另加真机验证（小米 15S Pro + API 26 模拟器）。

## 9. 待真机/待验证清单（未验证项，不得当结论）

1. `SupportOpenHelperFactory` 路径已编译验证；**Room driver 形态（`setDriver(SQLCipherDriver)`）未做构建验证**。
2. Keystore 密钥在**卸载重装 / 清除应用数据 / 换机 / 系统升级**后的实际存活与失效行为（官方文档未说明）。
3. 存量明文库一次性迁移在真机上的耗时与失败回滚表现。
4. `temp_store` 内存化后长事务/统计计算的性能影响（基准：`TenThousandBenchmark` 1 万笔）。
5. 加密后数据库文件首 16 字节确实是随机 salt（写完后 grep 明文特征应为 0 命中）。

## 10. 证据来源

- 计划：`D:/AIworkspace/记账/个人记账App开发计划V2.2.md` 第 83、247、252、298、300、342 行。
- 制品：Maven Central `net.zetetic:sqlcipher-android`（AAR/ELF/classes.jar 解析）、官方 README、zetetic.net/sqlcipher/license 与 /design 页面。
- 本机实测：`./gradlew.bat clean assembleDebug testDebugUnitTest --rerun`、`:app:dependencies --configuration debugRuntimeClasspath`、`aapt2 badging`、ELF/zip 头部解析。
- 取证脚本：`C:/Users/nimo/AppData/Local/Temp/ledger-verify/` 下 `aar_probe.py`、`apk_libs.py`、`apk_align.py`、`jar_list.py`、`jar_driver.py`、`test_fail_probe.py`、`count_tests.py`。

## 11. 「数据只在本机」的核验（2026-10-16 实测）

| 检查 | 命令 / 位置 | 结果 |
| --- | --- | --- |
| 应用清单声明 | `app/src/main/AndroidManifest.xml:4` | 注释明写「无 INTERNET 权限：对『数据不上云』的物理保证（计划 4.5）」 |
| 合并后清单 | `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml` | 只有 `com.nimo.ledger.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，**无 INTERNET / ACCESS_NETWORK_STATE** |
| 系统备份 | 合并清单 `android:allowBackup="false"` | 禁止系统自动把数据库备份到云 |
| 源码网络调用 | grep `java.net|okhttp|retrofit|HttpURLConnection|Socket(|URL(` over `app/src/main/java` | **0 命中** |
| 依赖里的网络库 | grep `okhttp|retrofit|ktor|volley|network` over `gradle/libs.versions.toml` | **0 命中** |

结论：这三条本地性保证是**编译产物级别的事实**，不是口头承诺。
