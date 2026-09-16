# 输入框补全：@ 引用与斜杠命令

日期：2026-09-13
状态：设计已确认，待实现

---

## 1. 要解决的问题

输入框现在只能打字。Claude Code 终端里最顺手的那两样，插件里都没有：

1. **`@` 引用文件** —— 现在要引用一个文件，得开文件、选中、右键「添加到 CCoder 聊天框」。进去的是一段**代码片段**，而想引用**整个文件**反而没有入口。
2. **`/` 命令** —— `/compact`、`/clear`、`/context`、`/usage` 全都够不着。其中 `/compact` 对长会话近乎刚需：上下文满了，现在只能新开会话、把上下文丢掉。

两者是同一个交互：**在输入框里打一个触发字符 → 弹候选 → 选中插入**。所以共用一个补全层。

---

## 2. 实测结论

一次性探针跑出来的、决定整套设计的事实（原始证据见 §10）。

| # | 事实 | 影响 |
|---|---|---|
| 1 | `supportedCommands()` 返回 45 条，带 `description` / `argumentHint` / `aliases`（sdk.d.ts:2757） | 命令列表拿得到，不用去黑 CLI |
| 2 | 把 `/name args` 当**普通消息文本**发过去，CLI 直接执行 | 不需要新的发送路径 |
| 3 | 命令输出走 **assistant 文本块**。`system/local_command_output` 一次没出现，`result.local_command` 恒为 `null` | **不需要新增渲染路径** —— 原以为要动 Kotlin 与 web 两层，是错的 |
| 4 | `@相对路径` 被 **CLI 自己展开**成文件内容（模型零工具调用就答对了文件里的值） | 插件不读文件、不内联内容，只管把 `@path` 发出去 |
| 5 | `supportedCommands()` 的 `name` **不一定是可发送的字符串** | 见 §4.1，需要三份来源 |
| 6 | `system/init` **每个回合都发一次**，不是会话开始时一次 | 收到 init ≠ 换了会话 |
| 7 | `/clear` 之后 `session_id` 变了，**进程不动** | 判定只能靠比对 id |
| 8 | `/compact` 会产生 `system/compact_boundary {trigger, pre_tokens, post_tokens, duration_ms}` | 本次不做，但记着它 |

另外两条与本次无关、但实现时容易踩的：

- **`canUseTool` 的 allow 必须带 `updatedInput`。** 类型上是可选的（sdk.d.ts:2340），运行时 Zod 校验却要求它，漏了工具直接报 `ZodError`。插件本来就发（`ClaudePanel.kt:1285`），不是 bug，只是别再写一遍。
- **`total_cost_usd` 本身是累计值**（"read the latest result rather than summing across results"）。将来做累计花费不用自己加。

---

## 3. 触发与键盘规则

### 3.1 触发条件

| 触发字符 | 条件 | 理由 |
|---|---|---|
| `/` | **只在消息开头**（前面只有空白） | `/` 出现在句子中间太常见：路径 `C:/Users`、日期 `1/2`、`and/or`。到处弹列表是纯噪音。终端本来也是这个语义——整条消息就是那条命令 |
| `@` | **词边界**（行首，或前一字符是空白） | `foo@bar.com` 不该弹 |

两者都是**补完第一段就收**：打了空格就关层。`/compact 自定义摘要指令` 的参数、`@path` 后面接的文字，都不再触发补全。

### 3.2 弹层开着时的按键接管

| 键 | 弹层开着 | 弹层关着 |
|---|---|---|
| ↑ / ↓ | 移动高亮 | 交给输入框移光标 |
| Enter | **采纳候选** | 按 `isSendKey` 决定发送/换行 |
| Tab | 采纳候选 | 交给输入框 |
| Esc | 关层，**已敲的内容一个字不动** | — |
| 其他字符 | 正常输入，再按新前缀重过滤 | 正常输入 |

**与 `isSendKey` 的冲突必须在调用点之前判掉。** 现在 Enter 是发送键（或按设置是换行），弹层开着时它只能是"采纳"——否则用户想选候选，结果把半截命令发出去了。

但**只能拦"弹层真开着"这一种情况**，不改 `isSendKey` 这个纯函数——它有自己那批测试，改它会牵动换行/发送的既有规则。

### 3.3 采纳后插入什么

- `@` → 插入 `@` + 路径 + 一个尾随空格，光标落其后。**`@` 字符留在文本里**——CLI 就是靠它认的（§2 事实 4）。
- `/` → 插入 `/` + 命令名。**永不自动发送。** 就算命令没有参数也要用户自己按回车：自动发送省不下一次按键，却让人没机会改主意；而带参数的命令（`/compact <说明>`）必须留出补参数的机会。

---

