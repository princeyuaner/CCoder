# CCoder 0.2.13：文件回滚 / 预置 prompt / MCP·hooks

日期：2026-09-15　分支：`v0.2.13-dev`　`pluginVersion=0.2.13`

三件事一起做（用户已定），但它们**互相独立**：各自可单独回滚，共享的只有一次版本开启与一次收尾。
本文件一份三章。

---

# 第一章　文件回滚

## 1.1 要解决的问题

用户原话的意思是"Claude 改错了，能撤回去"。主设计文档 §1.4 从第一版起就把这件事列在
"本版明确不做"里：

> 文件回滚 UI（SDK 有 `Query.rewindFiles()`，本版不接）

这一版把它划掉。

## 1.2 前提：探针量到的事实

全部由 `sidecar/tools/probe-rewind.mjs` 实测得出（可重跑：`node sidecar/tools/probe-rewind.mjs`）。

| # | 事实 | 怎么量到的 |
|---|---|---|
| 1 | **开关是必需的**：不带 `enableFileCheckpointing` 时 `canRewind=false`，error 原文 `File rewinding is not enabled.` | 对照臂（Q1） |
| 2 | **插件自己铸的 uuid 被采纳**：`dryRun(客户端 uuid).canRewind === true` | Q3 |
| 3 | 回放事件里**没有** user 消息自身的 uuid 回显（`user` 事件一条都没打出来）—— 所以"从事件流取 CLI 的 uuid"这条后备路**不存在** | Q2 |
| 4 | **真回滚确实改文件**：`target.txt` 从 CCC 回到 AAA；**回滚点之后新建的文件会被删掉** | Q4 |
| 5 | **`filesChanged` 不可用**：dryRun 回 `[]`，真回滚回 `null`、`+0 −0`，而盘上确实动了两个文件 | Q4 |
| 6 | **语义 = 恢复到「该条消息发出那一刻」的文件状态**：T1 发出时文件还不存在 → 回滚到 T1 就是把它删掉 | Q8 |
| 7 | 连续回滚正确（独立目录、单会话） | Q8 |
| 8 | 跨会话回滚也正确（同目录先后两个会话，后者回滚到自己那条 → 拿到前者留下的内容） | Q8b |
| 9 | `init` 事件的 `capabilities` 是 `null` —— CLI 没有在能力位里明示 checkpoint 支持 | Q2 |
| 10 | 与 `sessionStore` 互斥（同用会抛）；本仓库没用它 | `sdk.d.ts` |

### 一条未复现的异常（记在这里，不掩盖）

主线那次跑出过：会话里 `M1=AAA → M2=CCC → M3=late → 回滚 M1(得 AAA ✓) → M4=DDD → 回滚 M4`
得到 **CCC**（M2 写的值）。按事实 6 应当是 AAA。

随后两次定向复现（Q8 的独立目录、Q8b 的跨会话）**都没出现**。成因未知。
它不阻塞本版，但有两处后果写进了设计：确认框不得承诺"一定回到那一刻"（见 §1.5），
且这条异常连同探针一起留给以后复查。

## 1.3 语义（界面文案的唯一依据）

> **回滚到某条消息 = 把这些文件恢复到「你发出这条消息那一刻」的状态。**

推论，全部要落到文案上：

- 它**连这条消息自己的改动一起撤销**（不是"撤销这条之后的"）
- 那条消息之后**新建**的文件会被**删掉**
- **对话与上下文一个字都不变** —— `rewindFiles` 只动文件（名字里就写着 Files）。
  用户心里的"回滚"多半来自 CLI 的 `/rewind`（连对话一起回），不写清楚就是我们让他误解

## 1.4 线路

```
插件铸 uuid ──随 send 下行──▶ sidecar 塞进 item.uuid ──▶ CLI 记下这个联结点
   │
   └─ 插件同时记住「uuid ↔ 那条消息」
                                    ▲
用户点 ↺ ──▶ dryRun（只看 canRewind）──▶ 确认框 ──▶ 真回滚 ──▶ 系统提示
```

**为什么是插件铸 uuid（而不是 sidecar 铸了回传）**：事实 2 说客户端的 uuid 被采纳；
而 sidecar 铸的话，uuid 必然比"推气泡那一刻"晚一拍，要把它补回一条已经推出去的转写项，
就得新造 update 语义 —— 那正是 `TranscriptOp.kt:38` 明令不做的事。插件铸则 id 在发送那一刻就在手上。

