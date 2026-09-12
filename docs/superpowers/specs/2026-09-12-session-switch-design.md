# CCoder 会话切换设计

## 1. 背景与目标

### 1.1 为什么做

当前插件只有一个会话，关掉就没了。`ClaudePanel.kt:222` 的注释把这件事写得很直白：

> sidecar 目前不支持会话恢复（SDK 的 session id 只用于显示，没有回传给启动参数），断开重连会让模型丢掉上下文。

这条限制现在可以解除了。SDK 从 0.3.268 起提供了一整套会话 API，实测**运行时真导出**（不只是类型声明）：

```
listSessions   getSessionInfo   getSessionMessages
forkSession    renameSession    deleteSession      tagSession
```

本版做的是**接线，不是造轮子**：一个会话列表，点开恢复历史，接着聊。

### 1.2 已确认的约束

| 决策 | 取值 | 理由 |
|---|---|---|
| 范围 | 历史列表 + 恢复，**不做并行** | 单会话模型不变 |
| 恢复语义 | `resume`（接着写），**不 fork** | 与终端 `claude --resume` 一致；原会话继续增长 |
| 列表位置 | ClaudePanel **顶部状态栏**右侧 | 不引入第二个工具窗口 |
| 列表排布 | 单行紧凑，元信息靠右 | 设计稿 A（`docs/design/session-switch.html`） |
| 分支字段 | **不显示** | 实测 19 条会话全在 `v0.2.0-dev`，无信息量 |
| 忙时 | 可点，弹出后列表置灰 + 给一行说明 | "点了才说" |
| 噪音会话 | **不弱化** | 它们是真实历史，且排序已保证近期工作在顶上 |
| 会话过滤 | **不过滤** | 见 §4.2，实测 `includeProgrammatic: false` 会滤掉插件自己的会话 |

### 1.3 本版明确不做

- 多会话并行 / 后台会话（`--bg`）
- 重命名、删除、打标签（`renameSession` / `deleteSession` / `tagSession` 都在，但不接）
- `forkSession`（恢复语义已定为"接着写"）
- 虚拟滚动、会话搜索
- 自动恢复上次会话（关掉再打开仍是新会话）
- 自己解析 `~/.claude/projects/*.jsonl`

---

## 2. 架构

### 2.1 为什么**不**抽 project service

选"独立工具窗口"时本需要抽一层（两个窗口共享会话状态）。改为顶部状态栏后，会话列表和聊天面板是**同一个组件**，`ClaudePanel` 继续持有 `proc`/`client`/`ready`/`busy`（`ClaudePanel.kt:124-137`）即可，那 841 行不动。

这是本设计里最大的一处减法：**不重构 `ClaudePanel`**。

### 2.2 数据流

```
                    ┌──────────────── ClaudePanel ────────────────┐
                    │                                             │
  [顶部状态栏]  ──会话名?──▶  JBPopup 列表                          │
                    │            │                                │
                    │            │ 选中                            │
                    │            ▼                                │
                    │      stopSession()  ──▶ startSession(resume)  │
                    │            │                                │
  [转写区 JCEF] ◀── Reset + 历史 ops ── TranscriptPump ◀── renderPrompt / render
                    └─────────────────────────────────────────────┘
                                     │ NDJSON
                                     ▼
                              node sidecar ──▶ SDK ──▶ claude CLI
```

### 2.3 组件布局

顶部那一行（`ClaudePanel.kt:165-168`）现在只有左边的 `statusLabel`，右边整片空着。加一个会话标签：

```
[● 已连接]                          [还可以做什么功能 ▾]
 └ 现有                               └ 新增，靠右，可点
```

---

## 3. 协议扩展

### 3.1 新方法

```
插件 → sidecar
  listSessions   { id, limit, offset }
  loadHistory    { id, sessionId }
  start          { ...原有字段, resumeSessionId? }

sidecar → 插件
  sessions       { id, sessions[] }        ← 带 id 回显
  history        { id, sessionId, items[] }
```

`StartParams` 增加 `resumeSessionId`，透传为 SDK 的 `Options.resume`（`sdk.d.ts:1934`）。

### 3.2 id 回显与待决表

