# 使用量 · opencode Go 额度层（对账层的第二个供应商）

日期：2026-10-13　分支：`v0.2.41-dev`
状态：**已实现**（验证见 §7）

前置：`2026-10-12-usage-bill-design.md`（方案 A 对账层，DMXAPI 那一家）。
本篇是给「使用量」加的第二个供应商：选中 opencode 配置时，常驻条与统计框显示
**opencode Go 订阅的三档额度窗口**。

---

## 1. 用户要什么

> 「使用量这里，如果我选择了opencode，希望能显示当前的额度信息」（2026-10-13，带截图）

即：模型配置选中 `opencode.ai` 那条时，沿用 DMXAPI 账单已经占上的那两处
（常驻条尾段 + 统计框对账条位置）显示 opencode 的额度。三个展示口径**用户已选**：

1. 常驻条**三档全上**：`5h 12% · 周 4% · 月 2%`；
2. 数字给**已用**百分比（不是剩余）——与 Claude Code `/usage`、opencode 控制台一致；
3. 统计框里**每档一根进度条** + 行尾「12% · 20:46 重置」。

## 2. 口径（先钉死，再写码）

### 接口（2026-10-13 本机实测，密钥来自本机 opencode 的 auth.json）

- `GET https://opencode.ai/zen/go/v1/usage`，`Authorization: Bearer <密钥>`；
- 返回三档窗口，每档 `{ status, percent, resetsAt }`：

  ```json
  {"usage":{"rolling":{"status":"ok","percent":12,"resetsAt":"2026-10-13T12:46:37.000Z"},
            "weekly":{"status":"ok","percent":4,"resetsAt":"2026-10-19T00:00:00.000Z"},
            "monthly":{"status":"ok","percent":2,"resetsAt":"2026-11-13T06:58:17.000Z"}}}
  ```

- `rolling` = 5 小时滚动窗；`weekly` = 7 天窗（周一 00:00 UTC 重置）；
  `monthly` = 订阅周期窗（跟着付费周期走）；
- **只有百分比，没有金额** —— 美元数额只在 opencode 自己的控制台里，接口不给。
  界面上也就没有金额可写（与 DMX 的「实扣¥」不是一回事）；
- `status=rate-limited` = 那一档已打满（`percent` 未必再更新，按 100 处理）；
- 查询是纯状态读，**不消耗额度**；但第三方插件的建议是别高于每几分钟查一次
  —— 本实现节流窗取 **120 秒**（DMX 是 60 秒，见 §4）。

### 两条实测，留给后人（别再踩）

- **Cloudflare**：`java.net.http` 的**默认 UA 就能过**（200）；`python-urllib`
  的默认签名会被 **1010 拒**（`browser_signature_banned`）。所以 `fetchOpencodeUsage`
  别手痒换 UA / 换 HTTP 库；
- **Go 订阅与 Zen 按量共用 `opencode.ai` 主机名**：只有 Go 有官方额度接口；
  Zen（按量付费）**没有**公开余额接口（官方 issue #44189，只有带浏览器会话的
  console 端点 / 页面抓取）。所以判据只能按主机名，Zen 账号查询会失败 →
  **静默消失**（与 DMX 查不到同一条路，不伪造余额）。

### 与 DMXAPI 的对账层异同（一处一张对照）

| | DMXAPI 账单 | opencode Go 额度 |
|---|---|---|
| 密钥位置 | query 参数 `?key=` | `Authorization: Bearer` 头 |
| 数据 | 人民币实扣 + 余额 | 三档已用百分比 |
| 节流窗 | 60 秒（站点限速 10 次/分 per-IP） | 120 秒（插件建议别太频） |
| 识别 | host `dmxapi.cn`（含子域） | host `opencode.ai`（含子域） |
| 互斥 | 一条配置只可能命中一家 —— 两个槽互斥显示 | 同左 |

密钥同样从 PasswordSafe 现取（`ModelProfiles.secretOf`，即会话在用的那枚），
不新增存储、不进 sidecar；查询在插件进程里发（池化线程，首次碰 PasswordSafe 不堵 EDT）。

## 3. 数据层（`ui/UsageBilling.kt`，加一节）