**只有本次会话发出的消息可回滚**：`resume` 恢复进来的历史消息没有 uuid（那是 CLI 的历史，
不经我们的 `send`）。挂上 ↺ 却点不动比不挂更糟 —— 所以历史消息不挂。

## 1.5 UI

- **入口**：每条**本会话发出的**用户消息上一个 ↺（探针事实 2 让这条成立）
- **点击 → 确认框**，内容两段：
  1. **将要恢复的文件清单** —— **不能来自 SDK**（事实 5：`filesChanged` 是空的），
     只能**从转写区自己算**：那条消息之后的 `Write` / `Edit` / `MultiEdit` 工具卡里出现过的文件。
     措辞照实说是"这条之后改过的文件"，不假装是 SDK 报的
  2. 一句语义说明：恢复到你发送这条消息之前的状态；之后新建的文件会被删除；**对话不会变**
- **确认 → 真回滚 → 转写区追加一条系统提示**：「已回滚到这条消息之前（N 个文件）」
  —— N 同样来自我们自己那份清单，不来自回执
- **dryRun 仍然要跑**，但只用来拿 `canRewind` 与 `error` —— 它给不了文件清单，
  别把它当预览用

### 失败与边界（不静默）

| 情况 | 表现 |
|---|---|
| `canRewind=false` | 弹框把 `error` 原文带上。最常见的是 `File rewinding is not enabled.`（用户在设置里关了检查点） |
| 那条之后没有任何文件改动 | 仍然弹框，但把"这条之后没有文件改动"说明白 |
| 会话忙 | 入口不可点（回滚要在空闲时做），点了给一句说明 |
| 回滚中途会话被中断 | 明确报一句 |

## 1.6 检查点开关

`enableFileCheckpointing` 默认**开**，设置里可关（用户已定）。关掉时行为与今天一字不差。

代价照实记：开=每个被改的文件在改之前先备份一份。探针没有量出这个开销的上限
（单会话样本不足以外推），列为**已知未知**。

## 1.7 测试

| 层 | 测什么 |
|---|---|
| 探针 | §1.2 那十条 —— 唯一能证明"CLI 真的会还原文件"的手段，单测永远测不出 |
| 协议层 | `ProtocolTest`：编码形状（`dryRun` 显式布尔）、解析（缺 `id` 丢弃）、`responseIdOf` 认它 |
| sidecar | 假 session 注入，**断言调用形状**（`(userMessageId, dryRun)` 两个位置参数）；方法缺失时抛明确错误而非静默成功 |
| 纯函数 | 「那条消息之后改过哪些文件」的算法（从工具卡算）—— 抽成纯函数才好测 |
| 渲染探针 | 确认框与清单的排版 |

## 1.8 明确不做

- **对话回滚**（`/rewind` 那种连上下文一起回）—— `rewindFiles` 做不到
- **单张工具卡级别的撤销** —— API 不支持，粒度最细就是"回到发某条消息之前"
- **回滚痕迹持久化** —— `rewindFiles` 不动对话，CLI 的会话历史里本来就不留痕，恢复会话后看不出来
- 恢复进来的历史消息上挂 ↺

---

# 第二章　预置 prompt

## 2.1 要解决的问题

主设计文档 §1.3 推迟的四个差异化方向之一：**预置 prompt 快捷入口**。
输入框能打 `/` 出 CLI 命令，但用户自己那几条常用说法没有任何入口。

## 2.2 存储：APP 级

`.idea/` 整个被 gitignore（`git ls-files .idea` 为空），所以 PROJECT 级的 `ClaudeSettings`
**存不了可共享的东西**；而预置 prompt 是个人工作流快捷方式，跨项目复用才对。
→ 新建 APP 级 `PromptPresets`（`ccoderPromptPresets.xml`），照 `ModelProfiles` 的服务骨架
（不需要 `SecretStore` —— 预设里没有密钥）。

数据形状照 XmlSerializer 的要求（`var` + 默认值、集合用 `MutableList`）：
`PromptPreset(id, name, content)`。

## 2.3 入口与语义

- 走 `/` 补全的「预设」分组，**排在第一组**（分组标题只在换组时插一条，所以必须整组连续排）
- 插入走 `ClaudePanel.addToComposer`（末尾追加），与"添加选区 / 添加文件"同一语义；
  **不做**整串替换
