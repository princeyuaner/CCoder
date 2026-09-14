# 状态卡片（连接 / 上下文 / 子任务 / 子代理）

日期：2026-09-13
设计稿：`docs/design/status-cards.html`（四选一）、`docs/design/status-cards-b.html`（B 的三个打磨方向）
选定：**变体 2 · 四张微卡**

---

## 1. 要解决的问题

连接状态、上下文用量、子任务进度、子代理计数这四样，现在**全挤在输入卡片内部的一行**里：

```
[ 已连接   上下文 12.3k / 200k · 6%              ◐ 3/7 修复比对 │ ● 2 ▾ ]
```

三个问题：

1. **子任务与子代理被合并**成右边一条，计数与名字混在一个胶囊里，谁也不突出。
2. **连接状态占了最左**，而它 99% 的时间写着"已连接"——那一行的宽度被一个不变的字占着。
3. 胶囊**要按宽度截断**文字，于是有了 `runStripText` / `ellipsize` 一整套逻辑（连着 8 条断言），只为在 420px 里塞下三样东西。

## 2. 目标结构

四样各自成为一张卡，放在**输入卡片外面、权限卡下面**：

```
bottom (BorderLayout, 边距 6,8,8,8)
├── NORTH: headerSlot (BoxLayout Y)
│     ├── permissionSlot     权限卡，动态增删
│     └── statusCards        四张卡，常驻
└── CENTER: inputArea        输入卡，NORTH 槽取消
```

```
┌──────────┬──────────┬──────────┬──────────┐
│ 连接      │ 上下文    │ 子任务    │ 子代理    │
│ ● 已连接  │ 6%       │ 3/7      │ 2        │
│          │ 12.3k    │ ▪▪▪▫▫▫▫  │ ●●       │
│          │ /200k ▬▬ │          │          │
└──────────┴──────────┴──────────┴──────────┘
```

## 3. 每张卡的内容与形状

**每张卡按自己的信息形状画，而不是套同一个"大数字+小标签"模子。**

| 卡 | 标签 | 值 | 副值 | 底部指示 | 可点 |
|---|---|---|---|---|---|
| 连接 | 连接 | 已连接 | — | 无（只有状态点） | 否 |
| 上下文 | 上下文 | `6%` | `12.3k / 200k` | 比例条 | 否 |
| 子任务 | 子任务 | `3/7` | — | 七个小方块（成/进行中/未开始） | 是 |
| 子代理 | 子代理 | `2` | — | **N 个点**（N = 在跑数） | 是 |

### 3.1 两条硬规矩

**① 子代理不画进度条。** 子代理**没有分母** —— 在跑几个就是几个。画成"亮 2 格 / 共 4 格"会被读成 2/4，是凭空造出来的信息。用 N 个点，N 就是数。

**② 没有分母就不画格子。** 同理，没有任务清单时**不画**七个空格子（那是"0/7"）。上下文卡有真实分母（窗口容量），照画。

### 3.2 连接的八种文字 → 四种色调

`statusLabel` 会被写成八种文字，映射到一个纯函数：

| 文字 | 色调 |
|---|---|
| 已连接 | Ok（绿） |
| 正在启动… / 正在载入历史… | Warn（琥珀） |
| 未连接 / 会话已结束 | Idle（灰） |
| 启动失败 / 会话已断开 / 恢复失败 | Danger（红） |

## 4. ⚠️ 推翻的原则：四张卡常驻，不再"不造零值"

现有代码有一条明确原则（`RunStrip.kt:26`、`ContextUsage.kt:20`）：

> 取不到就不显示，不造零值。一条显示"0/0"的常驻空条，比不显示更糟 —— 它占着位置还说假话。

**本次反着来：四张卡永远在。** 没有内容的格子"**收边**"——不画边框、文字降到次要色、显示「空闲」。

**理由**：这是选 B（一排放四张）而不是 C（有内容才出现）的**全部意义**。卡出现/消失会让输入框在会话中途上下跳——你正打字、模型刚好甩出一个 TodoWrite，输入框就往下滑。稳定比安静重要。

**代价**：界面上永远挂着两个「空闲」。

**这条原则没有被废除，只是适用范围收窄了**：它仍然管**格子内部的指示器**（3.1 那两条）。格子本身常驻，格子里的图形不撒谎。

## 5. 交互

- **子任务卡、子代理卡可点** → 各自弹出自己的详情浮层。
  - 现在的合并浮层（`buildRunDetail`）拆成两段：任务清单一段、运行中一段。