**现状：收发是纯单向的。** `SidecarClient` 只有一个 listener，没有请求/响应配对（`SidecarClient.kt:46`）。每个消息虽然带 `id`，但响应不回显它。

所以要在 Kotlin 侧加一个待决表：请求发出时以 `id` 登记，收到 `sessions`/`history` 时按 `id` 唤醒。这套模式在 sidecar 侧已经跑着（`sidecar/session.js:41` 的权限待决表），两边对称。

**必须处理的三条终止路径**（与 spec §6.2 规则① 同源——挂起的 Promise 不能泄漏）：

1. 收到对应的响应 → 唤醒
2. 进程退出 / `stopSession()` → 全部以失败唤醒
3. 超时 → 唤醒并报错

### 3.3 容错规则

沿用既有原则：**未知即忽略，畸形即丢弃**（spec §3.3）。

- `sessions` 缺 `id`：丢弃整条（无法配对）
- 单条 session 缺 `sessionId`：跳过该条，不废掉整个列表
- `items` 里看不懂的条目：跳过，不抛错
- `summary` 与 `firstPrompt` 都为空：标题显示 `（无标题）`，不显示空白行

---

## 4. 列表

### 4.1 数据来源与排序

```js
listSessions({ dir: project.basePath, limit: 50 })
```

实测 **140ms，纯本地文件系统读取，不启动 CLI 进程**。

返回字段（`SDKSessionInfo`，`sdk.d.ts:5154`）：

| 字段 | 用途 |
|---|---|
| `sessionId` | 恢复时传给 `resume`；当前会话比对 |
| `summary` | 列表标题（SDK 已取「自定义标题 → 自动摘要 → 首个提问」） |
| `firstPrompt` | `summary` 为空时的回退 |
| `lastModified` | 相对时间；SDK 已按此倒序 |
| `fileSize` / `createdAt` / `cwd` / `tag` / `customTitle` | 本版不用 |

### 4.2 为什么不过滤 —— 一个实测出来的坑

SDK 文档说：

> IDE session pickers pass `false` for parity with terminal `/resume`.

看起来正该传 `includeProgrammatic: false`。**但实测它把 19 条会话全滤光了**（`includeProgrammatic=false: 0`）。原因是插件自己开的会话也是 SDK 会话（entrypoint `sdk-ts`），会被一并滤掉——**列表里永远看不到自己刚恢复过的那个会话**。

这个坑不实测根本看不出来，因为传 `false` 不是报错，是安静地返回空。

所以：**用默认值（`true`），不传这个参数**。

代价是列表里混着终端 Claude Code 的会话和 `"11"`、`"你好"` 这类噪音。这个代价是接受的（§1.2），而且它带来一个真实好处：**终端里聊到一半，可以切进 IDE 接着聊**。

### 4.3 行渲染规格

单行紧凑（设计稿 A）：

```
[✓]  还可以做什么功能                          12 分钟前
 │   └ 标题，粗体（仅当前会话），超宽省略号
 └ 当前会话的勾，宽 13px 固定 —— 出现或消失时标题不左右跳
```

沿用 `buildModeList` 的既有做法（`ComposerMode.kt:89`）：未选中也占同样的缩进位，避免文字跳动。

- 标题超宽：标题是唯一可伸缩的元素（`flex: 1 1 auto; min-width: 0`），时间与勾是固定宽度的兄弟节点。**时间不参与收缩**——被挤掉的话排序就看不出来了
- 底部：`还有更早的会话…`（仅当返回条数达到 `limit` 时显示）。不做翻页

### 4.4 当前会话标识

两个来源，缺一不可：

| 场景 | 来源 |
|---|---|
| resume 进来的 | 构造时即知（就是我们传的那个 id） |
| 全新会话 | `system`/`init` 事件里的 `session_id` |

第二项**不需要新协议消息**：`MessageRenderer.kt:122` 已经在读同一个字段（就是它渲染出「会话 xxxx · 模型 yyyy」那行），加一行存下来即可。

---

## 5. 切换流程

### 5.1 忙时拦截

`busy == true` 或 `permissionQueue.totalPending > 0` 时：**标签仍可点，弹出后列表置灰**，顶部给一行说明：

