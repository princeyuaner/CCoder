# 排队输入 Implementation Plan

> Spec：`docs/superpowers/specs/2026-09-15-queued-input-design.md`
> 设计稿：`docs/design/queued-input.html`（方案乙）
> 探针：`sidecar/tools/probe-queue.mjs`（spec §2 那六条事实可重跑）

**要解决的问题**：回合进行中按回车什么都不发生（`ClaudePanel.kt:387` 的 `if (busy) return`）。
改成：忙时回车把消息排进队列，这一回合跑完自动发出去；排队期间看得见、撤得回、
点「停止」时一起清掉。

## Global Constraints

1. **协议 / sidecar / web 三侧零改动。** 消息停在插件里（Kotlin 这一侧），
   发的还是今天那条 `Protocol.encodeSend`。所以这次**不用跑** `web/` 的
   `npm test`、也不用跑布局探针 —— 它们测的东西一个字节都没动。
2. **`./gradlew test -PskipWeb` 必须绿。** 加 `-PskipWeb` 是必须的：`test` 会
   顺着 `resources.srcDir(buildWebUi)` 拉起一次前端构建，而这次前端零改动。
3. **注释写"为什么"**，不写"是什么" —— 沿用本仓库既有的注释风格。
   每一处"为什么是这个顺序""为什么砍掉那个提示语"都要落在代码上。
4. **提交粒度**：
   - Task 1、Task 2 是纯新增，**各自可独立提交**；
   - Task 3 是纯重构（行为一字不变），单独提交，好回滚；
   - **Task 4 必须一次提交** —— `ClaudePanel` 不能半改（`sendNow` 抽出来了却没人调用、
     或者字段加了却没接线，都是编译不过）。2026-09-13 那次计划就栽在
     "任务边界打断了构建"上（见 status-cards 计划的《计划本身的三处缺陷》）。
5. **模型与 Swing 分开**：队列逻辑与排队条的排版模型都是纯 Kotlin，能单测；
   组件只负责画（`MainButtonState` 的先例）。

## 文件结构

新增：

| 文件 | 内容 |
|---|---|
| `src/main/kotlin/com/ccoder/ui/SendQueue.kt` | `QueuedInput` + `SendQueue` |
| `src/main/kotlin/com/ccoder/ui/QueueStrip.kt` | 排版模型（纯函数）+ `QueueStrip` 组件 |
| `src/test/kotlin/com/ccoder/ui/SendQueueTest.kt` | 队列语义 |
| `src/test/kotlin/com/ccoder/ui/QueueStripModelTest.kt` | 排版模型 |
| `src/test/kotlin/com/ccoder/ui/QueueStripRenderProbe.kt` | 出两张 PNG 给人眼看 |

改动：

| 文件 | 改什么 |
|---|---|
| `src/main/kotlin/com/ccoder/ui/ClaudePanel.kt` | Task 3 抽 `sendNow`；Task 4 接线 |
| `src/main/kotlin/com/ccoder/ui/MainButtonState.kt` | Task 4 加 `queued` 参数 |
| `src/test/kotlin/com/ccoder/ui/MainButtonStateTest.kt` | Task 4 补两条用例 |

---

### Task 1: 队列本体（纯 Kotlin，可独立提交）

**为什么队列不在 sidecar 里**：sidecar 那一侧要能"停干净"就得先知道回合什么时候结束，
而它只知道消息发出去没有。队列在面板里，撤回与清空就都是本地动作 ——
不必求 SDK 开口子（它也**没开**：`interrupt()` 不给收据，`cancel_async_message` 没暴露，
见 spec §2 的事实 05）。

新建 `src/main/kotlin/com/ccoder/ui/SendQueue.kt`：

```kotlin
package com.ccoder.ui

/**
 * 排队中的一条输入（spec §3）。
 *
 * 两个字段的分工是**不能合并**的：[text] 是发出去要用的正文，snippet 记号
 * 在入队那一刻就展开好了（那时候才拿得到 [ComposerReferences] 那张表）；
 * [typed] 是用户敲的原样，只用来认会话标题 —— 拿展开后的文本当标题会变成
 * 「```kotlin …」（见 ClaudePanel.sendCurrentInput 里同样的理由）。
 */
internal data class QueuedInput(val text: String, val typed: String)

/**
 * 排队中的输入。FIFO。
 *
 * **不依赖 Swing 与 Project**，所以能单测 —— 与 [MainButtonState] 同一个理由：
 * "什么时候该发下一条"是这次最容易写错的地方，而那部分不能靠人眼。
 */
internal class SendQueue {

    private val items = ArrayDeque<QueuedInput>()

    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    fun enqueue(text: String, typed: String) {
        items.addLast(QueuedInput(text, typed))
    }

