package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JLabel

/**
 * 提问卡片 —— 一次画一道题的那张。
 *
 * 它是**安全控件**的近亲：递交的答案会被当成"用户的选择"喂回模型，
 * 所以这里盯得最紧的两件事是「没答完不能提交」和「送出去的确实是选中的那个」。
 *
 * 题号推进（第几题、能不能往前走、回退时答案还在不在）不在这里测 ——
 * 那些在 [AskFlowTest] 里，与 Swing 无关。
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

    private val oneQuestion = request(
        """
        {"questions":[
          {"question":"你希望我接下来做什么？","header":"要做什么","options":[
            {"label":"继续未提交的改动","description":"接着往下做。"},
            {"label":"审查当前 diff","description":"做一次结构化审查。"}
          ]}
        ]}
        """
    )

    /** 把流程推到第 index 题：前面每题随便答一个，只是为了走得过去。 */
    private fun flowAt(index: Int, req: AskRequest = twoQuestions): AskFlow {
        val flow = AskFlow(req)
        while (flow.index < index) {
            flow.current.toggle(flow.question.options.first().label)
            flow.submitCurrent()
        }
        return flow
    }

    private fun card(
        flow: AskFlow = flowAt(0),
        onSubmit: (Picked) -> Unit = {},
        onAdvance: () -> Unit = {},
        onBack: () -> Unit = {},
        onDeny: () -> Unit = {},
        onMinimize: () -> Unit = {},
    ) = AskQuestionCard(flow, onSubmit, onAdvance, onBack, onDeny, onMinimize)

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
    private fun press(button: JButton) = button.doClick()

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

    private fun buttonTexts(root: Container): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JButton) out += child.text
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    /** 当前这一题里按文本找选项（一题一个框，所以不用 occurrence 了）。 */
    private fun clickOption(c: AskQuestionCard, text: String) = click(componentWithText(c, text))

    // ---- 一次只画一题 ----

    @Test
    fun `只画当前这道题的题干与选项`() {
        val texts = textsIn(card()).joinToString("\n")

        assertTrue(texts.contains("你希望我接下来做什么？"), texts)
        assertTrue(texts.contains("继续未提交的改动"), texts)
        // 说明是 A 方案的全部卖点，不能只渲染标题
        assertTrue(texts.contains("接着往下做。"), "选项说明没渲染：$texts")

        assertFalse(
            texts.contains("热切要不要写回长期设置？"),
            "第二题不该出现在第一题这个框里：$texts",
        )
    }

    @Test
    fun `推进到第二题时画的是第二题`() {
        val texts = textsIn(card(flowAt(1))).joinToString("\n")

        assertTrue(texts.contains("热切要不要写回长期设置？"), texts)
        assertFalse(texts.contains("你希望我接下来做什么？"), texts)
    }

    @Test
    fun `多题时顶栏说清第几题`() {
        val first = textsIn(card(flowAt(0)))
        assertTrue(first.any { it.contains("第 1 / 2 题") }, "少了进度：$first")

        val second = textsIn(card(flowAt(1)))
        assertTrue(second.any { it.contains("第 2 / 2 题") }, "少了进度：$second")
    }

    @Test
    fun `一题的问卷不写第几题`() {
        val texts = textsIn(card(flowAt(0, oneQuestion)))
        assertTrue(texts.none { it.contains("/ 1 题") }, "一题还写进度就是噪音：$texts")
    }

    @Test
    fun `「其它…」由界面自己补上`() {
        // SDK 要求宿主提供它（sdk-tools.d.ts:1070 的 options 注释）
        val texts = textsIn(card())
        assertEquals(1, texts.count { it.contains(OTHER_LABEL) }, "当前题该有一个：$texts")
    }

    // ---- 上一题 / 下一题 ----

    @Test
    fun `第一题没有上一题，第二题才有`() {
        assertTrue(buttonTexts(card(flowAt(0))).none { it.contains("上一题") }, "第一题没有可回的")

        val second = buttonTexts(card(flowAt(1)))
        assertTrue(second.any { it.contains("上一题") }, "第二题该能回看：$second")
    }

    @Test
    fun `非最后一题的按钮是下一题，最后一题才是提交`() {
        assertTrue(buttonTexts(card(flowAt(0))).contains(NEXT_LABEL))
        assertTrue(buttonTexts(card(flowAt(1))).contains(SUBMIT_LABEL))
    }

    @Test
    fun `按下下一题会推进一题并通知换框`() {
        var advanced = false
        val flow = flowAt(0)
        val c = card(flow, onAdvance = { advanced = true })

        clickOption(c, "审查当前 diff")
        press(c.submitButton)

        assertTrue(advanced, "该通知序列去弹下一个框")
        assertEquals(1, flow.index)
    }

    @Test
    fun `按下上一题会退回一题并通知换框`() {
        var back = false
        val flow = flowAt(1)
        val c = card(flow, onBack = { back = true })

        press(c.backButton)

        assertTrue(back)
        assertEquals(0, flow.index)
    }

    // ---- 布局（观感的事只能布局之后看坐标）----

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
        val question = componentWithText(c, "你希望我接下来做什么")

        assertTrue(chip.x < 30, "芯片被推到了 x=${chip.x}")
        assertTrue(question.x < 30, "题目被推到了 x=${question.x}")
    }

    @Test
    fun `选中前后那一行文本的宽度不变`() {
        // 渲染出来看到的是「✓ 审查当前 …」—— 少了一个字，还被加了省略号。
        // 根因是布局拿的还是选中**之前**的宽度（选中时前面多了个 ✓）。
        //
        // 2026-09-15 之后文本会换行，省略号这个症状从结构上不会再有（宽不够就折行），
        // 但那条不变式还在：**点一下不该让这一行重排**。所以这里盯宽度本身，
        // 顺带盯它有没有拿到自己要的宽度（拿不到就说明它被挤了）。
        val c = card()
        layOut(c, 430)
        val before = componentWithText(c, "审查当前 diff").width

        clickOption(c, "审查当前 diff")
        layOut(c, 430)

        val after = componentWithText(c, "审查当前 diff")
        assertEquals(before, after.width, "选中之后这一行重排了")
        assertTrue(
            after.width >= after.preferredSize.width,
            "文本宽 ${after.width}，但需要 ${after.preferredSize.width} —— 它被挤了",
        )
    }

    // ---- 长文本：换行，而不是把弹框撑宽（2026-09-15 用户报的那条）----

    /** 一条长到必须折行的题干 + 一段长说明。 */
    private val longTexts = request(
        """
        {"questions":[
          {"question":"${"长题干".repeat(60)}","header":"长题干","options":[
            {"label":"选项一","description":"${"这段说明也长得必须折行。".repeat(30)}"},
            {"label":"选项二","description":"短说明。"}
          ]}
        ]}
        """
    )

    @Test
    fun `长题干不会把卡片撑宽`() {
        // 对话框 `pack()` 看的是**最小**宽度 —— 只钉 preferred 不够，
        // 这正是当初被撑宽的根子（见 AskQuestionCard.getMinimumSize 的注释）
        val c = card(flowAt(0, longTexts))

        assertEquals(CARD_WIDTH, c.preferredSize.width, "首选宽度该钉在卡片宽度")
        assertTrue(
            c.minimumSize.width <= CARD_WIDTH,
            "最小宽度 ${c.minimumSize.width} 会把弹框顶宽",
        )
    }

    @Test
    fun `长题干真的折行了，而不是被裁掉`() {
        // 只断言"不宽"是不够的：把长文本裁掉的实现也能过上面那条。
        // 真折行 = 它拿到的高度大于一行的高度。
        val c = card(flowAt(0, longTexts))
        layOut(c, CARD_WIDTH)

        val question = componentWithText(c, "长题干")
        val oneLine = question.getFontMetrics(question.font).height
        assertTrue(
            question.height > oneLine,
            "题干只拿到 ${question.height}px（一行 $oneLine px）—— 没有折行",
        )
    }

    @Test
    fun `长说明也折行`() {
        val c = card(flowAt(0, longTexts))
        layOut(c, CARD_WIDTH)

        val desc = componentWithText(c, "这段说明也长得必须折行。")
        val oneLine = desc.getFontMetrics(desc.font).height
        assertTrue(desc.height > oneLine, "说明只拿到 ${desc.height}px —— 没有折行")
    }

    @Test
    fun `布局之后没有任何文本宽过卡片`() {
        // 折行 + 最小宽度两道都上了，这条是它们的结果：整棵树里没有任何东西
        // 需要比卡片更宽 —— 也就是弹框不会被撑宽。
        val c = card(flowAt(0, longTexts))
        layOut(c, CARD_WIDTH)

        val tooWide = textComponentsIn(c).filter { it.width > c.width }
        assertTrue(
            tooWide.isEmpty(),
            "这些文本宽过卡片：${tooWide.map { "${textOf(it)?.take(8)}宽${it.width}" }}",
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

        val wrapper = customWrapperIn(c, "你希望我接下来做什么？")
        assertTrue(wrapper.isVisible, "外壳该可见")
        assertTrue(wrapper.height > 0, "外壳拿到了 ${wrapper.height} 的高度，等于没显示")
        assertTrue(wrapper.width > 0, "外壳拿到了 ${wrapper.width} 的宽度")
    }

    @Test
    fun `第二题上的「其它…」一样能展开出输入框`() {
        // 题目是按文本索引的，两道题各有一份「其它…」—— 串了就会展开错的那个
        val c = card(flowAt(1))
        layOut(c, 430)
        clickOption(c, OTHER_LABEL)
        layOut(c, 430)

        val wrapper = customWrapperIn(c, "热切要不要写回长期设置？")
        assertTrue(wrapper.isVisible, "第二题的输入框该可见")
        assertTrue(wrapper.height > 0, "第二题的输入框高度是 ${wrapper.height}")
        assertNull(
            c.customWrapperFor("你希望我接下来做什么？"),
            "第一题的输入框不该在这个框里",
        )
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
    fun `当前题没答时不能往前`() {
        assertFalse(card().submitButton.isEnabled)
    }

    @Test
    fun `当前题答了才能往前`() {
        val c = card()
        clickOption(c, "审查当前 diff")
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
        assertTrue(c.flow.current.isSelected("审查当前 diff"))
    }

    @Test
    fun `单选下再点一个，前一个让位`() {
        val c = card()
        clickOption(c, "审查当前 diff")
        clickOption(c, "继续未提交的改动")
        assertFalse(c.flow.current.isSelected("审查当前 diff"))
        assertTrue(c.flow.current.isSelected("继续未提交的改动"))
    }

    /**
     * 2026-09-20 用户报的那条：**状态让位了，画面没让位**。
     *
     * 勾、文字色、边框都是每个选项行自己画的，而原先点击只刷被点的那一行 ——
     * 于是"选了 A 再选 B，A 的选择没有取消"。上一条只断言状态，它一直是绿的：
     * 断言必须落在**画面上**，否则这条 bug 再来一次还是拦不住。
     */
    @Test
    fun `单选下再点一个，前一个的勾画面上也收回去`() {
        val c = card()
        clickOption(c, "审查当前 diff")
        assertEquals("✓", c.tickFor("审查当前 diff"))

        clickOption(c, "继续未提交的改动")
        assertEquals("", c.tickFor("审查当前 diff"), "前一个的勾没收回去")
        assertEquals("✓", c.tickFor("继续未提交的改动"))
    }

    @Test
    fun `多选下两个勾都留着`() {
        val c = card(flowAt(1))
        clickOption(c, "只影响本次会话")
        clickOption(c, "写回设置")

        assertEquals("✓", c.tickFor("只影响本次会话"))
        assertEquals("✓", c.tickFor("写回设置"))
    }

    @Test
    fun `选了「其它…」再选普通选项，勾与输入框一起让位`() {
        val c = card()
        clickOption(c, OTHER_LABEL)
        c.flow.current.custom = "先别动，我想想"
        c.refreshSubmit()

        clickOption(c, "审查当前 diff")

        assertEquals("", c.tickFor(OTHER_LABEL), "「其它…」的勾该收回去")
        assertFalse(
            customWrapperIn(c, "你希望我接下来做什么？").isVisible,
            "「其它…」不再选中，输入框该收回去",
        )
        assertEquals(listOf("审查当前 diff"), c.flow.current.answer())
    }

    @Test
    fun `多选下两个都能选中`() {
        val c = card(flowAt(1))
        clickOption(c, "只影响本次会话")
        clickOption(c, "写回设置")
        assertEquals(
            listOf("只影响本次会话", "写回设置"),
            c.flow.current.answer(),
        )
    }

    // ---- 「其它…」 ----

    @Test
    fun `点「其它…」之前输入框不占位置`() {
        val c = card()
        val wrapper = customWrapperIn(c, "你希望我接下来做什么？")
        assertFalse(wrapper.isVisible, "没选「其它…」时不该占高度")
    }

    @Test
    fun `点「其它…」之后输入框出现`() {
        val c = card()
        clickOption(c, OTHER_LABEL)
        assertTrue(customWrapperIn(c, "你希望我接下来做什么？").isVisible)
    }

    @Test
    fun `「其它…」打了字才算答`() {
        val c = card()
        clickOption(c, OTHER_LABEL)

        assertFalse(c.submitButton.isEnabled, "光点「其它…」没打字，等于没答")

        c.flow.current.custom = "先别动，我想想"
        c.refreshSubmit()
        assertTrue(c.submitButton.isEnabled)
    }

    /**
     * 回看一题时现场要恢复。
     *
     * 选项的勾由选项行自己读状态刷出来，但**输入框**建出来永远是隐藏的空框 ——
     * 卡片原本是一次性的，没有"重开"这条路径。加了「上一题」之后就有了：
     * 答过「其它…」的题重开时，输入框该显形并且文字还在。
     */
    @Test
    fun `重开一题时「其它…」输入框连着文字一起回来`() {
        val flow = flowAt(0)
        val first = card(flow)
        clickOption(first, OTHER_LABEL)
        first.flow.current.custom = "先别动，我想想"

        // 换一道题再回来（序列就是这么重开的：每次都建一张新卡片）
        flow.submitCurrent()
        val second = card(flow)
        assertNull(second.customWrapperFor("你希望我接下来做什么？"), "第二题不该有第一题的输入框")

        assertTrue(flow.back())
        val reopened = card(flow)
        val wrapper = customWrapperIn(reopened, "你希望我接下来做什么？")
        assertTrue(wrapper.isVisible, "重开时输入框该显形")
        assertEquals("先别动，我想想", reopened.customTextFor("你希望我接下来做什么？"))
    }

    // ---- 送出的是什么 ----

    @Test
    fun `最后一题提交时把全部答案原样送出去`() {
        var got: Picked? = null
        val flow = flowAt(1)
        val c = card(flow, onSubmit = { got = it })

        clickOption(c, "写回设置")
        press(c.submitButton)

        val picked = got!!
        assertEquals(
            listOf("继续未提交的改动"),
            picked["你希望我接下来做什么？"],
            "前面答过的那题不能丢 —— 答案是一次交齐的",
        )
        assertEquals(listOf("写回设置"), picked["热切要不要写回长期设置？"])
    }

    @Test
    fun `提交时「其它…」被换成用户打的字`() {
        var got: Picked? = null
        val c = card(flowAt(0, oneQuestion), onSubmit = { got = it })

        clickOption(c, OTHER_LABEL)
        c.flow.current.custom = "先别动，我想想"
        // 真路径里这行是输入框的按键监听做的（打字 → 刷新门控）。
        // 这里直接改状态，得自己补上，否则按钮还灰着，doClick 什么都不会发生
        c.refreshSubmit()
        press(c.submitButton)

        assertEquals(listOf("先别动，我想想"), got!!["你希望我接下来做什么？"])
    }

    @Test
    fun `拒绝走的是另一条回调，不会误当成提交`() {
        var submitted = false
        var denied = false
        val c = card(
            flowAt(1),
            onSubmit = { submitted = true },
            onDeny = { denied = true },
        )

        clickOption(c, "写回设置")
        press(c.denyButton)

        assertTrue(denied)
        assertFalse(submitted, "点「拒绝」不该送答案出去")
    }

    private fun customWrapperIn(c: AskQuestionCard, question: String): Component =
        c.customWrapperFor(question) ?: error("找不到「$question」的「其它…」输入框")
}