> 当前回合还在跑。先按**停止**，再切会话 —— 否则这个回合会被腰斩，挂着的权限询问也会一并作废。

理由：切换要 `stopSession()`，会腰斩正在跑的回合；挂着的权限卡片也会作废。拦住比默默允许安全。

「点了才说」而不是「一开始就变灰」，是因为点不动的东西容易被当成 bug。

### 5.2 步骤

```
1. 检查忙否（§5.1）
2. stopSession()              ← 现有，spec §7.4 顺序
3. startSession(resumeSessionId = 选中的 id)
4. 等 ready
5. push(TranscriptOp.Reset)   ← 清空转写区
6. loadHistory(sessionId) → 逐条渲染 → 灌进同一个 TranscriptPump
7. 更新顶部会话名 + 列表打勾
```

**不复用 `restartSession()`**。它刻意**保留**转写历史（`ClaudePanel.kt:381`：「只换会话，转写历史留在界面上供参考」），而切换会话要的正是清空 —— 语义相反。

### 5.3 失败路径

`resume` 失败（会话文件被删、ID 不存在）时：

- 停掉刚起的 sidecar（不留半成品）
- 转写区**保持清空后的状态**，不能留下一半的历史
- 状态栏显示失败原因，会话标签回到 `新会话`
- **不静默回退到新会话** —— 那会让用户以为历史加载好了（spec §7.5 反对静默丢失）

---

## 6. 历史回放

### 6.1 复用渲染管线

`getSessionMessages` 返回的形状与流式事件**同构**：

```
{ type, uuid, session_id, message, parent_tool_use_id, parent_agent_id, timestamp }
```

所以回放 = 把每条 item 包成 `SidecarMessage.Event`，喂给既有链路：

```
item → MessageRenderer.Event → RenderItem → toOp() → TranscriptPump → React
```

**不新增渲染路径**，React 侧零改动。

### 6.2 user 消息那条路是空的

`MessageRenderer.renderEvent`（`MessageRenderer.kt:38-45`）只认 `assistant` / `result` / `system` / `stream_event`，`type: "user"` 落到 `else`。

live 路径下这没问题——用户气泡是 `sendCurrentInput()` 直接推的，不依赖事件。**回放不行**，历史里的提问必须从事件里挖。

而这里有个必须做的判定。实测最大会话的 user 消息构成：

```
user 消息 247 条
├── 工具结果   236 条   ← 必须丢弃
└── 真实提问    11 条   ← 只有这些要渲染
```

判定规则：`content` 是数组且含 `tool_result` → 丢弃；否则取文本块。

**做法**：给 `MessageRenderer` 加一个**只在回放路径调用**的入口（如 `renderPrompt(event): String?`），**不碰 live 路径**——碰了会双重渲染（live 下用户气泡已经 push 过一次）。

### 6.3 批大小上限

`TranscriptPump` 每 16ms 刷一次，且一次 flush 会把缓冲区**全部**发出去（`TranscriptPump.kt:49-67`）。回放若一次性 enqueue 804 条，下一拍就是一批 2.6MB 过 JCEF 桥。

所以要给 pump 加一个**批大小上限**（建议 200）：超出部分留在缓冲里等下一拍。这是对既有代码的改动，需要单测覆盖。

### 6.4 量级（实测）

| 会话 | 文件 | 消息数 | JSON | 取回耗时 |
|---|---|---|---|---|
| 最大 | 25.3 MB | 804 条 | 2.6 MB | 162 ms |
| 典型 | 149 KB | 3 条 | 2 KB | 2 ms |

**全量回放可行**，不需要截断。26MB 的文件里大量是工具结果和增量帧，实际消息数只有 804 条。

---

## 7. 顺带抽取

这次改动会让同一段代码出现第三份拷贝，值得抽：

`JBPopup` 的创建块（`ClaudePanel.kt:329-352`）现在在"任务详情"和"权限模式"各有一份，会话列表是第三份。抽成 `showTogglePopup(anchor, content, onClosed)`，把「再点一次收起」和 `showAboveOrBelow` 的位置计算一起收进去。

`popupAnchorY`（`ComposerStrip.kt:104`）**不需要改**：它先试下方、下方不够翻上方、都放不下贴顶，是方向无关的。顶部锚点走的是第一条分支。

