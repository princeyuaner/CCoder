# CCoder — PyCharm 调用 Claude Code 插件设计

日期：2026-09-11
状态：设计已确认，待实现

---

## 1. 背景与目标

### 1.1 为什么做

官方的 Claude Code JetBrains 插件本质是"把终端里的 Claude Code 搬进 IDE"：工具窗口聊天、diff 查看、选区上下文共享。**交互由用户手动发起，结果只落在聊天窗口里。**

CCoder 的目标是做官方插件做不到的事。具体功能方向暂缓决定（见 §1.3），但底座必须为此留出空间。

### 1.2 已确认的约束

| 决策 | 取值 | 理由 |
|---|---|---|
| 集成方式 | Node sidecar + Claude Agent SDK | SDK 的 `canUseTool` 回调是权限桥接的天然接口 |
| 首个版本范围 | 最小闭环 **+ 权限弹窗桥接** | 用户明确拒绝"静默拒绝"的体验 |
| 权限 UI 形态 | ToolWindow 内非模态卡片 | 见 §6.3 的补偿设计 |
| 插件语言 | Kotlin | IntelliJ 平台插件事实标准 |
| 目标 IDE | PyCharm 2025.3.1.1（本机版本） | |
| `claude` 可执行文件 | 运行时不打包，从设置/PATH 解析 | 见 §8 |

### 1.3 明确推迟的决策

**具体差异化功能**（"官方插件没有的那个能力"）暂缓。候选方向：结果标注到编辑器、事件驱动自动触发、批量/多会话并行、预置 prompt 快捷入口。

推迟是安全的：**这四个方向的底座是同一套**。先搭对底座，上面接什么都行。

### 1.4 本版明确不做

- 会话列表 / 多会话并行 / 会话重命名（用 `--session-id` / `--fork-session` / `--bg`）
- 编辑器内联标注、inspection 集成
- 事件驱动自动触发（保存/提交/测试失败）
- 批量处理
- 文件回滚 UI（SDK 有 `Query.rewindFiles()`，本版不接）
- 凭据管理（CLI 自己读 `~/.claude/settings.json`，插件不碰）

---

## 2. 架构

```
PyCharm (JVM / Kotlin)
└── CCoder plugin
    ├── ClaudeToolWindowFactory ── 注册工具窗口
    ├── ui/
    │   ├── ClaudePanel          ── 输入框 + 消息流 + 状态栏
    │   ├── MessageRenderer      ── SDKMessage → Swing 组件
    │   └── PermissionCard       ── 非模态权限卡片（§6）
    ├── sidecar/
    │   ├── SidecarProcess       ── 进程生命周期（§7）
    │   ├── SidecarClient        ── NDJSON-RPC 收发（§5）
    │   └── Protocol.kt          ── 消息类型定义
    ├── settings/ClaudeSettings  ── PersistentStateComponent（§9）
    └── ClaudeBundle             ── 文案资源

         │  stdin/stdout，换行分隔 JSON（NDJSON）
         ▼

Node Sidecar  (sidecar/index.js)
├── index.js       ── stdio NDJSON 循环
├── session.js     ── 包装 SDK query()，流式输入 + canUseTool
├── env.js         ── 子进程环境清洗（§4）
└── claude-path.js ── 可执行文件解析

         │  SDK 内部 spawn
         ▼

claude CLI 子进程  ← 用户已安装的那份，不打包
```

**进程是三层**：PyCharm → node sidecar → claude CLI。这个结构决定了 §7 的大部分复杂度。

---

## 3. 硬约束

以下三条不是设计选择，是实测和读源码得出的事实。违反任何一条都会导致功能不可用。

### 3.1 必须使用流式输入模式

`query()` 的 `prompt` 参数类型是 `string | AsyncIterable<SDKUserMessage>`（sdk.d.ts:2974），但 `Query` 接口上所有控制方法的注释都写着 *"only supported when streaming input/output is used"*（sdk.d.ts:2614-2616）：

