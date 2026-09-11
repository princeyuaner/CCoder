# CCoder 工具窗口 UI 重设计

日期：2026-09-11
状态：设计已确认，待实现
前置：`2026-09-11-pycharm-claude-code-plugin-design.md`（插件主体设计）

---

## 1. 背景与目标

### 1.1 为什么改

第一版界面功能完整但观感粗糙。具体问题，逐条对应实际缺陷：

| 问题 | 实际缺陷 |
|---|---|
| 气泡不是气泡 | 用 `JBTextArea` 加背景色充当，无圆角、无内边距层次、无内容边界 |
| 没有代码渲染 | 正文与代码同字体同字号，无等宽、无高亮、无行号 |
| "折叠"是假的 | `collapsed()` 只是加了个 `▸` 前缀，点击无反应 |
| 零动画 | 逐 token 流入是硬刷新文本，无光标、无淡入，滚动是跳变 |
| 排版是默认的 | 行高、字距、段距全用 IDE 默认，长段落是一堵墙 |

这些没有一条能靠调颜色解决。

### 1.2 本次改动的范围

**换渲染层**：转写区从原生 Swing 换成 JCEF + React。

**视觉重做**：按"现代聊天"风格（圆角气泡）重新设计。

**新增三项交互**（在 JCEF 中几乎零成本）：
- 代码块复制按钮
- 链接可点击
- 消息时间戳

### 1.3 明确不做

- 消息重新生成、编辑已发消息、导出对话 —— 这些要改 sidecar 协议（会话回滚/重发），不是界面工作
- 转写区的虚拟滚动 —— 会话长度达到需要它的量级前不做（YAGNI）
- 消息搜索/过滤
- 附件、图片渲染

---

## 2. 架构

### 2.1 组件布局

```
ClaudePanel (原生 Swing, BorderLayout)
├── 头部：状态指示                             原生
├── ClaudeTranscriptView (JBCefBrowser)         ← 本次替换的块
├── PermissionSlot                             原生，复用 PermissionCard
└── 输入区：[输入框] [发送/停止 合一按钮]       原生
```

**发送与停止合一**（2026-09-11 修改，原为顶部独立的停止按钮）：空闲时按钮是"发送"，回合进行中（已发出消息、未收到 `result`）变"停止"，点击发送 `interrupt`。

- **必须是 `interrupt` 而不是 `stop`**。`stop` 会销毁整个会话（`index.js` 把 `session` 置 null），而界面仍显示"已连接"，用户之后再也发不出消息且看不出原因。`interrupt` 只中断当前回合，会话与上下文都保留
- 状态规则抽在 `mainButtonState` 纯函数里，便于单测 —— ClaudePanel 依赖 Swing 与平台，起不了单测
- 回合进行中按 Enter 不发送：此时按钮是"停止"，Enter 却另发一条会让两者语义打架

**为什么输入区留在原生**：中文输入法是刚需，JCEF 的 IME 支持虽有改善但不如原生可靠；且原生输入保留 IDE 的全部输入快捷键。

**为什么权限卡片留在原生**：它是安全关键路径（spec §6 的三条 SDK 强制规则）。Web 视图异常不应连带让"能不能批准"失效。放在转写区与输入区之间的固定槽位，天然满足 §6.3 的"固定可见、不被滚走"。

### 2.2 桥接

用 `JBCefJSQuery`（IntelliJ 对 CefMessageRouter 的封装）。

**Kotlin → JS**：

```kotlin
browser.cefBrowser.executeJavaScript("window.ccoder.pushBatch($json)", url, 0)
```

**JS → Kotlin**：

```kotlin
val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
jsQuery.addHandler { msg -> handleFromJs(msg); null }
browser.cefBrowser.executeJavaScript(
    "window.ccoder = window.ccoder || {};" +
    "window.ccoder.send = function(m) { ${jsQuery.inject("m")} };",
    url, 0
)
```

JS 初始化后发 `{"op":"ready"}`，Kotlin 收到后才开始推送 —— 否则早期消息会丢。

### 2.3 消息流与节流

`includePartialMessages: true` 会让逐 token 增量以极高频率到达。**每来一个增量就跨一次 CEF 边界会明显卡顿。**

设计：Kotlin 侧维护一个操作缓冲队列，**按 16ms（约 60fps）合并推送**，把 N 次 `executeJavaScript` 压成 1 次：