## 4. 候选来源与数据流

**两边完全不互相依赖**：`/` 不碰 IDE 索引，`@` 不碰 sidecar。sidecar 断了 `@` 照常能用，索引没建好 `/` 照常能用。

### 4.1 `/` 命令

> **⚠️ 实现后修订（2026-09-13）。** 本节有两处被实测推翻，实现按修订后的走。
> 详见实现计划 `docs/superpowers/plans/2026-09-13-composer-completion.md` 的「执行结果」。
>
> 1. **C 来源不存在。** `reloadSkills()` 在 CLI 2.1.268 上报
>    `Unsupported control request subtype: reload_skills`。分组改走**可发送名的插件命名空间**，
>    标签「插件」/「其它」—— 协议里没有任何字段能区分 "CLI 内置命令" 与
>    "用户技能目录里的技能"，两边在 B 里都是裸名，所以「内置」这个标签是错的。
> 2. **A 与 B 的名字还差一层命名空间。** `brainstorming`（A）对应
>    `superpowers:brainstorming`（B）。配对规则除归一化外还必须容忍 `:前缀`
>    —— 否则 45 条只配上 27 条，superpowers 整个技能库静默消失。

sidecar 新增 `listCommands` 方法，一次聚齐三样。**三份来源职责不同，缺一不可**：

| 代号 | 来源 | 内容 | 职责 |
|---|---|---|---|
| **A** | `supportedCommands()`（sdk.d.ts:2757） | 45 条，带 `description` / `argumentHint` / `aliases` | 决定**显示什么** |
| **B** | `system/init.slash_commands` | 48 个纯字符串（kebab） | 决定**发什么** |
| **C** | `reloadSkills()`（sdk.d.ts:2845） | 纯 skill 列表 | 决定**怎么分组** |

**为什么必须有 B —— 事实 5。** 同一批命令，A 与 B 的写法不一样：

| 来源 | 写法 |
|---|---|
| A | `/Debug Issue`、`/Explore Codebase`、`/Refactor Safely`（空格 + 大写） |
| B | `debug-issue`、`explore-codebase`、`refactor-safely`（kebab） |

那几条是用户 skill 目录里的，display name 来自 frontmatter 的人话标题。**A 的名字直接发出去可能被当成普通文本。**

**规则**：

1. **A 决定显示什么**（名字与描述），**B 决定发什么**（插进输入框的字符串）。
2. 一条命令只有在 **A 里有显示信息、且能配上 B 里某个可发送名**时才出现。配不上的（A 有 B 无、或 B 有 A 无）**都不显示** —— 宁缺勿错。显示一个发出去会被当普通文本的"命令"，比不显示更糟。配对规则本身见 §9。

> **⚠️ 实现后修订（2026-09-15）：B 还没到时，这条规则要放宽。**
> 用户报"刚打开插件时打 `/` 不出一条候选，得先聊一句才有"。
>
> 量下来的（`sidecar/tools/probe-init-before-send.mjs`，可重跑）：
> `supportedCommands()`（A）**在第一条消息之前就能拿到**（457ms，31 条），
> 而 `system/init.slash_commands`（B）**拿不到** —— 流式输入下 init 要等用户先说话
> （什么都不发、静等 15 秒：零事件）；`reloadSkills()` 想当替代也不行
> （`Unsupported control request subtype: reload_skills`）。
> 于是没有 B 的时候，上面那条规则把整个列表滤空。
>
> 所以：**`sendable` 为空时改用归一化后的显示名当插入值**。这不是拍脑袋 ——
> 同一个探针并排打出 A、B 两份（31 条全对上）：25 条的显示名本来就是可发送名、
> 4 条归一化后正确（`Debug Issue` → `debug-issue`）、**2 条会猜错**
> （`frontend-design` → `frontend-design:frontend-design`，要插件命名空间前缀）。
> 那 2 条是这条规则的已知代价：万一是它们，CLI 会把那一行当普通文本。
>
> **B 一到就回到严格配对**（`sendable` 非空走原路），并且当场刷新一次弹层，
> 让每一行的插入值换成 CLI 认的那个。
3. **分组**：名字在 C 那份里的进「技能」组，其余进「内置」组。不解析描述里的 `(superpowers)` / `(user)` 后缀——那是文本，不是结构。

**刷新时机**：

- 会话就绪后拉一次
- 收到 `system/commands_changed`（sdk.d.ts:3446）时**整体替换** —— SDK 注释原文要求 REPLACE，不是合并
- 列表随会话走。会话结束就清空，所以**未连接时打 `/` 不弹任何东西**（不弹一份过期的，也不弹空列表加提示）

### 4.2 `@` 文件

