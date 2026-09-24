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
    fun `一张卡只有标题这一行（2026-09-24 第三次改版）`() {
        // 用户原话："也不用显示当前运行的工具，只需要显示标题即可" ——
        // 进行时（当前在跑的工具/这一步）与统计（token · 时长）都不画了。
        // 标题 = 类型 + 任务名，两样都有时中间**两个空格**（`joinToString("  ")`）。
        //
        // 喂的是"什么都报齐了"的一拍：哪天有人把其中一行加回来，这条就会红 ——
        // 它钉的就是"只画一行"这件事本身
        val running = tracker(
            started("t1", "找一下 token 刷新的调用点"),
            """{"type":"system","subtype":"task_progress","task_id":"t1",
                "description":"Reading TokenStore.kt","summary":"正在核对刷新路径",
                "usage":{"total_tokens":12400,"duration_ms":80000}}""",
        ).running

        assertEquals(
            listOf("explore  找一下 token 刷新的调用点"),
            labelsIn(buildRunningDetail(running, emptyList(), {}, onOpen = {  })),
        )
    }

    @Test
    fun `任务名与类型都没有时拿 id 顶（不至于画一张空卡）`() {
        // local_bash 那类后台命令没有 subagent_type，label 也可能缺 ——
        // 一张什么都不写的卡等于没卡
        val box = buildRunningDetail(
            listOf(RunningTask("call_12345678", null, null, null, 0, 0)),
            emptyList(),
            {},
            {},
        )

        assertEquals(listOf("call_123"), labelsIn(box), "实际：${labelsIn(box)}")
    }

    @Test
    fun `运行段空着时说实话，而不是给一个空框`() {
        // 卡上写"空闲"时不该弹得出来，但真弹出来了就得说实话。
        // 2026-09-24 起"空"只有一种：**没有在跑的子代理** —— 子代理清单不再被列出来，
        // 所以"清单也空"与"清单有内容"现在是同一条路（另一条用例喂了内容）
        assertEquals(
            listOf(CcoderText.text("transcript.detail.noRunningSubagents")),
            labelsIn(buildRunningDetail(emptyList(), emptyList(), {}, onOpen = {  })),
        )
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

    // ---- 只列在跑的（2026-09-24 用户："查看已结束的不要了"）----

    /**
     * 这条钉的是**两次改版合起来的结果**：同一批人从前被列了两遍（「运行中」+「全部
     * 子代理」），后来收成"卡 + 一条线"，最后用户说连那条线也不要了 ——
     * 现在**已结束的一个都不出现**。
     */
    @Test
    fun `只列在跑的，已结束的一个都不出现`() {
        val box = buildRunningDetail(
            listOf(RunningTask("call_1", "explore", "找调用点", null, 0, 0)),
            listOf(
                SubagentInfo("a1", "Explore", "找调用点", "call_1"),   // 正在跑
                SubagentInfo("a2", "Plan", "设计一下", "call_2"),      // 已结束
            ),
            {},
            onOpen = {  },
        )
        val labels = labelsIn(box)

        assertEquals(1, labels.count { it.contains("找调用点") }, "在跑的那条该只出现一次：$labels")
        assertTrue(labels.none { it.contains("设计一下") }, "已结束的被列出来了：$labels")
        assertTrue(labels.none { it.startsWith("✓") }, "不该再有记录行：$labels")
    }

    @Test
    fun `子代理清单不再被列出来 —— 它只剩"对上号"这一个用途`() {
        val box = buildRunningDetail(
            emptyList(),
            listOf(SubagentInfo("a1", "Explore", "找调用点", "call_9")),
            {},
            onOpen = {  },
        )

        val texts = labelsIn(box).joinToString("\n")
        assertTrue(!texts.contains("找调用点"), "空闲时不该列出任何子代理：$texts")
        assertTrue(
            texts.contains(CcoderText.text("transcript.detail.noRunningSubagents")),
            "只剩那句实话：$texts",
        )
    }

    // ---- 空闲那一版 ----

    /**
     * 用户问过："子代理都没了点开为什么还有内容。"
     *
     * 现在答得最干脆：**没有内容**，只有一句实话。那条"看已结束的 N 个 ›"连同它
     * 后面的记录在 2026-09-24 被整个删掉了（代价：已结束的子代理从此回看不了转写）。
     */
    @Test
    fun `空闲时只有一句实话，别的什么都没有`() {
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
        assertEquals(
            listOf(CcoderText.text("transcript.detail.noRunningSubagents")),
            texts,
            "空闲时该只剩那一条说明：$texts",
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
     * 这三样现在**根本不画**（见上面那条"只有标题这一行"），所以形状必然没变 ——
     * 该走"就地换内容"，不该把窗口拆了重建。
     */
    @Test
    fun `token、时长与进行时在变 —— 形状没变`() {
        val before = listOf(task(detail = "Reading A.kt", tokens = 12_400, durationMs = 8_000))
        val after = listOf(task(detail = "Reading B.kt", tokens = 47_500, durationMs = 19_000))

        assertTrue(after.rendersSameShapeAs(before), "这几样一变就拆窗重建，屏幕上就是闪")
        assertTrue(before.rendersSameShapeAs(after), "反过来也得一样（判等要对称）")
    }

    @Test
    fun `多一条、少一条、换一条都算形状变了`() {
        val one = listOf(task(id = "t1"))
        assertTrue(!(one + task(id = "t2")).rendersSameShapeAs(one), "多了一条")
        assertTrue(!emptyList<RunningTask>().rendersSameShapeAs(one), "少了一条")
        assertTrue(!listOf(task(label = "另一件事")).rendersSameShapeAs(one), "换了任务名")
        assertTrue(!listOf(task(kind = "Plan")).rendersSameShapeAs(one), "换了类型（标题上那个词）")
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
