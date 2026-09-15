# 排队输入（跑着的时候还能接着敲）

> 设计稿：`docs/design/queued-input.html`（方案乙）。探针：`sidecar/tools/probe-queue.mjs`。
> 2026-09-15。

## 1. 要解决的问题

回合进行中，用户想起一句话要补，按回车 —— **什么都不会发生**。

`ClaudePanel.kt:385-387`：

```kotlin
// 回合进行中不发送：那时按钮是"停止"，发送键却另发一条会让
// 两者语义打架（见 mainButtonState）
if (busy) return
```

那行备注说的矛盾是真的：忙时按钮是「停止」，按键却另发一条，两者确实打架。
但这个矛盾不该用"键盘什么都不做"来解 —— 用户想补的那句话**就这么没了**
（输入框还留着字，但按下回车毫无反应，没有任何提示）。

要的是：忙时回车**把消息排进队列**，这一回合跑完自动发出去；排队期间看得见、撤得回、
点「停止」时一起清掉。

## 2. 前提：探针量到的六条事实

全部来自 `probe-queue.mjs` 的实测（2026-09-15，CLI 2.1.268 一档），不是推断：

| # | 事实 | 对本设计的意义 |
|---|---|---|
| 01 | 忙时回车被吞（`ClaudePanel.kt:387`），输入框本身没禁用 | 口子只有这一处，鼠标没有第二条路 |
| 02 | 回合中推消息，CLI **收下并折进当前回合**：A 跑着时推 B、C → 只有 **1 个 result**，一段回复里答了 `A完成/B-OK/C-OK` | 排队**不能**靠 CLI：它的语义是"并进这一回合"，不是"排到后面" |
| 03 | 顺序保得住（B 在 C 前发，回答里也是 B-OK 在前） | 队列不需要我们排序 |
| 04 | **没有任何回执**：事件流没有"已收下/已折入"信号；自己盖的 `uuid` 在 result 上是 `-` | "排队中"只能靠本地状态，协议给不了 |
| 05 | 撤回没有 SDK 入口：`interrupt()` 返回 `undefined`；`cancel_async_message` 未暴露 | 撤回必须是**本地**动作 —— 消息还没发出去才撤得回 |
| 06 | 打断**不会**清掉排队的：D 被打断后 E 照样跑了 | 「停止」必须自己定义对排队的处理，否则看着像没停干净 |

事实 02 与 06 合起来是这个方案的立足点：**CLI 的队列不在我们手里，
我们才需要自己拿一个** —— 消息停在插件里，撤回和"停干净"就都是本地动作。

## 3. 数据模型

```kotlin
/** 排队中的一条输入。展开与清空在**入队那一刻**就做完了（见 §5）。 */
internal data class QueuedInput(
    /** 发出去时用的正文：snippet 记号已展开。 */
    val text: String,
    /** 用户敲的原样。只用来认会话标题 —— 拿展开后的文本当标题会变成「```kotlin …」。 */
    val typed: String,
)

