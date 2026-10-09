# MCP 的两颗动作：重连 / 启用停用（2026-09-28）

> 起念：MCP 服务器死了只能重启 IDE。SDK 从 0.3.28x 起给了四条控制请求，
> 我们一条都没接（开版挂账第 3 条）。
>
> **结论先说**：三条里**只做两条**（`mcp_reconnect` / `mcp_toggle`），第三条
> `mcp_set_servers` **测出来是个空炮**，不接 —— 理由见 §3。

## §1 量在写之前

`sidecar/tools/probe-mcp-controls.mjs`（照 `probe-mcp-hooks.mjs` 那套：配一个指向
**不存在命令**的 server，它必然 `failed`，而"它在列表里"正是我们要的前提。
全程只发控制请求，**不跑模型**）。2026-09-28 实测：

| 问题 | 结果 |
|---|---|
| 四条方法在这台 CLI 上存在吗 | 都在（`mcpServerStatus` / `reconnectMcpServer` / `toggleMcpServer` / `setMcpServers`） |
| `.mcp.json` 里的 server 会被读到吗 | 会（`ccoder-probe-bogus` status=failed，「Connection closed」） |
| `toggle(name,false)` 够得着它吗 | **够** —— 不抛错，status 当场变 `disabled` |
| `toggle(name,true)` 呢 | 会**真去连**；假 server 连不上 → 抛 `Connection closed`，status 留在 `failed` |
| `reconnect(name)` 呢 | 同上：够得着，连不上就抛那句 |
| `setMcpServers({})` 呢 | 对 `.mcp.json` 里的 server **纹丝不动** |

## §2 两条够得着的

- **重连**：掉线、失败、`needs-auth` 之后让它再试一次。
- **启用 / 停用**：停用是**当场生效**的（`disabled`），启用会真去连。

两条都**没有前置条件**：不需要我们先动态注册过什么，用户配在项目里的那些就吃这两颗。
这推翻了我的预设 —— `setMcpServers` 的文档把"动态"说得很死，我原以为
`mcp_toggle` 也一样；量出来才知道它管的是配置文件里的。

## §3 第三条为什么不接

SDK 对 `setMcpServers` 的原话（`sdk.d.ts:3176-3198`）：

> This only affects servers added dynamically via this method or the SDK.
> Servers configured via settings files are not affected.

而我们的 sidecar **从来不往 `Options.mcpServers` 里塞东西**（全仓零命中），
server 全来自项目根的 `.mcp.json`，由 CLI 自己读（`McpConfig.kt` 只写那个文件）。
所以今天接上它，作用的集合是**空的** —— 一颗点了什么都不发生的按钮，正是这个仓库
最不肯画的东西（`CardAction.kt` 那条注释）。

要让它有意义，得先做一个**产品决定**：把 MCP 配置搬进 `Options.mcpServers`
（插件自己管 server，动态增删、当即生效），而现在的模型是"写 `.mcp.json`，
**下次会话生效**"（界面上那句话就写在 `settings.mcp.formHint` 里，设计稿也写了）。
两条路不能同时走。**留作决定，不夹在这一版里偷着换。**

## §4 协议：失败也要回执

别的控制请求失败时只有一条 `error` 广播，而那条**不带 id**（收端配不上对，
只能等 10 秒超时）。这两个动作**不能**这样：

发起它们的地方是**设置框**，而设置框是模态的（`DialogWrapper.show()`）——
弹着的时候聊天被挡在后面。一条用户看不见的广播等于没报。所以：

- 成功 → `{type:'mcpControlled', id, action, serverName, enabled, error:null}`
- 失败 → **广播 + 回执都发**：广播进聊天留痕（事后能查"刚才为什么没连上"），
  回执进设置框当场把话说清（`error` 带原因）。

`enabled` 三态分得开：`true` / `false` / `null`（reconnect 没有这一档）。
"缺字段"**不当成停用**：那等于替用户关了一个 server（sidecar 与协议各有用例）。

## §5 那条线怎么走

设置框拿不到会话（会话与 `SidecarClient` 都住在 `ClaudePanel` 里），
`McpStatus` 的注释早把这条道理写下了 —— 所以**反向再要一个中立发布点**：

    McpStatus  ：面板 → 页面（server 现在什么样）
    McpControl ：页面 → 面板 → 页面（动作与它的结果）

`McpControl` 三个要点：

1. **`armed` = 有人接得住**。没人接就**一颗按钮都不画**。
2. **认主人摘**（`attach(owner)` / `detach(owner)`，同 `PendingPermissionCount.clear(this)`）：
   `dispose` 那刻 `isSelected(this)` 已经问不准，而问错的方向正好是最糟的那个
   —— 后台标签把前台刚挂上的线摘掉。
3. **收不下就把线放掉**（`send` 收到 false）：会话在"画按钮"与"点下去"之间断了时，
   不放的话那几颗按钮会一直画着、点一次石沉大海一次。会话再起来时面板会重新挂
   （`addNotify` 与 **start 之后**两处 —— 重启会话不经过切标签）。