    /** 看一眼队首，**不**取走 —— flush 要先画转写区再发。 */
    fun peek(): QueuedInput? = items.firstOrNull()

    /**
     * 撤回单条（排队条上的 ✕）。
     *
     * 按内容找第一条，不做引用追踪：两条内容一模一样的消息在界面上一模一样，
     * 撤掉哪一条对用户是同一件事。**返回是否真的撤掉了一条**，调用方据此决定
     * 要不要重画。
     */
    fun remove(item: QueuedInput): Boolean = items.remove(item)

    /** 清空，返回被清掉的那些（停止 / 断线 / 切会话 / 重启）。 */
    fun drain(): List<QueuedInput> {
        val out = items.toList()
        items.clear()
        return out
    }

    /** 只读快照，给排队条排版用。 */
    fun snapshot(): List<QueuedInput> = items.toList()
}
```

新建 `src/test/kotlin/com/ccoder/ui/SendQueueTest.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 排队语义。抽成纯类就是为了这几条能钉住 ——
 * 真正的风险不是"排队不工作"，是"顺序错了"和"点了停止还在发"。
 */
class SendQueueTest {

    @Test
    fun `先进先出`() {
        val q = SendQueue()
        q.enqueue("第一条", "第一条")
        q.enqueue("第二条", "第二条")
        assertEquals("第一条", q.peek()?.text)
        q.remove(q.peek()!!)
        assertEquals("第二条", q.peek()?.text)
    }

    @Test
    fun `peek 不取走`() {
        val q = SendQueue()
        q.enqueue("a", "a")
        assertEquals(1, q.size)
        q.peek()
        assertEquals(1, q.size)
    }

    @Test
    fun `撤回单条只撤一条`() {
        val q = SendQueue()
        q.enqueue("a", "a")
        q.enqueue("b", "b")
        assertTrue(q.remove(QueuedInput("a", "a")))
        assertEquals(1, q.size)
        assertEquals("b", q.peek()?.text)
    }

    @Test
    fun `撤一条不在队里的什么也不做`() {
        val q = SendQueue()
        q.enqueue("a", "a")
        assertFalse(q.remove(QueuedInput("没有这条", "没有这条")))
        assertEquals(1, q.size)
    }

    @Test
    fun `drain 返回被清掉的并按原顺序`() {
        val q = SendQueue()
        q.enqueue("a", "说 a")
        q.enqueue("b", "说 b")
        assertEquals(listOf("a", "b"), q.drain().map { it.text })
        assertTrue(q.isEmpty)
    }

    @Test
    fun `空队列的行为`() {
        val q = SendQueue()
        assertNull(q.peek())
        assertEquals(emptyList<QueuedInput>(), q.drain())
        assertEquals(0, q.size)
    }
}
```

**跑**：

```bash
./gradlew test -PskipWeb --tests "com.ccoder.ui.SendQueueTest"
```

**Expected**：6 条全绿；其余测试不受影响（这一步只是新增文件）。

---

### Task 2: 排队条的模型与组件（可独立提交）

新建 `src/main/kotlin/com/ccoder/ui/QueueStrip.kt`：

```kotlin
package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 排队条一行里最多放多少个字。
 *
 * 截断在**模型层**做而不是交给 JLabel 自动省略：自动省略的宽度取决于布局过程，
 * 单测测不到，而"一行的长度"正是这条带子最容易失控的地方（用户能敲进一整段代码）。
 */
internal const val QUEUE_ROW_CHARS = 40

/** 最后一行之外最多列几行，多的折成「还有 N 条」。 */
internal const val QUEUE_MAX_ROWS = 3

/** 排队条上的一行：[label] 是截好、折好行的显示文本，[item] 用来撤回。 */
internal data class QueueRow(val item: QueuedInput, val label: String)

/** 排队条要显示什么。空队列给 null —— 调用方据此把整块收起来（spec §6）。 */
internal data class QueueStripModel(
    val count: Int,
    val rows: List<QueueRow>,
    /** 没列出来的条数。0 表示都列出来了。 */
    val overflow: Int,
)

/** 一行能放下的展示文本：换行与连续空白折成一个空格，超长的截断加省略号。 */
internal fun queueRowText(text: String): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= QUEUE_ROW_CHARS) flat else flat.take(QUEUE_ROW_CHARS) + "…"
}

/**
 * 排队条的内容。空队列给 null。
 *
 * **只列前 [QUEUE_MAX_ROWS] 行**：这条带子挂在输入卡上方，它一高，
 * 输入区就矮 —— 排 20 条不是为了让它们占据整个屏幕。
 */
