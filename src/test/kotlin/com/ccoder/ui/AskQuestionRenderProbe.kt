package com.ccoder.ui

import com.google.gson.JsonParser
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
 * 产物：`build/ask-card.png`（未作答）、`build/ask-card-picked.png`（已作答）。
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
    fun `把提问卡片画成图片`() = render("build/ask-card.png", picked = false)

    @Test
    fun `把已作答的提问卡片画成图片`() = render("build/ask-card-picked.png", picked = true)

    private fun render(path: String, picked: Boolean) {
        SwingUtilities.invokeAndWait {
            val card = AskQuestionCard(request, onSubmit = {}, onDeny = {})
            val outer = javax.swing.JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(10)
                background = UIUtil.getPanelBackground()
                add(card, BorderLayout.CENTER)
            }

            val w = 430

            // **先布局，再点。** 真实路径就是这样：卡片先上屏，用户才点得着。
            // 反过来（先点后布局）"其它…"的输入框会在布局跑完之后才变可见，
            // 画出来当然没有它 —— 那是探针错，不是卡片错。
            outer.setSize(w, 10)
            layOutAll(outer)

            if (picked) {
                // 必须走**点击**：选中态（勾、边框、输入框显隐）全是在点击处理里
                // 刷的，直接改 state 画出来还是没选的样子，这张图就白渲染了
                clickOption(card, "审查当前 diff")
                clickOption(card, "只影响本次会话")
                clickOption(card, "写回设置")
                clickOption(card, OTHER_LABEL, occurrence = 1)
                card.state.states[1].custom = "另外再说一点"
                card.refreshSubmit()
            }

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
        }
    }

    private fun labelsIn(root: Container): List<JLabel> {
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

    /**
     * 按包含找标签再合成一次点击 —— 和真实路径同一条。
     *
     * `occurrence` 用来区分两道题里同名的选项（比如两题都有「其它…」）。
     */
    private fun clickOption(card: AskQuestionCard, text: String, occurrence: Int = 0) {
        val matches = labelsIn(card).filter { it.text.contains(text) }
        val target = matches.getOrNull(occurrence)
            ?: error("找不到第 ${occurrence + 1} 个「$text」，共 ${matches.size} 个")
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
