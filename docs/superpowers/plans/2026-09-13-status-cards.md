# 四张状态卡片 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把连接状态、上下文、子任务、子代理从输入卡内部的一行拆成四张独立卡片，放在输入框上方。

**Architecture:** 纯逻辑（`StatusCards.kt` 造模型）与渲染（`StatusCardView.kt` 画一张卡）分开，与现有 `RunStrip.kt` / `RunStripView.kt` 的拆法一致。四张卡常驻，没内容的"收边"（不画边框但保留 insets，所以布局不跳）。旧的合并胶囊（`RunStripView` + `runStripText` + `ellipsize`）整体下线。

**Tech Stack:** Kotlin 2.1 / Swing / IntelliJ Platform 2025.3 / JUnit 5 / Gson

**Spec:** `docs/superpowers/specs/2026-09-13-status-cards-design.md`

## Global Constraints

- 所有文件操作使用完整绝对 Windows 路径（`C:\Users\CY\Desktop\CCoder\...`）。
- 测试命令：`cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '<pattern>'`；全量 `./gradlew test`。
- **尾随 lambda 陷阱**：`Function0`/`Function1` 类型参数**永远放在参数列表最后**。本计划里 `StatusCardView(onOpen)` 的 `onOpen` 是唯一参数，`StatusCardsRow(onOpenTodos, onOpenRunning)` 两个都是 `Function0`，必须保持它俩在最后且不再追加参数。
- **不造分母**：任何指示器都必须有真实分母或真实计数。子代理**没有分母**，只能画 N 个点。
- 文字与颜色一律走平台 API（`UIUtil.getInactiveTextColor()`、`lineColor()`、`focusColor()`、`warningColor()`），不写死 hex。
- 每次提交前跑该任务的测试，必须全绿。

## ⚠️ 计划外的一处新增（需你知悉）

Spec §3.2 只定义了**连接卡**的色调映射。本计划在 `contextCardOf` 里**加了上下文用量阈值**：≥90% → Danger，≥70% → Warn，否则 Idle。

理由：一个永远同色的进度条是装饰，而上下文写满是长会话里**唯一会静默毁掉会话**的事。四行代码。

**你若不想要，Task 1 的 Step 3 里删掉那个 `when`，改成 `tone = Tone.Idle` 即可**，Step 1 对应的断言一并删掉。

---

## 文件结构

| 文件 | 责任 |
|---|---|
| `src/main/kotlin/com/ccoder/ui/StatusCards.kt` | **新建**。纯逻辑：`Tone`、`Indicator`、`StatusCardModel`、四个 `xxxCardOf` |
| `src/main/kotlin/com/ccoder/ui/StatusCardView.kt` | **新建**。画一张卡；悬停/展开/点击；`IndicatorView` 自画指示器 |
| `src/main/kotlin/com/ccoder/ui/StatusCardsRow.kt` | **新建**。四张卡一行 |
| `src/main/kotlin/com/ccoder/ui/ContextUsage.kt` | **改**。拆出 `contextPercentOf` / `contextRatioText`，删 `formatContextUsage` |
| `src/main/kotlin/com/ccoder/ui/RunDetail.kt` | **改名自 `RunStripView.kt`**。只留 `RoundedLineBorder`、`buildTodoDetail`、`buildRunningDetail`、`formatDuration` |
| `src/main/kotlin/com/ccoder/ui/ComposerStrip.kt` | **改**。删 `RunStripView` 类、`runStripText`、`ellipsize`、`EXPAND_CARET`；保留浮层定位四函数 |
| `src/main/kotlin/com/ccoder/ui/Composer.kt` | **改**。`buildComposerCard` 三参 → 两参 |
| `src/main/kotlin/com/ccoder/ui/ClaudePanel.kt` | **改**。接线 |
| `src/main/kotlin/com/ccoder/ui/RunStrip.kt` | **删除** |

---

### Task 1: 卡片模型与四个构造函数

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\StatusCards.kt`
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ContextUsage.kt:42-53`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\StatusCardsTest.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\ContextUsageTest.kt:93-116`

**Interfaces:**
- Consumes: `ContextUsage`、`formatTokenCount`（`ContextUsage.kt`）、`TaskList`（`TaskList.kt`）、`RunningTask`（`RunStatusTracker.kt`）
- Produces:
  - `enum class Tone { Ok, Warn, Danger, Idle }`
  - `sealed interface Indicator { None / Meter(fraction: Double) / Segments(done: Int, total: Int) / Dots(count: Int) }`
  - `data class StatusCardModel(label: String, value: String, tone: Tone = Tone.Idle, sub: String? = null, indicator: Indicator = Indicator.None, quiet: Boolean = false)`
  - `fun connectionCardOf(status: String): StatusCardModel`
  - `fun contextCardOf(usage: ContextUsage?): StatusCardModel`
  - `fun todoCardOf(todos: TaskList?): StatusCardModel`
  - `fun runningCardOf(running: List<RunningTask>): StatusCardModel`
  - `fun contextPercentOf(usage: ContextUsage): Int?`
  - `fun contextRatioText(usage: ContextUsage): String`
  - `const val CARD_IDLE_TEXT = "空闲"`

- [ ] **Step 1: 写失败的测试**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\StatusCardsTest.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 四张卡各自的内容与形状。全是纯函数，不碰 Swing。 */
class StatusCardsTest {

    // ---- 连接：八种文字 → 四种色调 ----

    @Test
    fun `连接状态八种文字各自的色调`() {
        assertEquals(Tone.Ok, connectionCardOf("已连接").tone)
        assertEquals(Tone.Warn, connectionCardOf("正在启动…").tone)
        assertEquals(Tone.Warn, connectionCardOf("正在载入历史…").tone)
        assertEquals(Tone.Idle, connectionCardOf("未连接").tone)
        assertEquals(Tone.Idle, connectionCardOf("会话已结束").tone)
        assertEquals(Tone.Danger, connectionCardOf("启动失败").tone)
        assertEquals(Tone.Danger, connectionCardOf("会话已断开").tone)
        assertEquals(Tone.Danger, connectionCardOf("恢复失败").tone)
    }

    @Test
    fun `没见过的连接文字不崩，退成 Idle`() {
        // 将来 statusLabel 多写一种文字，不该让整条状态行炸掉
        assertEquals(Tone.Idle, connectionCardOf("量子纠缠中").tone)
    }

    @Test
    fun `连接卡永远不空闲`() {
        // "未连接"是一种真实状态，不是"没数据"。它该有边框
        assertFalse(connectionCardOf("未连接").quiet)
        assertFalse(connectionCardOf("已连接").quiet)
    }

    // ---- 上下文 ----

    @Test
    fun `还没收到用量时收边`() {
        val card = contextCardOf(null)
        assertTrue(card.quiet)
        assertEquals(CARD_IDLE_TEXT, card.value)
        assertEquals(Indicator.None, card.indicator)
    }

    @Test
    fun `有用量时给百分比与绝对数`() {
        val card = contextCardOf(ContextUsage(inputTokens = 12345, contextWindow = 200000))

        assertEquals("6%", card.value)
        assertEquals("12.3k / 200k", card.sub, "绝对数是现存信息，不能在拆卡时弄丢")
        assertEquals(Indicator.Meter(0.06), card.indicator)
        assertFalse(card.quiet)
    }

    @Test
    fun `窗口未知时不显示百分比，也不做除零`() {
        val card = contextCardOf(ContextUsage(inputTokens = 500, contextWindow = 0))

        assertEquals(500L.toString(), card.value, "没有窗口就只能给已用量本身")
        assertEquals("500", card.sub)
        assertEquals(Indicator.None, card.indicator)
    }

    @Test
    fun `上下文将满时转警示色`() {
        // 计划外新增，见文档顶部说明
        assertEquals(Tone.Idle, contextCardOf(ContextUsage(1000, 200000)).tone)
        assertEquals(Tone.Warn, contextCardOf(ContextUsage(150000, 200000)).tone)
        assertEquals(Tone.Danger, contextCardOf(ContextUsage(190000, 200000)).tone)
    }

    // ---- 子任务 ----

    @Test
    fun `没有清单时收边，且不画格子`() {
        val card = todoCardOf(null)
        assertTrue(card.quiet)
        assertEquals(CARD_IDLE_TEXT, card.value)
        // 关键：不是 Segments(0, 0)。空清单画七个空格子会被读成 "0/7"
        assertEquals(Indicator.None, card.indicator)
    }

    @Test
    fun `零条的清单也收边，不显示 0 斜 0`() {
        val card = todoCardOf(TaskList(emptyList()))
        assertTrue(card.quiet)
        assertEquals(Indicator.None, card.indicator)
    }

    @Test
    fun `有清单时给进度与分段，分母是清单条数`() {
        val todos = TaskList(
            listOf(
                TodoItem("甲", TodoState.Completed),
                TodoItem("乙", TodoState.InProgress),
                TodoItem("丙", TodoState.Pending),
            )
        )
        val card = todoCardOf(todos)

        assertEquals("1/3", card.value)
        assertEquals(Indicator.Segments(done = 1, total = 3), card.indicator)
        assertFalse(card.quiet)
    }

    // ---- 子代理 ----

    @Test
    fun `没有在跑的任务时收边`() {
        val card = runningCardOf(emptyList())
        assertTrue(card.quiet)
        assertEquals(CARD_IDLE_TEXT, card.value)
        assertEquals(Indicator.None, card.indicator)
    }

    @Test
    fun `在跑几个就画几个点，没有分母`() {
        // 这条是本设计最要紧的一处：子代理**没有总数**。
        // 画成"共 4 格亮 2 格"会被读成 2/4，那是凭空造出来的信息
        val card = runningCardOf(listOf(task("t1"), task("t2")))

        assertEquals("2", card.value)
        assertEquals(Indicator.Dots(2), card.indicator)
        assertFalse(card.quiet)
    }

    @Test
    fun `点太多时封顶，但数字仍然是权威`() {
        val card = runningCardOf((1..20).map { task("t$it") })

        assertEquals("20", card.value, "数字必须是真实数量")
        assertEquals(Indicator.Dots(MAX_DOTS), card.indicator, "点只是辅助，封顶")
    }

    private fun task(id: String) =
        RunningTask(id = id, kind = null, label = null, detail = null, tokens = 0, durationMs = 0)
}
```