- `OPENCODE_USAGE_ENDPOINT` / `OPENCODE_PROVIDER_NAME`（`"opencode Go"`，品牌不翻译）；
- `QuotaWindowKind`（Rolling/Weekly/Monthly，枚举顺序就是界面顺序）、
  `QuotaWindow(kind, percent, rateLimited, resetsAt: Instant?)`、`OpencodeQuota(windows, fetchedAt)`；
- `opencodeQuotaEndpoint(baseUrl)`：与 `dmxBillEndpoint` 同一套（URI host 精确 /
  点边界后缀；`opencode.ai.evil.com`、`myopencode.ai` 都不认）；
- `parseOpencodeQuota(json, at)` 的宽容规则（都有用例钉着）：
  - `error` 字段优先抛 `UsageBillError`；非 JSON / 缺 `usage` / **一档都没解析出来** → 抛；
  - **单档缺 `percent` 跳过那一档** —— 不拿 0 糊弄（0% 会被读成"这档没用过"，是假话）；
  - 限流档 `percent` 抬到 100（"打满"与"已限流"自洽）；
  - `resetsAt` 认不出给 null（那一格不写重置时间，不毁整档）；
- `fetchOpencodeUsage(url, key)`：与 `fetchDmxBill` 共用 `billHttp`；非 200 先试 `error` 字段；
- **`UsageBillGate` 提炼成 `RefreshGate(intervalMs)` 类**，两个实例：
  `UsageBillGate = RefreshGate(60_000)`（DMX）、`OpencodeQuotaGate = RefreshGate(120_000)`。
  闸仍是应用级全局（多标签共享）：DMX 的限速是 per-IP、opencode 的是账号级，
  但"别家面板各自查"这个毛病一样。

## 4. 界面层

- **常驻条**（`UsageStrip.kt` / `UsageStripData` 加 `opencodeQuota`）：尾部一段
  `5h 12% · 周 4% · 月 2%`（短档名走词表、数字代码排、缺档跳过；
  `formatQuotaPercent` 取整）。**已知让位**：段变长后 420 宽下 14 天走势会被
  挤掉不画 —— 那是条自己既有的行为（地方不够就不硬塞），拉宽窗口走势就回来；
- **统计框**（`UsageDialog.kt` 的 `quotaPanel`）：与账单条同款虚线框（`DashPanel`），
  三行 = 档名 + 进度条 + 行尾「12% · 20:46 重置」（限流补「已限流」；没重置时间不写那截）；
  - 进度条复用表里的 `UsageShareBar`；**正常档的条色 = 强调文字色**（`accentTextFor`，
    与图里第一支模型色同一支）—— 第一版用选中底色（`Tree.selectionBackground`），
    浅色下与轨道糊成一片（渲染探针里现形），改掉；
  - 条色门槛沿用状态卡水位那条比例条：**70% 琥珀、90% 红**（`warningColor` / `dangerColor`）；
  - 行首/行尾先量后排：三行共用同一左右列宽；
  - 右下角「更新 20:03 ↻」与账单条同款（↻ = 强制重查，`refreshLabel` 改成接
    解好的 tooltip 字，账单/额度各说各的）；
  - 脚注在有额度时换成「额度为 opencode Go 账号三档窗口的已用比例（本账号）」；
- **空态**（本地一条用量都没有）：与账单一致，额度条也不出现（那句实话说的是"本地数据"）。
  常驻条不受影响 —— 它照常显示额度段。

## 5. 接线（`ClaudePanel`）

- `refreshUsageBill` 变**编排器**：`refreshDmxBill` + `refreshOpencodeQuota` 各走各的闸；
  触发点不变（报告到达 / 每回合 tick / 打开统计框 / 换模型配置）；
- 新增槽位：`opencodeQuota` / `opencodeQuotaProfileId` / `quotaAttemptProfileId`
  （换配置先清旧值；刚换配置算强制，不等 120 秒窗）；
- `submitUsageStrip` 拆出 `pushUsageStrip()`：账目状态变了就按**原样的走势**重提
  （账单、额度两条合并逻辑只有一处，晚到的那份不该重置走势）；
- 失败静默：日志一条（不带密钥、不带 URL），界面退回"没有那一段"。

## 6. 验证

