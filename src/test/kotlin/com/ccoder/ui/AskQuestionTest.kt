package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 解析 `AskUserQuestion` 的入参。
 *
 * 形状照抄 SDK 的 `AskUserQuestionInput`（sdk-tools.d.ts:1051）。
 *
 * 这里的原则是**输入宽容、判定严格**：字段缺了给合理默认值（header 缺了就
 * 不显示芯片，不至于整条不可用）；但一旦有东西**没法渲染**，就整个返回 null
 * 让调用方退回通用卡片 —— 半截的提问卡片比没有更糟，因为用户会以为
 * 剩下的问题不存在。
 */
class AskQuestionTest {

    private fun input(json: String) = JsonParser.parseString(json).asJsonObject

    private val twoQuestions = """
    {
      "questions": [
        {
          "question": "你希望我接下来做什么？",
          "header": "要做什么",
          "multiSelect": false,
          "options": [
            {"label":"继续未提交的改动","description":"接着往下做。"},
            {"label":"审查当前 diff","description":"做一次结构化审查。"}
          ]
        },
        {
          "question": "热切要不要写回长期设置？",
          "header": "作用范围",
          "multiSelect": true,
          "options": [
            {"label":"只影响本次会话","description":"下次启动还是原来的。"},
            {"label":"写回设置","description":"下次启动还用这个。"}
          ]
        }
      ]
    }
    """

    // ---- 正常解析 ----

    @Test
    fun `解析出题目、芯片、选项与说明`() {
        val req = askRequestOf(input(twoQuestions))!!
        assertEquals(2, req.questions.size)

        val q = req.questions[0]
        assertEquals("你希望我接下来做什么？", q.question)
        assertEquals("要做什么", q.header)
        assertFalse(q.multiSelect)
        assertEquals(2, q.options.size)
        assertEquals("继续未提交的改动", q.options[0].label)
        assertEquals("接着往下做。", q.options[0].description)
    }

    @Test
    fun `multiSelect 逐题解析，不是整条一个`() {
        val req = askRequestOf(input(twoQuestions))!!
        assertFalse(req.questions[0].multiSelect, "第一题是单选")
        assertTrue(req.questions[1].multiSelect, "第二题是多选")
    }

    @Test
    fun `preview 可选，没有就是 null`() {
        val req = askRequestOf(
            input("""{"questions":[{"question":"q","header":"h","options":[
              {"label":"a","description":"d","preview":"代码片段"},
              {"label":"b","description":"d"}]}]}""")
        )!!
        assertEquals("代码片段", req.questions[0].options[0].preview)
        assertNull(req.questions[0].options[1].preview)
    }

    // ---- 缺字段：给默认值，不整条失败 ----

    @Test
    fun `缺 header 时给空串而不是失败`() {
        // 芯片没有就不显示，问题本身还是能答的
        val req = askRequestOf(
            input("""{"questions":[{"question":"q","options":[
              {"label":"a","description":"d"},{"label":"b","description":"d"}]}]}""")
        )!!
        assertEquals("", req.questions[0].header)
    }

    @Test
    fun `缺 multiSelect 时按单选`() {
        val req = askRequestOf(
            input("""{"questions":[{"question":"q","header":"h","options":[
              {"label":"a","description":"d"},{"label":"b","description":"d"}]}]}""")
        )!!
        assertFalse(req.questions[0].multiSelect)
    }

    @Test
    fun `缺 description 时给空串`() {
        val req = askRequestOf(
            input("""{"questions":[{"question":"q","header":"h","options":[
              {"label":"a"},{"label":"b"}]}]}""")
        )!!
        assertEquals("", req.questions[0].options[0].description)
    }

    // ---- 没法渲染：整个 null，退回通用卡片 ----

    @Test
    fun `没有 questions 时返回 null`() {
        assertNull(askRequestOf(input("""{}""")))
        assertNull(askRequestOf(input("""{"questions":[]}""")))
        assertNull(askRequestOf(input("""{"questions":"不是数组"}""")))
    }

    @Test
    fun `某题没有题目文本时整条返回 null`() {
        // 半截的提问卡片比没有更糟 —— 用户会以为剩下的问题不存在
        assertNull(
            askRequestOf(
                input("""{"questions":[
                  {"question":"完整的","header":"h","options":[{"label":"a"},{"label":"b"}]},
                  {"header":"h","options":[{"label":"a"},{"label":"b"}]}
                ]}""")
            )
        )
    }