替换 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\ContextUsageTest.kt` 第 93–116 行（三个 `格式`/`窗口未知`/`四舍五入` 用例）为：

```kotlin
    @Test
    fun `比例与绝对数分开给，卡片各取所需`() {
        val usage = ContextUsage(inputTokens = 12345, contextWindow = 200000)
        assertEquals(6, contextPercentOf(usage))
        assertEquals("12.3k / 200k", contextRatioText(usage))
    }

    @Test
    fun `窗口未知时不给比例，绝对数只给已用量`() {
        val usage = ContextUsage(inputTokens = 500, contextWindow = 0)
        assertNull(contextPercentOf(usage), "没有窗口就没有比例，也不许除零")
        assertEquals("500", contextRatioText(usage))
    }

    @Test
    fun `占用比例四舍五入`() {
        assertEquals(1, contextPercentOf(ContextUsage(inputTokens = 1000, contextWindow = 200000)))
    }
```

同时在该文件顶部补 `import org.junit.jupiter.api.Assertions.assertNull`。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.StatusCardsTest' 2>&1 | tail -30
```

预期：编译失败，`unresolved reference: connectionCardOf`（`StatusCards.kt` 还不存在）。

- [ ] **Step 3: 写实现**

创建 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\StatusCards.kt`：

```kotlin
package com.ccoder.ui

/**
 * 卡片的色调。**只描述语义上的"要不要紧"**，具体颜色由视图决定：
 *
 * - 状态点：Ok 绿 / Warn 琥珀 / Danger 红 / Idle 次要色
 * - 指示器（条、分段、点）：Warn 琥珀 / Danger 红 / **其余一律强调色**
 *
 * 分成两层是因为上下文卡的进度条既不该是灰的（那是装饰），
 * 也不该是绿的（"绿"会被读成"正常"，可它平时就是正常，说它绿等于没说）。
 */
internal enum class Tone { Ok, Warn, Danger, Idle }

/**
 * 卡片底部那条微指示。
 *
 * **每一种都必须有真实分母或真实计数。** 画一个固定长度的格子条出来，
 * 会被读成"M 分之 N"，而那个 M 如果不存在，就是凭空造的信息。
 * 子代理正是这种情况 —— 它只有计数，没有总数，所以只能画 N 个点。
 */
internal sealed interface Indicator {
    object None : Indicator

    /** 比例条。fraction 恒在 0..1。 */
    data class Meter(val fraction: Double) : Indicator

    /** 分段。分母真实 —— 就是任务清单的条数。 */
    data class Segments(val done: Int, val total: Int) : Indicator

    /** N 个点，N 就是在跑的任务数。**没有分母。** */
    data class Dots(val count: Int) : Indicator
}

/**
 * 一张卡要显示的全部内容。
 *
 * @param quiet true = 这格没内容，**收边**：不画边框、值降为次要色。
 *   与 [Tone.Idle] 分开而不是合并 —— `Tone.Idle` 也在连接卡上用
 *   （"未连接"是真实状态，该有边框），而 quiet 只表示"没数据"。
 */
internal data class StatusCardModel(
    val label: String,
    val value: String,
    val tone: Tone = Tone.Idle,
    val sub: String? = null,
    val indicator: Indicator = Indicator.None,
    val quiet: Boolean = false,
)

/** 空格子里写什么。写"空闲"而不是"—"—— 破折号读起来像坏了。 */
internal const val CARD_IDLE_TEXT = "空闲"

/** 点数封顶。数字才是权威，点只是让"2"变得看得见。 */
internal const val MAX_DOTS = 6

private fun quietCard(label: String) =
    StatusCardModel(label = label, value = CARD_IDLE_TEXT, quiet = true)

// ---- 连接 ----

/**
 * 八种文字映射到四种色调。
 *
 * `else` 落到 Idle 而不是抛错：将来 [ClaudePanel] 多写一种状态文字，
 * 该退化成"看不出要紧"，而不是让整条状态行崩掉。
 */
internal fun connectionTone(status: String): Tone = when (status) {
    "已连接" -> Tone.Ok
    "正在启动…", "正在载入历史…" -> Tone.Warn
    "启动失败", "会话已断开", "恢复失败" -> Tone.Danger
    else -> Tone.Idle
}

/** 连接卡**永远不空闲** —— "未连接"是一种状态，不是"没数据"。 */
internal fun connectionCardOf(status: String) = StatusCardModel(
    label = "连接",
    value = status,
    tone = connectionTone(status),
)

// ---- 上下文 ----

/**
 * 上下文卡。
 *
 * 值给百分比（"还剩多少"一眼可见），副值给绝对数（"12.3k / 200k"）——
 * 绝对数在拆卡前就显示着，不能因为格子变窄就弄丢。
 *
 * 阈值 70/90 是计划外新增：一个永远同色的进度条是装饰，而上下文写满
 * 是长会话里唯一会**静默**毁掉会话的事。
 */
internal fun contextCardOf(usage: ContextUsage?): StatusCardModel {
    if (usage == null) return quietCard("上下文")

    val percent = contextPercentOf(usage)
    return StatusCardModel(
        label = "上下文",
        value = if (percent != null) "$percent%" else formatTokenCount(usage.inputTokens),
        tone = when {
            percent == null -> Tone.Idle
            percent >= 90 -> Tone.Danger
            percent >= 70 -> Tone.Warn
            else -> Tone.Idle
        },
        sub = contextRatioText(usage),
        indicator = if (percent != null) Indicator.Meter(percent / 100.0) else Indicator.None,
    )
}

// ---- 子任务 ----

/**
 * 子任务卡。
 *
 * `total == 0` 也收边：一条清单都没拆出来时画"0/0"或七个空格子，
 * 都是在说并不存在的事。
 */
internal fun todoCardOf(todos: TaskList?): StatusCardModel {
    if (todos == null || todos.total == 0) return quietCard("子任务")

    return StatusCardModel(
        label = "子任务",
        value = "${todos.completed}/${todos.total}",
        indicator = Indicator.Segments(done = todos.completed, total = todos.total),
    )
}

// ---- 子代理 ----

/**
 * 子代理卡。
 *
 * **不画进度条。** 子代理没有分母 —— 在跑几个就是几个。见 [Indicator]。
 */
