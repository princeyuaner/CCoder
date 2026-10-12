# 使用量 · 真实金额：DMXAPI 对账层（方案 A）

日期：2026-10-12　分支：`v0.2.35-dev`
状态：**已实现**（验证见 §7）

选型台：`web/tools/usage-cost-view.mjs` → `build/probe/usage-cost-view.html`
（四个方案 A/B/C/D，用户挑的是 **A 对账层**）。

---

## 1. 用户要什么

> 「使用量这个功能，能否加个今日消耗的真实金额，使用 token 的信息，
> 比如如果我选的是 dmx 的话，他的查询地址是 https://tokenlog.dmxapi.cn/，
> 不要直接做，先给我出几个方案放浏览器给我选择」

四个方案（A 对账层 / B 人民币为纲 / C 真账账本 / D 零联网）画进选型台后，用户选 **A**：
估算照旧、实扣是**叠加层** —— 统计框三块砖下面多一条「DMXAPI 账单」对账条，
常驻条尾部多一段 `实扣¥…`。

## 2. 口径（先钉死，再写码）

### 账单 API（2026-10-12 从 tokenlog.dmxapi.cn 自带文档弹窗抄的 + 实测）

- `GET https://tokenlog.dmxapi.cn/?key=sk-…`，**免登录、返回 JSON**；不传时间参数 = **当天**；
  `start_date / end_date`（YYYY-MM-DD）可查区间，跨度上限 366 天；
- 一次查询给：`stats.total_quota`（额度）、`stats.total_prompt` / `stats.total_completion`、
  `token_balance` / `token_unlimited`、`model_stats[]`、`data[]` 逐条明细（含 `other` 里的缓存读写）；
- **单位：额度 ÷ 500000 = 元** —— 站点自己的 JS 就这么除（`(total_quota / 500000).toFixed(2)`）；
- 限速 **10 次/分钟**（per IP），超了 429；错误形状**实测**（错 key）：
  `400 {"error":"API Key 格式不正确（应为 sk- 开头）"}` —— 错误正文就是给人看的中文，日志里带上它。

### 实扣 ≠ 估算（界面各带各的口径字样，不许糊成一个数）

- 估算（现状）：本地账本 × API 单价，**美元**、只算**这台机器**、离线可用；
- 实扣：DMXAPI 的账，**人民币**、算这枚令牌下**所有来源**、含网关自己的缓存计价；
- **账单分不了「本项目」** —— 接口没有项目字段，口径只能是「本令牌 · 今天」（对账条上写死）。

### 密钥从哪来

当前标签选中的模型配置（`ClaudePanel.currentModel`）：`baseUrl` 命中 `dmxapi.cn`（含子域）
才认定 DMXAPI；密钥从 PasswordSafe 现取（`ModelProfiles.secretOf`，即会话在用的那枚）。
不新增存储、不给 sidecar；查询在插件进程里发（池化线程，首次碰 PasswordSafe 不堵 EDT）。

## 3. 数据层（`ui/UsageBilling.kt`，新文件）

- `dmxBillEndpoint(baseUrl)`：识别；null = 非 dmx → 整条不出现（"退回今天的样子"）；
- `dmxBillUrl(endpoint, key)`：拼 URL（key 转义）；
- `parseDmxBill(json)` → `UsageBill(provider, yuanToday, promptTokens, completionTokens, balanceYuan?, fetchedAt)`；
  `error` 字段优先抛 `UsageBillError`（错误形状的唯一出处）；
- `fetchDmxBill(url)`：`java.net.http`（toolchain 21），10s 超时；非 200 也先试 error 字段；
- `UsageBillGate`：**应用级**节流闸（60s 窗口 + 飞行中不重入；`force` 只跳过时间窗）。
  闸不放在面板上：限速是 **per-IP**，多标签各自查会把它打爆；账单是 token 级数据，
  全局一份闸在语义上也正好。

## 4. 界面层

- **常驻条**（`UsageStrip.kt`）：`UsageStripData` 加 `todayBillYuan`；串变成
  `今日 123M · ≈$12.4 · 实扣¥8.30`（没账单一模一样，两段各自缺席时省掉）；
- **统计框**（`UsageDialog.kt`）：三块砖之下加「对账条」（强调色虚线框、两行）：
  行 1 = `今日实扣 ¥8.30 · 令牌余额 ¥123.40 …… 更新 14:32 ↻`；
  行 2 = `DMXAPI 账单 · 本令牌（与项目无关） · 账单 tok …：提示 …（含缓存）· 补全 …`；
  脚注在有账单时换成 `… 实扣为 DMXAPI 令牌账（本令牌）`；
- 失败 / 非 dmx：对账条**整条不出现** —— 不占位、不报错（只落一条日志）。

## 5. 触发与节流（`ClaudePanel`）

报告到达、每回合 tick、打开统计框、换模型配置 → `refreshUsageBill()`（走闸）；
条上的 ↻ 是 `force=true`；**换配置时先把旧账清掉再查新的**（不显示另一枚令牌的账）；
失败保持旧值、只落日志（日志不带密钥、不带整条 URL）。

## 6. 与选型台的偏离（都留一句解释）

**账单 tokens 不拆「缓存读/写」**：选型台画了拆分，但公开 API 的 `stats` 只给提示/补全，
拆分的原料在 `data[].other` 里、要翻页聚合（`page_size` 上限 100）—— 为一句话多打十几次
请求不值当（限速还摆着）。行 2 写成 `提示 …（含缓存）· 补全 …`。

## 7. 验证

- **Kotlin 全量绿**（`./gradlew test`，2m09s）：新增 `UsageBillingTest` 8 条
  （识别 6 例 / URL 转义 / 解析三态 / 错误两态 / 闸的时间窗与飞行位）、
  `UsagePeriodsTest` +2 条（带实扣的条文案两态、实扣两位小数格式）；
  `TextKeysTest` / `TextCatalogTest` / `EnglishCopyTest` 过（新键两份词表齐、占位符连续、无 CJK）。
- **渲染探针**（深浅各一版 —— 深色是用户的日常主题）：`build/usage-dialog-probe-bill.png` /
  `-bill-dark.png` / `usage-strip-probe-bill.png`，已离屏看过：对账条深浅两套纸下都读得清，
  常驻条在 420 宽里没挤掉走势。
- **网络与密钥这两段探针够不着**（探针不碰网、单测环境没有 PasswordSafe）：
  待人工实机 —— ① 选中 dmx 配置用一会儿会话 → 常驻条出现「实扣¥…」、统计框出现对账条；
  ② 条上 ↻ 能刷新（更新时间会跳）；③ 换成非 dmx 配置 → 实扣段整个消失（不显示别枚令牌的账）；
  ④ 对账条的数是「本令牌·今天」，与「本项目」的估算对不上是**正常口径差**，不是 bug。
- 已知坑（都留下注释）：解析器对缺字段宽容（缺了当零）—— 站点改口不该让整条对账消失；
  非 200 也先试 `error` 字段（站点在 400/429 时同样给 JSON）。
