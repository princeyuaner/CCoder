# 一个工具窗口里开多个会话（多标签）

日期：2026-09-16 · 分支 `v0.2.17-dev` · 状态：**设计已批，待实现**

## 一、要解决什么

现在一个项目只有一条会话：想"一边让 Claude 写代码、一边让它查日志"就得等第一条跑完。
更麻烦的是**切换会话是破坏性的** —— `switchToSession` 就是 `stopSession()` + 重开进程
（`ClaudePanel.kt:1517-1533`），正在跑的回合被腰斩、挂着的权限询问一并作废，所以干脆
拦着不让切（`SessionSwitchState.kt:32-36`）。

用户 2026-09-16 的原话是「同时开多个窗口」。

## 二、四条产品边界（用户拍板）

| 边界 | 决定 |
|---|---|
| 形态 | **同一个 CCoder 工具窗口内多标签**（不是每会话一个独立工具窗口） |
| 工作目录 | 多个会话**都在当前项目**，共享 cwd；不做"每标签一个 cwd" |
| 上限 | **5 个** —— 每个标签 = 一个 node 侧车 + 一个 claude CLI，实测约 250MB（`ClaudePanel.kt:654-658`） |
| 关忙标签 | **先弹一句确认**；空闲直接关 |
| 「＋」 | 语义改为**开新标签** —— 今天它顺手把当前会话停掉，那一半去掉 |

## 三、为什么是"每标签一个侧车进程"

### 3.1 侧车是进程级单会话，协议里没有会话维度

- `sidecar/index.js:107` `let session = null`；重复 `start` 直接 `fail('ALREADY_STARTED')`
  （`:123-127`），单测 `sidecar/test/index.test.js:121` 钉着。整个 dispatcher 只有一个会话槽，
  没有 `Map<sessionId, session>` 这种东西。
- `Protocol.kt:650-655` 的 `line(id, method, params)` 里 `id` 只是**面板私有的自增号**
  （`ClaudePanel.kt:3188` 的 `nextId()` → `req-N`，两个面板都从 0 开始）；`encodeSend`
  （`Protocol.kt:573-589`）不带 sessionId；推送侧 `index.js:134`（事件）与 `:147`（权限）
  也都不带。带 sessionId 的只有"列表/回放"那几个独立函数。

于是两条路：

| 路 | 改动面 | 结论 |
|---|---|---|
| **每标签一个侧车进程** | Kotlin 侧的服务边界（见 §六） | **协议一个字不改** —— 选它 |
| 一个进程多会话 | 协议加会话维度 + `sidecar/project-key.js:88` 的进程级记忆（跨项目直接不成立）+ 状态聚合（MCP / 命令列表 / 用量都是进程级） | 改动面全在最难测的层，不做 |

### 3.2 推翻一条旧决定（明写）

`docs/superpowers/specs/2026-09-12-session-switch-design.md:24/33-40` 写着本版**不做**
多会话并行 / 后台会话。**推翻前两条**，理由就是 §3.1：每标签一个进程这条路上协议不用动，
而"切换会话 = 杀进程"那条约束在多标签下不再必要（各标签各有一份完整状态）。

同文件 `:46-50` 那条"**不抽 project service**"的决定**不用推翻** —— 它反对的是把
`proc`/`client`/`ready` 抽成共享状态；多标签要的恰恰相反，是每标签一份互不相干的状态。
`ClaudePanel` 今天已经自足：构造器只有 `project`，`ClaudeTranscriptView` 自己 new
（`ClaudePanel.kt:88/90`）。这是本方案能小的根本原因。

`forkSession` 仍然不做（恢复语义仍是"接着写"）。

## 四、平台的三件硬事实（读真 jar 得到，不是回忆）

读的是 `C:\Program Files\JetBrains\PyCharm 2025.3.1.1\lib`（253.29346.308）。