- `interrupt()`（sdk.d.ts:2625）
- `setPermissionMode(mode)`（sdk.d.ts:2632）
- `setModel(model?)`（sdk.d.ts:2659）

传字符串 prompt = 永久放弃中断能力和运行时权限模式切换。**sidecar 从一开始就传 `AsyncIterable`。**

### 3.2 必须清洗子进程环境

**实测证据**（见 §11.1）：宿主注入的 `CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST=1` 会让 CLI 认为"provider 由宿主管"，从而跳过认证流程，返回 `authentication_failed`。

清洗后，用户 `~/.claude/settings.json` 中的 `env` 块（含 `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_BASE_URL` / 模型映射）会被 CLI 正常读取，**实测 `result: success`**。

要剥离的变量黑名单：

```
ANTHROPIC_MODEL
CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST
CLAUDE_CODE_ENTRYPOINT
CLAUDE_CODE_MESSAGING_TOKEN
CLAUDE_CODE_MESSAGING_SOCKET
CLAUDE_CODE_EXECPATH
CLAUDE_CODE_CHILD_SESSION
CLAUDE_USE_STDIN
CLAUDE_SESSION_ID
CLAUDE_CODE_SESSION_ID
```

前八项与 §11.1 的实测完全一致；最后两项（会话 ID）在实测中也一并剥离，列入黑名单以保证 sidecar 每次启动都是干净的会话上下文。

**黑名单是精确列表，不是前缀匹配。** `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`、`CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING`、`CLAUDE_CODE_DISABLE_1M_CONTEXT`、`CLAUDE_CODE_EFFORT_LEVEL` 等**不在**黑名单内，实测保留它们不影响认证。

其余变量照常继承（`PATH`、`HOME`、`USERPROFILE` 等是 CLI 定位配置所必需的）。

实现位置：`sidecar/env.js`，由 SDK `Options.env`（sdk.d.ts:1535，"omitted → inherits `process.env`"）注入。**这一组规则必须有单元测试钉死**（§10.1）。

### 3.3 必须容忍未知与噪声事件

两个独立原因：

1. `SDKMessage` 是 40+ 成员的联合类型（sdk.d.ts:4753），且会随版本增加
2. stdout 上会出现非 JSON 行。**实测**：`[claude-code:unrecognized_model] {...}` 混在 JSON 流里（见 §11.2）

规则：sidecar **原样透传** SDK 事件，不做白名单过滤；插件侧按 `type` 分发，**不认识的类型静默忽略**，绝不抛错或中断会话。

已知噪声（实测出现）：`SDKSystemMessage/subtype=hook_started`、`SDKSystemMessage/subtype=hook_response`。这两个默认折叠或隐藏。

---

## 4. 环境隔离

（约束见 §3.2，此处只写实现要点）

- `env.js` 导出 `buildChildEnv(baseEnv, overrides)`，返回清洗后的环境对象
- 黑名单以常量数组形式定义，便于测试和后续增补
- 插件设置允许用户**追加**环境变量覆盖（§9），但**不允许**移除黑名单项 —— 黑名单是正确性保证，不是偏好
- sidecar 自身进程继承完整环境（它需要 `PATH` 找 node），只在调用 SDK 时传清洗后的 `env`

---

## 5. 协议规范（插件 ↔ sidecar）

传输：stdin/stdout，**换行分隔 JSON（NDJSON）**，每条消息一行，UTF-8。

### 5.1 插件 → sidecar

| method | params | 说明 |
|---|---|---|
| `start` | `{cwd, permissionMode, model?, claudePath?, extraDirs?, envOverrides?}` | 建立会话。沿用启动的进程，仅调用一次 |
| `send` | `{text}` | 发送用户消息 |
| `interrupt` | `{}` | 中断当前回合（映射 `Query.interrupt()`） |
| `setPermissionMode` | `{mode}` | 运行时切换（映射 `Query.setPermissionMode()`） |
| `permissionDecision` | `{requestId, behavior, updatedPermissions?, message?}` | 权限卡片的结果（§6.2） |
| `stop` | `{}` | 优雅关闭 |

