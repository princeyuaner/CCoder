package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JLabel

/**
 * 提问卡片。
 *
 * 它是**安全控件**的近亲：递交的答案会被当成"用户的选择"喂回模型，
 * 所以这里盯得最紧的两件事是「没答完不能提交」和「送出去的确实是选中的那个」。
 */
class AskQuestionCardTest {

    private fun request(json: String) =
        askRequestOf(JsonParser.parseString(json).asJsonObject)!!

    private val twoQuestions = request(
        """
        {"questions":[
          {"question":"你希望我接下来做什么？","header":"要做什么","options":[
            {"label":"继续未提交的改动","description":"接着往下做。"},
            {"label":"审查当前 diff","description":"做一次结构化审查。"}
          ]},
          {"question":"热切要不要写回长期设置？","header":"作用范围","multiSelect":true,"options":[
            {"label":"只影响本次会话","description":"下次启动还是原来的。"},
            {"label":"写回设置","description":"下次启动还用这个。"}
          ]}
        ]}
        """
    )

    private fun card(
        req: AskRequest = twoQuestions,
        onSubmit: (Picked) -> Unit = {},
        onDeny: () -> Unit = {},
    ) = AskQuestionCard(req, onSubmit, onDeny)

    /** 自绘的选项行认的是合成 MouseEvent。 */
    private fun click(c: Component) {
        c.dispatchEvent(
            MouseEvent(c, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 3, 3, 1, false, MouseEvent.BUTTON1)
        )
    }

    /**
     * `JButton` **不认**合成的 MouseEvent —— `AbstractButton` 的事件挂在
     * 它的 model 上，只能走 `doClick()`。
     */
    private fun press(button: javax.swing.JButton) = button.doClick()

    private fun allLabels(root: Container): List<JLabel> {
        val out = mutableListOf<JLabel>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JLabel) out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    /** 两道题可能有同名选项（都有「其它…」），用 occurrence 区分。 */
    private fun clickOption(c: AskQuestionCard, text: String, occurrence: Int = 0) {
        val matches = allLabels(c).filter { it.text.contains(text) }
        val target = matches.getOrNull(occurrence)
            ?: error("找不到第 ${occurrence + 1} 个「$text」，树里有：${allLabels(c).map { it.text }}")
        click(target)
    }

    // ---- 渲染 ----

    @Test
    fun `把每道题和每个选项都渲染出来`() {
        val texts = allLabels(card()).map { it.text }.joinToString("\n")

        assertTrue(texts.contains("你希望我接下来做什么？"), texts)
        assertTrue(texts.contains("热切要不要写回长期设置？"), texts)
        assertTrue(texts.contains("继续未提交的改动"), texts)
        assertTrue(texts.contains("写回设置"), texts)
        // 说明是 A 方案的全部卖点，不能只渲染标题
        assertTrue(texts.contains("接着往下做。"), "选项说明没渲染：$texts")
    }

    @Test
    fun `「其它…」由界面自己补上`() {
        // SDK 要求宿主提供它（sdk-tools.d.ts:1070 的 options 注释）
        val texts = allLabels(card()).map { it.text }
        assertEquals(2, texts.count { it.contains(OTHER_LABEL) }, "两道题各该有一个：$texts")
    }

    @Test
    fun `题目和选项都靠左，不被挤到中间`() {
        // BoxLayout 不是按每个子项各摆各的：它取所有子项里
        // `alignmentX × 宽度` 的最大值当**公共对齐点**，再按各自的 alignmentX 摆放。
        //
        // 竖直 Filler 和选项行默认是 CENTER(0.5)，宽度又是整行 —— 于是基准被
        // 推到中间，左侧那些标签（芯片、题目）全被带着右移。实测两者都落在
        // x=148，而容器只有 406 宽，看起来就是"全都居中了"。
        //
        // 所以这条必须**布局之后看实际坐标**。只测 alignmentX 是测不出来的：
        // 属性本来就是对的（0.0），错的是 BoxLayout 拿它算出来的位置。
        val c = card()
        layOut(c, 430)

        val chip = allLabels(c).first { it.text == "要做什么" }
        val question = allLabels(c).first { it.text.contains("你希望我接下来做什么") }

        assertTrue(chip.x < 30, "芯片被推到了 x=${chip.x}")
        assertTrue(question.x < 30, "题目被推到了 x=${question.x}")
    }