internal fun runningCardOf(running: List<RunningTask>): StatusCardModel {
    if (running.isEmpty()) return quietCard("子代理")

    return StatusCardModel(
        label = "子代理",
        value = running.size.toString(),
        indicator = Indicator.Dots(minOf(running.size, MAX_DOTS)),
    )
}
```

在 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ContextUsage.kt` 里，把 `formatContextUsage`（第 42–53 行）整个换掉：

```kotlin
/**
 * 上下文占用百分比，四舍五入到整数。窗口未知时给 null —— **不做除法**。
 *
 * 与 [contextRatioText] 分开而不是返回一个拼好的字符串：卡片要把百分比
 * 放大、把绝对数放小，两者得能分开取。
 */
internal fun contextPercentOf(usage: ContextUsage): Int? {
    if (usage.contextWindow <= 0) return null
    return Math.round(usage.inputTokens * 100.0 / usage.contextWindow).toInt()
}

/** "12.3k / 200k"。窗口未知时只给已用量，仍然不做除法。 */
internal fun contextRatioText(usage: ContextUsage): String {
    val used = formatTokenCount(usage.inputTokens)
    if (usage.contextWindow <= 0) return used
    return "$used / ${formatTokenCount(usage.contextWindow)}"
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.StatusCardsTest' --tests 'com.ccoder.ui.ContextUsageTest' 2>&1 | tail -20
```

预期：全绿。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/StatusCards.kt src/main/kotlin/com/ccoder/ui/ContextUsage.kt src/test/kotlin/com/ccoder/ui/StatusCardsTest.kt src/test/kotlin/com/ccoder/ui/ContextUsageTest.kt && git commit -m "feat(ui): 状态卡片的模型与四个构造函数"
```

---

### Task 2: 一张卡

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\StatusCardView.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\StatusCardViewTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `StatusCardModel` / `Tone` / `Indicator`；`RoundedLineBorder`（`RunStripView.kt:35`）；`lineColor()` / `focusColor()` / `warningColor()`（`Composer.kt`）
- Produces:
  - `class StatusCardView(onOpen: (() -> Unit)? = null)`，含 `fun setModel(next: StatusCardModel)`、`fun setOpen(value: Boolean)`、`fun isOpen(): Boolean`
  - 卡上文字可通过 `UIUtil` 遍历到的 `JLabel` 读出（测试用）

- [ ] **Step 1: 写失败的测试**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\StatusCardViewTest.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel

/** 一张卡的外观与交互。 */
class StatusCardViewTest {

    private fun labelsIn(root: Component): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Component) {
            if (c is JLabel) out += c.text
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return out
    }

    /**
     * 描边色。卡片的 border 是 **CompoundBorder**（圆角描边 + 内边距），
     * 所以要先剥一层 —— 与 `ComposerRulesTest` 里读输入卡描边同一套写法。
     */
    private fun borderColorOf(card: StatusCardView) =
        ((card.border as javax.swing.border.CompoundBorder).outsideBorder as RoundedLineBorder).color()

    private fun hover(c: Component, entered: Boolean) {
        c.dispatchEvent(
            MouseEvent(
                c,
                if (entered) MouseEvent.MOUSE_ENTERED else MouseEvent.MOUSE_EXITED,
                System.currentTimeMillis(), 0, 5, 5, 0, false,
            )
        )
    }

    private val busy = StatusCardModel(
        label = "子任务",
        value = "3/7",
        indicator = Indicator.Segments(done = 3, total = 7),
    )

    // ---- 内容 ----

    @Test
    fun `标签、值、副值都画出来`() {
        val card = StatusCardView()
        card.setModel(busy.copy(sub = "12.3k / 200k"))

        val texts = labelsIn(card)
        assertTrue("子任务" in texts, "少了标签：$texts")
        assertTrue("3/7" in texts, "少了值：$texts")
        assertTrue("12.3k / 200k" in texts, "少了副值：$texts")
    }

    @Test
    fun `没有副值时不留空行`() {
        val card = StatusCardView()
        card.setModel(busy)

        assertTrue(
            card.components.none { it is JLabel && it.text.isEmpty() && it.isVisible },
            "空的副值标签该整个不可见，否则会撑出一行空气",
        )
    }

    // ---- 收边 ----

    @Test
    fun `收边的卡不画边框`() {
        // quiet 不是"隐藏"，是"退到背景里"。用全透明描边而不是换 border 对象 ——
        // 换了对象 insets 会变，四张卡的宽度就会跟着跳
        val card = StatusCardView()
        card.setModel(StatusCardModel(label = "子任务", value = CARD_IDLE_TEXT, quiet = true))

        assertEquals(0, borderColorOf(card).alpha, "收边的卡仍然画了边框")
    }

    @Test
    fun `收边与否不改变 insets —— 否则四张卡会左右跳`() {
        val card = StatusCardView()
        card.setModel(busy)
        val busyInsets = card.border.getBorderInsets(card)

        card.setModel(StatusCardModel(label = "子任务", value = CARD_IDLE_TEXT, quiet = true))

        assertEquals(busyInsets, card.border.getBorderInsets(card), "收边改了 insets，布局会跳")
    }

    @Test
    fun `平常的卡画得出边框`() {
        val card = StatusCardView()
        card.setModel(busy)

        assertTrue(borderColorOf(card).alpha > 0, "有内容的卡该看得见边框")
    }

    // ---- 交互 ----

    @Test
    fun `可点的卡悬停时提亮`() {
        val card = StatusCardView(onOpen = {})
        card.setModel(busy)
        val calm = borderColorOf(card)

        hover(card, entered = true)

        assertNotEquals(calm, borderColorOf(card), "可点的卡悬停后该提亮")
    }

    @Test
    fun `不可点的卡悬停不动声色`() {
        // 给了悬停反馈却点不动，是在骗人
        val card = StatusCardView(onOpen = null)
        card.setModel(busy)
        val calm = borderColorOf(card)

        hover(card, entered = true)

        assertEquals(calm, borderColorOf(card), "点不动的卡不该有悬停反馈")
    }

    @Test
    fun `点可点的卡触发回调`() {
        var opened = 0
        val card = StatusCardView(onOpen = { opened++ })
        card.setModel(busy)

        card.dispatchEvent(
            MouseEvent(card, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)
        )

        assertEquals(1, opened)
    }

    @Test
    fun `展开态即使移开鼠标也保持提亮`() {
        val card = StatusCardView(onOpen = {})
        card.setModel(busy)
        card.setOpen(true)

        hover(card, entered = true)
        hover(card, entered = false)

        assertTrue(card.isOpen())
        assertNotEquals(lineColor(), borderColorOf(card), "展开态该保持提亮")
    }

    @Test
    fun `不可点的卡点下去没有反应`() {
        val card = StatusCardView(onOpen = null)
        card.setModel(busy)

        // 不抛异常就是通过 —— 这条守的是"别为了好写而给个空 lambda"
        card.dispatchEvent(
            MouseEvent(card, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)
        )
    }

    // ---- 指示器 ----

    @Test
    fun `指示器是自画的组件，且随模型切换类型后仍然只有一个`() {
        val card = StatusCardView()
        card.setModel(busy)
        assertEquals(1, indicatorCount(card))

        card.setModel(busy.copy(indicator = Indicator.Dots(2)))
        assertEquals(1, indicatorCount(card), "换了指示器类型后多出来一个")
    }

    @Test
    fun `没有指示器时那一块不可见`() {
        val card = StatusCardView()
        card.setModel(StatusCardModel(label = "连接", value = "已连接"))

        // 直接找 IndicatorView。写成 filterIsInstance<JPanel> 会永远为真 ——
        // IndicatorView 继承 JComponent 而不是 JPanel，那种写法是条假测试
        val indicator = card.components.filterIsInstance<IndicatorView>().single()
        assertFalse(indicator.isVisible, "没有指示器时不该占着一块地方")
    }

    private fun indicatorCount(c: Container): Int {
        var n = 0
        for (child in c.components) {
            if (child is IndicatorView) n++
            if (child is Container) n += indicatorCount(child)
        }
        return n
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.StatusCardViewTest' 2>&1 | tail -30
```

预期：编译失败，`unresolved reference: StatusCardView`。

- [ ] **Step 3: 写实现**

创建 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\StatusCardView.kt`：

```kotlin
package com.ccoder.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** 描边圆角半径。 */
private const val CARD_ARC = 9

