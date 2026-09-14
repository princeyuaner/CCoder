package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 多题推进的状态机。全部与 Swing 无关 —— 界面那层只负责把 [AskFlow] 的
 * 判断画出来，判断本身在这里被钉死。
 */
class AskFlowTest {

    private fun request(json: String) = askRequestOf(JsonParser.parseString(json).asJsonObject)!!

    private val threeQuestions = """
    {
      "questions": [
        {"question":"第一题","header":"甲","options":[
          {"label":"a1","description":"d"},{"label":"a2","description":"d"}]},
        {"question":"第二题","header":"乙","options":[
          {"label":"b1","description":"d"},{"label":"b2","description":"d"}]},
        {"question":"第三题","header":"丙","options":[
          {"label":"c1","description":"d"},{"label":"c2","description":"d"}]}
      ]
    }
    """

    private val oneQuestion = """
    {"questions":[{"question":"唯一一题","header":"甲","options":[
      {"label":"a1","description":"d"},{"label":"a2","description":"d"}]}]}
    """

    @Test
    fun `只有一题时它既是第一题也是最后一题，答完就能提交`() {
        val flow = AskFlow(request(oneQuestion))

        assertEquals(1, flow.count)
        assertEquals(0, flow.index)
        assertTrue(flow.isLast, "一题的问卷里，第一题就是最后一题")
        assertFalse(flow.canAdvance, "还没答，按钮不该可点")

        flow.current.toggle("a1")

        assertTrue(flow.canAdvance)
        assertTrue(flow.submitCurrent(), "最后一题答完 = 该提交了")
    }

    @Test
    fun `三题逐题推进，只有最后一题返回提交`() {
        val flow = AskFlow(request(threeQuestions))

        flow.current.toggle("a1")
        assertFalse(flow.submitCurrent(), "第一题答完只是往下一题走")
        assertEquals(1, flow.index)

        flow.current.toggle("b2")
        assertFalse(flow.submitCurrent(), "第二题答完还不是提交")
        assertEquals(2, flow.index)
        assertTrue(flow.isLast)

        flow.current.toggle("c1")
        assertTrue(flow.submitCurrent(), "第三题答完才是提交")
        assertEquals(2, flow.index, "最后一题提交后不该再往前走")
    }

    /**
     * 这条守的是类注释里那条不变式：没答的题不会被跨过去。
     *
     * 少了这道闸，用户连点两下「下一题」就能把一道空白题留在身后，
     * 而答案回传时少一道题 —— 等于替用户答了。
     */
    @Test
    fun `当前题没答时不许往前走`() {
        val flow = AskFlow(request(threeQuestions))

        assertFalse(flow.submitCurrent())
        assertEquals(0, flow.index, "原地不动")

        flow.current.toggle("a1")
        flow.submitCurrent()
        assertEquals(1, flow.index)

        // 第二题没答就想走
        assertFalse(flow.submitCurrent())
        assertEquals(1, flow.index, "还是停在第二题")

        // 选中了再取消，等于没答 —— 同样不该放行
        flow.current.toggle("b1")
        flow.current.toggle("b1")
        assertFalse(flow.canAdvance)
        assertFalse(flow.submitCurrent())
        assertEquals(1, flow.index)
    }

    @Test
    fun `回上一题时作答还在，改完能再往下走`() {
        val flow = AskFlow(request(threeQuestions))

        flow.current.toggle("a2")
        flow.submitCurrent()

        assertTrue(flow.back(), "从第二题回得去第一题")
        assertEquals(0, flow.index)
        assertTrue(flow.current.isSelected("a2"), "第一题的选择还在")

        // 改主意：换成另一个选项
        flow.current.toggle("a1")
        assertFalse(flow.current.isSelected("a2"), "单选换选项是替换，不是叠加")
        assertFalse(flow.submitCurrent(), "改完还在第一题，继续往下走")
        assertEquals(1, flow.index)
    }

    /**
     * 回退发生在有「其它…」自由文本的题上时，那段文字也得跟着回来 ——
     * 它是 [QuestionState.custom]，不是输入框里的残留。
     */
    @Test
    fun `回上一题时其它项的自由文本也还在`() {
        val flow = AskFlow(request(threeQuestions))

        flow.current.toggle(OTHER_LABEL)
        flow.current.custom = "我自己写的"
        assertTrue(flow.canAdvance, "自由文本非空就算答了")
        flow.submitCurrent()

        flow.back()
        assertEquals(listOf("我自己写的"), flow.current.answer())
    }

    @Test
    fun `在第一题时回退是空操作`() {
        val flow = AskFlow(request(threeQuestions))

        assertFalse(flow.back())
        assertEquals(0, flow.index)
    }

    /**
     * 不变式：站在最后一题上 ⟹ 它**前面**每一题都答过（当前这题还没答，不算）。
     *
     * 这条不是"顺便测一下"：最后一题提交时回传的是 `picked()`，
     * 里面少一道题就等于替用户答了一道（[allAnswered] 是最后那道闸）。
     */
    @Test
    fun `站在最后一题时前面每一题都已经答过`() {
        val flow = AskFlow(request(threeQuestions))

        flow.current.toggle("a1")
        flow.submitCurrent()
        flow.current.toggle("b1")
        flow.submitCurrent()

        assertTrue(flow.isLast)
        assertFalse(allAnswered(flow.request, flow.picked()), "当前这题还没答，整条自然还没齐")

        val picked = flow.picked()
        val before = flow.request.questions.take(flow.index)
        assertTrue(
            before.all { q -> picked[q.question].orEmpty().any { it.isNotBlank() } },
            "走到最后一题时，前面答过的题一道都不能少：$picked",
        )

        // 答完最后一题才齐 —— 这才是提交前那道闸
        flow.current.toggle("c2")
        assertTrue(allAnswered(flow.request, flow.picked()))
    }

    @Test
    fun `多选题的多个选项一起回传`() {
        val flow = AskFlow(
            request(
                """
                {"questions":[{"question":"可多选","header":"甲","multiSelect":true,"options":[
                  {"label":"a1","description":"d"},{"label":"a2","description":"d"}]}]}
                """
            )
        )

        flow.current.toggle("a1")
        flow.current.toggle("a2")

        assertEquals(listOf("a1", "a2"), flow.current.answer())
        assertTrue(flow.submitCurrent())
    }
}