所有消息形如 `{"id": "<uuid>", "method": "...", "params": {...}}`。

### 5.2 sidecar → 插件

| type | 载荷 | 说明 |
|---|---|---|
| `ready` | `{sessionId, model, tools[]}` | SDK init 完成，携带 `SDKSystemMessage` 的关键字段 |
| `event` | `{event: <SDKMessage>}` | **原样透传**的 SDK 事件 |
| `permission` | `{requestId, toolName, input, title?, displayName?, description?, blockedPath?, decisionReason?, defaultToNo?, suppressAlwaysAllowRule?, suggestions?}` | 权限询问（§6.1） |
| `error` | `{message, code, fatal}` | 错误。`fatal=true` 表示会话已终止 |
| `exit` | `{code, signal}` | sidecar 退出（通常由插件收尾，见 §7.4） |

所有消息形如 `{"type": "...", ...}`，与 5.1 的 `method` 形态靠 `type` vs `method` 字段区分。

### 5.3 错误码

| code | 含义 | 插件行为 |
|---|---|---|
| `CLAUDE_NOT_FOUND` | 可执行文件找不到 | 显示设置引导，禁用输入框 |
| `NODE_NOT_FOUND` | node 不存在 | 同上 |
| `AUTH_FAILED` | 认证失败（SDK 的 `authentication_failed`） | 显示原始错误 + 提示检查 `~/.claude/settings.json` |
| `SDK_INIT_FAILED` | sidecar 启动 SDK 失败 | 显示 stderr 尾部 |

---

## 6. 权限桥接

### 6.1 流程

```
Claude 请求使用工具
  → SDK 调用 canUseTool(toolName, input, opts)          [sidecar]
  → sidecar 生成 requestId，发 permission 消息给插件
  → sidecar 返回一个【未决 Promise】
  → 插件在消息流中插入非模态权限卡片
  → 用户点击 拒绝 / 允许 / 总是允许
  → 插件发 permissionDecision 给 sidecar
  → sidecar resolve Promise
  → SDK 继续执行
```

### 6.2 三条强制规则

这三条来自 SDK 类型定义中的明文要求，不是 UI 偏好。

**规则 ①：必须永远 resolve。**

sdk.d.ts:203-207 原文警告：

> Return `null` ONLY after the consumer has already sent the control_response out-of-band... Fail-closed: an accidental null means no control_response is sent and the tool stays blocked indefinitely — **permission prompts have no park deadline**.

因此下列情况必须 deny 所有未决请求：

- 会话停止（`stop`）
- 项目关闭 / IDE 退出
- sidecar 进程退出
- 用户关闭权限卡片（视为 deny，**不是**"稍后再问"）

**`interrupt` 与 `stop` 的区别**（2026-09-11 补）：两者都会清空未决权限，但语义不同，不要混用。

| 方法 | 作用 | 会话 |
|---|---|---|
| `interrupt` | 中断**当前回合**（UI 上的"停止"按钮） | 保留，上下文不丢 |
| `stop` | 终止**整个会话**（关闭窗口 / 重启） | 销毁，`session` 置 null |

把 `stop` 用在"停止"按钮上会销毁会话，而界面仍显示"已连接"——用户之后发不出消息且看不出原因。

实现上，sidecar 维护 `pendingPermissions: Map<requestId, {resolve, reject}>`，在任何终止路径上遍历并全部 resolve 为 deny。

**规则 ②：不能被误触批准。**

sdk.d.ts:245-248（`defaultToNo` 字段）原文：

> The ask must not be approvable by a single stray keystroke: open the prompt on its decline option and offer no one-key approve shortcut.

非模态卡片形态下的落实：

- 批准**只能**通过显式点击按钮，**不绑任何键盘快捷键**
- 卡片取得焦点时，焦点落在**拒绝**按钮上
- 不提供"回车即批准"行为

**规则 ③："总是允许"受 `suppressAlwaysAllowRule` 控制。**

sdk.d.ts:249-253 原文：

> The ask must not offer a persistent "don't ask again" choice: the rule it would write grants more than this ask's own action.