```kotlin
class TranscriptPump(private val browser: JBCefBrowser) {
    private val buffer = mutableListOf<TranscriptOp>()
    private val timer = Timer(16) { flush() }.apply { isRepeats = true; start() }

    fun enqueue(op: TranscriptOp) = synchronized(buffer) { buffer.add(op) }

    private fun flush() {
        val batch = synchronized(buffer) { buffer.toList().also { buffer.clear() } }
        if (batch.isEmpty()) return
        browser.cefBrowser.executeJavaScript(
            "window.ccoder.pushBatch(${TranscriptOpCodec.encodeBatch(batch)})", url, 0
        )
    }
}
```

定时器只在有待推送内容时做事，空闲时不产生跨边界调用。

---

## 3. 桥接契约

这是 Kotlin 与 React 之间唯一的接口。**两侧共用一份 fixture 文件做测试**（§8.2），防止一端改了另一端不知道。

### 3.1 Kotlin → JS：批量操作

推送的是操作数组：

```json
[
  {"op":"append","item":{"kind":"user","id":"m0","ts":1726050000000,"text":"你好"}},
  {"op":"appendDelta","target":"assistant","text":"你"},
  {"op":"appendDelta","target":"assistant","text":"好"},
  {"op":"finalizeDelta","target":"assistant","text":"你好"},
  {"op":"clearDelta","target":"assistant"},
  {"op":"reset"}
]
```

| op | 载荷 | 含义 |
|---|---|---|
| `append` | `item` | 追加一条完整消息 |
| `appendDelta` | `target`, `text` | 把文本追加到"进行中"的气泡；不存在则创建 |
| `finalizeDelta` | `target`, `text` | 用最终文本覆盖进行中的气泡并收尾（停光标） |
| `clearDelta` | `target` | 丢弃进行中的气泡（`Result`/`ErrorItem` 到达时调用） |
| `reset` | — | 清空全部（新会话） |

`finalizeDelta` 的存在是必需的：增量与最终 assistant 消息是两条独立来源，**两者都渲染会出现重复文本**。以最终消息为准收尾，这与 Kotlin 侧现有 `consume()` 的逻辑一致。

**关于 `target` 的取值**：当前**只有 `"assistant"` 一个取值**。`RenderItem.ThinkingDelta` 在 Kotlin 侧的 `consume()` 中就被丢弃（`is RenderItem.ThinkingDelta -> Unit`），理由是思考过程的逐字流入会持续刷屏，而它对用户的决策价值远低于正文。思考块仍然通过 `append` 以完整形式呈现。

保留 `target` 字段是为了将来若要开放思考流的逐字渲染时不必改契约。

### 3.2 消息项（`item`）

```json
{"kind":"assistant","id":"m12","ts":1726050000000,"text":"..."}
```

| kind | 附加字段 |
|---|---|
| `user` | `text` |
| `assistant` | `text` |
| `thinking` | `text`（默认折叠） |
| `toolUse` | `name`, `input`（原始 JSON 字符串，默认折叠） |
| `error` | `text` |
| `result` | `subtype`, `costUsd?`, `durationMs?` |
| `systemNote` | `text` |

### 3.3 JS → Kotlin

```json
{"op":"ready"}
{"op":"openLink","url":"https://..."}
```

`openLink` 走桥而非 `window.open`：JCEF 里的页面导航会把整个应用页面替换掉。同时 **Kotlin 侧注册 `CefLoadHandler` 拦截一切非首页导航作为兜底** —— 即便 JS 侧漏了一处，也不会把界面导航掉。

---

## 4. 视觉设计系统

### 4.1 主题同步

JCEF 拿不到 IDE 的主题变量，因此 **Kotlin 在浏览器初始化时读取一组颜色与字体，注入成 CSS 变量**，并在主题切换时重新注入。这样明暗主题自动正确，而不是我们猜两套配色。

注入的变量（Kotlin 侧读值来源）：

| CSS 变量 | Kotlin 来源 |
|---|---|
| `--bg` | `UIUtil.getPanelBackground()` |
| `--text` | `UIUtil.getLabelForeground()` |
| `--text-dim` | `UIUtil.getInactiveTextColor()` |
| `--border` | `JBColor` 派生的边框色 |
| `--accent` | `UIUtil.getTreeSelectionBackground()` |
| `--surface` | `UIUtil.getTextFieldBackground()`；若与 `--bg` 过于接近则用 `ColorUtil` 按明暗主题加重/减淡 6% |
| `--code-bg` | 编辑器背景色 |
| `--error-bg` | 错误色的低饱和变体 |
| `--font-ui` | `UIUtil.getLabelFont()` 的 family + size |
| `--font-mono` | `EditorColorsScheme` 的等宽字体 family + size |

