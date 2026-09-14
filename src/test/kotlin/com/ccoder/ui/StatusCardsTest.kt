package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 四张卡各自的内容与形状。全是纯函数，不碰 Swing。 */
class StatusCardsTest {

    // ---- 连接：八种文字 → 四种色调 ----

    @Test
    fun `连接状态八种文字各自的色调`() {
        assertEquals(Tone.Ok, connectionCardOf("已连接").tone)
        assertEquals(Tone.Warn, connectionCardOf("正在启动…").tone)
        assertEquals(Tone.Warn, connectionCardOf("正在载入…").tone)
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

    @Test
    fun `忙时这张卡改说在干什么，色调统一成过渡态`() {
        // 用户原话：「我希望能实时显示当前在做什么，比如思考中，编辑文件，运行指令等等」。
        // 色调走 Warn 而不是 Ok —— 它是**过渡态**，绿色只留给"已连接"这种安定状态，
        // 与"正在启动…""正在载入…"同一族
        val card = activityCardOf(ACTIVITY_THINKING)

        assertEquals("连接", card.label, "格子身份不变，变的只是值")
        assertEquals(ACTIVITY_THINKING, card.value)
        assertEquals(Tone.Warn, card.tone)
        assertFalse(card.quiet, "正在干活不是「没数据」")
    }

    // ---- 上下文 ----

    @Test
    fun `还没收到用量时显示 0，不写「空闲」`() {
        // 「空闲」的意思是"没在跑"（子任务/子代理用它是对的），而上下文恰恰不是
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