## §6 界面（出图看过三遍）

> **2026-10-09 注**：本节三处是**旧两栏页**上出图看出来的；回接到卡片页（2026-09-30 方案一）
> 后整个页面换了形状，同样的道理按新形状重落了一遍（见 §9）—— 这张图也已按新页重出。

`build/probe/settings-mcp-actions.png`。三处是**出图才看出来**的：

1. 名字 + 动作 + 状态挤在一行时，`BorderLayout` 会让 WEST 与 EAST **直接重叠**
   （"我自己全局配的已连接"）→ 动作**另起一行**（只在 `armed` 时才有那一行，
   所以没会话接的那一屏与从前一模一样）。
2. 失败那句用一行的 `JBLabel` 必然被裁（"…MCP error -3:"）→ 换**折行的文本区**，
   照同步页 `logArea` 那套。
3. 但它摆在 `statusSlot` 里（卡身里又套了一层 BoxLayout）时被缩成**一半宽**，
   折成三截还裁一截 → 挪成**卡身的直接子件**、只钉高度，就铺满了。
   多套一层的代价看不出来，只在出图时露馅。

失败按**用户刚点的那一颗**挑措辞（重连 / 启用 / 停用 三句）——不按回执里的布尔值推：
那个字段可能是从我们自己的编码器漏出去的，而用户点的是哪一颗，页面最清楚。

## §7 顺带看到、这一版没动的

- **状态行本来就有一处重叠**：名字长的那些（"我自己全局配的"）与右边
  "已连接 · 3 个工具"会**贴在一起**（`settings-mcp.png` 那张旧图上就有，
  不是这次弄出来的）。这一栏两百来像素装不下 18 个字。改法是名字省略号 + tooltip，
  或者状态另起一行 —— 两条都会改动这一页的样子，**留给你挑**。
- **失败的控制请求没有 id**（协议层的老毛病，所有 op 都这样）：靠 10 秒超时收场。
  这次的两个动作绕开了它（回执带 error），但别的 op 还在里头。

## §8 2026-10-09 复量：SDK 0.3.293 下结论逐字不变

回接之前先把探针**原样**重跑（`node sidecar/tools/probe-mcp-controls.mjs`，仍然只发控制
请求、不跑模型）。两次量法完全一样，唯一变量是这中间 SDK 从 0.3.283 升到了 0.3.293
（285–287 连续修过一串 `mcp_toggle` 的坑：不关连接、对"还没连上 / 改过配置"的 server
不生效、把在途的工具调用挂住）：

| 问 | 2026-09-28（0.3.283） | 2026-10-09（0.3.293） |
|---|---|---|
| 四条方法都在 | ✓ | ✓ |
| `.mcp.json` 里的 server 被读到 | status=failed | status=**pending** —— **唯一可见的差别**：新 SDK 的 MCP 后台连接，失败原因晚一拍才到 |
| `toggle(name,false)` | 当场 → `disabled`，不抛 | 同（不变） |
| `toggle(name,true)` | 真去连；连不上抛 `Connection closed` | 同（不变） |
| `reconnect(name)` | 够得着；抛连不上那句 | 同（不变） |
| `setMcpServers({})` | 配置文件里的纹丝不动 | 同（不变）—— 新注释补了 plugin-owned server 的豁免一句，对我们（server 全来自 `.mcp.json`）结论不动 |

所以：**三条里只做两条的结论继续成立**，`setMcpServers` 继续不接；§3 那个产品决定
（要不要把 MCP 配置搬进 `Options.mcpServers`）仍然留给你。

## §9 回接到卡片页（2026-10-09）

代码与设计稿在 `attic/mcp-controls` 上放了十天，期间 MCP 页按选型稿改成了**一台一张卡片**
（2026-09-30）。回接就落在卡片页上 —— §4、§5 的线路一字未动，界面换形状：

- **卡脚分两组**：左 = 动这个**会话**的（重连 / 停用·启用，当即生效），右 = 动**文件**里
  那条配置的（编辑 / 删除，下次会话生效）。会话那组只在**有活着的会话接得住、且这个
  server 在会话里**时才画 —— 没会话接的那一屏与 0.2.31 出图的样子一致（对照
  `settings-mcp.png`）。
- **失败那句放页最上面**（列的第一个子件），红字折行 —— 旧页"说在状态卡最上面"的等价落位。
  出图逮到一处：滚动壳与视口不透明时整块是一张**白底空窗**；壳与视口都透明之后才成了
  一句话衬在页底上。
- **全局那一张卡也给会话动作**：它在这页上不能改不能删（插件只写项目里的 `.mcp.json`），
  但"死了重连一下"恰恰是它最需要的 —— 动的是会话，与文件是谁写的无关。
- 出图：`build/probe/settings-mcp-actions.png`（"有会话接 + 刚失败过一次"那一屏：
  失败的 / 已停用的 / 全局的三张卡 + 顶上那句失败）。