    @Test
    fun `题目是空白也算没有`() {
        assertNull(
            askRequestOf(
                input("""{"questions":[{"question":"   ","options":[{"label":"a"},{"label":"b"}]}]}""")
            )
        )
    }

    @Test
    fun `某题一个选项都没有时返回 null`() {
        assertNull(
            askRequestOf(input("""{"questions":[{"question":"q","header":"h","options":[]}]}"""))
        )
    }

    @Test
    fun `没有 label 的选项被跳过`() {
        val req = askRequestOf(
            input("""{"questions":[{"question":"q","header":"h","options":[
              {"label":"a","description":"d"},
              {"description":"没有 label，回传时无从表示"},
              {"label":"b","description":"d"}]}]}""")
        )!!
        assertEquals(listOf("a", "b"), req.questions[0].options.map { it.label })
    }

    @Test
    fun `选项全都没有 label 时那题不可渲染`() {
        assertNull(
            askRequestOf(
                input("""{"questions":[{"question":"q","header":"h",
                  "options":[{"description":"d"},{"description":"d"}]}]}""")
            )
        )
    }

    @Test
    fun `非对象的元素被跳过而不是抛错`() {
        assertNull(askRequestOf(input("""{"questions":["字符串", 42]}""")))
        assertNull(askRequestOf(input("""{"questions":[{"question":"q","options":["x"]}]}""")))
    }

    // ---- 答案的组装 ----

    @Test
    fun `答案按题目文本为键`() {
        val req = askRequestOf(input(twoQuestions))!!
        val answers = answersFor(
            req,
            mapOf(
                "你希望我接下来做什么？" to listOf("审查当前 diff"),
                "热切要不要写回长期设置？" to listOf("写回设置"),
            ),
        )

        assertEquals("审查当前 diff", answers.get("你希望我接下来做什么？").asString)
        assertEquals("写回设置", answers.get("热切要不要写回长期设置？").asString)
    }

    @Test
    fun `多选把若干 label 连成一个字符串，逗号分隔`() {
        // SDK 的 answers 值是 string，不是数组，而且**指明了连接符**：
        // answers: { [k: string]: string }（sdk-tools.d.ts:3861）+
        // "multi-select answers are comma-separated"（sdk-tools.d.ts:3873）。
        // 逐字断言而不是 contains —— 连接符本身就是这条契约的一部分
        val req = askRequestOf(input(twoQuestions))!!
        val answers = answersFor(req, mapOf("热切要不要写回长期设置？" to listOf("只影响本次会话", "写回设置")))

        var n = 0
        req.questions.forEach { if (answers.has(it.question)) n++ }
        assertEquals(1, n, "只有答过的那一题该出现")
        assertEquals("只影响本次会话, 写回设置", answers.get("热切要不要写回长期设置？").asString)
    }

    @Test
    fun `没有答案的题目不出现在 answers 里`() {
        val req = askRequestOf(input(twoQuestions))!!
        val answers = answersFor(req, mapOf("你希望我接下来做什么？" to listOf("审查当前 diff")))
        assertFalse(answers.has("热切要不要写回长期设置？"))
    }

    // ---- 提交门控 ----

    @Test
    fun `全部答完才能提交`() {
        val req = askRequestOf(input(twoQuestions))!!

        assertFalse(allAnswered(req, emptyMap()), "一题没答")
        assertFalse(
            allAnswered(req, mapOf("你希望我接下来做什么？" to listOf("审查当前 diff"))),
            "只答了一题，还有一题空着",
        )
        assertTrue(
            allAnswered(
                req,
                mapOf(
                    "你希望我接下来做什么？" to listOf("审查当前 diff"),
                    "热切要不要写回长期设置？" to listOf("写回设置"),
                ),
            ),
            "两题都答了",
        )
    }

    @Test
    fun `答了空列表不算答`() {
        val req = askRequestOf(input(twoQuestions))!!
        assertFalse(allAnswered(req, mapOf("你希望我接下来做什么？" to emptyList())))
    }

    @Test
    fun `自由文本也算答`() {
        val req = askRequestOf(input(twoQuestions))!!
        val picked = mapOf(
            "你希望我接下来做什么？" to listOf("  先别动，我想想  "),
            "热切要不要写回长期设置？" to listOf("写回设置"),
        )
        assertTrue(allAnswered(req, picked), "选了「其它…」并打了字就该能提交")
        assertEquals(
            "先别动，我想想",
            answersFor(req, picked).get("你希望我接下来做什么？").asString,
            "自由文本要 trim",
        )
    }

