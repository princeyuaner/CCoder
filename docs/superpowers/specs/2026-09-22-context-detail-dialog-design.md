# 上下文卡：点开看明细（方案乙 · 对话框）

日期：2026-09-22　分支：`v0.2.25-dev`　`pluginVersion=0.2.25`
状态：**已实现**

选型台：`docs/design/context-details.html`（三套画法 + 四条事实 + 三件小事）。
用户从甲/乙/丙里挑的是 **乙 · 居中对话框，分类表 + 分页签清单**。

---

## 1. 用户要什么

> 「点开上下文后我希望显示上下文的具体详情，sdk有查询接口吗？」

**有，而且我们早就在调了。** `sidecar/index.js` 的 `contextUsage` 命令走的就是
`Query.getContextUsage()`（SDK 原话：*"Structured twin of the /context report — the data a
client needs to render the context-usage card **without parsing the markdown table**"*），
但只取了 `totalTokens` / `rawMaxTokens` 两个数，其余全丢。

今天点开那张卡看到的 `buildContextDetail` 就只有"已用 / 窗口 / 剩余"三行 ——
数据不缺，缺的是把它接出来。

## 2. 数据形状（`sdk.d.ts` 的 `SDKContextUsage`）

| 字段 | 内容 |
|---|---|
| `categories[]` | `name`(英文展示名) · `tokens` · `kind: 'used' \| 'free' \| 'buffer' \| 'deferred'` |
| `mcp_tools[]` | 线名 `mcp__server__tool` + `server_name` + tokens |
| `memory_files[]` | 路径 + 来源标签（Project / User）+ tokens |
| `agents[]` | `agent_type` + `source` + tokens |
| `skills[]?` | 名字 + 来源 + 所属插件 + tokens（没有就整个字段不在） |
| `over_limit?` | `tokens_over` + `kind: 'hard_limit' \| 'compaction_window'` |
| `model` / `percentage` | 按哪个模型算的 / 四舍五入的比例 |

**拿不到的**：逐条消息的占用明细。粒度的天花板就是分类表 + 四张清单 —— 所以这一页
不做成"能一条条翻账"的样子（那是撒谎）。

## 3. 四条硬规矩（写错任一条，界面上都能看出来）

1. **分类按 `kind` 判，不按英文 `name`**（SDK 原话："Classify on this, never on the English name"）。
   `name` 只是**显示串**，说改就改。
2. **`deferred` 不计入用量**：不占堆叠条的宽度、不进求和。它只回答"我有 N 个 MCP 工具、
   一共多少 token"（决定要不要关掉几个）。
3. **求和不能自己拍**：`used` 各段之和要与 `total_tokens` 对得上；对不上就说明我们
   漏了某一行 —— 那种情况**照实显示 SDK 给的百分比**，不拿自己算的比率覆盖它。
4. **字段名可能是 camelCase 也可能是 snake_case**：`index.js:614` 那条注释记着实测
   是驼峰（`totalTokens`），而 d.ts 写的是下划线。新加的这几层**两种拼法都读**
   （`str("serverName") ?: str("server_name")`），读不到就退回空 —— 一条读不出来
   不该让整页空着。

## 4. 形状（乙）

```
┌ 上下文详情 ─────────────────  deepseek-v4-flash · 1M ┐
│  268k / 1M   27%                                     │
│  ▓▓▓▓▓▓▓▓▓▓▓▓░░░░░▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒   │  ← 堆叠条
│  [分类] [MCP 工具 12] [记忆文件 2] [子代理 3]          │  ← 空清单不给页签
│  对话消息                     250.0k                  │
│  ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔                        │
│  系统提示                      12.4k                  │
│  …                                                    │
│  MCP 工具（窗口外）             41.8k                  │
│  不占窗口 —— 列出来只为知会                            │
├───────────────────────────────────────────────────────┤
│                          [复制明细]  [关闭]            │
└───────────────────────────────────────────────────────┘
```

- 页签**只有 分类 + 有内容的清单**：一条都没有的清单不给页签（点进去看见空白页
  比看不见那个页签更糟）。
- 「复制明细」复制一份 Markdown（与设计稿里那张表同构），给"要贴给别人看"用。
- 超窗时顶上多一行：**硬顶**与**压缩窗**措辞不同（前者会被 API 拒绝，后者会自动压缩）。

## 5. 文案口径（选型稿里的"其一"）

CLI 给的 `name` / `type` / `source` 都是**英文展示串**。取的是**折中**：

- **认识的行给键**（`context.row.messages` 这种，中英两份词表都写）；
- **不认识的原样透传** —— CLI 哪天改了措辞，那一行退回英文，而不是空着或显示键名。

理由：全映射（一律给键）会在 CLI 改词时**静默少一行**；全英文（原样显示）会让中文界面
里恒定夹英文。折中把"不认识"变成设计的一部分，代价是维护一张短映射表。

映射表的键是**英文名**，所以它同时是"CLI 改了词"的探测器：词表里有键、界面上却出了英文，
说明该更新映射了（`ContextDetailTest` 钉住已知的那几个）。

## 6. 刻意不做的

- **不改轮询口径**：现在 `ready` + 每次 `result` 之后各问一次（`detail` 默认就是 `'full'`）。
  改成"轮询 summary、点开才 full"是个独立的优化 —— 它会动到卡面那个百分比，
  得先量一遍 summary 与 full 的数是否一致，不该搭这次的车。
- **不换卡面**：那张卡还是水位 + 百分比，一个字不改。这次只加"点开之后"。
- **不动另外两张卡的浮层**：任务 / 运行中照旧走 `showTogglePopup`。
  三张卡的手势因此不再一模一样 —— 这是选型稿里写明并接受的代价（乙的"装得下 + 一屏给全"）。

## 7. 验证

- 纯函数（`ContextDetail.kt`）：kind 判类、deferred 不进求和、未知英文名透传、
  over_limit 两种措辞 —— `ContextDetailTest`。
- 协议：camelCase 与 snake_case 两种拼法都能解析 —— `ProtocolContextDetailTest`。
- 文案：新键在两份词表里都有、且都被引用（`TextKeysTest` 双向扫）。
- 观感：`ContextDetailRenderProbe` 把对话框内容渲染成 PNG（`build/probe/`），
  离屏看一眼深浅两色 —— 单测看属性，看不出"好不好看"。