这是被本次改动逼出来的抽取，不是顺手重构别处。

---

## 8. 测试策略

### 8.1 Node 侧

`sidecar/test/index.test.js`，注入假 `sessionFactory`（现成模式）：

- `listSessions` / `loadHistory` 的转发与参数透传
- **没有活会话时也能应答**（这是设计的关键点：列表不需要先起会话）
- SDK 抛错 → 以 `error` 回执，而非静默

### 8.2 Kotlin 侧

| 目标 | 用例 |
|---|---|
| `ProtocolTest` | 新消息编解码；缺 `id` 的 `sessions` 被丢弃；单条缺 `sessionId` 只跳过该条；`items` 里的畸形条目被跳过 |
| `SidecarClientTest` | 按 `id` 配对唤醒；进程退出时全部待决项以失败唤醒；超时唤醒 |
| `MessageRendererTest` | `renderPrompt` 对三类输入：真实提问 / 含 `tool_result` 的数组 / 畸形 content |
| `TranscriptPumpTest` | 批大小上限：enqueue 500 条，首批不超过 200，剩余在下一拍发出 |
| `SessionSwitchStateTest` | 忙时能否切换的判定（见下） |

**`ClaudePanel` 本身不可单测**，它依赖平台类（`ToolWindowManager`、`ApplicationManager`），而本项目的测试全部是纯 JVM 单测——`build.gradle.kts:32-35` 明确说了不引入平台测试框架。

所以忙时判定要抽成纯函数放在独立文件里，照 `MainButtonState.kt` 的既有做法（那是"按钮该显示什么"的纯函数，同样是为了可测）。`ClaudePanel` 只负责把它的结果画出来。

### 8.3 手工冒烟

1. **切换最大的那个会话**（804 条）——看回放卡不卡、有没有半截
2. **忙时点列表** ——确认列表置灰且说明出现
3. **切到一个已删除的会话 id** ——确认是明确的失败，不是静默的新会话
4. **恢复后再关掉 IDE 重开** ——确认是全新会话（本版不自动恢复），列表里能找到刚才那个

---

## 9. 附录：实测证据

均在 2026-09-12、本机、`@anthropic-ai/claude-agent-sdk@0.3.268`、项目 `C:\Users\CY\Desktop\CCoder` 上取得。

### 9.1 SDK 会话 API 是运行时真导出的

```
$ node -e "import('...sdk.mjs').then(m => console.log(Object.keys(m).filter(k => /session/i.test(k))))"
InMemorySessionStore, deleteSession, foldSessionSummary, forkSession,
getSessionInfo, getSessionMessages, importSessionToStore, listSessions,
renameSession, tagSession
```

### 9.2 `listSessions` 是纯本地读取

```
listSessions({ dir, limit: 8 })  →  8 条，耗时 140ms
```

字段：`sessionId, summary, lastModified, fileSize, customTitle, firstPrompt, gitBranch, cwd, tag, createdAt`

### 9.3 `includeProgrammatic: false` 会滤掉全部会话

```
全部: 19   includeProgrammatic=false: 0   includeWorktrees=false: 19
```

本机 19 条会话**全部**被判定为程序化会话。这是 §4.2 的直接依据。

### 9.4 回放量级与 user 消息构成

```
最大会话 d9617553  25.3MB → 804 条消息 / 2610KB JSON / 162ms
type 分布: { user: 247, assistant: 557 }
user 里：真实提问 11 条，工具结果 236 条

典型会话 5afd6725  149KB → 3 条消息 / 2ms
```

### 9.5 分支字段没有信息量

19 条会话的 `gitBranch` 全部是 `v0.2.0-dev`。这是 §1.2 里否掉分支徽章的依据。

### 9.6 会话文件首行不是消息

```
{ type, operation, timestamp, sessionId }
```

是一行 header，没有 `entrypoint` 字段。所以"自己解析 jsonl 来判断会话来源"这条路不可行——这也是 §1.3 排除它的理由之一。

### 9.7 `resume` 之后 `init` 会重发，且 id 与传入的一致

做法：造一个**一次性会话**（不碰任何真实会话）→ `resume` 它（**不 fork**，走的正是切换时那条路），问完两个都删。