- **连接卡、上下文卡不可点** —— 它们没有更多可看的东西。给一个点不动的可点外观是骗人。
- 浮层定位沿用 `popupAnchorY` / `popupCenteredX` / `showTogglePopup`，锚点从 `runStripView` 换成被点的那张卡。

**可点的视觉线索**：卡片描边在悬停/展开时换成 `focusColor()`。现有 `RoundedLineBorder` 本来就接收一个 `colorProvider: () -> Color`（`RunStripView.kt:35`），正是为此设计的，直接复用。

## 6. 数据的可见性规则

| 卡 | 数据源 | "空闲"的条件 |
|---|---|---|
| 连接 | `statusLabel` 的文字 | 永不空闲（未连接也是一种状态） |
| 上下文 | `contextUsageOf(event)` | 还没收到过带 `modelUsage` 的 result |
| 子任务 | `RunStatusTracker.todos` | 从没收到过任务清单（见 §6.1） |
| 子代理 | `RunStatusTracker.running` | 没有在跑的任务 |

`ContextUsage` 缺窗口容量时（`contextWindow <= 0`）副值只显示已用量，不做除法——沿用 `formatContextUsage` 现有规则。

### 6.1 任务清单的两代形状：`TodoWrite` 与 `Task*`

**这条是 2026-09-14 补的**：卡做出来之后一直没亮过，查下来不是"模型没用"，
而是**它等的那个工具已经不在工具清单里了** —— 换什么模型都一样。

| 代 | 工具 | 形状 | 出处 |
|---|---|---|---|
| 老 | `TodoWrite` | 一次交一整张清单 | `sdk-tools.d.ts:1017` |
| 新 | `TaskCreate` / `TaskUpdate` / `TaskList` / `TaskGet` | 增量：一次一条 + 打补丁 | `sdk-tools.d.ts:2717/2743/3933` |

CLI 2.1.268 只提供**新一代**，而且对第三方模型（deepseek-* 这种）还额外**关着闸**：

- 实测：`claude --print --verbose --output-format stream-json` 的 `init` 事件里工具数
  **24**；加上 `CLAUDE_CODE_ENABLE_TODO_TOOLS=true` 之后是 **28**，多出来的正是
  `TaskCreate` / `TaskGet` / `TaskList` / `TaskUpdate` —— 两轮里都没有 `TodoWrite`。
- CLI 内部的判断（`mL()`）：模型不认识、是 application-inference-profile、或属于一批
  内置模型 id 时默认开；否则要 `CLAUDE_CODE_ENABLE_TODO_TOOLS === true`。
- 所以**在第三方网关上，不主动给这个变量，这张卡就永远是空的**。CCoder 现在这样给：
  **选中了提供端点或密钥的模型配置时**，默认往 `envOverrides` 里加
  `CLAUDE_CODE_ENABLE_TODO_TOOLS=true`（`ModelProfile.taskToolsEnv`）。
- 它是**默认值**而非硬规则：用户在 `envOverrides` 里手填过这个键就听手填的（想关就关）。
  这一项与端点/凭证那批刻意不同 —— 那些混搭会 401，必须由配置说了算。
- 没选中任何配置时插件不插话：那种会话的模型名 CLI 认识，它自己会给。

形状（真实样本，同一次会话抓的）：

```
[TaskCreate] {"subject":"写文档","description":"编写相关文档","activeForm":"写文档"}
     ↳ 结果: Task #1 created successfully: 写文档
[TaskUpdate] {"taskId":"1","status":"in_progress"}
     ↳ 结果: Updated task #1 status
[TaskList  ] {}
     ↳ 结果: #1 [completed] 写文档
              #2 [pending] 跑测试
```

**id 只在结果文本里**，所以 `RunStatusTracker` 现在也消费 `user` 事件（工具结果）——
光看 assistant 侧拼不出清单：

- `TaskCreate` → 先建占位条目，结果回来时认领 id（`taskIdOfCreated`）；建失败
  （`is_error`）则把占位删掉，不留幽灵条目
- `TaskUpdate` → 按 id 打补丁；`status:"deleted"` 是**删掉**，不是状态
- `TaskList` → 结果是一份整表快照，照单全收（`taskEntriesOf`），连本会话没见过的 id
  一起收 —— 清单是跨会话续着的
- 只认自己发出过的那些 tool_use：照文本硬猜的话，别的工具恰好打印了同样格式就会接管清单

老一代的 `TodoWrite` 分支**保留**（别的 CLI 版本还在给）。两代混用时谁最后来谁说了算
—— `TodoWrite` 是快照语义，一来就把增量攒的覆盖掉。

## 7. 要删掉的（本次的主要风险面）