**不硬编码任何颜色。** 用户的编辑器字体设置会被尊重。

### 4.2 气泡规格

| | 用户消息 | Claude 消息 |
|---|---|---|
| 对齐 | 右 | 左 |
| 最大宽度 | 85% | 92% |
| 圆角 | 12px | 12px |
| 内边距 | 10px 14px | 10px 14px |
| 背景 | 主色调低饱和变体 | `--surface` |

### 4.3 代码块

**留在气泡内，不突破内边距。** 代码块自身不带左右内边距，直接吃到气泡的 14px 边缘。

> 设计论证修正：初稿提出"代码块突破气泡内边距全宽显示"，理由是"压在内边距里会短到没法看"。实测算下来损失只有约 16%（294px vs 350px），不足以论证这个会让代码块视觉上脱离气泡的方案。改为贴边。

**默认不换行，横向滚动。** 原因是换行会破坏缩进：一个缩进四层的语句换行后续行会顶到最左边，视觉上与顶层代码齐平，Python / YAML / 嵌套 JSON 这类"缩进即语义"的代码会变得不可读。这与 GitHub、VS Code、终端的通行做法一致。

**右上角提供「自动换行」开关**，与复制按钮并排。状态存 `localStorage`。要看完整长行时一键切换 —— 把"要不要滚动"变成显式选择，而不是让换行悄悄破坏语义。

### 4.4 动画

| 动效 | 参数 |
|---|---|
| 消息入场 | `opacity 0→1` + `translateY(4px→0)`，180ms ease-out |
| 逐字流入 | 末尾 2px 宽光标，1s 步进闪烁 |
| 折叠展开 | `max-height` 过渡，160ms ease |
| 复制反馈 | 按钮文案切换为"已复制"，1500ms 后复原 |

**尊重 `prefers-reduced-motion`**：系统设为减少动效时全部关闭。

### 4.5 滚动跟随

**v1 曾有，重写时丢失 —— 这是一处回归。** v1 的 `ClaudePanel.scrollToBottom()` 在 `appendItem()` 与每个 `AssistantDelta` / `AssistantText` 之后无条件执行 `verticalScrollBar.value = verticalScrollBar.maximum`。JCEF 重写（`fda599a`）删掉了这一整块，React 侧没有补回来，于是转写区从"滚动很跳"退化成**完全不滚**：内容一超过一屏，最新输出就永远留在视野外。

§1.1 表格里的「滚动是跳变」描述的是 v1 的体验问题，**不是**重写后应有的状态 —— 那一项在重写中连同实现一起丢了，而不是被改好了。

**契约（智能跟随）**：

| 状态 | 进入条件 | 行为 |
|---|---|---|
| 跟随 | 默认；或用户滚回底部附近 | 每次内容增长后把视口带到最底端 |
| 暂停 | 用户主动向上滚、离开底部超过阈值 | 新内容不再移动视口 |
| 恢复 | 用户滚回距底 ≤ 阈值；或点击「回到底部」 | 回到跟随 |

**明确不做：无条件强制贴底。** 用户向上翻看历史时被拽回底部，等于历史不可读。无条件贴底只在"内容永远短于一屏"时成立，而逐 token 流式输出恰恰最容易突破一屏 —— 所以这不是可选的优化，是跟随必须"有记忆"的原因。

**阈值**：`scrollHeight - scrollTop - clientHeight <= 32`（px）。不判精确等于 0：浏览器缩放与亚像素布局下，"恰好为 0"不可靠，而误判的代价是把跟随莫名关掉。

**技术要点**：

| 点 | 说明 |
|---|---|
| 监听对象 | `.transcript` 元素本身（`styles.css` 中它是唯一的溢出容器），**不是** `window` |
| 程序化滚动必须打标记 | 否则自己触发的 `scroll` 事件会被当作用户滚动，把跟随关掉 |
| 流式跟随必须瞬时 | 逐 token 更新下，平滑动画每次都被新内容重启，视口永远追不上，表现为滞后抖动 —— 这是 §1.1「滚动是跳变」的另一种形态 |
| 因此移除 `.transcript` 的 `scroll-behavior: smooth` | 该声明会让**一切**程序化滚动（含 `scrollTop =` 赋值）变成动画。它目前是惰性的（没有任何程序化滚动，也无锚点导航），删掉无副作用；需要动画的只有「回到底部」按钮，在那里显式请求 `behavior: 'smooth'` |
| 依赖必须包含流式文本 | 只监听 `items.length` 会漏掉流式过程 —— `appendDelta` 期间 `items` 不变，只有 `live.assistant` 在增长 |