    @Test
    fun `选中之后标签不会被压窄到显示省略号`() {
        // 渲染出来看到的是「✓ 审查当前 …」—— 少了一个字，还被加了省略号。
        // 根因是布局拿的还是选中**之前**的宽度（选中时前面多了个 ✓）。
        //
        // getText() 这时仍然是完整的（截断只发生在绘制时），所以只能比宽度：
        // 标签拿到手的宽度小于它需要的宽度，就会画成省略号
        val c = card()
        layOut(c, 430)
        clickOption(c, "审查当前 diff")
        layOut(c, 430)

        val label = allLabels(c).first { it.text.contains("审查当前 diff") }
        assertTrue(
            label.width >= label.preferredSize.width,
            "标签宽 ${label.width}，但需要 ${label.preferredSize.width} —— 会被截成省略号：${label.text}",
        )
    }

    @Test
    fun `选中「其它…」之后输入框真的占到位置`() {
        // isVisible 变 true 不等于它能被看见 —— 布局还得真的给它分配高度。
        // 渲染出来那张图上，第一题的勾打上了、输入框却没露头
        val c = card()
        layOut(c, 430)
        clickOption(c, OTHER_LABEL)
        layOut(c, 430)

        val wrapper = customWrapperOf(c, "你希望我接下来做什么？")
        assertTrue(wrapper.isVisible, "外壳该可见")
        assertTrue(wrapper.height > 0, "外壳拿到了 ${wrapper.height} 的高度，等于没显示")
        assertTrue(wrapper.width > 0, "外壳拿到了 ${wrapper.width} 的宽度")
    }

    @Test
    fun `第二题的「其它…」同样能展开出输入框`() {
        // 上面那条只盯了第一题。渲染图里露出问题的是**第二题** ——
        // 只测一题会漏掉"两题之间串了"这类错
        val c = card()
        layOut(c, 430)
        clickOption(c, OTHER_LABEL, occurrence = 1)
        layOut(c, 430)

        val second = customWrapperOf(c, "热切要不要写回长期设置？")
        assertTrue(second.isVisible, "第二题的输入框该可见")
        assertTrue(second.height > 0, "第二题的输入框高度是 ${second.height}")

        val first = customWrapperOf(c, "你希望我接下来做什么？")
        assertFalse(first.isVisible, "第一题没选「其它…」，不该跟着展开")
    }

    /**
     * 手动跑一遍布局。
     *
     * **必须显式 `invalidate()`。** `BoxLayout` 把子项尺寸算在容器的
     * `layoutSerial` 上，而 `revalidate()` 在未上屏的层级里不往上传播 ——
     * 卡片不在窗口里时那次 revalidate 等于没发生，缓存不作废。
     *
     * 真实应用里卡片在可显示层级中，`revalidate()` 会传到校验根，所以那条路
     * 是对的；这里 invalidate 是**替它把同一件事做掉**，否则这些手动布局的
     * 断言测的就不是真实的排布结果。
     */
    private fun layOut(c: AskQuestionCard, width: Int) {
        val outer = javax.swing.JPanel(java.awt.BorderLayout()).apply {
            add(c, java.awt.BorderLayout.CENTER)
        }
        outer.setSize(width, 800)
        fun walk(x: java.awt.Container) {
            x.invalidate()
            x.doLayout()
            x.components.filterIsInstance<java.awt.Container>().forEach { walk(it) }
        }
        walk(outer)
    }

    // ---- 提交门控 ----

    @Test
    fun `一题没答时不能提交`() {
        assertFalse(card().submitButton.isEnabled)
    }

