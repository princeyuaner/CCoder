package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把补全弹层画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测能钉住"分组标题只出一条""长描述被截断"、
 * "选中行有底色"，钉不住三件事：
 *
 *  1. 长描述截到 110 字之后还读不读得懂
 *  2. 分组标题的分量会不会跟候选抢注意力
 *  3. 一行里"名字 · 描述"的间隔读起来累不累
 *
 * 产物在 `build/completion-probe*.png`。改了弹层的观感就跑一下看一眼。
 *
 * **只出浅色两张。** 项目现有的 [ComposerRenderProbe] 也不切主题 ——
 * 离屏渲染拿不到 IDE 的深色 LaF，硬造一个只会画出一个谁也没见过的配色。
 * 深色下的观感在沙箱里切主题看。
 */
class CompletionRenderProbe {

    @Test
    fun `命令组（分组标题与长描述）画成图片`() =
        render("build/completion-probe-commands.png", commands())

    @Test
    fun `文件组（长路径）画成图片`() =
        render("build/completion-probe-files.png", files())

    /** 数据取自实测：`brainstorming` 那条是本例里最长的真实描述。 */
    private fun commands() = listOf(
        CompletionItem("compact", "compact", "参数 <optional custom summarization instructions>", group = GROUP_OTHER),
        CompletionItem(
            "clear", "clear", "Start a new session with empty context · 别名 reset、new",
            group = GROUP_OTHER,
        ),
        CompletionItem("context", "context", "Show current context usage", group = GROUP_OTHER),
        CompletionItem("usage", "usage", "Show the total cost and duration · 别名 cost、stats", group = GROUP_OTHER),
        CompletionItem(
            "Debug Issue", "debug-issue",
            "Systematically debug issues using graph-powered code navigation (user)",
            group = GROUP_OTHER,
        ),
        CompletionItem(
            "caveman", "caveman",
            "Upstream: github.com/JuliusBrussee/caveman",
            group = GROUP_OTHER,
        ),
        CompletionItem(
            "brainstorming", "superpowers:brainstorming",
            "You MUST use this before any creative work - creating features, building components, " +
                "adding functionality, or modifying behavior. Explores user intent, requirements " +
                "and design before implementation.",
            group = GROUP_PLUGIN,
        ),
    )

    private fun files() = listOf(
        "src/main/kotlin/com/ccoder/ui/Completion.kt",
        "src/main/kotlin/com/ccoder/ui/CompletionPopup.kt",
        "src/main/kotlin/com/ccoder/ui/CommandCandidates.kt",
        "src/main/kotlin/com/ccoder/ui/ClaudePanel.kt",
        "src/test/kotlin/com/ccoder/ui/CompletionTest.kt",
    ).map { CompletionItem(it, it) }

    private fun render(path: String, items: List<CompletionItem>) {
        SwingUtilities.invokeAndWait {
            val content = buildCompletionList(items, selected = 1)
            val bg: Color = UIUtil.getPanelBackground()

            val outer = JPanel(BorderLayout()).apply {
                background = bg
                border = JBUI.Borders.empty(8)
                add(content, BorderLayout.CENTER)
            }

            val ps = content.preferredSize
            val w = ps.width + 16
            val h = ps.height + 16
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = bg
            g.fillRect(0, 0, w, h)
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
