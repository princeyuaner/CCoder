package com.ccoder.ui

import com.google.gson.JsonParser
import com.ccoder.sidecar.SubagentInfo
import com.ccoder.text.CcoderText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.event.MouseEvent
import java.awt.Container
import javax.swing.JLabel

/** 详情浮层的内容，以及时长格式。 */
class RunDetailTest {

    private fun ev(json: String) = JsonParser.parseString(json).asJsonObject

    private fun tracker(vararg events: String) = RunStatusTracker().apply {
        events.forEach { consume(ev(it)) }
    }

    private fun todosLabel(vararg pairs: Pair<String, String>) =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"TodoWrite","input":{"todos":[${
            pairs.joinToString(",") { (t, s) -> """{"content":"$t","status":"$s"}""" }
        }]}}]}}"""

    private fun started(id: String, desc: String) =
        """{"type":"system","subtype":"task_started","task_id":"$id","description":"$desc","subagent_type":"explore"}"""

    /** 深度优先收集所有 JLabel 的文字 —— 结构断言够用了，不必去比对像素。 */
    private fun labelsIn(root: Component): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Component) {
            if (c is JLabel) out += c.text
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return out
    }

    // ---- 清单段 ----

    @Test
    fun `清单段带上进度计数`() {
        val todos = tracker(todosLabel("甲" to "completed", "乙" to "in_progress")).todos!!
        val labels = labelsIn(buildTodoDetail(todos))

        assertTrue("任务列表" in labels, "要有段标题")
        assertTrue("1/2" in labels, "要带进度计数：$labels")
    }

    @Test
    fun `清单里每条一行，三种状态各有字形`() {
        val todos = tracker(
            todosLabel("甲" to "completed", "乙" to "in_progress", "丙" to "pending")
        ).todos!!
        val labels = labelsIn(buildTodoDetail(todos))

        assertTrue(labels.any { it.startsWith("✓") && it.endsWith("甲") }, "已完成：$labels")
        assertTrue(labels.any { it.startsWith("◐") && it.endsWith("乙") }, "进行中：$labels")
        assertTrue(labels.any { it.startsWith("○") && it.endsWith("丙") }, "待办：$labels")
    }

    // ---- 运行段 ----

    @Test
    fun `运行段带上计数`() {
        val running = tracker(started("t1", "查找 sidecar 启动路径"), started("t2", "核对 SDK 类型")).running
        val labels = labelsIn(buildRunningDetail(running, emptyList(), {}, onOpen = {  }))

        assertTrue("运行中" in labels, "要有段标题")
        assertTrue("2" in labels, "要带计数：$labels")
    }

    @Test
    fun `运行中的任务带上 token 与时长`() {
        val running = tracker(
            started("t1", "查找 sidecar 启动路径"),
            """{"type":"system","subtype":"task_progress","task_id":"t1",
                "usage":{"total_tokens":12400,"duration_ms":8000}}""",
        ).running
        val labels = labelsIn(buildRunningDetail(running, emptyList(), {}, onOpen = {  }))

        assertTrue(labels.any { it == "12.4k tok · 8s" }, "实际：$labels")
    }

    @Test
    fun `跑着的时候两行：任务名一行，进行时一行（B2）`() {
        // task_progress 的 description 是"当前这一步"，每步都来、不要钱
        // （实测见 docs/superpowers/specs/2026-09-18-subagent-nesting-design.md 事实 6）
        val running = tracker(
            started("t1", "找一下 token 刷新的调用点"),
            """{"type":"system","subtype":"task_progress","task_id":"t1",
                "description":"Reading TokenStore.kt",
                "usage":{"total_tokens":12400,"duration_ms":80000}}""",
        ).running
        val labels = labelsIn(buildRunningDetail(running, emptyList(), {}, onOpen = {  }))

        // 第一行是任务名（**不**再被进行时顶掉），统计还在它右边
        assertTrue(labels.any { it.contains("找一下 token 刷新的调用点") }, "任务名丢了：$labels")
        // 第二行是进行时
        assertTrue(labels.any { it == "Reading TokenStore.kt" }, "进行时没画出来：$labels")
        assertTrue(labels.any { it == "12.4k tok · 1m20s" }, "统计丢了：$labels")
    }

    @Test
    fun `没有进行时的时候就是一行 —— 与从前一字不差`() {
        val running = tracker(started("t1", "查找 sidecar 启动路径")).running
        val labels = labelsIn(buildRunningDetail(running, emptyList(), {}, onOpen = {  }))

        assertTrue(labels.any { it.contains("查找 sidecar 启动路径") }, "实际：$labels")
        // 没有那句话就**不画**第二行（空行会让浮层里每条都多占一格）
        assertEquals(1, labels.count { it.contains("查找 sidecar 启动路径") }, "实际：$labels")
    }

    @Test
    fun `summary 比 description 优先（有那句人话就用它）`() {
        val running = tracker(
            started("t1", "找一下 token 刷新的调用点"),
            """{"type":"system","subtype":"task_progress","task_id":"t1",
                "description":"Reading TokenStore.kt","summary":"正在核对刷新路径"}""",
        ).running
        val labels = labelsIn(buildRunningDetail(running, emptyList(), {}, onOpen = {  }))

        assertTrue(labels.any { it == "正在核对刷新路径" }, "实际：$labels")
        assertTrue(labels.none { it == "Reading TokenStore.kt" }, "两条都画了：$labels")
    }

    @Test
    fun `运行段空着时说实话，而不是给一个空框`() {
        // 卡上写"空闲"时不该弹得出来，但真弹出来了就得说实话
        assertEquals(listOf("当前没有任务"), labelsIn(buildRunningDetail(emptyList(), emptyList(), {}, onOpen = {  })))
    }

    // ---- 两段分家 ----

    @Test
    fun `点哪张卡只看哪一段，不再有合并浮层`() {
        // 旧版是一个浮层里两段。拆卡之后点哪张卡就该只看哪一段 ——
        // 点"任务列表"却弹出"运行中"会让人以为两边是一回事
        val t = tracker(todosLabel("甲" to "pending"), started("t1", "甲"))

        val todoLabels = labelsIn(buildTodoDetail(t.todos!!))
        val runningLabels = labelsIn(buildRunningDetail(t.running, emptyList(), {}, onOpen = {  }))

        assertTrue("任务列表" in todoLabels)
        assertTrue("运行中" !in todoLabels, "清单浮层里不该出现运行段：$todoLabels")
        assertTrue("运行中" in runningLabels)
        assertTrue("任务列表" !in runningLabels, "运行浮层里不该出现清单段：$runningLabels")
    }

    // ---- 时长 ----

    @Test
    fun `时长按量级给不同精度`() {
        assertEquals("0s", formatDuration(400))
        assertEquals("8s", formatDuration(8_000))
        assertEquals("59s", formatDuration(59_999))
        assertEquals("1m35s", formatDuration(95_000))
        assertEquals("1h02m", formatDuration(3_720_000))
    }

    @Test
    fun `时长用 ROOT locale，不随系统语言变成逗号`() {
        // 某些语言下 %.2f 之类会输出逗号，这里虽全是整数格式，
        // 但整条链路统一 locale 才不会被将来的改动咬到
        assertEquals("1m05s", formatDuration(65_000))
    }

    // ---- 子代理段 ----

    @Test
    fun `子代理那一段列出类型与描述`() {
        val box = buildRunningDetail(
            emptyList(),
            listOf(SubagentInfo("a1", "Explore", "找调用点", "call_9")),
            {},
            onOpen = {  },
            historyExpanded = true,   // 空闲时默认收着 —— 见「空闲时先回答…」那几条
        )

        val texts = labelsIn(box).joinToString("\n")
        assertTrue(texts.contains("Explore"), "实际：$texts")
        assertTrue(texts.contains("找调用点"), "实际：$texts")
    }

    // ---- 空闲那一版（2026-09-20）----

    /**
     * 用户问："子代理都没了点开为什么还有内容。"
     *
     * 那些内容是**记录**（本会话跑过的，点一条能回看转写），不是"还在跑"。
     * 收着的版本先答"现在有没有在跑"，记录退到一行后面。
     */
    @Test
    fun `空闲时先回答没有在跑的，记录收在一行后面`() {
        val box = buildRunningDetail(
            emptyList(),
            listOf(
                SubagentInfo("a1", "Explore", "找调用点", "call_1"),
                SubagentInfo("a2", "Plan", "设计一下", "call_2"),
            ),
            {},
            onOpen = {  },
        )

        val texts = labelsIn(box)
        assertTrue(
            texts.contains(CcoderText.text("transcript.detail.noRunningSubagents")),
            "没先回答现在有没有在跑：$texts",
        )
        assertTrue(
            texts.any { it.startsWith(CcoderText.text("transcript.detail.showFinished", 2)) },
            "没有看已结束的 N 个那一行：$texts",
        )
        assertTrue(texts.none { it.contains("找调用点") }, "收着的时候把记录也列出来了：$texts")
    }

    @Test
    fun `点那一行把展开报出去`() {
        val opened = mutableListOf<Unit>()
        val box = buildRunningDetail(
            emptyList(),
            listOf(SubagentInfo("a1", "Explore", "找调用点", "call_1")),
            {},
            onOpen = {  },
            onToggleHistory = { opened += Unit },
        )

        clickFirstClickable(box)

        assertEquals(1, opened.size, "那一行点不动")
    }

    /** 展开之后就是原来那两段的样子（小标题 + 条数 + 每条一行）。 */
    @Test
    fun `展开之后照旧列出记录`() {
        val picked = mutableListOf<String>()
        val box = buildRunningDetail(
            emptyList(),
            listOf(
                SubagentInfo("a1", "Explore", "找调用点", "call_1"),
                SubagentInfo("a2", "Plan", "设计一下", "call_2"),
            ),
            {},
            onOpen = { picked += it.agentId },
            historyExpanded = true,
        )

        assertEquals(
            listOf(SUBAGENTS_SECTION, "2"),
            labelsIn(box).take(2),
            "展开之后小标题与条数要照旧：${labelsIn(box)}",
        )

        clickFirstClickable(box)
        assertEquals(listOf("a1"), picked, "展开之后点第一条该打开它的转写")
    }

    /** 有在跑的时候不受这条管：那种场合本来就要两段摊开（那一段里混着正在跑的）。 */
    @Test
    fun `有在跑时两段照旧摊开`() {
        val box = buildRunningDetail(
            listOf(RunningTask("call_x", "local_bash", "跑测试", null, 0, 0)),
            listOf(SubagentInfo("a1", "Explore", "找调用点", "call_1")),
            {},
            onOpen = {  },
        )

        val texts = labelsIn(box)
        assertEquals(RUNNING_SECTION, texts.firstOrNull(), "实际：$texts")
        assertTrue(texts.contains(SUBAGENTS_SECTION), "有在跑时把记录收起来了：$texts")
        assertTrue(texts.any { it.contains("找调用点") }, "实际：$texts")
    }

    @Test
    fun `两段的小标题各自写清「运行中」与「全部子代理」`() {
        // 2026-09-17 用户问过「这个会话的子代理」是什么意思 —— 浮层本来就是
        // "这个会话"的，那四个字不区分任何东西（要区分的是上面那段"此刻在跑"）。
        // 也不能写"跑过的"：转写是边跑边写的，还在跑的子代理同样在这一段里
        val running = buildRunningDetail(
            listOf(RunningTask("call_x", "local_bash", "跑测试", null, 0, 0)),
            emptyList(),
            {},
            onOpen = {  },
        )
        val subagents = buildRunningDetail(
            emptyList(),
            listOf(
                SubagentInfo("a1", "Explore", "找调用点", "call_1"),
                SubagentInfo("a2", "Plan", "设计一下", "call_2"),
            ),
            {},
            onOpen = {  },
            historyExpanded = true,
        )

        assertEquals(RUNNING_SECTION, labelsIn(running).firstOrNull(), "实际：${labelsIn(running)}")
        assertEquals(
            listOf(SUBAGENTS_SECTION, "2"),
            labelsIn(subagents).take(2),
            "标题后面要跟条数：${labelsIn(subagents)}",
        )
    }

    @Test
    fun `点某个子代理把打开动作报出去`() {
        val picked = mutableListOf<String>()
        val box = buildRunningDetail(
            emptyList(),
            listOf(SubagentInfo("a1", "Explore", "找调用点", "call_9")),
            {},
            onOpen = { picked += it.agentId },
            historyExpanded = true,
        )

        clickFirstClickable(box)

        assertEquals(listOf("a1"), picked)
    }

    @Test
    fun `对不上子代理的运行中任务不可点开转写（终止钮是另一回事）`() {
        // 后台命令（local_bash 那种）没有子代理转写 —— 给它一个"点开转写"
        // 的外观是骗人。终止钮不受这条管：停的是任务，任何任务都停得掉
        val box = buildRunningDetail(
            listOf(RunningTask("call_x", "local_bash", "跑测试", null, 0, 0)),
            emptyList(),
            {},
            {},
        )

        val stop = findStop(box)
        assertTrue(stop != null, "任何在跑的任务都该有终止钮")
        assertEquals(stop, findClickable(box), "除了终止钮，这里不该有别的可点组件")
    }

    @Test
    fun `对得上的运行中任务可以点开`() {
        val picked = mutableListOf<String>()
        val box = buildRunningDetail(
            listOf(RunningTask("call_9", "explore", "找调用点", null, 0, 0)),
            listOf(SubagentInfo("a1", "Explore", "找调用点", "call_9")),
            {},
            onOpen = { picked += it.agentId },
        )

        clickFirstClickable(box)

        assertEquals(listOf("a1"), picked, "任务的 id 就是 tool_use id，靠它对上子代理")
    }

    // ---- 终止（2026-09-18）----

    @Test
    fun `运行中那行右端有终止钮，点了报出 task id`() {
        val stopped = mutableListOf<String>()
        val box = buildRunningDetail(
            listOf(RunningTask("t1", "explore", "找调用点", null, 0, 0)),
            emptyList(),
            { stopped += it },
            {},
        )

        clickStop(box)

        assertEquals(listOf("t1"), stopped)
    }

    @Test
    fun `没有对应子代理的任务也有终止钮 —— 停的是任务，不是「子代理」这个身份`() {
        val stopped = mutableListOf<String>()
        val box = buildRunningDetail(
            listOf(RunningTask("t9", "local_bash", "跑测试", null, 0, 0)),
            emptyList(),
            { stopped += it },
            {},
        )

        clickStop(box)

        assertEquals(listOf("t9"), stopped)
    }

    @Test
    fun `点终止钮不会顺手把转写页翻出来`() {
        // Swing 的点击只发给最深的那个组件 —— 行上"看它的转写"的监听器
        // 收不到子组件里的点击。这条钉的就是那个假设
        val opened = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val box = buildRunningDetail(
            listOf(RunningTask("call_9", "explore", "找调用点", null, 0, 0)),
            listOf(SubagentInfo("a1", "Explore", "找调用点", "call_9")),
            { stopped += it },
            { opened += it.agentId },
        )

        clickStop(box)

        assertEquals(listOf("call_9"), stopped)
        assertEquals(emptyList<String>(), opened, "点的是终止，不该把转写页也翻出来")
    }

    @Test
    fun `子代理转写把条数写在标题上`() {
        val box = buildSubagentDetail(
            SubagentInfo("a1", "Explore", "找调用点", "call_9"),
            listOf(ev("""{"type":"user","message":{"content":"去找"}}""")),
        )

        assertTrue(labelsIn(box).joinToString("\n").contains("1 条"), "实际：${labelsIn(box)}")
    }

    // ---- 消息取字 ----

    @Test
    fun `messageText 认字符串与块数组两种形状`() {
        assertEquals("你好", messageText(ev("""{"message":{"content":"你好"}}""")))
        assertEquals(
            "第一段\n第二段",
            messageText(
                ev("""{"message":{"content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}]}}""")
            ),
        )
    }

    @Test
    fun `messageText 把工具调用折成一行`() {
        // 一段全是工具调用的转写，不给这一行就跟空白没区别
        assertEquals(
            "→ Read",
            messageText(ev("""{"message":{"content":[{"type":"tool_use","name":"Read"}]}}""")),
        )
    }

    @Test
    fun `messageText 读不出文字时给 null`() {
        assertNull(messageText(ev("""{"message":{"content":[]}}""")))
        assertNull(messageText(ev("""{"type":"system"}""")))
    }

    /** 点树里第一个挂了监听器的组件 —— 行是 JPanel，不是按钮。 */
    private fun clickFirstClickable(root: Component) {
        findClickable(root)?.dispatchEvent(
            MouseEvent(
                findClickable(root), MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 3, 3, 1, false, MouseEvent.BUTTON1,
            )
        )
    }

    private fun findClickable(root: Component): Component? {
        if (root.mouseListeners.isNotEmpty()) return root
        if (root is Container) {
            for (c in root.components) findClickable(c)?.let { return it }
        }
        return null
    }

    /** 点树里那颗终止钮（自绘组件，按类型找）。 */
    private fun clickStop(root: Component) {
        val button = findStop(root) ?: throw AssertionError("这棵树里没有终止钮")
        button.dispatchEvent(
            MouseEvent(
                button, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 2, 2, 1, false, MouseEvent.BUTTON1,
            )
        )
    }

    private fun findStop(root: Component): TaskStopButton? {
        if (root is TaskStopButton) return root
        if (root is Container) {
            for (c in root.components) findStop(c)?.let { return it }
        }
        return null
    }
}
