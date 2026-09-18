# 子代理那层：嵌套转写（A1）＋「运行中」两行（B2）

日期：2026-09-18　分支：`v0.2.21-dev`　`pluginVersion=0.2.21`
状态：**实现中**

---

## 1. 用户的选择

选型台（`web/tools/subagent-view.mjs` → `build/probe/subagent-view.html`，七个方案里的 A0–A3 / B0–B3）
用户挑的是 **A1（嵌套：子代理单独一块）＋ B2（运行中那行改两行）**。

A1 解决的问题是**归属**：今天子代理跑的工具卡平铺在主转写里，跟主线程自己跑的长得一样
（插件**完全没有用** `parent_tool_use_id` —— 侧车只在一处写了 `null`，web 侧一个引用都没有）。
B2 解决的问题是**活性**：那行只有最初的任务名，跑着的时候看不出它在干嘛。

## 2. 先钉事实（探针 `sidecar/tools/probe-subagent-text.mjs`）

三跑，全部真起会话：**甲**＝今天的选项（对照）、**乙**＝两个开关都开、**丙**＝开开关且让子代理一直忙到 78 秒
（丙是为了让 `summary` 有机会生成，甲/乙的子代理 2 秒就干完了，那个字段自然一直是空的）。

| # | 事实 | 怎么量到的 |
|---|---|---|
| 1 | 子代理的消息（`assistant`/`user`）带**三个**额外字段：`parent_tool_use_id`（= 主线程那条 Task 的 `tool_use.id`）、`subagent_type`（`"Explore"`）、`task_description` | 三跑样本的 keys 实证 |
| 2 | 主线程消息上 `parent_tool_use_id` **字段在、值是 `null`**（不是缺字段） | 甲跑打印 |
| 3 | 默认只转发子代理的 `tool_use`/`tool_result`：甲跑带 parent 的 assistant 只有 2 条，**正文 0、思考 0** | 甲 |
| 4 | 开 `forwardSubagentText` 后，**正文与思考也带 parent 过来**（乙：正文 1 条、思考 2 条） | 乙 |
| 5 | **子代理的正文没有流式增量帧**：`stream_event` 带 parent 的 0 条，三跑一致 | 甲/乙/丙 |
| 6 | `task_progress.description` 是**当前这一步**（`"Reading fixture\note-17.txt"`），**每一步都来**；`task_progress.summary` 只有开了 `agentProgressSummaries` 才有，**每 ~30s 一条**，是模型写的一句话（`"Reading note-26.txt fixture file"`） | 丙（78 秒里出了两条：+42.4s、+72.9s） |
| 7 | `task_progress.usage.total_tokens` 每步都在涨（丙：2.5w → 3.1w） | 丙 |
| 8 | **子代理的 Read 不经过 `canUseTool`**（丙跑了 60 次 Read，回调 0 次） | 丙 |
| 9 | 磁盘上：主转写 `<sessionId>.jsonl` **不含**子代理消息；子代理在自己的 `subagents/agent-<agentId>.jsonl`（`isSidechain: true`，**没有** parent 字段），旁边 `<…>.meta.json` 里有 `{agentType, description, toolUseId}` ——**toolUseId 就是接回那张 Task 卡的钥匙** | 读探针留下的转写目录 |
| 10 | SDK 的 `getSubagentMessages()` 返回的 `SessionMessage` **带 `parent_tool_use_id`**（sdk.d.ts:6055） | 读 .d.ts |
| 11 | 探针跑在 `deepseek-v4-flash`（本机第三方配置）上 —— 以上结论对 Anthropic 自家模型是否逐字一致，**未验证** | init 事件里的 model |

事实 6 是这一版最值钱的一条：**"现在在干嘛"不用买**。`description` 每步都来、免费；
`summary` 是更顺眼的一句话，但每 30 秒才有一句，而且要开开关。

## 3. A1：嵌套

### 3.1 数据通路（四层，都是一小笔）

```
SDK(forwardSubagentText) → 侧车透传 → MessageRenderer.kt 认 parent → TranscriptOp 带上它 → web 分组渲染
```

- **侧车**：`session.js` 的 options 加 `forwardSubagentText: true`；
- **`RenderItem`**（`MessageRenderer.kt`）：`ToolUse` / `AssistantText` / `Thinking` 各加一个
  `parent: String?`（`AssistantDelta` 不用 —— 事实 5 说子代理正文没有增量帧）；
- **`TranscriptItem`**（`TranscriptOp.kt`）：对应项加同名字段，**只在非空时写进 JSON**；
- **web**：`ToolUseItem` / `AssistantItem` / `ThinkingItem` 加可选 `parent`，渲染时分组。

### 3.2 渲染：分组，不是重排