internal fun queueStripModel(queue: SendQueue): QueueStripModel? {
    if (queue.isEmpty) return null
    val all = queue.snapshot()
    val rows = all.take(QUEUE_MAX_ROWS).map { QueueRow(it, queueRowText(it.text)) }
    return QueueStripModel(count = all.size, rows = rows, overflow = all.size - rows.size)
}

/**
 * 排队条：哪些消息还排着、各自一眼能认出来、都能撤（spec §6）。
 *
 * 它只负责画 —— 显示什么由 [queueStripModel] 这个纯函数决定，
 * 那条规则有测试钉着（同 [RoundSendButton] 与 [MainButtonState] 的分工）。
 *
 * **不进插槽的可见性管理**：空队列时由外面那个插槽整块收掉（见 ClaudePanel.queueSlot），
 * 这里只管把自己画对。
 */
internal class QueueStrip(private val onRemove: (QueuedInput) -> Unit) : JPanel() {

    private val head = JLabel()
    private val body = JPanel()
    private val more = JLabel()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(2, 8)

        for (label in listOf(head, more)) {
            label.font = JBUI.Fonts.smallFont()
            label.foreground = UIUtil.getInactiveTextColor()
        }
        body.layout = BoxLayout(body, BoxLayout.Y_AXIS)
        body.isOpaque = false

        add(head)
        add(body)
        add(more)
    }

    fun setModel(model: QueueStripModel?) {
        if (model == null) return // 空的时候由插槽收走，这里不必清

        // 不做复数处理：中文里「排队 1」与「排队 2」同一个说法，写「1 条」反而啰嗦
        head.text = "排队 ${model.count}"

        body.removeAll()
        for (row in model.rows) body.add(buildRow(row))

        more.text = if (model.overflow > 0) "还有 ${model.overflow} 条" else ""
        more.isVisible = model.overflow > 0

        revalidate()
        repaint()
    }

    private fun buildRow(row: QueueRow) = JPanel(BorderLayout()).apply {
        isOpaque = false
        // 卡面只留一行摘要，全文挂 tooltip（与工具卡片那条摘要同一个道理）
        add(
            JLabel(row.label).apply {
                font = JBUI.Fonts.smallFont()
                toolTipText = row.item.text
            },
            BorderLayout.CENTER,
        )
        add(
            JButton("✕").apply {
                isContentAreaFilled = false
                isBorderPaced = true
                toolTipText = "从队列里撤掉这条"
                addActionListener { onRemove(row.item) }
            },
            BorderLayout.EAST,
        )
    }
}
```

> `isBorderPaced` 是 `JButton` 上没有的属性，写代码时删掉 —— 留在这里是提醒：
> 那个 ✕ 的尺寸与留白**以 Task 5 的探针图为准**，单测管不着它。

新建 `src/test/kotlin/com/ccoder/ui/QueueStripModelTest.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QueueStripModelTest {

    @Test
    fun `空队列给 null —— 那块整块不占位`() {
        assertNull(queueStripModel(SendQueue()))
    }

    @Test
    fun `一条就是一行，没有折叠`() {
        val q = SendQueue().apply { enqueue("跑完把测试补一下", "跑完把测试补一下") }
        val m = queueStripModel(q)!!
        assertEquals(1, m.count)
        assertEquals(1, m.rows.size)
        assertEquals(0, m.overflow)
        assertEquals("跑完把测试补一下", m.rows[0].label)
    }

    @Test
    fun `超过三行折进还有 N 条`() {
        val q = SendQueue()
        repeat(5) { q.enqueue("第 $it 条", "第 $it 条") }
        val m = queueStripModel(q)!!
        assertEquals(5, m.count)
        assertEquals(3, m.rows.size)
        assertEquals(2, m.overflow)
    }

    @Test
    fun `超长的行截断加省略号`() {
        val long = "平".repeat(60)
        assertEquals("平".repeat(QUEUE_ROW_CHARS) + "…", queueRowText(long))
    }

    @Test
    fun `正好到上限不截断`() {
        val exact = "平".repeat(QUEUE_ROW_CHARS)
        assertEquals(exact, queueRowText(exact))
    }

    @Test
    fun `多行文本折成一行 —— 一行记号，不是一个块`() {
        assertEquals("第一行 第二行", queueRowText("第一行\n第二行"))
        assertEquals("a b", queueRowText("  a \n\n   b  "))
    }
}
```

**跑**：

```bash
./gradlew test -PskipWeb --tests "com.ccoder.ui.SendQueueTest" --tests "com.ccoder.ui.QueueStripModelTest"
```

**Expected**：12 条全绿（6 + 6）。`QueueStrip` 此时还没有人实例化它，编译得过也跑得着。

---

### Task 3: 抽出 `sendNow()`（纯重构，行为一字不变，单独提交）

今天"发一条消息"的五步都写在 `sendCurrentInput()` 里。排队之后有两条路要发消息
（直接发、排队后发），各写一遍迟早漂移 —— 尤其 `lastSendWasCommand` 是
**发送那一刻**的属性：排队时算不算命令，要等真发出去才算（spec §5.1）。

改 `ClaudePanel.kt` 的 `sendCurrentInput()`（约 2350 行），把尾部的五步换成一次调用：

```kotlin
    private fun sendCurrentInput() {
        if (input.text.isBlank()) return

        // 用户敲进去的原样。标题取的是**它**，不是下面展开后的文本 ——
        // 展开会把记号变成一大段代码，拿它当标题就成了「```kotlin …」
        val typed = input.text.trim()

        // 记号在这一刻展开：输入框里只是「一行记号」，发出去的是路径 + 围栏 + 代码全文。
        // 展开放在**清空输入框之前**，而清空之后表也一起清掉 —— 记号已经不在文本里了，
        // 留着它只会随会话越攒越大
        val text = snippetRefs.expand(typed)

        input.text = ""
        snippetRefs.clear()
        pushOp(toOp(RenderItem.UserText(text)))

        if (!ready) {
            // 会话还没就绪。可能是 fatal 断开后残留的进程，先清干净再起一个，
            // 否则 startSession 会因为 proc != null 直接返回、消息永远发不出去。
            if (proc != null) stopSession()
            disconnected = false

            // 消息暂存，就绪后由 Ready 分支补发 —— 若此处直接丢弃，
            // 用户点第一次"发送"时会看到消息出现却毫无反应。
            pendingFirstMessage = text
            // 标题取用户敲的那份（见上面 typed），暂存着等 Ready 之后一起认
            pendingFirstMessageTitle = typed
            refreshMainButton()
            startSession()
            return
        }

        sendNow(text, typed)
    }

    /**
     * 真正把一条消息发出去。**直接发与排队后发唯一的出口**（spec §5.1）。
     *
     * 这五步都必须发生在**发送那一刻**，不能提前到入队那一刻：
     * - [adoptTitleFrom]：标题取用户敲的原样。排队时就认，等于让一条还没发出去、
     *   还可能被撤掉的消息改掉会话标题
     * - `lastSendWasCommand`：命令回合的判据是"发出去的是什么"
     *   （实测 `result.local_command` 恒为 null，见设计稿 §5.1）
     * - [setBusy] / [setActivity]：它们描述的是"现在在跑"，而排队中并没有在跑
     *
     * 标题必须**紧挨着发送**：会话还没就绪时 startSession() 之后 Ready 分支会把
     * 标题清成 null，提前认的那一次会被它抹掉。
     */
    private fun sendNow(text: String, typed: String) {
        adoptTitleFrom(typed)
        client?.sendLine(Protocol.encodeSend(nextId(), text))
        lastSendWasCommand = text.startsWith("/")
        setBusy(true)
        setActivity(ACTIVITY_WAITING)
    }