/** FIFO 队列。**不依赖 Swing 与 Project**，所以能单测（同 MainButtonState 的理由）。 */
internal class SendQueue {
    fun enqueue(text: String, typed: String)
    fun peek(): QueuedInput?
    fun remove(item: QueuedInput): Boolean   // ✕ 撤回单条
    fun drain(): List<QueuedInput>           // 清空，返回被清掉的（停止 / 断线 / 重启）
    val size: Int
}
```

不做上限（设计稿 ④）。队列只活在面板里，**不进持久化** —— 关掉 IDE 没发出去的
那句话就没了，与"输入框里没敲完的字"同命，这是对的。

## 4. 排队条画在哪：输入卡上方，**不进转写区**

设计稿的 mockup 把排队中的消息画成了转写区里的一个虚线气泡。**落地上要偏这一点**，
理由是硬性的：

- `TranscriptOp` 只有 Append / AppendDelta / FinalizeDelta / ClearDelta / Reset，
  **没有"改一条已经推出去的项"**，而且那条规矩是有意的（`TranscriptOp.kt:38`：
  "这样操作序列保持只追加"）。要在转写区里把虚线气泡改成实心、或把它撤掉，
  就得新造一个 update/remove 操作，协议、Kotlin codec、web codec、渲染器一起动。
- 更根本的是**它还不该在那儿**：转写区是"跟模型说过什么"的记录，
  而排队的消息**还没发出去**；撤回时它更没说过。把它画进去，撤回就得在
  记录上开一个洞。

所以：排队条落在**输入卡上方、状态卡下方**（`ClaudePanel` 的 `header` 槽，
现在只有状态卡 + 一条 strut）。发出去的那一刻，它从条上消失、
照常以 `RenderItem.UserText` 进转写区 —— 走的是**今天就在用的那条路**，
协议与 web 侧**零改动**。

## 5. 线路

### 5.1 一个发出口：`sendNow`

标题认领（`adoptTitleFrom`）、`lastSendWasCommand`、`setBusy(true)`、
`setActivity(ACTIVITY_WAITING)` 现在都写在 `sendCurrentInput()` 里。
这两条路（直接发 / 排队后发）如果各写一遍，迟早漂移 —— 尤其
`lastSendWasCommand` 是**发送那一刻**的属性（排队时算不算命令，要等真发出去才算）。

抽一个：

```kotlin
/** 真正把一条文本发出去。两条路（直接发 / 排队后发）唯一的出口。 */
private fun sendNow(text: String, typed: String) {
    adoptTitleFrom(typed)
    client?.sendLine(Protocol.encodeSend(nextId(), text))
    lastSendWasCommand = text.startsWith("/")
    setBusy(true)
    setActivity(ACTIVITY_WAITING)
}
```

### 5.2 入队

`sendCurrentInput()` 里，展开与清空输入框之后分岔：

```kotlin
val text = snippetRefs.expand(typed)   // 记号在这一刻展开
input.text = ""
snippetRefs.clear()

if (!ready) { /* 未就绪那条路一个字不动，见 §5.5 */ }

if (busy) {                            // ← 新增：忙时入队而不是丢弃
    queue.enqueue(text, typed)
    refreshQueueStrip()
    return
}

pushOp(toOp(RenderItem.UserText(text)))  // 只有真的发出去才进转写区
sendNow(text, typed)
```

注意 `pushOp` 的位置：**直接发那条路照旧**（气泡立刻出现），
**排队那条路不推**（气泡要等发出去才出现）。

### 5.3 flush

`result` 分支里（今天 `setBusy(false)` 那一处，`ClaudePanel.kt:1813`）：

```kotlin
if (items.any { it is RenderItem.Result }) {
    setBusy(false)
    lastSendWasCommand = false
    flushQueue()      // ← 新增
    requestContextUsage()
}
```

```kotlin
/** 回合结束，把队首发出去。一次只发一条 —— 它的 result 到了再发下一条。 */
private fun flushQueue() {
    val next = queue.peek() ?: return
    queue.remove(next)
    pushOp(toOp(RenderItem.UserText(next.text)))
    sendNow(next.text, next.typed)
    refreshQueueStrip()
}
```

`sendNow` 里的 `setBusy(true)` 会把忙态重新支起来 —— 所以 `flushQueue` 必须
**排在 `setBusy(false)` 之后**，顺序反了按钮会闪一下「发送」。

### 5.4 停止 / 断线 / 重启

- **停止**（`mainButtonState` 的 `Interrupt` 分支）：先 `queue.drain()` 再 `interrupt`。
  清空是**立刻**的，不等回执（事实 05：回执也没有）。按钮 tooltip 在队列非空时
  写成「停止并清掉 N 条排队」。
- **fatal 断开 / `restartSession` / `stopSession`**：`queue.drain()`。
  会话都换了，把上一段的指令发到新会话里是灾难。
- 被清掉的消息**不留痕迹**（它从没进过转写区，§4）。设计稿里"气泡变灰写已取消"
  是 mockup 阶段的说法，落地按这里。

### 5.5 不动的两条路

- **未就绪的首条消息**（`pendingFirstMessage`，`ClaudePanel.kt:2374`）原样保留。
  未就绪时 `busy == false`（`mainButtonState` 给的是「启动中…」），
  两条路不会同时命中。
- **权限询问**（模态）：与排队正交 —— 排队期间弹权限框照旧。

## 6. UI：排队条

**2026-09-15 按用户要求改过一次**：「排队的信息大于 1 条应该转成一个列表，
点击后展开，不然太占用空间了」。第一版是"最多列 3 行 + 还有 N 条"，仍然太高。

```
一条                     两条以上（默认收着）        点开之后
┌───────────────────┐   ┌───────────────────┐   ┌───────────────────┐
│ 排队 1 · 等一下… ✕ │   │ 排队 2          ▸ │   │ 排队 2          ▾ │
└───────────────────┘   └───────────────────┘   │  等一下，先别动… ✕ │
                                                │  跑完把这几个…   ✕ │
                                                └───────────────────┘