- **Kotlin 全量绿**（`./gradlew test`，3m01s；改条色后又全量复跑一次）：
  新增 `UsageBillingTest` 6 条（识别 8 例 / 解析三态 —— 实测样本、限流+缺档+坏时间、
  三种抛错 / 闸：120 秒窗、与 60 秒那条各测各的）、
  `UsagePeriodsTest` +2 条（额度段三态 —— 三档全上、缺档跳过、只估无成本；
  取整与重置时刻格式化 —— 24 小时内给钟点、之外带日子）。
  `TextKeysTest` / `TextCatalogTest` / `EnglishCopyTest` 过（12 个新键两份词表齐、
  占位符连续、无 CJK）。
- **渲染探针**（`build/usage-dialog-probe-quota(-dark).png` / `usage-strip-probe-quota.png`）：
  深浅各一版已离屏看过；条色对比问题是这一轮探针里现形、改完又看过的
  —— "选中底色当进度条太淡"这条就是探针的价值。扫描线实测：12% 档的填充
  39/361px（几何对）。
- **待人工实机**（探针不碰网）：① 选中 opencode 配置用一会儿 → 常驻条出现
  `5h …% · 周 …% · 月 …%`、统计框出现三根进度条；② 条上/框里 ↻ 能刷新
  （更新时间会跳；120 秒内非强制查询会被闸挡住，这是设计）；③ 换成非 opencode
  配置 → 额度段整个消失（不显示别家的额度）；④ Go 订阅外的 opencode 账号
  （Zen 按量）或无网 → 静默无段（日志里一条 `额度查询失败`）。

## 7. 留的坑与注释（都在代码里）

- Cloudflare 只认默认 UA（python 那类签名 1010）—— `fetchOpencodeUsage` 头注；
- Zen 无余额接口（#44189）—— 主机名识别一节注；
- 缺档不编、全缺抛错、坏时间 null、限流抬 100 —— `parseOpencodeQuota` 逐条注释；
- 探针不碰网、真账要人工实机 —— 同 DMX 篇。

## 8. 追加：弹窗宽度（2026-10-13 用户报「这个框太宽了，缩小一半」）

- 案发：13 个长名模型把**内容最小宽**撑到 2358px（探针实测：`panel min=2358`）
  —— 图例第一版是一条不换行的 X 排；表格无责，名字列本就钳死 170。
  Windows 上弹窗被顶到近满屏（实测 1568，屏幕 1920×1080 / 缩放 1.0）。
- 修法：`legendRows` 按内容宽（设计宽 − 28 内边距）贪婪折行；`USAGE_DIALOG_WIDTH`
  620 → 780（= 用户看到的那一版的一半）；表格名字列 170 → 200 + 全名进 tooltip。
- 护栏：`UsageDialogLayoutTest`（13 模型时 min ≤ 800、preferred = 780）；
  渲染探针 `usage-dialog-probe-many.png`（图例 4 行、无裁字）。

## 9. 追加：DeepSeek 余额（2026-10-13 用户报「如果选择的是deepseek，使用量中要显示当前余额信息」）

- 接口（官方文档 + **无效密钥实测**错误形状）：`GET https://api.deepseek.com/user/balance`，
  Bearer。三条口径 —— 金额是**字符串**；可能多币种（CNY/USD）**取 CNY 优先**；
  **只有余额、没有用量窗口**。401 的错是**对象形** `{"error":{"message":…,"type":…}}`
  —— `errorMessageOf` 因此扩成两形通吃（DMX 的字符串形不受影响，旧用例钉着）。
- 识别：host `deepseek.com`（含子域）。用户那条「我的ds」（`api.deepseek.com/anthropic`）
  命中；**公司 tokenhub 是转卖的，不认**（它没有这条账）。
- UI：常驻条尾段 `余额 ¥110.00`（余额段比额度段短，420 宽的走势条保得住）；统计框一条
  （余额 + 赠额/充值 + 更新/↻ + 脚注），`is_available=false` 缀琥珀「已不可用」。
- 节流 120 秒（与 opencode 同档）；换配置强制首查、失败静默 —— 全同前两家的纪律。
- 验证：`UsageBillingTest` +5 组用例、`UsagePeriodsTest` +2；探针
  `usage-dialog-probe-balance(-dark).png` / `usage-strip-probe-balance.png` 已离屏看过；
  全量绿（3m02s）。