- 预设与 CLI 命令**同名时两条都出现**，分组不同 —— 预设那条的 `description` 放内容首行摘录
- **不跟会话清**：`ClaudePanel.kt:1830` 会话停止时会清 `sendableNames` 并关补全，
  而预设来自设置，不该跟着一起消失

## 2.4 明确不做

占位符（`{{selection}}` 之类）、按项目覆盖、导入导出、拖拽排序、直接绑快捷键发送。

---

# 第三章　MCP / hooks

## 3.1 前提：探针量到的事实

由 `sidecar/tools/probe-mcp-hooks.mjs` 实测（`node sidecar/tools/probe-mcp-hooks.mjs`）。

| # | 事实 | 怎么量到的 |
|---|---|---|
| 1 | **`.mcp.json` 的形状成立**：项目根下 `{"mcpServers": {"名字": {"command": ..., "args": [...]}}}` 会被读到 —— 配一个指向不存在命令的 server，它以 `status=failed`、`scope=project` 出现在 `mcpServerStatus()` 里 | Q1 |
| 2 | **不需要批准也会被读**（`enableAllProjectMcpServers` 前后 status 不变） | Q2 |
| 3 | `mcpServerStatus()` 会列出**所有来源**的 server，并带 `scope`（`user` / `project`） | 基线 |
| 4 | 本机基线：user 级有 `codegraph`、`vibe-trading`（都是 failed），project 级有 `code-review-graph`（pending） | 基线 |

**hooks 那一半尚未实测**（`--hooks` 才跑，要花模型回合）：`.claude/settings.json` 的
`hooks` 键是否被读、`hook_started` / `hook_response` 事件的形状、以及
`Options.settings`（flag 层，不落盘）能不能作为后备。
**本文件在那一跑之前不对 hooks 的实现方式下结论。**

## 3.2 已知的 SDK 形状（读 d.ts 得出，非实测）

- `mcpServers` 是纯 JSON，可过 NDJSON 线；`hooks` 是 **JS 回调**，**函数过不了线** ——
  所以 hooks 只能写文件或走 `Options.settings`
- 项目 `.mcp.json` 的 server 有批准机制（`enableAllProjectMcpServers` / `enabledMcpjsonServers`）；
  但事实 2 显示本机不需要批准就读到了，**这一条要在实现时再确认**
- 33 个 hook 事件；本版只暴露常用的 7 个

## 3.3 设计要点

- **两个页签**：`McpSettingsPage`（左配置 / 右实时状态）、`HooksSettingsPage`
- **合并写**：`.mcp.json` 与 `.claude/settings.json` 里都可能有别人的东西，
  一个字节都不能碰；序列化必须稳定（固定缩进、尾换行），否则每次保存都产生 diff 噪音。
  纯函数 + 单测钉死
- **"下次会话生效"要说在明面上**：改完 `.mcp.json` 当前会话不会重连
- 面板同时显示**实时状态**（`mcpServerStatus()`，只读），并带上 `scope` —— 用户能一眼看到
  哪些是项目里的、哪些是他自己全局配的
- **明确不做**：独立的"测连接"按钮、`sdk` / `claudeai-proxy` 两种形状、
  `timeout` / `alwaysLoad` 高级项、其余 26 个 hook 事件

## 3.4 连带效应（这一条是本版新发现的）

`MessageRenderer.kt:280` 现在是：

```kotlin
"hook_started", "hook_response" -> emptyList()
```

hooks **被整条丢弃**。配上 hooks 面板之后，一个"拦住写文件"的 PreToolUse hook 会让写入
静默不发生，而转写区一个字都不解释。要改成**只在阻断 / 失败时出一行**（用户已定）。

---

# 第四章　三件事共享的

- **版本**：`v0.2.13-dev` + `pluginVersion=0.2.13`，照 `chore: 开 0.2.x` 的成规
- **提交粒度**：每件事自成一段；纯新增可独立提交，"接线"必须一次提交（这个仓库栽过
  "改了一半、组件加了没人用"的跟头）
- **不静默**：所有失败路径都要说一句话，这是本仓库的既定要求
- **探针留在仓库里**：`probe-rewind.mjs`、`probe-mcp-hooks.mjs` 不是一次性脚本，
  本文件里那些"事实"要靠它们重跑
- **渲染探针产出要真的打开看**：这个仓库有过"单测全绿、那个圆点还是错的"的记录
