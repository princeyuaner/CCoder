package com.ccoder.ui

import com.google.gson.JsonParser
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把提问卡片画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测能钉住"没答完不能提交""送出去的是选中的那个"，
 * 钉不住"它看起来像不像一张能答的卡片"。
 *
 * 产物：`build/ask-card.png`（第 1 题，未作答）、`build/ask-card-picked.png`
 * （第 2 题，答完并开着「其它…」的输入框 —— 顺带把「← 上一题」和进度行画进去）。
 */
class AskQuestionRenderProbe {

    private val request = askRequestOf(
        JsonParser.parseString(
            """
            {"questions":[
              {"question":"你希望我接下来做什么？","header":"要做什么","options":[
                {"label":"继续未提交的改动","description":"接着当前的 ComposerMode / 输入区往下做。"},
                {"label":"审查当前 diff","description":"对 20 个已改文件 + 4 个新文件做结构化审查。"}
              ]},
              {"question":"热切要不要写回长期设置？","header":"作用范围","multiSelect":true,"options":[
                {"label":"只影响本次会话","description":"下次启动还是设置里那个。"},
                {"label":"写回设置","description":"下次启动还用这个。"}
              ]}
            ]}
            """
        ).asJsonObject
    )!!

    @Test
    fun `把第一题画成图片`() = render("build/ask-card.png", index = 0, picked = false)

    @Test
    fun `把第二题作答中的样子画成图片`() = render("build/ask-card-picked.png", index = 1, picked = true)

    /**
     * 单选换选之后的样子（2026-09-20 用户报的那条）。
     *
     * 先点「继续未提交的改动」再点「审查当前 diff」—— 图里该**只有一个勾**。
     * 改之前这张图上两个勾都亮着（状态早就是对的，错的是没人叫前一行重画）。
     */
    @Test
    fun `把单选换选后的样子画成图片`() = render(
        "build/ask-card-swapped.png",
        index = 0,
        picked = false,
        clicks = listOf("继续未提交的改动", "审查当前 diff"),
    )

    /**
     * 长题干 + 长说明。
     *
     * 2026-09-15 用户报"问题描述过长没有换行，会导致整个弹框很宽"—— 这张图就是
     * 那条的现场：改之前它会一路拉宽，改之后该在卡片宽度里折行。
     */
    @Test
    fun `把长题干的样子画成图片`() = render("build/ask-card-long.png", index = 0, picked = false, req = longTexts)

    /**
     * **最小化之后，工具窗口里那条回来的路**（[AskRestoreBar]）。
     *
     * 2026-09-15 用户报"最小化之后我找不到从哪里重新打开了"—— 上一版只有状态栏
     * 那一行字。画出来看一眼：它是不是一眼能认出是"点这儿回去"。
     */
    @Test
    fun `把最小化后的回来的路画成图片`() = SwingUtilities.invokeAndWait {
        val bar = AskRestoreBar(onRestore = {}).apply { setSuspended(true) }
        val outer = javax.swing.JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            background = UIUtil.getPanelBackground()
            add(bar, BorderLayout.NORTH)
        }
        outer.setSize(450, 10)
        // 加进去之后还得再 revalidate 一次：容器**加新子项**不会自动重算布局，
        // 量到的会是"没有这条带子"的高度（第一次画出来只有 207 字节的白图）
        outer.revalidate()
        layOutAll(outer)

        // **量完再把尺寸定下来**：paint 用的是组件自己的尺寸，不是画布的。
        // 先 setSize(450, 10) 再 paint 等于把内容裁在 10px 高里（第二次画出来
        // 还是一片空白，只是文件大了点 —— 图小不代表图对）
        val h = outer.preferredSize.height + 20
        outer.setSize(450, h)
        layOutAll(outer)