```

**跑**：

```bash
./gradlew test -PskipWeb
```

**Expected**：全绿，且**行为与改动前一字不差** —— 这一步不新增任何行为。
评审时盯着这三件事：`pushOp` 仍在 `if (!ready)` **之前**（未就绪时气泡也立刻出现，
这是今天的既有行为）、`adoptTitleFrom` 仍在紧挨 `sendLine` 之前、
`setActivity(ACTIVITY_WAITING)` 仍在最后。

---

### Task 4: 入队 / flush / 清空 / 按钮文案（一次提交）

#### 4.1 `MainButtonState.kt`：忙时那个「停止」要说清它会清掉几条

```kotlin
/**
 * 发送/停止合一按钮的状态。
 *
 * 规则，按优先级：
 *  1. 会话已 fatal 断开 → "重启会话"，点击重连。
 *     **优先于"停止"**：进程都没了，没有东西可以中断
 *  2. 回合进行中 → "停止"，点击发 `interrupt` 让模型停下。
 *     注意不是 `stop` —— `stop` 会销毁整个会话（index.js 里把 session 置 null），
 *     用户会看到"已连接"却再也发不出消息。
 *     **队列非空时文案要带上条数**：点下去排队的一起没（spec §5.4），
 *     不说的话那两条是无声消失的
 *  3. 未就绪且未断开 → "启动中…"，禁用
 *  4. 其余 → "发送"
 */