1. **`canCloseContents` 只能写在 plugin.xml**。`ToolWindowEP.canCloseContents` 是 public 字段，
   而 `ToolWindow` 接口上只有 getter `canCloseContents()`，`ToolWindowImpl` 里是
   `private final boolean`。标签上那个叉由 `TabbedContentAction$CloseAction` 按它决定出不出来。
2. **关闭确认只能靠 `ContentManagerListener.contentRemoveQuery(e)` + `e.consume()`**。
   `ContentManagerImpl.doRemoveContent` 里 `fireContentRemoveQuery` 返回 false 就直接
   `return ActionCallback.REJECTED`，内容原封不动。`Content.CLOSE_LISTENER_KEY` 在 2025.3
   没有任何读取方，别用。X 与 `Close` 都走 `ContentManager.removeContent(content, true)`。
3. **最后一个标签关掉之后，平台不会重建面板**。`ToolWindowImpl.createContentIfNeeded()` 把
   factory 那个 `AtomicReference` CAS 成 null 之后就不再调 `createToolWindowContent`。
   所以"至少留一个标签"**不是可选项，是平台约束**。

另外两条：

- 标签**拖出成独立窗口**：平台那条路被内部 Registry 位 `debugger.new.tool.window.layout.dnd`
  （默认 false，且是内部实验位）挡着，不依赖。
- `Content.displayName` **可以跑起来改**（`ContentImpl.setDisplayName` 会
  `firePropertyChange("displayName")`，标签立刻重画）→ 标签标题跟着会话标题走。
  `Content.setTabName` 在 2025.3 没人读，别用。

## 五、结构

```
ToolWindow "CCoder"
 └─ ContentManager
     ├─ Content#1  component = ClaudePanel(project)   ← 完整会话 A（proc/client/JCEF/队列/浮层）
     ├─ Content#2  component = ClaudePanel(project)   ← 完整会话 B
     └─ ...

新增项目服务（都不持有会话状态）：
  SessionTabs             标签容器：上限、关闭确认、可关闭性、owner↔Content
  OpenSessions            占用登记：sessionId → 哪个面板在跑
  PendingPermissionCount  单槽 → 按 owner 聚合
  McpStatus               单槽 → 选中者发布
```

## 六、状态归属

| 状态 | 归属 | 理由 |
|---|---|---|
| `proc` `client` `ready` `busy` `starting` `permissionQueue` `currentMode` `currentEffort` `autoAllow` `runStatus` `lastUsage` `currentSessionId` 等 | **面板私有** | 一条会话一份。不动它是本方案能小的原因 |
| sessionId 占用 | **项目服务 `OpenSessions`** | 跨标签唯一真相 |
| 待决权限计数 / 挂起提问 / restoreAsk | **按 owner 聚合** | 状态栏物理上只有一个，必须求和；`restoreAsk` 必须按标签存 |
| MCP 状态 | **单槽 + 选中者发布** | 设置页只表达"当前会话" |
| 拖动比例（`TRANSCRIPT_SPLIT_KEY`） | **应用级单键** | 见 §八 |
| `permissionMode` / `effort` | **设置当模板 + 面板私有当前值** | 保持"下次起会话按它"的口径 |

## 七、一次具体的碰撞：为什么必须先做占用登记

今天两个面板会各自去 `listSessions` 并 resume "最近那条"（`ClaudePanel.kt:1865-1907`），
很可能同时选中同一条会话 id —— 两边同时写同一个 jsonl。这条风险在设计稿里已被点名
（`2026-09-12-session-switch-design.md:400`："本版不做检测"），多标签把它从"理论上"变成"必然"。

机制：**预占用**我们发出去的 `resumeSessionId`（挡住 `listSessions → 挑选 → start` 那段
异步窗口，那里面除了我们自己发的东西没有任何权威信号），**确认**用 `init` 事件回的
`session_id`（`ClaudePanel.kt:2188`）—— 权威信号是 init 那个，因为 `resume` 未必按我们
传的 id 成立。

## 八、已知取舍（明确接受，不修）