落实：

- 仅当 `suppressAlwaysAllowRule !== true` **且** `suggestions` 非空时，才显示"总是允许"按钮
- 点击后回传 `{behavior: 'allow', updatedPermissions: suggestions}`
- 否则该按钮不渲染（不是禁用，是不渲染）

### 6.3 不可忽略性（非模态形态的补偿设计）

非模态卡片的核心风险：**用户没注意到 → Claude 无限等待**（规则①说的 "no park deadline"）。以下措施把"待决状态"变成无法忽略：

| 措施 | 触发条件 |
|---|---|
| 状态栏常驻组件显示待决数量 | 有任一待决请求时。点击跳转到卡片 |
| 卡片固定在消息流顶部，自动滚动到可见 | 插入时 |
| 卡片边框高亮 + 轻微动效 | 未处理时持续 |
| **粘性通知**（sticky balloon），点击导航到卡片 | 卡片插入时若工具窗口不可见，或待决超过 30 秒 |
| 升级提醒（仅提醒，**不**升级为模态） | 待决超过 30 秒 |

明确不做：升级为模态对话框。用户已选择非模态形态，补偿措施止于"提醒"。

### 6.4 并发与串行化

SDK 支持并行工具调用（一条 assistant 消息可含多个 `tool_use`），因此 `canUseTool` 可能被并发调用多次。

设计：**一次只显示一张卡片，其余排队**。卡片标题在有排队时显示"还有 N 个待确认"。

不采用"同时弹出多张卡片" —— 会产生弹窗风暴，且违背规则②的精神。

### 6.5 卡片内容

| 元素 | 来源 | 备注 |
|---|---|---|
| 主文案 | `opts.title` | SDK 已渲染为完整问句（如 "Claude wants to read foo.txt"）。**有则优先使用**，不要从 `toolName`+`input` 重新拼 |
| 副标题 | `opts.description` | |
| 按钮文案 | `opts.displayName` | 短名词短语（如 "Read file"），适合做按钮标签 |
| 触发路径 | `opts.blockedPath` | 单独高亮。"为什么问我"的关键信息 |
| 原因 | `opts.decisionReason` | |
| 原始输入 | `input` | 折叠区，展开看 JSON（Bash 命令 / 文件内容等） |

**回退路径**：`title` 缺失时，用 `displayName ?? toolName` + `input` 的摘要拼一个。这是降级方案，不是默认路径。

### 6.6 超时

**不设硬超时。** 超时 deny 会让 Claude 收到一个无来由的拒绝，并可能促使它重试或改变策略，不如让它等待。

取而代之：§6.3 的提醒机制 + §6.2 规则①的终止清空。

---

## 7. 进程生命周期

### 7.1 三层进程的后果

PyCharm → node sidecar → claude CLI。**杀掉父进程不会自动杀掉孙进程。** 在 Windows 上，关闭 PyCharm 后 claude 可能变成孤儿进程继续运行并消耗额度。

### 7.2 启动

- **打开 CCoder 窗口时启动**（2026-09-11 修改，原为"第一次发送消息才启动"）
  - 状态栏立刻显示"已连接"，第一条消息零等待；不再出现"窗口开着却显示未连接"这种读起来像故障的状态
  - **不在插件加载时启动**：那会变成"IDE 一打开就连"，即使本次根本不打算用
  - **不在 `createToolWindowContent` 里启动**：该项目启动时若工具窗口本就处于显示状态，该方法也会被调用，等于退化成"IDE 一打开就连"。改用面板的 `addNotify` + `isShowing` 判定
  - 代价：窗口开着期间常驻 node + claude（实测约 250MB）。**隐藏窗口不断开** —— sidecar 不支持会话恢复（SDK 的 session id 只用于显示），断开重连会让模型丢上下文而界面看不出区别，属于 §7.5 明确反对的静默丢失。进程随内容 Disposer 结束
- 一个 sidecar 进程 = 一个会话；`cwd` 默认为项目根目录
- 启动前检查：
  - `node` 存在且版本 >= 18（SDK `engines` 要求，package.json）
  - `claude` 可执行文件可解析（§8）
