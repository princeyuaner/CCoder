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
    fun `运行段不再有小标题 —— 卡本身就是"在跑"`() {
        // 2026-09-24 改版（用户从选型台上挑了方案甲）：从前这里一条「运行中 N」的
        // 小标题，下面还有一段「全部子代理」把同一批人再列一遍。现在在跑的各自一张卡，
        // 卡就是"在跑"的标题；数目那个数归状态卡（`子代理 3`），不在这儿重复
        val running = tracker(started("t1", "查找 sidecar 启动路径"), started("t2", "核对 SDK 类型")).running
        val labels = labelsIn(buildRunningDetail(running, emptyList(), {}, onOpen = {  }))

        assertTrue(labels.none { it == "运行中" }, "小标题该没了：$labels")
        assertTrue(labels.any { it.contains("查找 sidecar 启动路径") }, "实际：$labels")
        assertTrue(labels.any { it.contains("核对 SDK 类型") }, "实际：$labels")
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

    /** 展开之后是一列压暗的记录行；**那条线还在**（从前展开就再也收不回去）。 */
    @Test
    fun `展开之后列出记录，那条线还在 —— 能收回去`() {
        val box = buildRunningDetail(
            emptyList(),
            listOf(
                SubagentInfo("a1", "Explore", "找调用点", "call_1"),
                SubagentInfo("a2", "Plan", "设计一下", "call_2"),
            ),
            {},
            onOpen = {  },
            historyExpanded = true,
        )

        val labels = labelsIn(box)
        assertTrue(
            // 末尾还跟着一个 `›`（disclosureRow 加的），所以用 startsWith
            labels.any { it.startsWith(CcoderText.text("transcript.detail.hideFinished")) },
            "展开之后没有收回的路（从前只能关掉浮层重开）：$labels",
        )
        assertTrue(labels.any { it.contains("找调用点") }, "记录没列出来：$labels")
        assertTrue(
            labels.count { it.startsWith("✓") } == 2,
            "记录行该有个 ✓（与卡上那个活点是一对：点 = 在跑，勾 = 记录）：$labels",
        )
    }

    // ---- 2026-09-24：卡 + 一条线（方案甲）----

    /** 这条钉的就是改版的**全部理由**：同一批人从前被列了两遍。 */
    @Test
    fun `正在跑的那条不在记录里重复出现`() {
        val box = buildRunningDetail(
            listOf(RunningTask("call_1", "explore", "找调用点", null, 0, 0)),
            listOf(
                SubagentInfo("a1", "Explore", "找调用点", "call_1"),   // 正在跑
                SubagentInfo("a2", "Plan", "设计一下", "call_2"),      // 已结束
            ),
            {},
            onOpen = {  },
            historyExpanded = true,
        )
        val labels = labelsIn(box)

        assertEquals(1, labels.count { it.contains("找调用点") }, "正在跑的被列了两遍：$labels")
        assertTrue(labels.any { it.contains("设计一下") }, "已结束的没列出来：$labels")
        assertEquals(1, labels.count { it.startsWith("✓") }, "记录里混进了在跑的那条：$labels")
    }

    @Test
    fun `那条线上的数目只数已结束的`() {
        val box = buildRunningDetail(
            listOf(RunningTask("call_1", "explore", "找调用点", null, 0, 0)),
            listOf(
                SubagentInfo("a1", "Explore", "找调用点", "call_1"),
                SubagentInfo("a2", "Plan", "设计一下", "call_2"),
            ),
            {},
            onOpen = {  },
        )

        assertTrue(
            labelsIn(box).any { it.startsWith(CcoderText.text("transcript.detail.showFinished", 1)) },
            "该写「看已结束的 1 个」（一共 2 条，其中一条还在跑）：${labelsIn(box)}",
        )
    }

    @Test
    fun `在跑的超过上限时只画几张，剩下的说出来`() {
        val running = (1..MAX_AGENT_CARDS + 2).map {
            RunningTask("t$it", "explore", "任务 $it", null, 0, 0)
        }
        val box = buildRunningDetail(running, emptyList(), {}, onOpen = {  })

        assertEquals(
            MAX_AGENT_CARDS,
            countCards(box),
            "张数该封顶 —— 浮层是向上弹的，再高就顶出屏幕（见 MAX_AGENT_CARDS）",
        )
        assertTrue(
            labelsIn(box).any { it == CcoderText.text("transcript.detail.moreRunning", 2) },
            "多出来的两条没交代：${labelsIn(box)}",
        )
    }

    @Test
    fun `一张卡就是一个在跑的子代理`() {
        val running = tracker(started("t1", "找调用点"), started("t2", "核对类型")).running
        val box = buildRunningDetail(running, emptyList(), {}, onOpen = {  })

        assertEquals(running.size, countCards(box), "有几个在跑就该有几张卡")
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

        // **按文字找那一条**，不能用"第一个可点的"：展开之后第一条可点的是
        // 「收起」（2026-09-24 起两种状态都画那条线）
        clickLabelContaining(box, "找调用点")

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

    // ---- 形状判等（2026-09-24 修"闪来闪去"）----

    private fun task(
        id: String = "t1",
        kind: String? = "Explore",
        label: String? = "找调用点",
        detail: String? = null,
        tokens: Long = 0,
        durationMs: Long = 0,
    ) = RunningTask(id, kind, label, detail, tokens, durationMs)

    /**
     * **这条钉的就是闪的根因**：`task_progress` 每走一步只改 token / 时长 / 进行时，
     * 这几样都不改变布局 —— 该走"就地换内容"，不该把窗口拆了重建。
     */
    @Test
    fun `只有 token、时长与进行时的字在变 —— 形状没变`() {
        val before = listOf(task(detail = "Reading A.kt", tokens = 12_400, durationMs = 8_000))
        val after = listOf(task(detail = "Reading B.kt", tokens = 47_500, durationMs = 19_000))

        assertTrue(after.rendersSameShapeAs(before), "这几样一变就拆窗重建，屏幕上就是闪")
    }

    @Test
    fun `进行时从无到有算形状变了 —— 那会多出一行`() {
        assertTrue(
            !listOf(task(detail = "Reading A.kt")).rendersSameShapeAs(listOf(task(detail = null))),
            "有无进行时会改高度，重建才是对的",
        )
    }

    @Test
    fun `统计从无到有算形状变了 —— 那会多出一条分隔线`() {
        assertTrue(
            !listOf(task(tokens = 1)).rendersSameShapeAs(listOf(task(tokens = 0))),
            "统计那一行是有无的问题，不是数值的问题",
        )
    }

    @Test
    fun `多一条、少一条、换一条都算形状变了`() {
        val one = listOf(task(id = "t1"))
        assertTrue(!(one + task(id = "t2")).rendersSameShapeAs(one), "多了一条")
        assertTrue(!emptyList<RunningTask>().rendersSameShapeAs(one), "少了一条")
        assertTrue(!listOf(task(label = "另一件事")).rendersSameShapeAs(one), "换了一条")
        assertTrue(!listOf(task(id = "t2")).rendersSameShapeAs(one), "换了 id（对不上子代理了）")
    }

    /** 树里有几张卡（在跑的子代理）。 */
    private fun countCards(root: Component): Int {
        var n = 0
        fun walk(c: Component) {
            if (c is AgentCard) n++
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return n
    }

    /** 点写着某段文字的那个可点标签（展开之后"第一个可点的"可能是「收起」那一行）。 */
    private fun clickLabelContaining(root: Component, text: String) {
        val label = findLabel(root, text) ?: throw AssertionError("找不到写着「$text」的可点标签")
        label.dispatchEvent(
            MouseEvent(
                label, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 3, 3, 1, false, MouseEvent.BUTTON1,
            )
        )
    }

    private fun findLabel(root: Component, text: String): JLabel? {
        // 可点标签自己挂了监听器（Swing 的事件不冒泡，文字那块得单独挂）
        if (root is JLabel && root.text.contains(text) && root.mouseListeners.isNotEmpty()) return root
        if (root is Container) {
            for (c in root.components) findLabel(c, text)?.let { return it }
        }
        return null
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