    // ---- 作答状态（与 Swing 无关，纯模型）----

    private fun req() = askRequestOf(input(twoQuestions))!!

    @Test
    fun `单选：选了另一个，前一个自动取消`() {
        val s = QuestionState(req().questions[0])
        s.toggle("继续未提交的改动")
        s.toggle("审查当前 diff")

        assertFalse(s.isSelected("继续未提交的改动"))
        assertTrue(s.isSelected("审查当前 diff"))
        assertEquals(listOf("审查当前 diff"), s.answer())
    }

    @Test
    fun `多选：可以同时选中多个`() {
        val s = QuestionState(req().questions[1])
        s.toggle("只影响本次会话")
        s.toggle("写回设置")

        assertEquals(listOf("只影响本次会话", "写回设置"), s.answer())
    }

    @Test
    fun `再点一次取消选中`() {
        val s = QuestionState(req().questions[0])
        s.toggle("审查当前 diff")
        s.toggle("审查当前 diff")

        assertFalse(s.answered)
    }

    @Test
    fun `选了「其它…」但没打字，不算答`() {
        // 否则会提交一个空字符串当答案，模型那边收到的是"用户选了空"
        val s = QuestionState(req().questions[0])
        s.toggle(OTHER_LABEL)

        assertFalse(s.answered, "光点「其它…」没打字，不能算答了")
    }

    @Test
    fun `「其它…」打上字之后，答案是那段文字而不是「其它…」`() {
        val s = QuestionState(req().questions[0])
        s.toggle(OTHER_LABEL)
        s.custom = "  先别动，我想想  "

        assertTrue(s.answered)
        assertEquals(listOf("先别动，我想想"), s.answer())
    }

    @Test
    fun `单选下从「其它…」切到普通选项，「其它」自动让位`() {
        val s = QuestionState(req().questions[0])
        s.toggle(OTHER_LABEL)
        s.custom = "随便写点什么"
        s.toggle("审查当前 diff")

        assertFalse(s.isSelected(OTHER_LABEL))
        assertEquals(listOf("审查当前 diff"), s.answer())
    }

    @Test
    fun `多选下「其它…」能和普通选项并存`() {
        val s = QuestionState(req().questions[1])
        s.toggle("只影响本次会话")
        s.toggle(OTHER_LABEL)
        s.custom = "另外再说一点"

        assertEquals(listOf("只影响本次会话", "另外再说一点"), s.answer())
    }

    @Test
    fun `整张卡片：全部答完才算完`() {
        val state = AskState(req())
        assertFalse(state.complete)

        state.states[0].toggle("审查当前 diff")
        assertFalse(state.complete, "还有一题空着")

        state.states[1].toggle("写回设置")
        assertTrue(state.complete)
    }

    // ---- 并进入参 ----

    @Test
    fun `updatedInput 保留原入参再加 answers`() {
        // SDK 是把 updatedInput **整个**当成这个工具的新入参，
        // 所以只送 answers 会丢掉 questions —— 工具那边直接不认
        val original = input(
            """{"questions":[{"question":"q","header":"h","options":[{"label":"a"}]}],
                "别的字段":1}"""
        )
        val request = askRequestOf(original)!!
        val out = updatedInputFor(original, request, mapOf("q" to listOf("a")))

        assertEquals(1, out.get("别的字段").asInt, "原有字段必须原样保留")
        assertEquals(1, out.getAsJsonArray("questions").size(), "questions 也得留着")
        assertEquals("a", out.getAsJsonObject("answers").get("q").asString)
    }

    @Test
    fun `updatedInput 不改动传进来的那个对象`() {
        // 原对象是 sidecar 消息里的，别的地方可能还在读它
        val original = input(
            """{"questions":[{"question":"q","header":"h","options":[{"label":"a"}]}]}"""
        )
        val request = askRequestOf(original)!!
        updatedInputFor(original, request, mapOf("q" to listOf("a")))
        assertFalse(original.has("answers"), "原对象被就地改了")
    }

    @Test
    fun `整张卡片：picked 按题目文本给出答案`() {
        val state = AskState(req())
        state.states[0].toggle("审查当前 diff")
        state.states[1].toggle("只影响本次会话")
        state.states[1].toggle("写回设置")

        val picked = state.picked()
        assertEquals(listOf("审查当前 diff"), picked["你希望我接下来做什么？"])
        assertEquals(listOf("只影响本次会话", "写回设置"), picked["热切要不要写回长期设置？"])
    }
}