        val img = BufferedImage(450, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        outer.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File("build/ask-restore-bar.png"))
    }

    private val longTexts = askRequestOf(
        JsonParser.parseString(
            """
            {"questions":[
              {"question":"${"长题干".repeat(40)}","header":"长题干","options":[
                {"label":"继续未提交的改动","description":"${"这段说明也长得必须折行。".repeat(20)}"},
                {"label":"审查当前 diff","description":"短说明。"}
              ]}
            ]}
            """
        ).asJsonObject
    )!!

    /** 推到第 index 题：前面每题随便答一个，只是为了走得过去。 */    private fun flowAt(index: Int, req: AskRequest = request): AskFlow {
        val flow = AskFlow(req)
        while (flow.index < index) {
            flow.current.toggle(flow.question.options.first().label)
            flow.submitCurrent()
        }
        return flow
    }

    private fun render(
        path: String,
        index: Int,
        picked: Boolean,
        req: AskRequest = request,
        /** 布局跑完之后要按顺序点哪些选项（单选换选那种现场）。 */
        clicks: List<String> = emptyList(),
    ) {
        SwingUtilities.invokeAndWait {
            val flow = flowAt(index, req)
            val card = AskQuestionCard(
                flow,
                onSubmit = {},
                onAdvance = {},
                onBack = {},
                onDeny = {},
                onMinimize = {},
            )
            val outer = javax.swing.JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(10)
                background = UIUtil.getPanelBackground()
                add(card, BorderLayout.CENTER)
            }

            val w = 450

            // **先布局，再点。** 真实路径就是这样：卡片先上屏，用户才点得着。
            // 反过来（先点后布局）"其它…"的输入框会在布局跑完之后才变可见，
            // 画出来当然没有它 —— 那是探针错，不是卡片错。
            outer.setSize(w, 10)
            layOutAll(outer)

            if (picked) {
                // 必须走**点击**：选中态（勾、边框、输入框显隐）全是在点击处理里
                // 刷的，直接改状态画出来还是没选的样子，这张图就白渲染了
                clickOption(card, "写回设置")
                clickOption(card, OTHER_LABEL)
                // 走输入框本身：真实路径里那段字是在这个框里打的。直接改 state
                // 的话画出来是个空框（第一版就是这样，图和实际对不上）
                flow.current.custom = "另外再说一点"
                textFieldsIn(card).firstOrNull()?.text = "另外再说一点"
                card.refreshSubmit()
            }

            clicks.forEach { clickOption(card, it) }

            // 高度要在**一次布局之后**量：BoxLayout 的首选尺寸也走同一个缓存，
            // 缓存没作废之前量出来的是没有输入框的那个高度，卡片底部会被截掉
            layOutAll(outer)
            val h = card.preferredSize.height + 20
            outer.setSize(w, h)
            layOutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))

            // 顺手把宽度印出来。**图会骗人，数不会** —— 2026-09-15 追那个"弹框
            // 被长题干撑宽"的问题时，看图看了半天，最后还是靠量数定的案。
            val widest = textComponentsIn(card).maxOfOrNull { it.width } ?: 0
            println(
                "[ask-probe] $path 卡片首选宽=${card.preferredSize.width} " +
                    "最小宽=${card.minimumSize.width} 树里最宽文本=$widest",
            )
        }
    }

    /** 「其它…」那个输入框 —— 卡片树里唯一的文本输入控件。 */
    private fun textFieldsIn(root: Container): List<JBTextField> {
        val out = mutableListOf<JBTextField>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JBTextField) out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    /** 按包含找文本组件再合成一次点击 —— 和真实路径同一条（见 TextLookup.kt）。 */
    private fun clickOption(card: AskQuestionCard, text: String) {
        val target = componentWithText(card, text)
        target.dispatchEvent(
            MouseEvent(
                target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 3, 3, 1, false, MouseEvent.BUTTON1,
            )
        )
        card.revalidate()
    }

    /**
     * 手动跑一遍布局。
     *
     * **必须先 `invalidate()`**：`BoxLayout` 把每个子项的尺寸算在容器的
     * `layoutSerial` 上，而 `revalidate()` 在**未上屏**的层级里不往上传播 ——
     * 卡片不在窗口里，那次 revalidate 等于没发生，缓存不作废。
     *
     * 症状很隐蔽：点开「其它…」之后，输入框 `isVisible=true`、首选高度 26，
     * 但布局只给了它 **0** —— 画出来就是什么都没有。
     *
     * 真实应用里卡片在可显示层级中，`revalidate()` 会传到校验根，所以没这个问题。
     * 是探针（和只建新容器的手动布局）不忠实。
     */
    private fun layOutAll(c: Container) {
        c.invalidate()
        c.doLayout()
        c.components.filterIsInstance<Container>().forEach { layOutAll(it) }
    }
}
