package com.ccoder.ui

import com.ccoder.sync.SyncRun
import com.ccoder.sync.SyncSnapshot
import com.ccoder.sync.SyncTexts
import com.ccoder.text.CcoderText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 五张卡各自的内容与形状。全是纯函数，不碰 Swing。 */
class StatusCardsTest {

    // ---- 连接：每一档自带色调 ----

    @Test
    fun `每一档连接状态都自带色调`() {
        // 这条原来是「八种文字 → 四种色调」的查表用例（`connectionTone("已连接")`）。
        // 改成枚举之后它的价值反而更高：**每一档**都被钉住，而新增一档忘了给色调
        // 是编译错误 —— 那种"翻译之后整体塌成灰色、零报错"的路已经被堵死。
        assertEquals(Tone.Ok, ConnectionState.Connected.tone)
        assertEquals(Tone.Warn, ConnectionState.Starting.tone)
        assertEquals(Tone.Warn, ConnectionState.Loading.tone)
        assertEquals(Tone.Idle, ConnectionState.Idle.tone)
        assertEquals(Tone.Danger, ConnectionState.StartFailed.tone)
        assertEquals(Tone.Danger, ConnectionState.Disconnected.tone)
        assertEquals(Tone.Danger, ConnectionState.RestoreFailed.tone)
    }

    @Test
    fun `连接结束这一档的色调是刻意保留的，不是漏了`() {
        // 「已结束」（sidecar 进程退了）今天落到灰 —— 它原来压根没写进那张映射表。
        // 两种读法都说得通：进程退出可能是用户自己停的（灰是对的），也可能是崩溃
        // （值得显出来，SidecarExitReport 存在的全部理由就是这个）。
        // 这次翻译**只把现状钉住**，改不改另开一条（设计稿口径 5）。
        assertEquals(Tone.Idle, ConnectionState.Ended.tone)
    }

    @Test
    fun `连接卡永远不空闲`() {
        // "未连接"是一种真实状态，不是"没数据"。它该有边框
        assertFalse(connectionCardOf(ConnectionState.Idle).quiet)
        assertFalse(connectionCardOf(ConnectionState.Connected).quiet)
    }

    // ---- 归属：这一格的状态是不是子代理的（视图据此画小角标）----

    @Test
    fun `动作词可以带子代理归属，连接状态永远不带`() {
        assertTrue(activityCardOf(Activity.Running, subagent = true).subagent)
        assertFalse(activityCardOf(Activity.Running).subagent, "不给 = 主线程在跑")
        assertTrue(waitingCardOf(12, subagent = true).subagent)
        assertFalse(waitingCardOf(12).subagent)
        // "已连接"是会话的状态，不是谁的
        assertFalse(connectionCardOf(ConnectionState.Connected).subagent)
    }

    @Test
    fun `带归属不影响卡面文字与色调 —— 它只是多画一个点`() {
        val plain = activityCardOf(Activity.Editing)
        val marked = activityCardOf(Activity.Editing, subagent = true)

        assertEquals(plain.value, marked.value)
        assertEquals(plain.label, marked.label)
        assertEquals(plain.tone, marked.tone)
    }

    @Test
    fun `忙时这张卡改说在干什么，色调统一成过渡态`() {
        // 用户原话：「我希望能实时显示当前在做什么，比如思考中，编辑文件，运行指令等等」。
        // 色调走 Warn 而不是 Ok —— 它是**过渡态**，绿色只留给"已连接"这种安定状态，
        // 与"启动中…""载入中…"同一族
        val card = activityCardOf(Activity.Thinking)

        assertEquals(CARD_LINK, card.label, "格子身份不变，变的只是值")
        // 用 text() 比而不是写死"思考中"：换语言跑（-PtestLang=en）时这条也该绿。
        // 真正的回归点是"值来自词表，不是那个键"——下半句就是钉这个的
        assertEquals(Activity.Thinking.text(), card.value)
        assertTrue(!card.value.startsWith("status."), "值是个裸键：词表里少了一条")
        assertEquals(Tone.Warn, card.tone)
        assertFalse(card.quiet, "正在干活不是「没数据」")
    }

    // ---- 等待响应那一格：秒数得动起来 ----

    @Test
    fun `等待响应时动作词上标签行、秒数上值行`() {
        // 2026-09-15 用户问"为什么调用工具后会突然卡几十秒"。那几十秒里四个字
        // 不动的「等待响应」分不出"它在走"还是"它挂了" —— 秒数要跳。
        //
        // 换行放的理由是宽度：这一格约 95px，「等待响应 12s」并排会被省略号截掉
        val card = waitingCardOf(12)

        assertEquals(Activity.Waiting.text(), card.label)
        assertTrue(!card.label.startsWith("status."), "标签是个裸键：词表里少了一条")
        assertEquals("12s", card.value)
        assertEquals(Tone.Warn, card.tone, "等待也是过渡态")
        assertFalse(card.quiet)
    }