走 IDE 原生索引（`FilenameIndex` / `ProjectFileIndex`），按前缀查。自动跟随项目范围与排除规则，不自己扫盘。

第一版**只列文件**，不含文件夹与符号。

---

## 5. 命令回合的两个后果

### 5.1 输出怎么渲染

事实 3：命令输出走 assistant 文本块。所以 `/cost`、`/context` 的报告**直接就是一段 assistant 文本，现有组件已经能显示，不需要新的渲染路径**。

但有两个坑：`/clear` 的 assistant 文本是字面量 **`"(no content)"`**，`/compact` 是**空串**。渲染成气泡会看着像 bug。

**规则：插件知道自己发了什么。** 发出去的消息以 `/` 开头 → 这一回合按"命令回合"处理：

| 情况 | 处理 |
|---|---|
| 文本非空、且不是 `(no content)` | 照常渲染（`/cost`、`/context` 靠这条） |
| 空串 / `(no content)` | **不渲染气泡** |

**不去猜 `result.local_command`** —— 实测恒为 `null`，不能用。

### 5.2 `/clear` 换会话

事实 6 + 7：`/clear` 之后 `session_id` 换了、进程不动；而 `system/init` 每回合都发一次。

**判定"换了会话"只能靠比对 session id**，不能靠"收到 init"。现在 `ClaudePanel.kt:1083` 把 init 当"填 session id 与模型名"用，逻辑上正好；要加的是**比对**：id 与当前记录不同 → 走切换。

切换动作：

1. **清空转写区**
2. 重载会话列表（`listSessions`）
3. 当前会话指针 = 新 id，标签退回斜体「新会话」

**不做确认弹窗。** 误触代价很低——旧会话真的在磁盘上，会话列表里能切回去（resume 已实现）。这正是"清空转写区"这个选择能被接受的全部理由；加确认框反而是在暗示这操作有风险。

**边界**：刚 `/clear` 出来的新会话可能还没落盘（它还没有任何消息），`listSessions` 未必立刻列得到。所以**当前会话指针以 init 里的 id 为准，不以列表为准**——列表晚一步跟上就行。

---

## 6. 组件划分

| 文件 | 内容 | 依赖 |
|---|---|---|
| `Completion.kt` | 纯逻辑：`CompletionItem`（名字 / 描述 / 分组 / **插入文本**）、前缀过滤、高亮移动、按键意图 | 无 Swing |
| `CompletionPopup.kt` | 组件：非聚焦 `JBPopup`，锚点取**光标位置**（`modelToView2D`），不是控件边缘 | Swing / JBUI |
| `FileCandidates.kt` | `@` 的候选提供者 | `FilenameIndex` / `ProjectFileIndex` |
| `CommandCandidates.kt` | `/` 的候选：把 §4.1 那两份来源映射成 `CompletionItem` | 无 |

**不复用 `showTogglePopup`。** 现有的三个浮层（任务详情 / 权限模式 / 会话列表，`ClaudePanel.kt:449`）都是"点一下开、再点一下关"的**点击驱动**浮层，锚点是控件。补全层是**键盘驱动**、锚点是光标、还要接收输入框转发过来的方向键。共用的只有 `popupAnchorY` 那套定位计算，那个是真能复用。

接线点：`ClaudePanel.kt:223` 那个输入框 `KeyListener` 上加一层。sidecar 侧是 `index.js` 加 `listCommands`、`session.js` 暴露 `supportedCommands()` 与 `reloadSkills()`。

---

## 7. 测试

### 纯逻辑 `CompletionTest`

- 触发判定：`/` 只在开头（`C:/Users`、`1/2` 不触发）；`@` 只在词边界（`foo@bar.com` 不触发）
- 前缀过滤：大小写不敏感、空前缀给全部
- 高亮移动的边界：第一项再往上、最后一项再往下 —— **不循环**，与 IDE 补全一致
- 按键意图：弹层开着 Enter 是"采纳"，关着走 `isSendKey`
- **名字映射**：`/Debug Issue` 显示、插入 `debug-issue`；只在一边存在的命令**不出现**

### 组件 `CompletionPopupTest`

- 弹层不抢焦点（输入框仍是 `focusOwner`）
- 锚点在光标下方

### 回归

现有 6 套 composer 测试（`ComposerInputTest` / `ComposerModeTest` / `ComposerRulesTest` / `ComposerStripTest` / `ComposerToolbarTest` / `ComposerRenderProbe`）**一条不改地继续通过**。

这是"不动输入框"那个选择该付的账，也是它有没有守住的唯一证据。

### 渲染探针 `CompletionRenderProbe`

命令组（分组标题 + 长描述）与文件组（长路径）各出一张 PNG，深浅两色各一张。按项目惯例 —— 单测看属性看不出"好不好看"。