    @Test
    fun `只答了一题还是不能提交`() {
        val c = card()
        clickOption(c, "审查当前 diff")
        assertFalse(c.submitButton.isEnabled, "第二题还空着")
    }

    @Test
    fun `两题都答了才能提交`() {
        val c = card()
        clickOption(c, "审查当前 diff")
        clickOption(c, "写回设置")
        assertTrue(c.submitButton.isEnabled)
    }

    @Test
    fun `禁用时给出原因，而不是让人猜`() {
        val c = card()
        assertNotNull(c.submitButton.toolTipText, "灰着的按钮得说清为什么")
    }

    // ---- 点选 ----

    @Test
    fun `点选项会改动作答状态`() {
        val c = card()
        clickOption(c, "审查当前 diff")
        assertTrue(c.state.states[0].isSelected("审查当前 diff"))
    }

    @Test
    fun `单选下再点一个，前一个让位`() {
        val c = card()
        clickOption(c, "审查当前 diff")
        clickOption(c, "继续未提交的改动")
        assertFalse(c.state.states[0].isSelected("审查当前 diff"))
        assertTrue(c.state.states[0].isSelected("继续未提交的改动"))
    }

    @Test
    fun `多选下两个都能选中`() {
        val c = card()
        clickOption(c, "只影响本次会话")
        clickOption(c, "写回设置")
        assertEquals(
            listOf("只影响本次会话", "写回设置"),
            c.state.states[1].answer(),
        )
    }

    // ---- 「其它…」 ----

    @Test
    fun `点「其它…」之前输入框不占位置`() {
        val c = card()
        val wrapper = customWrapperOf(c, "你希望我接下来做什么？")
        assertFalse(wrapper.isVisible, "没选「其它…」时不该占高度")
    }

    @Test
    fun `点「其它…」之后输入框出现`() {
        val c = card()
        clickOption(c, OTHER_LABEL)
        assertTrue(customWrapperOf(c, "你希望我接下来做什么？").isVisible)
    }

    @Test
    fun `「其它…」打了字才算答`() {
        val c = card()
        clickOption(c, OTHER_LABEL)
        clickOption(c, "写回设置")

        assertFalse(c.submitButton.isEnabled, "光点「其它…」没打字，那题还是没答")

        c.state.states[0].custom = "先别动，我想想"
        c.refreshSubmit()
        assertTrue(c.submitButton.isEnabled)
    }

    // ---- 送出的是什么 ----

    @Test
    fun `提交时把选中的答案原样送出去`() {
        var got: Picked? = null
        val c = card(onSubmit = { got = it })

        clickOption(c, "审查当前 diff")
        clickOption(c, "写回设置")
        press(c.submitButton)

        val picked = got!!
        assertEquals(listOf("审查当前 diff"), picked["你希望我接下来做什么？"])
        assertEquals(listOf("写回设置"), picked["热切要不要写回长期设置？"])
    }

    @Test
    fun `提交时「其它…」被换成用户打的字`() {
        var got: Picked? = null
        val c = card(onSubmit = { got = it })

        clickOption(c, OTHER_LABEL)
        c.state.states[0].custom = "先别动，我想想"
        clickOption(c, "写回设置")
        press(c.submitButton)

        assertEquals(listOf("先别动，我想想"), got!!["你希望我接下来做什么？"])
    }

    @Test
    fun `拒绝走的是另一条回调，不会误当成提交`() {
        var submitted = false
        var denied = false
        val c = card(onSubmit = { submitted = true }, onDeny = { denied = true })

        clickOption(c, "审查当前 diff")
        clickOption(c, "写回设置")
        press(c.denyButton)

        assertTrue(denied)
        assertFalse(submitted, "点「拒绝」不该送答案出去")
    }

    private fun customWrapperOf(c: AskQuestionCard, question: String): Component =
        c.customWrapperFor(question) ?: error("找不到「$question」的「其它…」输入框")
}