- 任一检查失败 → 发 `error`，禁用输入框，显示设置引导

### 7.3 运行

- 插件侧持有 `Process` 引用，以及一个读线程（或协程）解析 stdout
- stderr **单独读取**（不要与 stdout 合并），保留最近 N 行用于错误上报
- sidecar 未就绪时收到 `send` → **排队**，等 `ready` 事件到达后按序发送。不丢弃、不报错

### 7.4 关闭

关闭顺序（**必须按序**）：

1. 向 sidecar 发 `stop`
2. 等待最多 3 秒，让其自行退出（SDK 会清理 claude 子进程）
3. 仍在运行 → `Process.destroyForcibly()`
4. **兜底**：`taskkill /PID <pid> /T /F`（`/T` 是杀整棵进程树的关键）

第 4 步在 Windows 上是必需的 —— `destroyForcibly()` 只杀直接子进程，不会触及孙进程 `claude`。

触发场景：项目关闭、IDE 退出、插件卸载、`Disposable` 释放。

### 7.5 异常恢复

- sidecar 非正常退出 → 发 `error{fatal:true}` + `exit`，UI 标记会话已断开，显示**"重启会话"**按钮
- **不静默重连** —— 重连会让用户误以为上下文还在，而实际上会话已丢失
- 重启后是新会话（新的 `sessionId`），UI 需明确提示上下文已重置

---

## 8. 可执行文件解析与打包

### 8.1 体积实测

`@anthropic-ai/claude-agent-sdk` 的 `node_modules` 总计 **498M**，其中：

| 包 | 体积 |
|---|---|
| `@anthropic-ai/claude-agent-sdk-win32-x64` | **212M**（内含 `claude.exe`，221,637,792 字节） |
| `@anthropic-ai/sdk` | 12M |
| `@anthropic-ai/claude-agent-sdk` | 4.9M |
| `zod` | 5.9M |
| `@modelcontextprotocol/sdk` | 5.6M |

### 8.2 决策：不打包平台二进制

那 212M 占总量 42%，且**完全可以省掉** —— SDK 提供 `pathToClaudeCodeExecutable` 选项（sdk.d.ts:1835），而目标用户必然已经装了 `claude`（要用 Claude Code 插件的人不可能没有）。

**打包内容**：仅 sidecar 的 JS 依赖，约 **28M**。

**运行时解析 `claude` 路径**，优先级：

1. 插件设置中的显式路径（§9）
2. `PATH` 中的 `claude`
3. 常见安装位置探测（npm 全局目录等）

解析失败 → `error{code:"CLAUDE_NOT_FOUND"}` + 设置引导。

> 附带好处：本机存在两套 `claude`（npm 安装的 v2.1.268，以及 CodeMoss 自带的 221M 那份），此设置项让用户可以显式选择。

### 8.3 运行时提取

`node_modules` 无法从 jar 内直接运行。启动时：

1. 将插件包内的 `sidecar/` 提取到 `PathManager.getSystemPath()/ccoder/sidecar/<sidecar-version>/`
2. `<sidecar-version>` 取自 `sidecar/package.json` 的 `version` 字段。构建时由 Gradle 读取并写入插件资源；版本变化时重新提取到新目录，避免升级后残留旧代码
3. 提取为幂等操作（已存在且版本匹配则跳过）

---

## 9. 设置

`PersistentStateComponent`，项目级。

| 项 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `claudePath` | String | 空 | 空 = 自动解析（§8.2） |
| `permissionMode` | enum | `default` | `default` / `acceptEdits` / `plan` / `bypassPermissions` / `dontAsk` |
| `model` | String | 空 | 空 = 用 CLI 配置的模型 |
| `extraDirs` | List\<String\> | 空 | 映射 `Options.additionalDirectories`，对应 CLI 的 `--add-dir` |
| `envOverrides` | Map | 空 | 追加环境变量。**不能**移除 §3.2 的黑名单项 |
| `pendingReminderSeconds` | Int | 30 | §6.3 的升级提醒阈值 |

