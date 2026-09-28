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
import javax.swing.JPanel

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

    /**
     * 生产签名有七个参数，测试里绝大多数只关心其中一两样 —— 这一层把其余填成默认。
     *
     * 2026-09-28 第四次改版：`buildRunningDetail` 变成了 [buildTasksDetail]
     * （在跑 / 暂停 / 刚结束三段）。这里跟着换名，同时也把"默认空"写在一处。
     */
    private fun panel(
        running: List<RunningTask> = emptyList(),
        finished: List<FinishedTask> = emptyList(),
        ambient: Int = 0,
        subagents: List<SubagentInfo> = emptyList(),
        onStop: (String) -> Unit = {},
        onOpenTranscript: (SubagentInfo) -> Unit = {},
        onOpenOutput: (FinishedTask) -> Unit = {},
    ): JPanel = buildTasksDetail(running, finished, ambient, subagents, onStop, onOpenTranscript, onOpenOutput)

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

        assertTrue(CcoderText.text("status.card.tasks") in labels, "要有段标题")
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
    fun `在跑那一段有小标题与计数（2026-09-28 第四次改版）`() {
        // 第三次改版时这条小标题被删过（"卡本身就是在跑"）—— 第四次三段并列，
        // 没有标题就分不清哪几行是"在跑"、哪几行是"刚结束"
        val running = tracker(started("t1", "查找 sidecar 启动路径"), started("t2", "核对 SDK 类型")).running
        val labels = labelsIn(panel(running = running))

        assertTrue(CcoderText.text("transcript.detail.section.running") in labels, "要有段标题：$labels")
        assertTrue("2" in labels, "段标题右端要有条数：$labels")
        assertTrue(labels.any { it.contains("查找 sidecar 启动路径") }, "实际：$labels")
        assertTrue(labels.any { it.contains("核对 SDK 类型") }, "实际：$labels")
    }

    @Test
    fun `在跑那张卡是两排 —— 名字一排，进行时与读数一排`() {
        // 2026-09-24 第三次改版把下排删过（用户原话："也不用显示当前运行的工具，
        // 只需要显示标题即可"）；2026-09-28 第四次按选型稿 乙 加回来 —— 面板一半的
        // 价值就在这两排读数上。**哪天觉得吵，该删的也是下排**，不是整段。
        val running = tracker(
            started("t1", "找一下 token 刷新的调用点"),
            """{"type":"system","subtype":"task_progress","task_id":"t1",
                "description":"Reading TokenStore.kt","summary":"正在核对刷新路径",
                "last_tool_name":"Read",
                "usage":{"total_tokens":12400,"tool_uses":7,"duration_ms":80000}}""",
        ).running
        val labels = labelsIn(panel(running = running))

        assertTrue(labels.any { it == "explore  找一下 token 刷新的调用点" }, "上排是类型 + 名字：$labels")
        assertTrue(labels.any { it == "正在核对刷新路径" }, "下排是进行时（summary 优先）：$labels")
        assertTrue(labels.any { it == "Read" }, "最后用到的那个工具：$labels")
        assertTrue(
            labels.any { it.contains("80s") && it.contains("7 工具") && it.contains("12.4k") },
            "读数那串（时长 · 工具数 · token）：$labels",
        )
    }

    @Test
    fun `只有 description 没有 summary 时，下排用 description`() {
        // task_progress 的两级：summary 要 CLI 开着 agentProgressSummaries（我们开着），
        // description 每一步都来且不要钱 —— 短任务只有后者
        val running = tracker(
            started("t1", "跑测试"),
            """{"type":"system","subtype":"task_progress","task_id":"t1",
                "description":"Reading TokenStore.kt","usage":{"total_tokens":1,"duration_ms":1}}""",
        ).running

        val labels = labelsIn(panel(running = running))
        assertTrue(labels.any { it == "Reading TokenStore.kt" }, "实际：$labels")
    }

    @Test
    fun `任务名与类型都没有时拿 id 顶（不至于画一张空卡）`() {
        // local_bash 那类后台命令没有 subagent_type，label 也可能缺 ——
        // 一张什么都不写的卡等于没卡
        val box = panel(running = listOf(RunningTask("call_12345678", null, null, null, 0, 0)))

        val labels = labelsIn(box)
        assertTrue(labels.any { it == "call_123" }, "名字该退到 id 前 8 位：$labels")
        assertEquals(1, countCards(box), "仍然是一张卡：$labels")
    }

    @Test
    fun `空着时说实话，而不是给一个空框`() {
        // 卡上写"空闲"时不该弹得出来，但真弹出来了就得说实话
        assertEquals(
            listOf(CcoderText.text("transcript.detail.noTasks")),
            labelsIn(panel()),
        )
    }

    // ---- 两段分家 ----

    @Test
    fun `点哪张卡只看哪一段，不再有合并浮层`() {
        // 旧版是一个浮层里两段。拆卡之后点哪张卡就该只看哪一段 ——
        // 点"任务列表"却弹出"运行中"会让人以为两边是一回事
        val t = tracker(todosLabel("甲" to "pending"), started("t1", "甲"))

        val todoLabels = labelsIn(buildTodoDetail(t.todos!!))
        val runningLabels = labelsIn(panel(running = t.running))

        assertTrue(CcoderText.text("status.card.tasks") in todoLabels)
        assertTrue(CcoderText.text("transcript.detail.section.running") !in todoLabels,
            "清单浮层里不该出现运行段：$todoLabels")
        assertTrue(CcoderText.text("status.card.tasks") !in runningLabels,
            "任务浮层里不该出现清单段：$runningLabels")
    }

    // ---- 磁盘记录不列 / 刚结束照留 ----

    /**
     * 磁盘上那份"全部子代理"记录**列不出来**（2026-09-24 用户："查看已结束的不要了"）——
     * [SubagentInfo] 从此只剩"对上号"这一个用途。
     *
     * 但**刚结束的任务**照留（2026-09-28 第四次改版）：它在事件流里，回答的是"刚才那一下
     * 怎么了"；磁盘那份是没有时间线的历史列表。两件事不一样，别顺手把后一条也删了。
     */
    @Test
    fun `磁盘上那份子代理记录不会被列出来`() {
        val box = panel(
            running = listOf(RunningTask("call_1", "explore", "找调用点", null, 0, 0)),
            subagents = listOf(
                SubagentInfo("a1", "Explore", "找调用点", "call_1"),   // 正在跑
                SubagentInfo("a2", "Plan", "设计一下", "call_2"),      // 已结束
            ),
        )
        val labels = labelsIn(box)

        assertEquals(1, labels.count { it.contains("找调用点") }, "在跑的那条该只出现一次：$labels")
        assertTrue(labels.none { it.contains("设计一下") }, "磁盘记录被列出来了：$labels")
    }

    @Test
    fun `刚结束的留最近两条 —— 带结局、读数与「看输出」`() {
        val opened = mutableListOf<String>()
        val t = tracker(
            started("t1", "校验 plugin.xml 的十个通道"),
            """{"type":"system","subtype":"task_notification","task_id":"t1","status":"completed",
                "summary":"10 条通道全绿","output_file":"C:/tmp/out.txt",
                "usage":{"total_tokens":31000,"tool_uses":38,"duration_ms":128000}}""",
        )
        val box = panel(finished = t.recentFinished, onOpenOutput = { opened += it.id })

        val labels = labelsIn(box)
        assertTrue(CcoderText.text("transcript.detail.section.finished") in labels, "要有刚结束那段：$labels")
        assertTrue(labels.any { it == "✓" }, "结局记号：$labels")
        assertTrue(labels.any { it.contains("校验 plugin.xml") }, "名字：$labels")
        assertTrue(labels.any { it == "10 条通道全绿" }, "一行结局：$labels")
        assertTrue(labels.any { it.contains("38 工具") && it.contains("31k") }, "读数：$labels")

        val output = linkButtons(box).firstOrNull() ?: error("没有看输出那颗按钮：$labels")
        assertEquals(CcoderText.text("transcript.detail.viewOutput"), output.text)
        output.doClick()
        assertEquals(listOf("t1"), opened, "点它要报出是哪一条")
    }

    @Test
    fun `失败的刚结束行把原因画出来，没有输出文件就不给「看输出」`() {
        // 通知里没有 error（那一条来自 task_updated）；output_file 也可能缺
        val t = tracker(
            started("t1", "跑探针"),
            """{"type":"system","subtype":"task_updated","task_id":"t1",
                "patch":{"status":"failed","error":"Cannot find module x"}}""",
        )
        val box = panel(finished = t.recentFinished)

        val labels = labelsIn(box)
        assertTrue(labels.any { it == "✕" }, "失败的记号：$labels")
        assertTrue(labels.any { it == "Cannot find module x" }, "失败原因要画出来：$labels")
        assertTrue(linkButtons(box).isEmpty(), "没有 output_file 就不该有看输出：$labels")
    }

    @Test
    fun `暂停的任务单起一段，不与在跑的混在一起`() {
        val t = tracker(
            started("t1", "甲"),
            started("t2", "乙"),
            """{"type":"system","subtype":"task_updated","task_id":"t2","patch":{"status":"paused"}}""",
        )
        val labels = labelsIn(panel(running = t.running))

        assertTrue(CcoderText.text("transcript.detail.section.running") in labels, "在跑那段：$labels")
        assertTrue(CcoderText.text("transcript.detail.section.paused") in labels, "暂停那段：$labels")
        assertTrue(labels.any { it == CcoderText.text("transcript.detail.paused") }, "行上要写出来：$labels")
    }

    @Test
    fun `管家任务折成一句，不逐条画卡`() {
        // SDK 说它们不是"活动"，不该进活动指示；但"可以出现在任务面板里" ——
        // 于是只贡献一句计数
        val t = tracker(
            started("t1", "甲"),
            """{"type":"system","subtype":"task_started","task_id":"w1","description":"监视","ambient":true}""",
            """{"type":"system","subtype":"task_started","task_id":"w2","description":"看盘","ambient":true}""",
        )
        val box = panel(running = t.running, ambient = t.ambientCount)
        val labels = labelsIn(box)

        assertTrue(labels.any { it == CcoderText.text("transcript.detail.ambient", 2) }, "要有那句计数：$labels")
        assertEquals(1, countCards(box), "管家任务不画卡")
    }

    @Test
    fun `子代理清单不再被列出来 —— 它只剩"对上号"这一个用途`() {
        val box = panel(subagents = listOf(SubagentInfo("a1", "Explore", "找调用点", "call_9")))

        val texts = labelsIn(box).joinToString("\n")
        assertTrue(!texts.contains("找调用点"), "空闲时不该列出任何子代理：$texts")
        assertTrue(
            texts.contains(CcoderText.text("transcript.detail.noTasks")),
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
        val box = panel(
            subagents = listOf(
                SubagentInfo("a1", "Explore", "找调用点", "call_1"),
                SubagentInfo("a2", "Plan", "设计一下", "call_2"),
            ),
        )

        val texts = labelsIn(box)
        assertEquals(
            listOf(CcoderText.text("transcript.detail.noTasks")),
            texts,
            "空闲时该只剩那一条说明：$texts",
        )
    }

    @Test
    fun `在跑的超过上限时只画几张，剩下的说出来`() {
        val running = (1..MAX_AGENT_CARDS + 2).map {
            RunningTask("t$it", "explore", "任务 $it", null, 0, 0)
        }
        val box = panel(running = running)

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
        val box = panel(running = running)

        assertEquals(running.size, countCards(box), "有几个在跑就该有几张卡")
    }

    @Test
    fun `对不上子代理的运行中任务不可点开转写（终止钮是另一回事）`() {
        // 后台命令（local_bash 那种）没有子代理转写 —— 给它一个"点开转写"
        // 的外观是骗人。终止钮不受这条管：停的是任务，任何任务都停得掉
        val box = panel(running = listOf(RunningTask("call_x", "local_bash", "跑测试", null, 0, 0)))

        val stop = findStop(box)
        assertTrue(stop != null, "任何在跑的任务都该有终止钮")
        assertEquals(stop, findClickable(box), "除了终止钮，这里不该有别的可点组件")
    }

    @Test
    fun `对得上的运行中任务可以点开`() {
        val picked = mutableListOf<String>()
        val box = panel(
            running = listOf(RunningTask("call_9", "explore", "找调用点", null, 0, 0)),
            subagents = listOf(SubagentInfo("a1", "Explore", "找调用点", "call_9")),
            onOpenTranscript = { picked += it.agentId },
        )

        clickFirstClickable(box)

        assertEquals(listOf("a1"), picked, "任务的 id 就是 tool_use id，靠它对上子代理")
    }

    // ---- 终止（2026-09-18）----

    @Test
    fun `运行中那行右端有终止钮，点了报出 task id`() {
        val stopped = mutableListOf<String>()
        val box = panel(
            running = listOf(RunningTask("t1", "explore", "找调用点", null, 0, 0)),
            onStop = { stopped += it },
        )

        clickStop(box)

        assertEquals(listOf("t1"), stopped)
    }

    @Test
    fun `没有对应子代理的任务也有终止钮 —— 停的是任务，不是「子代理」这个身份`() {
        val stopped = mutableListOf<String>()
        val box = panel(
            running = listOf(RunningTask("t9", "local_bash", "跑测试", null, 0, 0)),
            onStop = { stopped += it },
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
        val box = panel(
            running = listOf(RunningTask("call_9", "explore", "找调用点", null, 0, 0)),
            subagents = listOf(SubagentInfo("a1", "Explore", "找调用点", "call_9")),
            onStop = { stopped += it },
            onOpenTranscript = { opened += it.agentId },
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

    /** 树里那几颗小按钮（LinkButton 是 JButton，"看输出"/"看转写"用它）。 */
    private fun linkButtons(root: Component): List<LinkButton> {
        val out = mutableListOf<LinkButton>()
        fun walk(c: Component) {
            if (c is LinkButton) out += c
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return out
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