`Transcript.tsx` 现在把 `state.items` 平铺着渲染。加一层**纯函数** `nestByParent(items)`：

- 带 `parent` 的项 → 挂到 `toolUseId === parent` 的那张卡下；
- **父项找不到**（老版本 Kotlin 不送、或 id 对不上）→ **就地留在主流水里**，不丢。
  这条与 `ToolResultItem` 的容忍口径一致："配不上就当没有，挂错卡片比不显示更糟"。

子代理那一块画在 Task 卡的展开体里（缩进 + 左侧一条强调色竖线 + 一层浅底），
块头写 `子代理的对话 Explore · N 次工具调用`（`Explore` 来自事实 1 的 `subagent_type`）。

**默认展开**：A1 的全部价值就是"看得见子代理在干嘛"，默认收着等于把它退化成 A3。
卡头给计数与 token，嫌长的点一下收起。**不做"跑完自动收起"** —— 那会在你正读最后一句时把它抽走。

### 3.3 回放（历史会话）也做

事实 9 + 10 给了完整的钥匙，所以这条不做成"已知缺口"：

- 侧车的历史那条路（`index.js` 的 `getSessionMessages` 分支）**追加**：扫同目录下的
  `subagents/*.meta.json`，拿 `toolUseId` 与 `agentType`，对每个 agent 调
  `sessionApi.getSubagentMessages(sessionId, agentId)`，把这些消息**按 parent 插到对应位置**；
- 之后它们与实时消息走**同一条**渲染路径（Kotlin 的 `MessageRenderer` → web 分组），
  所以"实时有嵌套、恢复出来没有"这个不一致不会出现。

代价：回放时多几个文件读与一次 SDK 调用；失败**不能让整次恢复失败**（读不到就当没有子代理）。

## 4. B2：运行中那行改两行

今天 `RunDetail.taskRow` 是一行：`listOfNotNull(kind, detail ?: label)`。改两行：

```
第一行：Explore  找一下 token 刷新的调用点        ← task_started.description（任务名，一直有）
第二行：正在核对 TokenStore 的刷新路径            ← summary ?: task_progress.description（活着的那行）
```

- **优先级**：`summary`（模型写的一句话，每 ~30s）优先，没有就退到 `description`（当前步骤，每步都有）。
  于是**短子代理也有第二行**（只是更像"正在读 X"），长子代理每 30 秒换成一句人话。
- `RunStatusTracker` 的 `task_progress` 分支改成 `detail = summary ?? description ?? prev.detail`。
- 第二行用次要色 + 小一号字；`detail` 为空时（老 Kotlin 送的旧状态）**保持今天的一行**，不退化成空行。
- `agentProgressSummaries: true` 照开（用户选的就是这个方案）：代价按 SDK 文档是
  "the fork reuses the subagent's model and prompt cache, so cost is typically minimal"。
  它值不值，等真机上跑一次长子代理就知道 —— 关掉它就是删一行。

## 5. 明确不做

- **子代理的权限归属提示**（"这条权限询问来自 Explore 子代理"）：事实 8 说子代理的 Read 根本不问；
  真会问的是写与 Bash。等真机上真撞见一次再说，现在做等于凭空造需求。
- **子代理那块的"跑完自动收起"**、以及任何折叠记忆。
- **递归嵌套**（子代理里再派子代理）：事实 1 的 `parent_agent_id`（sdk.d.ts:6062）说明这结构存在，
  但今天最多一层；出现深一层时，我们的分组逻辑天然会把孙代理挂到子代理那张卡上（同一个 key），
  没有额外工作 —— 但**不专门测**。
- **把 `task_progress.description` 的路径洗成人话**（`Reading fixture\note-17.txt` 里的反斜杠照原样显示）。

## 6. 验证

- **侧车**：`session.js` 的两个开关有用例钉住（照现有 options 用例的写法）；
  回放那条 join 用**假 sessionApi** 测（meta 缺失 / 调用抛错都不能让恢复失败）。
- **Kotlin**：`MessageRenderer` 带 parent 的三个 case 各一条；`RunStatusTracker` 的
  `summary ?? description` 两条（有 summary 用 summary、没有用 description、都没有保持原值）。
- **web**：`nestByParent` 纯函数用例（正常分组 / 父项缺失时留在主流水 / 顺序不变）；
  `RunDetail` 那两行有 Swing 用例。
- **出图**：`build/probe/subagent-nesting.png`（一张真的带子代理的转写，深色浅色各一张），
  看完只回答四件事：嵌套的缩进看不看得出层级、竖线会不会太抢、两行那处会不会挤、深浅两套都要能读。
- **真机冒烟**（探针替代不了）：派一个真子代理，看嵌套块是不是边跑边填、以及那句 30 秒一次的话是否真出现。