    @Test
    fun `等待秒数两位数也放得下`() {
        // 实测这条路径上 P99 到过 38.8s、最长 89s —— 秒数会长到两位数。
        // 标签那行的上限按语言分：中文是 ≤4 个字（量出来的），英文那个数由
        // StatusCardsRenderProbe 在英文下量（设计稿 §8），这里给粗界。
        val card = waitingCardOf(89)
        val maxLabel = if (CcoderText.tag() == "zh") 6 else 12

        assertTrue(card.value.length <= 4, "值那一行放不下：${card.value}")
        assertTrue(card.label.length <= maxLabel, "标签那一行也窄：${card.label}")
    }

    @Test
    fun `秒数写法与工具卡一致`() {
        // 同一个东西在三个地方（状态卡、工具卡、思考块）长得一样，用户不用重新认
        assertEquals("5s", elapsedText(5))
        assertEquals("89s", elapsedText(89))
    }

    // ---- 上下文 ----

    @Test
    fun `还没收到用量时显示 0，不写「空闲」`() {
        // 「空闲」的意思是"没在跑"（任务列表/子代理用它是对的），而上下文恰恰不是
        // 这回事 —— 恢复一场长对话之后它一点都不空闲，我们只是还没测过。
        // 显示 0 至少是个能被纠正的数字
        val card = contextCardOf(null)

        assertEquals("0", card.value)
        assertNull(card.sub, "窗口都不知道，副值会拼成两遍同一个数")
        assertEquals(Indicator.None, card.indicator)
        assertFalse(card.quiet, "这是个读数，不是「这格没内容」")
    }

    @Test
    fun `有用量时给百分比与绝对数`() {
        val card = contextCardOf(ContextUsage(usedTokens = 12345, windowTokens = 200000))

        assertEquals("6%", card.value)
        assertEquals("12.3k / 200k", card.sub, "绝对数是现存信息，不能在拆卡时弄丢")
        assertEquals(Indicator.Meter(0.06), card.indicator)
        assertFalse(card.quiet)
    }

    @Test
    fun `窗口未知时不显示百分比，也不做除零`() {
        val card = contextCardOf(ContextUsage(usedTokens = 500, windowTokens = 0))

        assertEquals(500L.toString(), card.value, "没有窗口就只能给已用量本身")
        // 副值只在窗口已知时给：否则会拼出"500 / 500"这种把同一个数说两遍的样子
        assertNull(card.sub)
        assertEquals(Indicator.None, card.indicator)
    }

    @Test
    fun `上下文将满时转警示色`() {
        // 计划外新增，见计划文档顶部说明
        assertEquals(Tone.Idle, contextCardOf(ContextUsage(usedTokens = 1000, windowTokens = 200000)).tone)
        assertEquals(Tone.Warn, contextCardOf(ContextUsage(usedTokens = 150000, windowTokens = 200000)).tone)
        assertEquals(Tone.Danger, contextCardOf(ContextUsage(usedTokens = 190000, windowTokens = 200000)).tone)
    }

    // ---- 任务列表 ----

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

    // ---- 目录同步（2026-09-24）----

    @Test
    fun `同步卡的五个状态各有各的词与色调`() {
        // 词**必须**短到装进 58px：五张卡时值行只有那么宽，而 JLabel 撑破了会静默打
        // 省略号（`StatusCardsRowTest` 那条按文字量的用例守着长度，这条守"哪一档说哪句"）。
        // 词一律取自 `sync.state.*` —— 与设置页那行「状态」同一个来源，不再抄一份
        val tones = mapOf(
            SyncRun.RUNNING to Tone.Ok,
            SyncRun.FAILED to Tone.Danger,
            SyncRun.OCCUPIED to Tone.Warn,
            SyncRun.STOPPED to Tone.Warn,
            SyncRun.DISABLED to Tone.Idle,
        )
        for ((run, tone) in tones) {
            val card = syncCardOf(SyncSnapshot(run = run))
            assertEquals(CcoderText.text(SyncTexts.runKey(run)), card.value, "$run 那一档的词")
            assertEquals(tone, card.tone, "$run 那一档的色调")
        }
    }

    @Test
    fun `没开同步也不收边 —— "没开"是一种状态，不是没数据`() {
        // 同连接卡那条：这一格是"要不要开"的唯一入口，灰掉/收边会让人以为它坏了
        val card = syncCardOf(null)

        assertFalse(card.quiet, "没配置也不该收边")
        assertEquals(CARD_SYNC, card.label)
        assertEquals(CcoderText.text(SyncTexts.runKey(SyncRun.DISABLED)), card.value)
    }

    @Test
    fun `同步卡不画比例条 —— 一轮的总量跑完才知道`() {
        // 拿计划数当分母就是画一条一路涨到满的假进度（Indicator 那条"必须有真实分母"）
        assertEquals(Indicator.None, syncCardOf(SyncSnapshot(run = SyncRun.RUNNING)).indicator)
    }

    @Test
    fun `副值就是状态栏那句话 —— 同一件事不写两份`() {
        val snapshot = SyncSnapshot(run = SyncRun.RUNNING, lastRoundAtMs = 1_700_000_000_000L, copied = 3)

        assertEquals(syncStatusBarTooltip(snapshot), syncCardOf(snapshot).sub)
    }
}