/**
 * 一张状态卡。
 *
 * ## 收边为什么用"透明描边"而不是"不设 border"
 *
 * 四张卡等宽并排。若收边时换掉 border 对象，`getBorderInsets` 会从 1 变成 0，
 * 这一格就宽出去 2px —— 旁边三张跟着挪。用全透明色画同一条边，insets 恒定，
 * 布局一动不动。
 *
 * ## 为什么用 JPanel 而不是像旧的胶囊那样自画
 *
 * 旧的 `RunStripView` 自画是因为它的文字要按可用宽度截断。卡里放的是
 * `3/7`、`2` 这种定宽短值，不需要截断，用 JLabel 反而能免费拿到平台的
 * 字体、高 DPI 与主题色。
 *
 * 描边复用 [RoundedLineBorder]：它本来就收一个 `colorProvider` 函数，
 * 换色只需 repaint，不必重建 border。
 */
internal class StatusCardView(
    private val onOpen: (() -> Unit)? = null,
) : JPanel() {

    private var model: StatusCardModel? = null
    private var open = false
    private var hovered = false

    private val labelView = JBLabel()
    private val valueView = JBLabel()
    private val subView = JBLabel()
    private val indicatorView = IndicatorView()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = javax.swing.BorderFactory.createCompoundBorder(
            RoundedLineBorder(colorProvider = ::strokeColor, arc = JBUI.scale(CARD_ARC)),
            JBUI.Borders.empty(6, 9, 7, 9),
        )
        labelView.font = labelView.font.deriveFont(labelView.font.size2D - 2f)
        labelView.foreground = UIUtil.getInactiveTextColor()
        valueView.font = valueView.font.deriveFont(valueView.font.size2D + 2f)
        subView.font = subView.font.deriveFont(subView.font.size2D - 2f)
        subView.foreground = UIUtil.getInactiveTextColor()

        add(labelView)
        add(valueView)
        add(subView)
        add(indicatorView)

        if (onOpen != null) {
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onOpen.invoke()
                    override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
                }
            )
        }
    }

    fun setModel(next: StatusCardModel) {
        model = next
        labelView.text = next.label
        valueView.text = next.value
        valueView.foreground =
            if (next.quiet) UIUtil.getInactiveTextColor() else UIUtil.getLabelForeground()

        subView.text = next.sub.orEmpty()
        subView.isVisible = !next.sub.isNullOrEmpty()

        indicatorView.set(next.indicator, next.tone)
        indicatorView.isVisible = next.indicator != Indicator.None

        revalidate()
        repaint()
    }

    fun setOpen(value: Boolean) {
        if (open == value) return
        open = value
        repaint()
    }

    fun isOpen(): Boolean = open

    /**
     * 描边色。**收边时给全透明** —— 不是去掉 border，那样 insets 会变。
     */
    private fun strokeColor(): Color = when {
        model?.quiet == true -> Color(0, 0, 0, 0)
        open || hovered -> focusColor()
        else -> lineColor()
    }
}

/**
 * 卡片底部那条微指示。
 *
 * 自画而不是拼组件：它有三种形状（条 / 分段 / 点），每种都只有几行绘制代码，
 * 拼出来反而要维护三套子组件。
 */
internal class IndicatorView : JComponent() {

    private var indicator: Indicator = Indicator.None
    private var tone: Tone = Tone.Idle

    init {
        isOpaque = false
        // 显式设字体。不设的话 JBUI.scale 之外的高度计算会拿到 null 字体，
        // 旧 RunStripView 上踩过同一个坑
        font = UIUtil.getLabelFont()
    }