| 文件 | 处理 |
|---|---|
| `RunStrip.kt` | **整个删除**（`RunStrip`、`runStripOf`） |
| `ComposerStrip.kt` | 删 `RunStripView` 类、`runStripText`、`ellipsize`、`EXPAND_CARET`；保留 `popupAnchorY`/`popupCenteredX`/`popupHeightOf`/`popupWidthOf` |
| `RunStripView.kt` | 删 `buildContextRow`、`buildUsageLabel`；剩下的 `RoundedLineBorder`/`buildRunDetail`/`formatDuration` 移到 `RunDetail.kt` |
| `Composer.kt` | `buildComposerCard` 从三参变两参（`inputScroll`, `toolbar`） |
| `RunStripTest.kt` | **整个删除**（测试对象没了） |
| `ComposerStripTest.kt` | 删 `runStripText`/`ellipsize`/条组件相关断言；保留浮层定位断言 |
| `ComposerRulesTest.kt` | 改三处 `buildComposerCard` 调用 |
| `ComposerRenderProbe.kt`、`TopRowRenderProbe.kt` | 改 `buildContextRow` 调用 |

`ellipsize` 那套截断整体消失——它存在的理由是胶囊要按宽度截断文字，而卡里放的是 `3/7`、`2` 这种定宽短值。

## 8. 新增

| 文件 | 内容 |
|---|---|
| `StatusCards.kt` | 纯逻辑：`StatusCardModel`、`Tone`、`Indicator`、`connectionCardOf` / `contextCardOf` / `todoCardOf` / `runningCardOf` |
| `StatusCardView.kt` | Swing 组件：渲染一张卡，处理悬停/展开/点击 |
| `StatusCardsRow.kt` | 组装四张卡成一行 |

### 数据模型

```kotlin
internal enum class Tone { Ok, Warn, Danger, Idle }

internal sealed interface Indicator {
    object None : Indicator
    data class Meter(val fraction: Double) : Indicator
    data class Segments(val done: Int, val total: Int) : Indicator
    data class Dots(val count: Int) : Indicator
}

internal data class StatusCardModel(
    val label: String,
    val value: String,
    val tone: Tone = Tone.Idle,
    val sub: String? = null,
    val indicator: Indicator = Indicator.None,
    /** true = 这格没内容，收边（不画边框、值降为次要色） */
    val quiet: Boolean = false,
)
```

`quiet` 与 `tone` 分开而不是合并：`Tone.Idle` 也在**连接卡**上用（"未连接"是一种真实状态，该有边框），而 `quiet` 只表示"这格没数据"。

## 9. 代价

**底部区域最小高度增加约 58px。** 分隔条设了 `honorComponentsMinimumSize`（`TranscriptSplit.kt:49`），底部变高会顶掉转写区同样的高度。

`DEFAULT_PROPORTION`（0.89）**先不动**，装上看一眼再决定。

⚠️ 58px 是**估的，没量过**。实现时写一条断言直接读 `minimumSize`，把它变成量出来的数。

## 10. 测试

**纯逻辑（`StatusCardsTest`）**
- 连接八种文字 → 四种色调，逐条断言
- 上下文：取不到 → `quiet`；有数据 → 值 `6%`、副值 `12.3k / 200k`、`Meter(0.06)`
- 上下文窗口容量缺失 → 副值只给用量，不做除法
- 子任务：没有清单 → `quiet` 且 `Indicator.None`（**不是** `Segments(0, 0)`）
- 子任务：有清单 → `3/7`、`Segments(3, 7)`
- 子代理：空的 → `quiet`；2 个在跑 → `2`、`Dots(2)`（**不是** `Segments(2, 4)`）

**组件（`StatusCardViewTest`）**
- 四张卡**始终都在**（包括没有数据时）—— 这是对 §4 那次推翻的钉子
- `quiet` 的卡**不画**边框
- 点子任务卡 → 回调；点连接卡 → **不**回调
- 悬停可点的卡 → 描边换成 `focusColor()`
- 展开态下描边保持 `focusColor()`

**回归（`StatusCardsRowTest`）**
- 四张卡等宽，且总宽不超过面板宽
- 状态行的 `minimumSize.height` = 量出来的值（把 §9 那个"估的 58px"变成事实）

**探针（`StatusCardsRenderProbe`）**
- 忙态 / 空闲态各出一张 PNG，深浅两色各一张

## 11. 明确不做的

- 不做卡片的展开动画
- 不做卡片拖拽重排
- 不做点击连接卡的历史/诊断
- 不改 `DEFAULT_PROPORTION`
- 不给子代理卡放名字（95px 放不下，名字在详情浮层里）