internal fun mainButtonState(
    ready: Boolean,
    busy: Boolean,
    disconnected: Boolean,
    /** 队列里排着几条。默认 0 = 不排队，与从前一字不差。 */
    queued: Int = 0,
): MainButton = when {
    disconnected -> MainButton("重启会话", MainAction.Restart, enabled = true)
    busy && queued > 0 ->
        MainButton("停止（并清掉 $queued 条排队）", MainAction.Interrupt, enabled = true)
    busy -> MainButton("停止", MainAction.Interrupt, enabled = true)
    !ready -> MainButton("启动中…", MainAction.Disabled, enabled = false)
    else -> MainButton("发送", MainAction.Send, enabled = true)
}
```

`MainButtonStateTest.kt` 补两条：

```kotlin
    @Test
    fun `忙且队列非空时，停止的文案说清会清掉几条`() {
        assertEquals(
            MainButton("停止（并清掉 2 条排队）", MainAction.Interrupt, enabled = true),
            mainButtonState(ready = true, busy = true, disconnected = false, queued = 2),
        )
    }

    @Test
    fun `队列为空时文案与从前一字不差`() {
        assertEquals(
            MainButton("停止", MainAction.Interrupt, enabled = true),
            mainButtonState(ready = true, busy = true, disconnected = false, queued = 0),
        )
    }
```

#### 4.2 `ClaudePanel.kt`：字段

紧挨 `private var lastSendWasCommand = false`（约 351 行）之后加：

```kotlin
    /** 排队中的输入（spec §3）。忙时回车进这里，回合结束由 [flushQueue] 发出去。 */
    private val queue = SendQueue()

    /** 排队条本体。存成字段而不是现场 new —— 刷新时要直接够得着它。 */
    private val queueStrip = QueueStrip { removeQueued(it) }

    /**
     * 排队条连同它上下的呼吸空间。
     *
     * 包一层是因为**空队列时整块要收掉**（spec §6：常驻一块空白会让人以为坏了），
     * 而 `BoxLayout` 对 invisible 子项的处理（那两条 strut 会不会照样占位）
     * 不值得赌 —— 收外层是最稳的一种。
     */
    private val queueSlot = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
        add(Box.createVerticalStrut(JBUI.scale(5)))
        add(queueStrip)
        add(Box.createVerticalStrut(JBUI.scale(5)))
    }
```

> `javax.swing.Box` / `BoxLayout` / `JPanel` 这个文件里都在用（`header` 那段），
> import 照抄即可。

#### 4.3 `ClaudePanel.kt`：把插槽挂进 header

`init` 里 `header` 那段（约 424 行）：

```kotlin
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(statusCards)
            // 卡片与输入框之间留一口气。紧贴着看时，四张卡像是输入框的一部分
            // （而且状态卡是"常驻控件"，不是输入区里的一行）
            add(Box.createVerticalStrut(JBUI.scale(7)))
            // 排队条紧贴输入卡：它是"还没发出去的输入"，不是状态
            add(queueSlot)
        }
```

#### 4.4 `ClaudePanel.kt`：忙时入队

`sendCurrentInput()` 里，在 `pushOp` **之前**分岔：

```kotlin
        input.text = ""
        snippetRefs.clear()

        // 忙时入队（spec §5.2）—— 不回退成"什么都不做"：用户敲的这句话本来就该
        // 有个去处。**不推转写区**：它还没发出去，转写区是"跟模型说过什么"的记录
        // （spec §4）。发出去的那一刻才补一条 [RenderItem.UserText]。
        //
        // 未就绪时 busy 恒为 false（mainButtonState 那时给的是「启动中…」，
        // fail / onSidecarDied 都会 setBusy(false)），所以与下面那条路不会同时命中。
        if (busy) {
            queue.enqueue(text, typed)
            refreshQueueStrip()
            return
        }

        pushOp(toOp(RenderItem.UserText(text)))
```

#### 4.5 `ClaudePanel.kt`：四个新方法

放在 `sendNow` 之后：

```kotlin
    /**
     * 回合结束，把队首发出去。**一次只发一条**（spec §5.3）——
     * 它的 result 到了再发下一条：顺序天然正确，也不会两条挤进同一回合。
     */
    private fun flushQueue() {
        val next = queue.peek() ?: return
        queue.remove(next)
        // 先重画再发：sendNow 里的 setBusy(true) 会连带刷按钮，
        // 而按钮的文案里带着队列条数 —— 顺序反了它会拿着旧数字去刷
        refreshQueueStrip()
        pushOp(toOp(RenderItem.UserText(next.text)))
        sendNow(next.text, next.typed)
    }

    /** ✕ 撤回单条。撤掉的那条从没进过转写区，所以它不留痕迹（spec §5.4）。 */
    private fun removeQueued(item: QueuedInput) {
        if (queue.remove(item)) refreshQueueStrip()
    }

    /**
     * 清空队列（停止 / 断线 / 切会话 / 重启）。
     *
     * **这是唯一的清空出口**：漏一条路，上一段会话排着的指令就会被发进新会话里。
     */
    private fun clearQueue() {
        if (queue.drain().isEmpty()) return
        refreshQueueStrip()
    }

    /**
     * 排队条的唯一写入口。顺带刷按钮 —— 它的文案里有"会清掉几条"。
     */
    private fun refreshQueueStrip() {
        val model = queueStripModel(queue)
        queueStrip.setModel(model)
        // 整块收起/展开：空队列时它一点都不占位（spec §6）
        queueSlot.isVisible = model != null
        refreshMainButton()
    }