关于 `bypassPermissions`：SDK 要求同时设 `allowDangerouslySkipPermissions: true`（sdk.d.ts:1852-1856）。UI 上该选项需带明确警告文案。

**凭据不进设置。** CLI 自己读 `~/.claude/settings.json`，插件不接触、不存储任何 token。

---

## 10. 测试策略

### 10.1 单元测试（最高价值）

**环境清洗规则** —— 这是实测踩出来的，必须用测试钉死防止回归：

- 黑名单中每个变量都被移除
- 非黑名单变量（`PATH` / `HOME` / `USERPROFILE`）被保留
- **黑名单按精确名称匹配，不是前缀匹配** —— 断言 `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`、`CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING`、`CLAUDE_CODE_DISABLE_1M_CONTEXT`、`CLAUDE_CODE_EFFORT_LEVEL` 清洗后**仍然存在**（§3.2）
- `envOverrides` 能追加变量
- `envOverrides` **不能**恢复被黑名单移除的变量

**协议编解码**：
- NDJSON 往返
- 含有换行 / 特殊字符的载荷正确转义
- 分片到达的消息正确重组（不能假设一次 read = 一条完整消息）

**事件路由**：
- 未知 `type` 的事件被忽略且不抛错（约束 §3.3）
- `hook_started` / `hook_response` 不进入 UI 消息流

**权限规则**：
- `suppressAlwaysAllowRule: true` 时不产生"总是允许"选项
- `suggestions` 为空时不产生"总是允许"选项
- 终止路径（stop / 关闭）下所有待决请求被 resolve 为 deny —— **验证没有任何 Promise 泄漏**

### 10.2 集成测试

**假 sidecar**：一个纯脚本（不调用真实 API），按脚本产生预定义的事件序列。验证：

- 进程启动 / 就绪 / 关闭的完整生命周期
- sidecar 崩溃 → 插件识别并显示重启入口，不静默重连
- 超时路径（§7.4 第 2 步）
- 权限请求 → 决定回传的往返

### 10.3 手工冒烟

`./gradlew runIde` 启动沙箱 PyCharm，向真实 `claude` 发一条消息，验证端到端。

冒烟清单（每条对应一个已知风险）：

1. 第一条消息能收到回复（验证 §3.2 环境清洗）
2. 让 Claude 读一个文件 → 权限卡片出现 → 点"允许" → 它继续（验证 §6 全链路）
3. 权限卡片出现时不点，关闭工具窗口 → 粘性通知出现（验证 §6.3）
4. 权限卡片出现时关闭项目 → 重新打开，无残留挂起（验证 §6.2 规则①）
5. 会话进行中关闭 PyCharm → 任务管理器确认无孤儿 `claude` / `node` 进程（验证 §7.4）

---

## 11. 附录：实测证据

以下均为 2026-09-11 在本机实测所得，非推断。

### 11.1 环境变量污染导致认证失败

**失败路径**（继承宿主环境）：

```
$ echo "reply with exactly: OK" | claude -p --tools "" --output-format stream-json --verbose
```

输出事件序列：

```
0  system/hook_started
1  system/hook_response
2  system/init
3  [非 JSON] [claude-code:unrecognized_model] {"model":"deepseek-v4-flash[1m]","query_source":"sdk"}
4  assistant  → "Not logged in · Please run /login"   error=authentication_failed
5  result
```

**成功路径**（清洗环境后。实际剥离的变量：`ANTHROPIC_MODEL`、`CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST`、`CLAUDE_CODE_ENTRYPOINT`、`CLAUDE_CODE_MESSAGING_TOKEN`、`CLAUDE_CODE_MESSAGING_SOCKET`、`CLAUDE_CODE_EXECPATH`、`CLAUDE_CODE_CHILD_SESSION`、`CLAUDE_USE_STDIN`、`CLAUDE_SESSION_ID`、`CLAUDE_CODE_SESSION_ID`）：