1. **拖动比例是应用级单键**。`JBSplitter` 读的是静态 `PropertiesComponent`
   （`JBSplitter(vertical, key, proportion)` 构造里 `getInstance().getFloat(key, 0.5f)`），
   且 `addNotify()` 每次上屏都重载比例。要按标签分键只能拿"标签序号"当键，而关掉一个
   标签会让后面所有标签的序号漂移、比例跟着跳。**结论：一个工具窗口共用一份上下分栏比例。**
2. **设置页右栏的 MCP 状态跟着选中的标签变**。这正是想要的口径（它表达的本来就是"当前会话"）。
3. **模态框仍是应用级**（审批、提问、设置）。审批是安全关键路径，同一时刻只该有一个决定框；
   代价是整个 IDE 冻住，那是既有行为，不因多标签变差。

## 九、不做（附理由）

| 不做 | 理由 |
|---|---|
| 一个进程多会话（协议加 sessionId） | 见 §3.1 |
| 跨项目会话 / 每标签一个 cwd | 用户已拍；工具窗口本身是 project 级的 |
| `forkSession` | 沿用旧决定 |
| 同一会话在两个标签打开 | 用占用登记**禁止**，不做"检测并警告"那种半吊子 |
| 标签拖出成独立窗口 | 平台那条路被内部开关挡着（§四） |
| 未读徽标 / 后台标签完成提醒 | 平台有 `Content.setAlertIcon`，值得另开一版；混进来验证面翻倍 |
| 跨标签广播改名/删除 | 边角场景；`MessageBus` 那条路留着 |

## 十、待实测清单（实现完回填）

- 标签条本身的观感（它在平台 UI 里，**离屏探针画不出来**，只能 `runIde` 截图）
- 忙时关标签的确认框：确认与取消两条分支
- `Close All` / `Ctrl+F4` 的批量确认会不会连问 N 次（设计上是"第一次确认后 1.5s 内放行"，
  这条去抖**只能靠手工验证**，静态分析证不了平台是不是同一批事件里连发）
- 关到只剩一个时，那个标签的叉是否真的消失
- 点 ＋ 后**立刻**点叉：进程数必须回到起点（这是 §十一 那条洞的验收）
- 1 / 3 / 5 个标签的内存记账（任务管理器看 node + claude 个数与占用）
- 分割条拖动后切标签、隐藏工具窗口再显示、开关项目

## 十一、本功能引入的真 bug（已核，必须先堵）

**启动期被停 → 孤儿进程。** `startSession` 在池线程里 `p.start()`（`ClaudePanel.kt:1854`）
之后，要等 `invokeLater` 才 `proc = p; client = c`（`:1857-1862`）。这中间 `proc`/`client`
都是 null，而 `stopSession()`（`:2010-2056`）只对这两者动刀、也不 bump `sessionEpoch` ——
**进程已经起来了，却没有任何代码会去杀它**。

今天 `canCloseContents` 没开、content 根本移除不了，所以这条路径不可达；开了多标签之后
"点 ＋ 再马上点叉"是再自然不过的动作，而症状是**静默**的：界面上什么都没发生，额度在掉。

堵法：抽 `SessionGate`（`begin()` / `isCurrent(token)` / `invalidate()` / `finish()`），
`stopSession()` 与 `dispose()` 里 `invalidate()`；池线程在 `p.start()` 之后与 `c.start()`
之前各判一次，不认就 `p.shutdown(); c.close()` 后返回。

## 出处

- `docs/superpowers/specs/2026-09-12-session-switch-design.md`（被推翻的那两条、以及不做检测的那条风险）
- `docs/design/settings-v2.html`（设置对话框的分隔线，与本设计无关但同一批平台约定）
- 平台 API：`PyCharm 2025.3.1.1/lib` 的 `ToolWindow` / `ToolWindowImpl` / `ContentManagerImpl` /
  `ContentImpl` / `TabbedContentAction$CloseAction` / `JBSplitter`