```

#### 4.6 `ClaudePanel.kt`：flush 的触发点

`onMessage` 的 `result` 分支（约 1813 行）：

```kotlin
                    if (items.any { it is RenderItem.Result }) {
                        setBusy(false)
                        lastSendWasCommand = false

                        // 用量只在 result 事件里给；取不到就保持原样。
                        // ...（原注释一字不动）
                        requestContextUsage()

                        // **排在最后**：这一条的 result 已经把这一回合结掉了，
                        // 用量读的是"刚才那一回合"的数。放前面会让用量请求
                        // 与下一条消息抢同一根管子（2026-09-14 那次事故是反例）
                        flushQueue()
                    }
```

#### 4.7 `ClaudePanel.kt`：清空的三处

**停止**（`onMainButtonClick` 的 `MainAction.Interrupt` 分支，约 521 行）：

```kotlin
            MainAction.Interrupt -> {
                // 排队的一起没：队列里的还没发出去，清掉是本地动作，不必等回执
                // （SDK 那一侧本来也没有回执，spec §2 事实 05）
                clearQueue()
                // sidecar 收到 interrupt 会把挂起的 canUseTool 全部 deny 掉
                // ...（原注释与剩下的四行一字不动）
            }
```

**停会话**（`stopSession()` 里，放在 `ready = false` 之后）：

```kotlin
        // 排队的是**上一个会话**的指令。会话都没了，把它们发出去是灾难 ——
        // 这条路径覆盖了重启 / 新建 / 切会话 / dispose 全部四个入口
        clearQueue()
        ready = false
```

**进程自己死了**（`onSidecarDied()` 里，紧跟 `permissionQueue.cancelAll()` 那一段）：

```kotlin
        permissionQueue.cancelAll()
        closeDecisionDialogs()
        updateStatusBar()
        // 这一次不走 stopSession（那边会碰 client/proc，而它们已经死了），
        // 所以在这里单独清一次
        clearQueue()
```

> `fail()` 里**不加**：它只有两个调用方（`onSidecarDied` 的未就绪分支、
> `startSession` 的启动失败），两边都不可能同时没有队列 ——
> 未就绪时 `busy` 恒为 false，队列根本没有入口。加了是死代码。

#### 4.8 `ClaudePanel.kt`：按钮读队列长度

```kotlin
    private fun refreshMainButton() {
        sendButton.setState(mainButtonState(ready, busy, disconnected, queued = queue.size))
    }
```

**跑**：

```bash
./gradlew test -PskipWeb
```

**Expected**：全绿（新增两条 `MainButtonStateTest`；`ClaudePanel` 起不了单测，
所以它那半边靠 Task 5 的探针图与手工冒烟）。

---

### Task 5: 渲染探针 + 手工冒烟

新建 `src/test/kotlin/com/ccoder/ui/QueueStripRenderProbe.kt`（照 `ComposerRenderProbe` 的模子）：

```kotlin
package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.ccoder.settings.ModelProfile
import com.ccoder.settings.PermissionModeSetting
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：排队条与输入区放在一起，出两张图。
 *
 * 没有断言，也不该有 —— 单测能钉住"超过三行折成还有 N 条"，
 * 钉不住"空队列时那块到底有没有凭空多出一截空白"。而后者正是这次改动的
 * 全部理由（spec §6），不该只靠信念。
 *
 * 产物在 `build/queue-probe-*.png`。改了排队条就跑一下看一眼。
 */
class QueueStripRenderProbe {

    @Test
    fun `空队列——不该多出任何空白`() = render("build/queue-probe-empty.png", emptyList())

    @Test
    fun `两条排队的样子`() =
        render(
            "build/queue-probe-2.png",
            listOf(
                "等一下，先别动 README" to "等一下，先别动 README",
                "跑完把这几个测试补一下。顺便看看 web/ 那边的 ToolsCallBlock 有没有跟着改" to "…",
            ),
        )