---

## 8. 明确不做

- 模糊匹配（只做前缀）
- 命令图标、收藏、最近使用
- `@` 文件夹与符号
- 命令自动发送
- 除 `commands_changed` 之外的中途变更
- `/compact` 的边界事件接进上下文卡（事实 8 记着，但不在本次范围）

---

## 9. 待实现时确认

| 项 | 怎么确认 |
|---|---|
| A 与 B 的名字配对规则（`/Debug Issue` ↔ `debug-issue`） | 跑一次 `sidecar/tools/probe-slash.mjs`（见 §10），把 A、B 两份列表并排打出来对。配不上的条目按 §4.1 规则 2 不显示，**不猜** |
| 命令输出是否真的都走 assistant 文本 | 已验 `/cost`、`/context`、`/compact`、`/clear` 四条；`/usage`、`/review` 等未验，实现时抽两条抽查 |
| 分组的观感 | 渲染探针出图后再定，本 spec 不预设 |

---

## 10. 附录：实测证据

探针：`sidecar/tools/probe-slash.mjs`。选项照抄 `session.js`，环境走 `env.js` 的清洗——不清洗必然认证失败（见插件设计稿 §11.1）。

**这个脚本留在仓库里**，因为 §9 那条确认项要用它重跑（与 `web/tools/layout-probe.mjs` 同一个惯例）。它不是产品代码。

用法：`MSYS_NO_PATHCONV=1 node sidecar/tools/probe-slash.mjs "/cost" "/context"`。
（`MSYS_NO_PATHCONV=1` 是必需的：Git Bash 会把 `/cost` 转成 `C:/Program Files/Git/cost`。）

不带参数跑默认那三问（`/cost`、`@引用`、预热）；带参数则按参数逐条发。

### 10.1 `supportedCommands()` 拿到 45 条

```
/clear  [name]  —  Start a new session with empty context; previous session stays on disk  [别名: reset, new]
/compact  <optional custom summarization instructions>  —  Free up context by summarizing the conversation so far
/context    —  Show current context usage
/usage    —  Show the total cost and duration of the current session  [别名: cost, stats]
/Debug Issue    —  Systematically debug issues using graph-powered code navigation (user)
/Explore Codebase    —  Navigate and understand codebase structure using the knowledge graph (user)
```

### 10.2 `/cost` 被当命令执行，输出走 assistant 文本

```
=== 发送 /cost ===
  EVENT system/init
  EVENT assistant/ [text]
  EVENT result/success
  → result.subtype=success  local_command=null  num_turns=0
  → local_command_output 事件：0 个
  → 本轮 tool_use：[]
  → assistant 文本："Total cost:            $0.1600 ... Usage by model:
     deepseek-flash[1m][1m]:  31.4k input, 128 output, 0 cache read, 0 cache write ($0.1600)"
```

### 10.3 `@路径` 被 CLI 展开（零工具调用）

```
=== 发送 @ 引用 ===
  发送："@sidecar/package.json 这个文件里 version 字段的值是什么？只回答值本身。"
  → result.subtype=success  num_turns=1
  → 本轮 tool_use：[]          ← 一次工具调用都没有
  → assistant 文本："0.2.0"    ← 文件内容是 CLI 内联进 prompt 的
```

### 10.4 `/clear` 换了 session id，进程不动

```
        2 "session_id":"8c40719f-47b5-439b-a68e-727cfbb43cba"   ← /clear 之后
        1 "session_id":"468865b6-2573-4431-9bd9-95cb56574046"   ← 预热那一回合
```

### 10.5 `/compact` 的事件链

```
system/status  {status:"requesting"}
assistant      [thinking] → [text]          ← 摘要本身
system/status  {status:"compacting"}
system/hook_started / hook_response
system/status  {status:null, compact_result:"success"}
system/init
system/compact_boundary
  {trigger:"manual", pre_tokens:31483, post_tokens:1915, duration_ms:10165}
```

### 10.6 探针自身踩到的坑（留给下次写探针的人）

- **`canUseTool` 的 allow 分支必须把 `input` 原样回传。** 第一版写成 `updatedInput: undefined`，SDK 直接抛 `ZodError`，工具全部失败。类型上标着可选（sdk.d.ts:2340），运行时却要求它。
- **必须消费事件流。** `query()` 返回的 `Query` 不 `for await` 就一个事件都收不到——第一版漏了这圈，表现为"超时且没有任何输出"。`session.js` 里那圈是必需的，不是装饰。
- **流式输入下，`system/init` 要等第一条消息才来。** 探针第一版先等 init 再发消息，60 秒超时。生产路径不受影响：`ready` 是 `index.js` 在 `start` 里**同步**发的，不等 init。