```

- **一条**：内容直接写在那一行上（`排队 1 · 正文` + `✕`）。「排队 1」单独占
  一行是白占一行。
- **两条以上**：收成一行「排队 N ▸」，点那一行展开/收起（`▾`/`▸` 提示可点）。
  展开后逐行列出，每行带自己的 `✕`。
- **开合状态不随刷新重置**：开着看的时候又来一条，不能把它关掉；
  队列回到一条或空时才归位。
- **不再有行数上限**：收折已经把空间问题解决，而用户**主动点开**就该看得全。
- 队列空时**整块不占位**（`isVisible = false`）——
  常驻一块空白会让人以为坏了；"凭空多一块"是这个面板反复躲过的坑。
- 排队期间输入框照旧可编辑、可补全、可 ↑ 取历史 —— 排队的只是"发出去"，
  不是输入区（设计稿 ⑤）。
- 忙时回车入队，**不再需要提示语**：排队条一出现，这件事就自解释了。
  （设计稿 mockup 里那行「它在跑 —— 回车会把消息排进队列」按这个理由砍掉。）

## 7. 测试

| 测什么 | 在哪 |
|---|---|
| FIFO 顺序、撤回单条、`drain` 返回被清的、`size` | `SendQueueTest` —— 纯 Kotlin，无 Swing |
| 排队条的排版与"空时不占位" | `ClaudePanel` 起不了单测 → 照 `ComposerRenderProbe` 的惯例加 `QueueStripRenderProbe` 截图 |
| 忙时回车不再被吞 | 评审要点（`ClaudePanel` 不可测）；`SendQueueTest` 只能守队列那半边 |

`web/` 与 `sidecar/` **不加测试**：这两侧本次零改动。
（探针 `probe-queue.mjs` 留在仓库，事实要重跑时用它。）

## 8. 代价 / 已知限制

- **与终端手感不同**：你排的第二句不会再折进当前回合（事实 02 那种），
  而是各起一回合。换来的是"看得见、撤得回、停得干净"。
- **flush 的判据是 `result`**：现有代码已经这么假设（`setBusy(false)` 就在那儿）。
  **待确认**：一个任务里会不会出现"中间 result" —— 若会，排队会提前放行。
  实测（探针阶段 1/3）里每次顶层回合恰好一个 result。
- **排队期间它仍在跑**：用户排了 3 条，第 1 条的回合可能跑很久；期间不能改
  已排的内容，只能撤掉重敲。

## 9. 明确不做的

- 排队条数的上限（要拦也只拦越界情况，如排队里发 `/` 命令 —— 照排，不特殊对待）。
- 编辑已排队的消息（撤销 + 重敲就够）。
- 把排队的消息画进转写区（§4 的两条理由）。
- 设计稿方案丙那颗鼠标可达的发送键 —— 留着，用几天发现"忙时只会按回车"不够用再加。
- 协议 / sidecar / web 侧的任何改动。