```
0  system/hook_started
1  system/hook_response
2  system/init    model=deepseek-v4-flash[1m]  session_id=4617411e-...
3  [非 JSON]     [claude-code:unrecognized_model] {...}    ← 警告仍在，但非致命
4  assistant     block: thinking
5  assistant     block: text -> "OK"
6  result        subtype=success  cost=0.06441  dur=2185ms
```

结论：清洗环境是必需条件，且清洗后配置自动生效。

### 11.2 非 JSON 行混入 stdout

上面两条路径的第 3 行都是 `[claude-code:unrecognized_model] {...}` —— **非 JSON 前缀 + JSON 体**。这说明 stdout 不是纯 JSON 流，解析器必须逐行容错。

该警告在成功路径中依然出现且不影响结果，因此归类为**噪声**而非错误。

### 11.3 本机环境

| 项 | 值 |
|---|---|
| `claude` CLI | 2.1.268（npm 全局） |
| 另一套 `claude` | CodeMoss 自带，`C:\Users\CY\.codemoss\...\claude.exe`，221MB |
| CLI 认证方式 | `~/.claude/settings.json` 的 `env` 块（`ANTHROPIC_AUTH_TOKEN` + `ANTHROPIC_BASE_URL`） |
| `claude auth status` | `loggedIn: false`（认证走 settings.json，非 OAuth） |
| JDK | Temurin 21.0.12.1 |
| Gradle | 9.7.0 |
| PyCharm | 2025.3.1.1 |

### 11.4 构建工具链

IntelliJ Platform Gradle Plugin 2.x（插件 ID `org.jetbrains.intellij.platform`，最新约 2.18.0）。

- 要求 Gradle 8.13+ / JDK 17+（本机满足）
- 平台目标用 `pycharm("2025.3.1.1")`
- 按 2026.2 兼容建议，**不设** `untilBuild`
- 首次构建会下载约 800MB IDE SDK

---

## 12. 风险与未决

| 风险 | 影响 | 缓解 |
|---|---|---|
| stream-json / SDK 协议随版本变化 | 解析失效 | sidecar 透传 + 插件忽略未知类型（§3.3）。SDK 版本固定并记录 |
| 非模态卡片被忽略 → Claude 长时间等待 | 用户困惑 | §6.3 补偿措施。若实践证伪，回退到模态对话框 |
| `canUseTool` 的 Promise 泄漏 | 工具永久阻塞 | §10.1 专项测试 + §6.2 规则①的终止清空 |
| 打包 28M JS 进插件包 | 包体积 | 可进一步裁剪（`.d.ts`、`browser-sdk.*`、`extractFromBunfs.*` 均为非必需）。实现时实测 |
| `node` 不在用户环境中 | 插件不可用 | 启动前检查 + 明确错误引导（§5.3） |

### 待实现时确认

- sidecar 依赖裁剪后的实际体积
- SDK 版本固定策略（跟随 CLI 版本 vs 独立）
- 沙箱 PyCharm 中非模态卡片的实际可发现性（§10.3 冒烟第 3 条）

---

## 13. 模块划分

```
CCoder/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── sidecar/                          # 独立 npm 项目
│   ├── package.json
│   ├── index.js                      # stdio NDJSON 循环
│   ├── session.js                    # 包装 query()，流式输入 + canUseTool
│   ├── env.js                        # 环境清洗（§4）
│   ├── claude-path.js                # 可执行文件解析（§8.2）
│   └── test/                         # node:test
└── src/main/kotlin/com/ccoder/
    ├── ClaudeToolWindowFactory.kt
    ├── ui/
    │   ├── ClaudePanel.kt
    │   ├── MessageRenderer.kt
    │   └── PermissionCard.kt
    ├── sidecar/
    │   ├── SidecarProcess.kt
    │   ├── SidecarClient.kt
    │   ├── SidecarExtractor.kt       # 运行时提取（§8.3）
    │   └── Protocol.kt
    ├── settings/
    │   ├── ClaudeSettings.kt
    │   └── ClaudeSettingsPanel.kt
    └── ClaudeBundle.kt
```