**「回到底部」按钮**：仅在**暂停状态且已有新内容到达**时显示，浮于转写区右下角。点击 → `behavior: 'smooth'` 滚到底并恢复跟随。

对应测试见 §8.1，手工验证见 §8.4 第 9 条。

---

## 5. 组件规格

React 只负责转写区（输入区与权限卡片在原生）。

```
<Transcript>
  <UserBubble />
  <AssistantBubble />
  <ThinkingBlock />        ← 真折叠，默认收起
  <ToolCallBlock />        ← 真折叠，默认收起，标题显示工具名，展开显示原始 input
  <CodeBlock />            ← 语言标签 + 复制 + 自动换行开关 + 语法高亮
  <ErrorBubble />
  <ResultLine />
  <SystemNote />
  <StreamingCursor />
</Transcript>
```

**消息时间戳**：每条消息底部一行 `--text-dim` 的小字，格式 `HH:mm`。悬停显示完整日期。

**链接**：Markdown 渲染出的 `<a>` 一律拦截点击，走 §3.3 的 `openLink`。

---

## 6. 构建与打包

### 6.1 前端项目

`web/` 是独立的 Vite + React + TypeScript 项目。

**用 `vite-plugin-singlefile` 把所有 JS/CSS 内联进单个 HTML**。运行时直接 `loadHTML()` —— 不需要运行时提取文件，不需要自定义 `CefResourceHandler`。这是最少活动部件的做法。

**语法高亮按需引入语言**：highlight.js 全量约 1MB，只引常用十余种后约 100KB。

### 6.2 Gradle 集成

新增 `buildWebUi` 任务：`npm ci` → `npm run build` → 产物拷进插件资源 `webui/`。挂在 `processResources` 上。

**加 `-PskipWeb` 开关**：改纯 Kotlin 时可跳过 npm 步骤，避免每次构建都付这个代价。

### 6.3 开发体验

用 Vite 的价值在于 HMR，必须把它拿回来：

- **开发模式**：`runIde` 时若设了 `-Dccoder.devServer=http://localhost:5173`，JCEF 直接 `loadURL` 指向 dev server。改 React 代码热更新，**不用重启 IDE**。
- **生产模式**：读内联的单文件 HTML。

---

## 7. 依赖与平台要求

- **JCEF**：需在 `build.gradle.kts` 加 `bundledPlugin("com.intellij.modules.jcef")`。`com.intellij.modules.jcef` 自 2025.3.1 起是稳定标识（项目基线是 2025.3.1.1，满足）。
- **前端依赖**：react、react-dom、marked（或 markdown-it）、highlight.js、vite、@vitejs/plugin-react、vite-plugin-singlefile、vitest、@testing-library/react

---

## 8. 测试

### 8.1 React 侧

Vitest + Testing Library。覆盖：

- 每种 `kind` 的消息渲染正确
- `appendDelta` 累积到进行中的气泡，`finalizeDelta` 以最终文本覆盖
- `clearDelta` 丢弃进行中的气泡
- 复制按钮写入剪贴板并把文案切为"已复制"
- 自动换行开关切换 `white-space` 并持久化
- 链接点击走桥而非默认导航
- 未知 `kind` 不导致渲染崩溃（对应 spec §3.3 的"未知即忽略"原则）
- **滚动跟随（§4.5）**：内容增长时视口被带到最底端；用户上滚离开底部后新内容不再移动视口；用户滚回底部附近后跟随自动恢复；暂停态下「回到底部」按钮出现，点击后恢复跟随

### 8.2 契约测试

`web/src/__fixtures__/transcript-ops.json` 是一份**两侧共用**的操作序列 fixture。

- Kotlin 侧：`TranscriptOpCodecTest` 断言序列化结果与 fixture 逐字节一致
- React 侧：`codec.test.ts` 断言能正确消费该 fixture

任一端的契约改动都会让另一端的测试变红。

### 8.3 Kotlin 侧