```
resume 后 init 出现次数: 1
resume 传的 id: 0e343811-7ae9-44c1-8dbf-bfbed3e46b22
init 报的 id  : 0e343811-7ae9-44c1-8dbf-bfbed3e46b22
两者一致: true
init 带的 model: deepseek-flash[1m][1m]
```

两条结论：

1. **`init` 会重发** → 切换后模型名标签会自己更新，Task 10 不需要在 resume 路径上做占位处理。
2. **`init` 报的 id 与 `resume` 传入的一致** → §4.4 的当前会话标识两个来源不会打架，可以放心用。

**探针本身的教训（记下来免得再踩）：不能用 `forkSession: true` 做这个探针。** fork 必然产生新 id，
于是"init 报的 id 与 `resume` 的一致"这条判读**永远不可能成立**。第一版就是这么写的，
跑出来一个无法判读的结果（而且它把 `interrupt()` 放在 `break` 之后调用，传输已关，
脚本死在清理之前，留下一个 fork 会话没删）。

原计划用 fork 是为了"不污染真实会话"—— 那个担心用**自造一次性会话**就能解决，
不必牺牲判读力。实现时若还要跑类似探针，照 9.7 这个做法。

---

## 10. 风险与未决

| 风险 | 影响 | 缓解 |
|---|---|---|
| `resume` 一个正被终端占用的会话 | 两边同时写同一个 jsonl | 本版不做检测。**若实践出问题**，可在恢复前比对该会话的 `lastModified` 是否在近期变化 |
| 回放期间用户又发了消息 | 历史与实时消息交错 | 回放完成前禁用输入（同 `busy` 的处理） |
| `listSessions` 在会话极多时变慢 | 列表卡顿 | 实测 19 条 140ms；`limit: 50` 封顶。真变慢再加 `offset` 分页 |
| SDK 版本变化导致会话文件格式变更 | 回放解析失效 | 回放**不解析文件**，只消费 `getSessionMessages` 的返回值 |
| `ready.sessionId` 的误导性 | —— | 它回显的是**请求参数**（`sidecar/index.js:72`），全新会话时为 `null`。真正的 id 在 `system`/`init` 事件里（§4.4）。**不要**改用它 |

### 待实现时确认

- ~~`resume` 之后 SDK 是否重发 `system`/`init`~~ → **已实测**，见 §9.7：会重发，且 id 与传入的一致。
  结论是 Task 10 不需要为 resume 路径做特殊处理。
- 忙时拦截阈值：`totalPending > 0` 是否过于严格（排队中的权限算不算"忙"）

### 实现前的前置工作

当前工作区 `v0.2.0-dev` 有大量未提交改动（输入区卡片化、任务条、AskUserQuestion 卡片等）。**开工前先提交或另起分支**，否则这次改动会和不相关的 UI 工作混在一起。

---

## 11. 模块划分

```
新增
├── docs/design/session-switch.html            设计稿（已存在）
├── src/main/kotlin/com/ccoder/ui/
│   ├── SessionLabel.kt                        会话标签（照 ModeLabel 的形状）
│   ├── SessionList.kt                         列表弹出层 + 行渲染
│   └── SessionSwitchState.kt                  忙时能否切换的纯判定（照 MainButtonState）
└── src/test/kotlin/com/ccoder/ui/
    ├── SessionListTest.kt
    ├── SessionLabelTest.kt
    └── SessionSwitchStateTest.kt

修改
├── src/main/kotlin/com/ccoder/sidecar/
│   ├── Protocol.kt                            + 3 个 encode、+ 2 个 parse 分支
│   ├── SidecarClient.kt                       + 待决表与配对
│   └── TranscriptOp.kt                        （可能不需要动 —— Reset 已有）
├── src/main/kotlin/com/ccoder/ui/
│   ├── ClaudePanel.kt                         顶部会话标签、切换流程、showTogglePopup
│   ├── MessageRenderer.kt                     + renderPrompt（只走回放路径）
│   └── TranscriptPump.kt                      + 批大小上限
├── sidecar/
│   ├── index.js                               + listSessions / loadHistory 分发
│   └── session.js                             + resumeSessionId 透传
└── src/test/kotlin/…                          见 §8.2
```