    fun set(next: Indicator, nextTone: Tone) {
        indicator = next
        tone = nextTone
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension = when (indicator) {
        Indicator.None -> Dimension(0, 0)
        is Indicator.Meter, is Indicator.Segments -> Dimension(JBUI.scale(60), JBUI.scale(4))
        is Indicator.Dots -> Dimension(JBUI.scale(60), JBUI.scale(6))
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            when (val i = indicator) {
                Indicator.None -> Unit
                is Indicator.Meter -> paintMeter(g2, i)
                is Indicator.Segments -> paintSegments(g2, i)
                is Indicator.Dots -> paintDots(g2, i)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun paintMeter(g2: Graphics2D, meter: Indicator.Meter) {
        val h = height
        val arc = h
        g2.color = trackColor()
        g2.fillRoundRect(0, 0, width, h, arc, arc)

        val filled = (width * meter.fraction).toInt().coerceIn(0, width)
        if (filled <= 0) return
        g2.color = alertColor() ?: focusColor()
        g2.fillRoundRect(0, 0, filled, h, arc, arc)
    }

    private fun paintSegments(g2: Graphics2D, segments: Indicator.Segments) {
        if (segments.total <= 0) return
        val gap = JBUI.scale(2)
        // 均匀分：总宽减去所有间隙，再等分
        val segW = ((width - gap * (segments.total - 1)).toDouble() / segments.total).toInt()
        if (segW <= 0) return

        var x = 0
        for (index in 0 until segments.total) {
            g2.color = when {
                index < segments.done -> okColor()
                // 进行中的那一个：done 之后的第一个，且确实还没做完
                index == segments.done -> focusColor()
                else -> trackColor()
            }
            g2.fillRoundRect(x, 0, segW, height, JBUI.scale(1), JBUI.scale(1))
            x += segW + gap
        }
    }

    private fun paintDots(g2: Graphics2D, dots: Indicator.Dots) {
        val size = JBUI.scale(5)
        val gap = JBUI.scale(3)
        val y = (height - size) / 2
        val color = alertColor() ?: focusColor()
        for (index in 0 until dots.count) {
            val x = index * (size + gap)
            if (x + size > width) break
            g2.color = color
            g2.fillOval(x, y, size, size)
        }
    }

    /** 只有警示色调才覆盖指示器颜色。其余一律强调色 —— 见 [Tone] 的说明。 */
    private fun alertColor(): Color? = when (tone) {
        Tone.Warn -> warningColor()
        Tone.Danger -> dangerColor()
        else -> null
    }

    private fun trackColor(): Color = JBColor.namedColor(
        "Component.borderColor",
        JBColor(Color(0x33, 0x36, 0x3B), Color(0xE0, 0xE2, 0xE7)),
    )

    private fun okColor(): Color = JBColor.namedColor(
        "Component.successColor",
        JBColor(Color(0x3D, 0x8B, 0x43), Color(0x5F, 0xAD, 0x65)),
    )
}
```

在 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\Composer.kt` 末尾补一个导出：

```kotlin
/** 危险状态色。卡片指示器与权限卡共用。 */
internal fun dangerColor(): Color = JBColor.namedColor(
    "Component.errorFocusColor",
    JBColor(Color(0xC0, 0x39, 0x2B), Color(0xDB, 0x5C, 0x5C)),
)
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.StatusCardViewTest' 2>&1 | tail -25
```

预期：全绿。若 `border` 那两行赋值报"val cannot be reassigned"，改成一次构造：

```kotlin
        border = javax.swing.BorderFactory.createCompoundBorder(
            RoundedLineBorder(colorProvider = ::strokeColor, arc = JBUI.scale(CARD_ARC)),
            JBUI.Borders.empty(6, 9, 7, 9),
        )
```

（`JComponent.setBorder` 是普通方法不是 `val`，正常可直接赋两次；这里给出退路。）

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/StatusCardView.kt src/main/kotlin/com/ccoder/ui/Composer.kt src/test/kotlin/com/ccoder/ui/StatusCardViewTest.kt && git commit -m "feat(ui): 状态卡片组件与指示器"
```

---

### Task 3: 四张卡一行

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\StatusCardsRow.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\StatusCardsRowTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `StatusCardView`；Task 1 的 `StatusCardModel`
- Produces: `class StatusCardsRow(onOpenTodos: () -> Unit, onOpenRunning: () -> Unit) : JPanel`，公开四个 `internal val`：`connection`、`context`、`todos`、`running`（都是 `StatusCardView`）

- [ ] **Step 1: 写失败的测试**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\StatusCardsRowTest.kt`：

```kotlin
package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container

/** 四张卡怎么排。 */
class StatusCardsRowTest {

    private val quietTodos = StatusCardModel(label = "子任务", value = CARD_IDLE_TEXT, quiet = true)

    private fun row() = StatusCardsRow(onOpenTodos = {}, onOpenRunning = {})

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }

    @Test
    fun `四张卡始终都在，包括没内容的时候`() {
        // 这条钉的是 spec §4 那次推翻：旧原则是"取不到就不显示"，
        // 而这里必须常驻 —— 卡一会儿出现一会儿消失，输入框就会上下跳
        val r = row()
        r.todos.setModel(quietTodos)
        r.running.setModel(StatusCardModel(label = "子代理", value = CARD_IDLE_TEXT, quiet = true))

        assertEquals(4, r.componentCount, "四张卡必须常驻")
        assertTrue(r.components.all { it.isVisible }, "收边不等于隐藏")
    }

    @Test
    fun `只有子任务与子代理可点`() {
        val r = row()

        assertTrue(r.todos.mouseListeners.isNotEmpty(), "子任务卡该可点")
        assertTrue(r.running.mouseListeners.isNotEmpty(), "子代理卡该可点")
        assertTrue(r.connection.mouseListeners.isEmpty(), "连接卡没有更多可看的，不该可点")
        assertTrue(r.context.mouseListeners.isEmpty(), "上下文卡没有更多可看的，不该可点")
    }

    @Test
    fun `四张卡等宽，总量不超出可用宽度`() {
        val r = row()
        r.setSize(420, 60)
        layoutAll(r)

        val widths = r.components.map { it.width }
        assertEquals(1, widths.distinct().size, "四张卡宽度不一致：$widths")
        assertTrue(widths.sum() <= 420, "总宽超出面板：${widths.sum()}")
        assertTrue(widths.all { it > 80 }, "每张卡被压得太窄，内容会裁掉：$widths")
    }

    @Test
    fun `状态行有自己的最小高度，不会被压没`() {
        // 分隔条设了 honorComponentsMinimumSize，这个值决定底部最少占多高。
        // 计划里那个"约 58px"是估的，这条把它变成量出来的数
        val r = row()
        val min = r.minimumSize.height

        assertTrue(min > 0, "最小高度为 0 会被分隔条压成一条线")
        // 太低说明布局没算上内边距，太高会无谓地吃转写区
        assertTrue(min in 40..90, "状态行最小高度是 ${min}px，超出预期区间 40..90")
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.StatusCardsRowTest' 2>&1 | tail -30
```

预期：编译失败，`unresolved reference: StatusCardsRow`。

- [ ] **Step 3: 写实现**

创建 `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\StatusCardsRow.kt`：

```kotlin
package com.ccoder.ui

import com.intellij.util.ui.JBUI
import java.awt.GridLayout
import javax.swing.JPanel

/** 四张卡之间的横向间隙。 */
private const val CARD_GAP = 5

/**
 * 一排四张状态卡。
 *
 * ## 为什么用 GridLayout 而不是 BoxLayout
 *
 * 四张卡必须**等宽**。BoxLayout 按首选宽度分配，而"已连接"比"2"宽得多，
 * 结果就是连接卡挤掉子代理卡。GridLayout 强制等分，宽度与内容无关。
 *
 * ## 为什么公开四个子视图而不是收一个 `setModels(...)`
 *
 * 四份数据来自四个互不相干的地方（`statusLabel` 的文字、`contextUsageOf`、
 * `todos`、`running`），刷新时机也各不相同 —— 收成一个四参函数会逼着
 * 每次刷新都重算另外三份。
 *
 * ## 参数顺序
 *
 * 两个 `() -> Unit` 都在最后。**将来加参数一律加到它们前面** ——
 * 尾随 lambda 会静默绑到最后一个参数上，这个坑本项目里踩过两次。
 */
internal class StatusCardsRow(
    onOpenTodos: () -> Unit,
    onOpenRunning: () -> Unit,
) : JPanel(GridLayout(1, 4, JBUI.scale(CARD_GAP), 0)) {

    internal val connection = StatusCardView()
    internal val context = StatusCardView()
    internal val todos = StatusCardView(onOpen = onOpenTodos)
    internal val running = StatusCardView(onOpen = onOpenRunning)

    init {
        isOpaque = false
        add(connection)
        add(context)
        add(todos)
        add(running)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.StatusCardsRowTest' 2>&1 | tail -25
```

预期：全绿。

⚠️ 若 `四张卡等宽` 那条失败，说明 `StatusCardsRow` 的 `setSize` 之后 GridLayout 没生效 —— 检查是否漏了 `layoutAll` 里的递归（`doLayout()` 不递归，这是本项目里踩过的坑）。

⚠️ 若 `最小高度` 那条报出的值超出 40..90，**先看实测值再改区间**，不要反过来把区间放宽去迁就实现。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/StatusCardsRow.kt src/test/kotlin/com/ccoder/ui/StatusCardsRowTest.kt && git commit -m "feat(ui): 四张状态卡排成一行"
```

---

### Task 4: 把详情浮层拆成两段

**Files:**
- Rename+Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\RunStripView.kt` → `RunDetail.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\RunStripViewTest.kt` → 保留其"详情"部分

**Interfaces:**
- Consumes: `RunStatusTracker`、`RoundedLineBorder` 自己
- Produces:
  - `fun buildTodoDetail(todos: TaskList): JComponent`
  - `fun buildRunningDetail(running: List<RunningTask>): JComponent`
  - `fun formatDuration(ms: Long): String`
  - `class RoundedLineBorder(colorProvider: () -> Color, arc: Int)`
  - ~~`buildRunDetail`~~ 删除
  - ~~`buildContextRow` / `buildUsageLabel`~~ 删除

- [ ] **Step 1: 先改测试（新契约）**

在 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\RunStripViewTest.kt` 里：

1. 删掉 `上下文行里状态与用量在左、条在右` 这个用例（第 44–63 行）以及它用到的 `BorderLayout` / `assertSame` / `JPanel` import。
2. 把 `详情分两段，各自带上计数`、`只有运行中时不出现清单段`、`只有清单时不出现运行段`、`两段都空时给出实话，而不是一个空框` 四个用例替换为：

```kotlin
    @Test
    fun `清单段带上进度计数`() {
        val labels = labelsIn(
            buildTodoDetail(
                RunStatusTracker().apply { consume(ev(todosLabel("甲" to "completed", "乙" to "in_progress"))) }
                    .todos!!
            )
        )

        assertTrue("任务清单" in labels, "要有段标题")
        assertTrue("1/2" in labels, "要带进度计数")
    }

    @Test
    fun `运行段带上计数`() {
        val tracker = tracker(started("t1", "查找 sidecar 启动路径"), started("t2", "核对 SDK 类型"))
        val labels = labelsIn(buildRunningDetail(tracker.running))

        assertTrue("运行中" in labels)
        assertTrue("2" in labels)
    }

    @Test
    fun `两段由各自的卡分别弹出，不再有合并浮层`() {
        // 旧版是一个浮层里两段。拆卡之后点哪张卡就该只看哪一段 ——
        // 点"子任务"却弹出"运行中"会让人以为两边是一回事
        val tracker = tracker(todosLabel("甲" to "pending"), started("t1", "甲"))

        val todoLabels = labelsIn(buildTodoDetail(tracker.todos!!))
        val runningLabels = labelsIn(buildRunningDetail(tracker.running))

        assertTrue("任务清单" in todoLabels)
        assertTrue("运行中" !in todoLabels, "清单浮层里不该出现运行段：$todoLabels")
        assertTrue("运行中" in runningLabels)
        assertTrue("任务清单" !in runningLabels, "运行浮层里不该出现清单段：$runningLabels")
    }
```

3. 其余用例（`清单里每条一行，三种状态各有字形`、`运行中的任务带上 token 与时长`、两个时长用例）保留，只把里面的 `buildRunDetail(...)` 按内容换成 `buildTodoDetail(...todos!!)` 或 `buildRunningDetail(...running)`。

4. 类名从 `RunStripViewTest` 改为 `RunDetailTest`，文件名同步改为 `RunDetailTest.kt`。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.RunDetailTest' 2>&1 | tail -30
```

预期：编译失败，`unresolved reference: buildTodoDetail`。

- [ ] **Step 3: 写实现**

`git mv` 改名，然后把 `RunStripView.kt` 里的 `buildContextRow`、`buildUsageLabel`、`buildRunDetail` 换掉：

```bash
cd "C:/Users/CY/Desktop/CCoder" && git mv src/main/kotlin/com/ccoder/ui/RunStripView.kt src/main/kotlin/com/ccoder/ui/RunDetail.kt && git mv src/test/kotlin/com/ccoder/ui/RunStripViewTest.kt src/test/kotlin/com/ccoder/ui/RunDetailTest.kt
```

在 `RunDetail.kt` 中：删掉 `buildContextRow`、`buildUsageLabel`、`buildRunDetail` 三个函数，新增：

```kotlin
/**
 * 任务清单那一段。点"子任务"卡弹它。
 *
 * 与运行段分开而不是合成一个浮层：两段的数据来源不同（`TodoWrite` 声明的
 * 计划 vs 真在跑的 task 族），点哪张卡只看哪一段才不会让人以为两边是一回事。
 */
internal fun buildTodoDetail(todos: TaskList): JComponent {
    val box = detailBox()
    box.add(sectionHeader("任务清单", "${todos.completed}/${todos.total}"))
    todos.items.forEach { box.add(todoRow(it)) }
    return box
}

/** 运行中那一段。点"子代理"卡弹它。 */
internal fun buildRunningDetail(running: List<RunningTask>): JComponent {
    val box = detailBox()
    if (running.isEmpty()) {
        // 卡上写"空闲"时不该弹得出来，但真弹出来了就得说实话，
        // 而不是给一个空框
        box.add(JBLabel("当前没有任务").apply { foreground = UIUtil.getInactiveTextColor() })
        return box
    }
    box.add(sectionHeader("运行中", running.size.toString()))
    running.forEach { box.add(taskRow(it)) }
    return box
}

private fun detailBox() = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    border = JBUI.Borders.empty(10, 12, 11, 12)
}
```

同时删掉 `RunDetail.kt` 顶部 `buildContextRow` 用到的 `BorderLayout` import（若 `buildRunDetail` 之外的代码不再需要）。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.RunDetailTest' 2>&1 | tail -25
```

预期：全绿。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add -A src/main/kotlin/com/ccoder/ui/RunDetail.kt src/main/kotlin/com/ccoder/ui/RunStripView.kt src/test/kotlin/com/ccoder/ui/RunDetailTest.kt src/test/kotlin/com/ccoder/ui/RunStripViewTest.kt && git commit -m "refactor(ui): 详情浮层拆成清单段与运行段"
```

---

### Task 5: 接线进 ClaudePanel

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\Composer.kt:131-139`
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudePanel.kt`（多处）
- Modify: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\ComposerRulesTest.kt:77-95`

**Interfaces:**
- Consumes: Task 1/2/3/4 的全部产出
- Produces: `fun buildComposerCard(inputScroll: JComponent, toolbar: JComponent): ComposerCard`（**两参**）

- [ ] **Step 1: 改 `buildComposerCard` 与它的测试**

`Composer.kt` 第 121–139 行改为：

```kotlin
/**
 * 卡片内部自上而下两段：
 *
 *   CENTER 输入框（撑满可用高度）
 *   SOUTH  控件工具栏（发送/停止在右，左侧留给模型切换等）
 *
 * NORTH 原本挂着上下文行。那四样拆成独立卡片挪到输入卡**外面**之后，
 * 这一层就不需要了 —— 卡片是独立的一排，不是输入框的一部分。
 *
 * 工具栏放 SOUTH 而不是把按钮摆在输入框右边（原来那样）—— 右边放不下
 * 以后的模型切换、权限模式；摆下面则加控件只是往工具栏左侧添，不必再动结构。
 */
internal fun buildComposerCard(
    inputScroll: JComponent,
    toolbar: JComponent,
): ComposerCard = ComposerCard().apply {
    add(inputScroll, BorderLayout.CENTER)
    add(toolbar, BorderLayout.SOUTH)
}
```

`ComposerRulesTest.kt` 第 77–95 行那个用例改为：

```kotlin
    @Test
    fun `输入框居中、工具栏在下`() {
        val scroll = JPanel()
        val toolbar = JPanel()

        val card = buildComposerCard(scroll, toolbar)

        val layout = card.layout as BorderLayout
        assertSame(scroll, layout.getLayoutComponent(BorderLayout.CENTER), "输入框应在中间区域")
        assertSame(
            toolbar, layout.getLayoutComponent(BorderLayout.SOUTH),
            "工具栏必须在下方；摆到 EAST 就退回成「按钮挤在输入框右边」，右侧放不下以后的控件",
        )
        assertNull(
            layout.getLayoutComponent(BorderLayout.NORTH),
            "NORTH 该空着 —— 四张状态卡是独立的一排，在输入卡外面",
        )
    }
```

同文件第 101、109 行的 `buildComposerCard(JPanel(), JPanel(), JPanel())` 改为 `buildComposerCard(JPanel(), JPanel())`，并补 `import org.junit.jupiter.api.Assertions.assertNull`。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.ComposerRulesTest' 2>&1 | tail -20
```

预期：先编译失败（三参调用点还在 `ClaudePanel.kt:238`）。改完 `ClaudePanel` 后转绿。

- [ ] **Step 3: 改 ClaudePanel**

**(a) 字段**（第 78–95 行）——把 `statusLabel` / `usageLabel` / `runStripView` / `contextRow` 四个字段换成：

```kotlin
    /**
     * 连接状态卡的**文字源**。卡自己不知道有哪些状态，这里写什么它就显示什么。
     *
     * 保留一个纯字符串字段而不是直接拿卡当状态：状态的"要翻译成什么色调"
     * 是纯逻辑（[connectionTone]），能在无头单测里钉住；卡本身是 Swing。
     */
    private var connectionText = "未连接"

    /** 四张状态卡。常驻 —— 没内容的格子收边，不隐藏（spec §4）。 */
    private val statusCards = StatusCardsRow(
        onOpenTodos = { toggleDetail(todosOpen = true) },
        onOpenRunning = { toggleDetail(todosOpen = false) },
    )
```

**(b) 原来给 `statusLabel.text = ...` 赋值的地方**（共 8 处：`ClaudePanel.kt` 的 619、663、676、778、825、885、942、970 行）——全部改成 `setConnection(...)`，并新增：

```kotlin
    /** 唯一的连接状态写入口。文字变了卡上的点和色也跟着变。 */
    private fun setConnection(text: String) {
        connectionText = text
        refreshStatusCards()
    }
```

例如 `statusLabel.text = "已连接"` → `setConnection("已连接")`。

**(c) `updateUsage`**（第 320–324 行）改为：

```kotlin
    /**
     * 记下最新用量并刷新卡片。
     *
     * 不是每个 result 都带 `modelUsage` —— 清空会把已有的读数抹掉。
     */
    private var lastUsage: ContextUsage? = null

    private fun updateUsage(event: JsonObject) {
        val usage = contextUsageOf(event) ?: return
        lastUsage = usage
        refreshStatusCards()
    }
```

**(d) `refreshRunStrip`**（第 326–329 行）改为：

```kotlin
    /** 按当前四份数据重画四张卡。没有内容的格子由 [StatusCardModel.quiet] 收边。 */
    private fun refreshStatusCards() {
        statusCards.connection.setModel(connectionCardOf(connectionText))
        statusCards.context.setModel(contextCardOf(lastUsage))
        statusCards.todos.setModel(todoCardOf(runStatus.todos))
        statusCards.running.setModel(runningCardOf(runStatus.running))
    }
```

同时把第 760、935 行的 `refreshRunStrip()` 调用改为 `refreshStatusCards()`。

**(e) `toggleRunDetail`**（第 331–350 行）改为：

```kotlin
    /**
     * 点卡 → 弹它那一段详情；再点一次 → 收起。
     *
     * 两张卡共用一个浮层字段：同一时刻只该有一个浮层挂着，
     * 而 [todosOpen] 记住是哪一个，好在关闭时把对应的卡取消高亮。
     */
    private var todosOpen = false

    /** 参数名刻意不叫 `todosOpen` —— 与字段同名会遮蔽它，`this.` 一旦漏写就是静默的错。 */
    private fun toggleDetail(wantsTodos: Boolean) {
        val card = if (wantsTodos) statusCards.todos else statusCards.running
        val wasOpen = todosOpen == wantsTodos && runDetailPopup != null

        runDetailPopup?.cancel()
        runDetailPopup = null
        statusCards.todos.setOpen(false)
        statusCards.running.setOpen(false)

        if (wasOpen) return

        todosOpen = wantsTodos
        runDetailPopup = showTogglePopup(
            anchor = card,
            content = if (wantsTodos) {
                runStatus.todos?.let(::buildTodoDetail) ?: buildRunningDetail(emptyList())
            } else {
                buildRunningDetail(runStatus.running)
            },
        ) {
            runDetailPopup = null
            statusCards.todos.setOpen(false)
            statusCards.running.setOpen(false)
        }
        card.setOpen(true)
    }
```

**(f) `stopSession`**（第 844–848 行）里 `runStripView.setOpen(false)` 改为：

```kotlin
        statusCards.todos.setOpen(false)
        statusCards.running.setOpen(false)
```

**(g) 布局**（第 234–249 行）改为：

```kotlin
        val inputArea = buildComposerCard(inputScroll, composerToolbar)

        // 权限卡与状态卡共用 NORTH：两张卡区都在输入卡**外面**、上方，
        // 顺序是权限卡在上（它更急）、状态卡紧贴输入框
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(permissionSlot)
            add(statusCards)
        }

        // 不再单独画顶边线：输入区现在是一张圆角卡片，它自己的上沿
        // 就是与转写区之间的边界，再画一条会变成两道线
        val bottom = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8, 8, 8)
            add(header, BorderLayout.NORTH)
            // 输入区放 CENTER 而不是 SOUTH：BorderLayout 只给 SOUTH 首选高度，
            // 那样把分隔条往上拖，多出来的高度会落到空着的 CENTER，输入区
            // 纹丝不动 —— 看起来像"拖了没用"。放 CENTER 才能真正吸收。
            add(inputArea, BorderLayout.CENTER)
        }
```

**(h)** 在 `setupUI` 里 `buildComposerCard` 之前，先给四张卡灌一次初值：

```kotlin
        refreshStatusCards()
```

**(i)** 补 import：`javax.swing.BoxLayout`。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test 2>&1 | tail -30
```

预期：除 Task 6 要删掉的那些旧测试外，全绿。**此刻旧代码还在**（`RunStripView` 等），它们没有调用点了但不影响编译。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add -A src/main/kotlin/com/ccoder/ui/ClaudePanel.kt src/main/kotlin/com/ccoder/ui/Composer.kt src/test/kotlin/com/ccoder/ui/ComposerRulesTest.kt && git commit -m "feat(ui): 四张状态卡接进面板，详情浮层按卡分开"
```

---

### Task 6: 拆掉旧的合并胶囊

**Files:**
- Delete: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\RunStrip.kt`
- Delete: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\RunStripTest.kt`
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ComposerStrip.kt`
- Modify: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\ComposerStripTest.kt`
- Modify: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\ComposerRenderProbe.kt`
- Modify: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\TopRowRenderProbe.kt`

**Interfaces:**
- Produces: `ComposerStrip.kt` 只剩 `popupHeightOf` / `popupWidthOf` / `popupAnchorY` / `popupCenteredX`

- [ ] **Step 1: 删文件**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git rm src/main/kotlin/com/ccoder/ui/RunStrip.kt src/test/kotlin/com/ccoder/ui/RunStripTest.kt
```

- [ ] **Step 2: 跑全量测试，看谁在引用**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew compileKotlin compileTestKotlin 2>&1 | grep -E "error:|unresolved" | head -30
```

预期：列出 `ComposerStrip.kt` 里 `RunStripView` / `runStripText` / `ellipsize` 相关的未解析引用，以及两个探针里的 `buildContextRow`。

- [ ] **Step 3: 清 `ComposerStrip.kt`**

删掉 `RunStripView` 类（第 169–262 行整块）、`runStripText`（第 32 行起）、`ellipsize`（第 61 行起），以及私有的 `CARET` 别名（第 20 行）。

⚠️ **`EXPAND_CARET`（`Composer.kt:155`）不许删。** 它虽然看起来是这一套的，但 `ComposerMode.kt:54`（权限模式标签）和 `:70`（「本会话不再询问」）都在用它 —— `CARET` 只是它的一个私有别名。`CARET` 删，`EXPAND_CARET` 留。

**保留** `popupHeightOf` / `popupWidthOf` / `popupAnchorY` / `popupCenteredX` 及其文档。

文件头部补一句说明剩下的东西是什么：

```kotlin
/**
 * 浮层定位。条本身已经拆成四张状态卡（见 [StatusCardsRow]），
 * 这里只剩下"浮层该出现在哪"的计算 —— 那部分与卡片形状无关，
 * 点会话标签、点状态卡都用同一套。
 */
```

- [ ] **Step 4: 清 `ComposerStripTest.kt`**

删掉这些用例：`只有清单时给进度和当前任务`、`只有运行中时给计数`、`两者都有时用竖线分开`、`宽度不够时先截任务名`、`挤到极限也只截名字，两个计数都还在`、`名字短到放不下时整段消失`、`放得下就原样返回`、`放不下就截断并以省略号结尾`、`连省略号都放不下时给空串`、`预算为零或负数时给空串而不是抛错`、`放进一行里布局之后，条不会被压成一个点`、`没有任务时条整个不可见`。

**保留**浮层那几个：`浮层还没显示时用内容的首选高度`、`已经量过的尺寸优先于内容首选高度`、`两个都没有时给 0`、四条 `popupAnchorY`、三条 `popupCenteredX`。

删掉只给被删用例用的 helper：`ten`、`strip`、`layoutAll`（保留浮层用例不需要它）。类文档补一句：`/** 浮层定位。 */`

- [ ] **Step 5: 清两个探针**

`ComposerRenderProbe.kt`：把 `buildContextRow(status, usage, strip)` 那段换成四张卡的组装，并改 `buildComposerCard(contextRow, inputScroll, toolbar)` → `buildComposerCard(inputScroll, toolbar)`：

```kotlin
            val cards = StatusCardsRow(onOpenTodos = {}, onOpenRunning = {}).apply {
                connection.setModel(connectionCardOf("已连接"))
                context.setModel(contextCardOf(ContextUsage(inputTokens = 12300, contextWindow = 200000)))
                todos.setModel(
                    todoCardOf(
                        TaskList(
                            listOf(
                                TodoItem("定位调用点", TodoState.Completed),
                                TodoItem("替换指纹函数", TodoState.InProgress),
                                TodoItem("跑测试", TodoState.Pending),
                            )
                        )
                    )
                )
                running.setModel(runningCardOf(listOf(taskStub("t1"), taskStub("t2"))))
            }
```

并在文件里补：

```kotlin
    private fun taskStub(id: String) =
        RunningTask(id = id, kind = null, label = null, detail = null, tokens = 0, durationMs = 0)
```

外层容器把 `cards` 放 `BorderLayout.NORTH`、`card` 放 `BorderLayout.CENTER`，`h` 从 150 调到 220（多了状态行）。

`TopRowRenderProbe.kt`：删掉 `status` / `usage` / `contextRow` 那段（第 50、66–70 行），只留顶部那一行的渲染；`h` 相应调整。文件文档补一句"连接状态已经不在顶部那一行了，它和另外三样一起在 `ComposerRenderProbe` 里"。

- [ ] **Step 6: 跑全量测试**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test 2>&1 | tail -30
```

预期：全绿。**记下用例总数**，与改动前对比（本次应净减约 12 条）。

- [ ] **Step 7: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add -A && git commit -m "refactor(ui): 拆掉合并的上下文胶囊，删 RunStrip 与截断逻辑"
```

---

### Task 7: 渲染探针与打包

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\StatusCardsRenderProbe.kt`

- [ ] **Step 1: 写渲染探针**

创建 `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\StatusCardsRenderProbe.kt`：

```kotlin
package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把四张状态卡画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"收边的卡 alpha 是 0""四张等宽"，
 * 钉不住"四张卡放在 420px 里整体好不好看"。而后者正是这次改版的
 * **全部理由**（用户要的就是"更美观"），不该只靠信念。
 *
 * 产物在 `build/status-cards-probe*.png`。
 */
class StatusCardsRenderProbe {

    @Test
    fun `把忙时的四张卡画成图片`() = render("build/status-cards-probe.png", busy = true)

    @Test
    fun `把空闲的四张卡画成图片`() = render("build/status-cards-probe-idle.png", busy = false)

    private fun render(path: String, busy: Boolean) {
        SwingUtilities.invokeAndWait {
            val cards = StatusCardsRow(onOpenTodos = {}, onOpenRunning = {}).apply {
                connection.setModel(connectionCardOf(if (busy) "已连接" else "会话已断开"))
                context.setModel(
                    contextCardOf(
                        if (busy) ContextUsage(12300, 200000) else ContextUsage(185000, 200000)
                    )
                )
                todos.setModel(
                    if (busy) {
                        todoCardOf(
                            TaskList(
                                listOf(
                                    TodoItem("定位调用点", TodoState.Completed),
                                    TodoItem("替换指纹函数", TodoState.Completed),
                                    TodoItem("跑测试", TodoState.InProgress),
                                    TodoItem("更新文档", TodoState.Pending),
                                )
                            )
                        )
                    } else {
                        todoCardOf(null)
                    }
                )
                running.setModel(if (busy) runningCardOf(listOf(stub("t1"), stub("t2"))) else runningCardOf(emptyList()))
            }

            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(8)
                add(cards, BorderLayout.NORTH)
            }

            // 420px 是工具窗口的真实宽度
            val w = 420
            val h = outer.preferredSize.height
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun stub(id: String) =
        RunningTask(id = id, kind = null, label = null, detail = null, tokens = 0, durationMs = 0)

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
```

- [ ] **Step 2: 跑探针并看图**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests 'com.ccoder.ui.StatusCardsRenderProbe' 2>&1 | tail -5
```

然后**打开这两张 PNG 看**：

```
C:\Users\CY\Desktop\CCoder\build\status-cards-probe.png
C:\Users\CY\Desktop\CCoder\build\status-cards-probe-idle.png
```

对照 `docs/design/status-cards-b.html` 里的「变体 2」。**不好看就改，别交。**

- [ ] **Step 3: 全量测试**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test 2>&1 | tail -20
```

预期：全绿。同时跑 sidecar 测试确认没被波及：

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && node --test 2>&1 | tail -10
```

- [ ] **Step 4: 打包**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew buildPlugin 2>&1 | tail -10 && ls -la build/distributions/
```

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add -A && git commit -m "test(ui): 状态卡片的渲染探针"
```

---

## 收尾：手工冒烟（不在自动化范围内）

装包后需要人眼确认，写进 `docs/superpowers/plans/2026-09-13-status-cards.md` 末尾的执行记录：

1. 四张卡在真实 420px 里**没有文字被裁**
2. 空闲时后两张确实"收边"，看起来是退到背景而不是坏掉
3. 点子任务卡 → 弹出清单；再点 → 收起；点子代理卡 → 前一个浮层自动关
4. **转写区少的高度能不能接受**（这是 spec §9 那个"约 58px"的真实检验）
5. 上下文从 6% 涨到 70% 时进度条转琥珀
6. 断开连接时连接卡转红

---

## 自查记录

**Spec 覆盖：** §2 结构 → Task 5(g)；§3 四卡内容 → Task 1；§3.1 两条硬规矩 → Task 1 的 `Indicator` 与对应断言；§3.2 八种文字 → Task 1；§4 常驻与收边 → Task 2 + Task 3；§5 交互 → Task 2/3/4/5；§6 可见性 → Task 1；§7 删除面 → Task 6；§8 新增 → Task 1/2/3；§9 代价 → Task 3 Step 1 的最小高度断言；§10 测试 → 各任务；§11 不做 → 全计划未涉及。

**占位符扫描：** 无 TODO/TBD。每步都有可执行代码或命令。

**类型一致性：** `StatusCardModel` 的字段名（`label`/`value`/`tone`/`sub`/`indicator`/`quiet`）在 Task 1 定义，Task 2/3/5/7 引用一致；`Indicator` 四个分支名一致；`StatusCardView.setModel`/`setOpen`/`isOpen` 在 Task 2 定义、Task 3/5 引用一致；`buildTodoDetail`/`buildRunningDetail` 在 Task 4 定义、Task 5 引用一致。

**发现并修正的问题（都是自查时抓到的，已改在正文里）：**

1. **`EXPAND_CARET` 差点被误删。** 它看着像是胶囊那一套的，实际 `ComposerMode.kt:54,70` 在用（权限模式标签、「本会话不再询问」）。只有私有的 `CARET` 别名该删。
2. **Task 2 的测试读不到描边色。** 实现把 `RoundedLineBorder` 包在 `CompoundBorder` 里（要带内边距），测试却直接 `as RoundedLineBorder` —— ClassCastException。改成先剥一层，与 `ComposerRulesTest` 现有写法一致。
3. **Task 2 有一条假测试。** `没有指示器时那一块不可见` 原本用 `filterIsInstance<JPanel>()` 找指示器，而 `IndicatorView` 继承的是 `JComponent` —— 那个断言**恒为真，永远绿**。改成直接找 `IndicatorView`。
4. **Task 5 的 `toggleDetail` 参数遮蔽字段。** 参数原本也叫 `todosOpen`，靠 `this.` 区分，漏写一处就是静默的错。改名 `wantsTodos`。
5. **Task 2 的 `border` 两次赋值**改成一次构造，顺带消掉 Step 4 的退路说明。

**未处理的一处重复（记下，不在本次范围）：** `SessionList.kt` 里有个私有的 `DELETE_DANGER`，色值与新增的 `dangerColor()` 完全相同。本次不合并 —— 那要动会话列表和它的测试，与本次改动无关。将来谁碰 `SessionList` 顺手换掉即可。

---

## 执行记录（2026-09-13）

**七个任务全部完成。** Kotlin 426 条（执行前 409）、sidecar 89 条，全绿。
包：`build/distributions/CCoder-0.2.4.zip`（7,745,492 字节，08:11）。
已验证 jar 里有 `StatusCardsKt` / `StatusCardView` / `StatusCardsRow` / `RunDetailKt`，
且 **`RunStrip` 零残留**。

### 计划本身的三处缺陷（执行时才暴露）

1. **Task 1 会打断构建。** 删掉 `formatContextUsage` 立刻让 `ClaudePanel.kt:322`
   编译不过。补了一个最小过渡写法保住常绿 —— 计划里没写这一步。
2. **Task 4 和 Task 5 必须合并。** 删掉 `buildContextRow` / `buildUsageLabel`
   就直接打断了 `ClaudePanel`，而计划把接线放在下一个任务。所以 4/5/6 是一次提交。
3. **`EXPAND_CARET` 差点被误删**（自查时抓到，见前文）。

### 渲染出来才发现的三处错（单测全绿时它们都在）

1. **连接状态的颜色完全看不见。** `connectionCardOf` 的 `indicator` 是 `None`，
   于是 `Tone` 是一个算了没人用的数 —— "已连接/会话已断开/启动失败"三种状态
   在界面上长得一模一样。加前置状态点（`leadingDot`）。
2. **连接卡的值套错了模子。** "大字号"是给数字的（`6%`/`2/4`/`2`），而它的值
   是词 —— "会话已断开"被 JLabel 自动截成"会话已…"。降回普通字号（`bigValue`）。
3. **指示器跟内容走，四张卡底边参差不齐。** 上下文卡的条在第三行下面，
   子任务卡的分段在第二行下面。加竖直弹簧（`Box.createVerticalGlue`）让它们贴底。

### 两处实测数值（计划里是估的）

- **状态行最小高度 67px**，计划估的"约 58px"少算 9px。其中上下文卡 67px、
  其余三张 49px —— 差在上下文卡多一行副值。转写区相应少这么多高。
- **连接卡的值可用宽度只有 66px。** 实测"正在载入历史…"需要 81px，差整整
  一个字。八条连接文字里只有它放不下，文案缩成"正在载入…"，并补了一条
  逐条量宽度的测试（`八种连接文字都放得下，不会被截断`）钉住。

### 顺带修掉的两个真问题

- **卡片能被压扁。** `JLabel` 没有布局管理器，`getMinimumSize()` 落到
  `Component.size()` 也就是 0，整行最小高度只剩边框那 2px。分隔条设了
  `honorComponentsMinimumSize`，用户就能把状态行拖成一条缝。覆写
  `getMinimumSize` 成首选高度。
- **一处变量遮蔽。** 探针的参数 `connection: String?` 盖住了
  `StatusCardsRow.connection` 属性（Kotlin 里外层局部变量优先于隐式接收者的
  成员）。`toggleDetail` 的参数命名也因此改成了 `wantsTodos`。

### 仍未做的

**手工冒烟**（见文末"收尾"一节）—— 装包后人眼确认：真实 420px 里没有文字
被裁、空闲两张确实"收边"、点卡弹浮层、转写区少的高度能不能接受、
上下文涨到 70% 转琥珀、断开时转红。