**现有 101 个测试全部保留。** `MessageRenderer`（SDK 事件 → `RenderItem`）与用什么渲染无关，一行不动。

新增：`TranscriptOpCodecTest`（§8.2）、`ThemeInjectorTest`（颜色与字体转 CSS 变量的格式化）。

### 8.4 手工冒烟

视觉效果只能人眼判断。清单：

1. 明暗主题各看一遍，配色无死板硬编码
2. 发一条长回复，观察逐字流入是否平滑（验证 §2.3 的节流）
3. 让 Claude 输出一段带缩进的 Python 代码，确认默认不换行、横向可滚、缩进完整
4. 切换「自动换行」，确认状态持久化
5. 点击代码块复制按钮，确认剪贴板内容与显示一致
6. 点一个链接，确认在外部浏览器打开而非把界面导航掉
7. 触发权限卡片，确认它在固定槽位、不被转写区滚动带走
8. 关闭工具窗口、再关闭整个项目，两次都确认无残留 `node.exe` / `claude.exe`（回归 spec §7.4）
9. 发一条会超过一屏的长回复，观察视口是否始终跟到最新内容；再手动向上滚，确认视口不再被拽走、右下角浮出「回到底部」，点击后回到底部并恢复跟随（验证 §4.5）

---

## 9. 现有代码的处置

| 文件 | 处置 |
|---|---|
| `MessageRenderer.kt` | **保留不动** —— 它做的是"SDK 事件 → RenderItem"，与渲染方式无关 |
| `MessageRendererTest.kt` | **保留不动** |
| `PermissionCard.kt` / `PermissionQueue.kt` | **保留不动**，含 14 个测试 |
| `ClaudePanel.kt` | 大幅修改：转写区换成 `ClaudeTranscriptView`，头部/输入区/权限槽位保留 |
| 新增 | `sidecar/TranscriptOp.kt`（操作类型）、`ui/TranscriptOpCodec.kt`、`ui/TranscriptPump.kt`、`ui/ThemeInjector.kt`、`ui/ClaudeTranscriptView.kt`、`web/` 整个前端项目 |

**改动是外科式的：只替换转写区的渲染实现，其余逻辑一行不动。** 这样出问题时能干净地定位是"渲染层"还是"逻辑层"。

---

## 10. 顺带修复的既有缺陷

### 10.1 通知组注册失败

`runIde` 日志：

```
WARN - #c.i.n.NotificationGroupManagerImpl -
  Cannot create notification group "CCoder Permissions`": displayType should be not null
```

`plugin.xml` 中已写 `displayType="STICKY"`，但平台判定为 null。**根因尚未定位** —— `notificationGroup` EP 在 2025.3 的定义可能与写法不符。

影响：spec §6.3「不可忽略性补偿」中的粘性通知目前不工作（状态栏与卡片置顶两项仍正常）。

处置：本次一并修复。先查清 2025.3 的 `notificationGroup` EP 定义再改，不猜测。

**范围边界**：这是一个独立的、预期为单文件配置的修复。若查下来根因涉及平台 API 变更或需要改用 `NotificationGroupManager` 的非 EP 注册路径，把它拆成独立任务，**不拖住 UI 重设计的主体工作**。

---

## 11. 风险与未决

| 风险 | 影响 | 缓解 |
|---|---|---|
| JCEF 初始化失败或不可用 | 转写区空白 | 检测 `JBCefApp.isSupported()`，不支持时回退到现有的原生渲染并提示。**原生渲染代码不删，保留为降级路径** |
| 逐 token 增量推送仍卡顿 | "流畅"目标落空 | §2.3 的 16ms 节流。若仍不够，退一步降低推送频率或改为 JS 侧插值 |
| 气泡在窄栏的固有拥挤 | 可读性 | 已用代码块贴边、宽度上限、收紧内边距缓解。这是选定风格的固有代价 |
| 前端构建拖慢 Kotlin 侧的构建 | 开发体验 | `-PskipWeb` 开关（§6.2） |
| 主题色彩注入不准 | 明暗下观感断裂 | 只从平台 API 取值，不自己造色（§4.1） |
| highlight.js 体积 | 插件包膨胀 | 按需引入语言（§6.1） |

### 待实现时确认

- `com.intellij.modules.jcef` 依赖在 2025.3.1.1 下的确切写法
- `vite-plugin-singlefile` 产出的实际体积
- 通知组 EP 的根因（§10.1）
