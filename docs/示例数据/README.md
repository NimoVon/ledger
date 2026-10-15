# 示例数据（合成、脱敏）

这里的数据**不是真实账目**，是合成的演示数据，用来让你在不录入任何东西的情况下试用「导入 JSON / 导入 CSV」。

| 文件 | 内容 |
| --- | --- |
| `ledger-backup-sample.json` | 完整备份：5 个账户、20 个分类（含 2 个二级分类）、15 笔交易（支出/收入/转账/余额调整四种类型都有）、4 条预算 |
| `accounts.csv` `categories.csv` `transactions.csv` `budgets.csv` | 同一批数据的 CSV 形态，金额是「元、两位小数」，UTF-8 带 BOM、CRLF 换行，Excel 可直接打开 |

## 怎么用

1. **整机覆盖替换**：设置 → 导出与导入 →「导入 JSON（覆盖替换）」→ 选 `ledger-backup-sample.json`。现有数据会被这份数据替换（替换前 App 会自动留一份自动备份）。
2. **增量合并**：把 4 个 CSV 放进手机上的**同一个文件夹**（例如 `Download/ledger-sample/`）→ 设置 →「导入 CSV（增量合并）」→ 选中那个文件夹。选完会先给你看「将写入多少行、跳过多少行」，确认之后才真的写库；同一 id 覆盖、新 id 追加，本机独有的数据不会被删。

## 格式约定（想自己造数据时看这里）

- 金额两种单位，**不许混**：JSON 与数据库是**整数分**（`12345` = 123.45 元），CSV 是**元、固定两位小数**（`123.45`）。
- 日期：交易是 `YYYY-MM-DD`，预算是 `YYYY-MM`。
- 枚举取值：账户 `type` = `CASH` / `DEBIT` / `CREDIT` / `WECHAT` / `ALIPAY` / `OTHER`；交易 `type` = `EXPENSE` / `INCOME` / `TRANSFER` / `BALANCE_ADJUST`；分类 `kind` = `EXPENSE` / `INCOME`。
- 转账必须填 `account_id` 与 `to_account_id`（两端不同账户、不带分类）；余额调整只填 `account_id`、金额可负但不能为 0；收支必须有 category_id。
- 分类 id 1~18 与应用预置分类一致（支出 11 个 + 收入 7 个），19/20 是「早餐 / 外卖」两个二级分类（`parent_id = 1`）。
- CSV 表头是稳定契约，改名等于换格式——导入按列名解析，认不出的行会跳过并告诉你「哪个文件的第几行、为什么」。

## 这份数据怎么来的

用应用**自己的导出代码**（`BackupRepository.exportJson()` / `exportCsvFiles()`）生成，不是手抄的，所以格式与应用实际导出完全一致。
`app/src/test/java/com/nimo/bookkeeping/docs/SampleDataFilesTest.kt` 会在单元测试里校验这几个文件：JSON 能被 `BackupCodec` 解码且行数正确、CSV 是 BOM+CRLF+元、四个文件解析零问题、对空库预览写入 44 行、JSON 能真的恢复且往返一致。文件被改坏或格式漂移，测试会直接失败。