    private fun render(path: String, queued: List<Pair<String, String>>) {
        SwingUtilities.invokeAndWait {
            val queue = SendQueue().apply { queued.forEach { (text, typed) -> enqueue(text, typed) } }
            val strip = QueueStrip {}.apply { setModel(queueStripModel(queue)) }

            val cards = StatusCardsRow(onOpenContext = {}, onOpenTodos = {}, onOpenRunning = {}).apply {
                connection.setModel(connectionCardOf("已连接"))
                context.setModel(contextCardOf(ContextUsage(usedTokens = 12300, windowTokens = 200000)))
            }

            val input = ComposerTextArea(COMPOSER_MIN_ROWS, 40).apply {
                lineWrap = true
                styleComposerInput(this)
                text = ""
            }
            val inputScroll = JBScrollPane(input).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }
            val toolbar = buildComposerToolbar(
                ModelLabel {}.apply { setProfile(ModelProfile(name = "Sonnet 4.5")) },
                ModeLabel {}.apply { setMode(PermissionModeSetting.DEFAULT) },
                EffortLabel {}.apply { setEffort(EffortSetting.HIGH) },
                RoundSendButton().apply {
                    setState(
                        mainButtonState(
                            ready = true, busy = queue.isNotEmpty, disconnected = false,
                            queued = queue.size,
                        )
                    )
                },
            )
            val card = buildComposerCard(inputScroll, toolbar)

            // 与 ClaudePanel 的 header 同构：状态卡 → strut → 排队条插槽 → 输入卡
            val header = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(cards)
                add(Box.createVerticalStrut(JBUI.scale(7)))
                add(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        isOpaque = false
                        isVisible = queue.isNotEmpty
                        add(Box.createVerticalStrut(JBUI.scale(5)))
                        add(strip)
                        add(Box.createVerticalStrut(JBUI.scale(5)))
                    }
                )
            }

            val outer = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(6, 8, 8, 8)
                background = UIUtil.getPanelBackground()
                add(header, BorderLayout.NORTH)
                add(card, BorderLayout.CENTER)
            }

            val w = 430
            val h = 300
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
```

**跑**：

```bash
./gradlew test -PskipWeb --tests "com.ccoder.ui.QueueStripRenderProbe"
```

**然后看图**（这一步不能省，单测全绿时也可能不对）：

```
build/queue-probe-empty.png     空队列：状态卡与输入卡之间**不该**多出空白
build/queue-probe-2.png         两条：长的那条截断成一行、✕ 在位、按钮 tooltip 带条数
```

### 收尾：手工冒烟（不在自动化范围内）

装包（**先把 node 铺进 PATH，守护进程停掉重建**）后人眼确认：

1. 发一条会跑一阵的消息（如"跑一遍测试"），回合进行中敲一句话按回车
   —— 输入框清空、排队条出现、**转写区没有多出气泡**。
2. 再敲一条：排队条变成两行。
3. 回合跑完：排队条收掉，两条依次变成转写区里的用户气泡，各自起一回合。
4. 忙时点 ✕：那一条从条上消失，其余照旧。
5. 排两条后点停止：**两条都不发**，按钮 tooltip 事前写着会清掉 2 条。
6. 排一条后杀掉 sidecar（或用重启会话）：排队条消失，没有消息被发进新会话。
7. 排队期间输入框照旧能编辑、能补全、能按 ↑ 取历史。

## 自查记录（占位符扫描）

- 无 TODO / TBD。Task 2 里"删掉 `isBorderPaced`"那一段是**明确的一句提醒**，
  不是占位符：那个换行符的尺寸以 Task 5 的图为准，这是这类 Swing 控件的固有性质
  （`RoundSendButton` 当初也是渲出来才定的 29px）。
- 每一条改动的锚点（`lastSendWasCommand`、`header`、`result` 分支、
  `onMainButtonClick`、`stopSession`、`onSidecarDied`）都在写这份计划时
  对着磁盘上的当前代码核过。

## 执行记录（2026-09-15）

五个任务按计划执行完，Kotlin 882 条全绿（其中本次新增 14 条：
`SendQueueTest` 6、`QueueStripModelTest` 6、`MainButtonStateTest` +2）。
协议 / sidecar / web 三侧**一个字节没动**，`npm test` 与布局探针没有跑的必要。

### 计划本身的两处缺陷（执行时才暴露）

1. **`queueSlot` 那层包装是多余的，而且正是错位的来源。** 计划里写的是
   "BoxLayout 对 invisible 子项的处理不值得赌，包一层最稳" —— 实测**反了**：
   BoxLayout 会跳过不可见的子项（空队列时 header 高 54 = 卡片 47 + strut 7，
   一分不多），而那层包装反而被摆到了 x=113、宽 301。
   删掉包装，排队条**直接挂成 header 的子项**。
2. **`Int.MAX_VALUE` 不能当 max 尺寸用**（计划 §Task 2 里是这么写的）。
   BoxLayout 会把子项的 max 宽度**相加**，Int 上限当场溢出。改用
   `Short.MAX_VALUE.toInt()` —— `JComponent` 自己的默认上限就是它，同一个道理。
   （它不是"位置错乱"的病根，见下，但写错了就是写错了。）

### 渲染出来才发现的错（单测全绿时它们都在）

第一版探头图里，`排队 2` 和两行文字**全都被居中**，整条带子缩在中间：

- **病根是 BoxLayout 的"加权平均对齐点"**。摆不拉伸的子项时，它按子项的
  alignmentX 加权平均定位，而权重是子项的 max 宽度。排队条为了铺满整行把
  max 宽度开到很大，那一个巨大的权重把平均值拖向它自己 —— 于是 414 宽的头里，
  它待在 x=113、宽 301。**把 header 的三个子项一律设成 `alignmentX = 0`
  （LEFT）之后各归各位**：实测 strip x=0、宽 414，单遍布局即可（不需要第二遍）。
- **「排队 N」那行被居中**是同一件事的另一半：JLabel / JPanel 的 alignmentX
  默认是 0.5。`QueueStrip` 里那两处 `alignmentX = LEFT_ALIGNMENT` 的注释
  是这次最值钱的一行。
- 顺带一句：`Box.createVerticalStrut(...).apply { alignmentX = … }` **编译不过**
  （静态类型是 `java.awt.Component`，Kotlin 把它解析成 val）。最后没需要它 ——
  strut 宽 0，在加权平均里权重也是 0。

### 与计划的两处偏离

- **上下留白改由排队条自己的 `Borders.empty(4, 8)` 承担**，不用外面的 strut：
  strut 是独立子项，排队条收起来时它照样占位。
- **可见性收归 `QueueStrip.setModel` 自己管**（`isVisible = model != null`），
  `ClaudePanel.refreshQueueStrip` 不再记第二遍。

### 仍未做的

**手工冒烟**（上面那七条）—— 需要装包。装包记得先把 node 铺进 PATH，
并且**不要**用 `-x buildWebUi`（那样会打进旧前端）。

### 装完之后补的一处：整个功能本来是死代码

第一次装包后用户问「会话没结束是无法发送下一条信息吗」—— 一问就戳中了：
**`ClaudePanel.kt:393` 的 `if (busy) return` 没拆掉。**

它在**按键监听器**里，回车在到达 `sendCurrentInput()` 之前就被它吞了，
而忙时按钮又是「停止」（`MainAction.Interrupt`），鼠标也没有第二条路 ——
于是 §4.4 加的那个入队分岔**没人能走到**，排队条永远不出现。

- **计划漏了它**：Task 4 只写了"`sendCurrentInput()` 里分岔"，没把
  "键盘那道闸门得一起拆"列成一步。spec §5.2 写的是"忙时回车入队"，
  但没点名闸门在哪一行。
- **执行也漏了**：本计划《自查记录》里明明白白写着"忙时回车不再被吞 |
  评审要点（`ClaudePanel` 不可测）"—— 那一条评审没有做。
- **只有真机能发现**：单测钉不住（`ClaudePanel` 起不了），探头图也钉不住
  （它是直接调 `setModel` 画的，从不经过键盘）。

修法是把那道 `return` 换成一段说明为什么不再拦的注释（见 `ClaudePanel.kt`
里 2026-09-15 那一段）—— 留注释而不是留测试，是因为这里没有可测的缝：
它是一句 `if`，抽成纯函数只会多一层包装，挡不住"下次又加一道闸"。
第二次装包已含此修复。

### 用户看过界面之后又改的一处：两条以上收成一行

原话：「排队的信息大于 1 条应该转成一个列表，点击后展开，不然太占用空间了」。
第一版是"最多列 3 行 + 还有 N 条"，即便一条也要占两行（`排队 1` + 内容）。
改成：

- **一条**：`排队 1 · 正文 ✕` —— 一行写完，不再有单独的表头；
- **两条以上**：`排队 N ▸`，点那一行展开成列表（`▾`），每行带自己的 `✕`；
- 开合状态不随刷新重置（开着看时又来一条不该被关掉），队列回到一条或空时归位；
- **删掉行数上限与「还有 N 条」** —— 收折已经解决了空间，主动点开就该看得全。

代码上：`QUEUE_MAX_ROWS` / `overflow` 两个概念一起删了，
新增纯函数 `queueLineText(model)` 管"那一行写什么"（有测试），
开合住在 `QueueStrip` 里（Swing 的开合测不了，靠探头图看）。
探头从 2 张变 4 张：空 / 一条 / 三条收着 / 三条展开。
全量 885 条绿。
