package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把四张状态卡画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"收边的卡 alpha 是 0""四张等宽"，
 * 钉不住"四张卡放在 420px 里整体好不好看"。而后者正是这次改版的
 * **全部理由**（用户要的就是"更美观"），不该只靠信念。
 *
 * 产物在 `build/status-cards-probe*.png`。改了卡的观感就跑一下看一眼。
 */
class StatusCardsRenderProbe {

    @Test
    fun `把忙时的四张卡画成图片`() = render("build/status-cards-probe.png", busy = true)

    @Test
    fun `把空闲的四张卡画成图片`() = render("build/status-cards-probe-idle.png", busy = false)

    /**
     * **最长的那条连接文字**单独出一张。
     *
     * 每张卡在 420px 里只有约 81px，减掉状态点只剩 70px —— 而
     * "正在载入…"有七个字。这条不单独看的话，"放不下被截成
     * 正在载入…"要等装到 IDE 里才发现。
     */
    @Test
    fun `把最长的连接文字画成图片`() =
        render("build/status-cards-probe-longstatus.png", busy = true, connectionText = "正在载入…")

    /**
     * **还没测到用量**那一版单独出一张。
     *
     * 这条是**会话刚建立、还没问回用量**那一拍的样子。它以前是收边的「空闲」，
     * 现在是一个正常字号带边框的 `0` —— 后者是不是看着像一句"这个会话是空的"
     * 的断言，得看图才知道。
     */
    @Test
    fun `把还没测到用量的上下文卡画成图片`() =
        render("build/status-cards-probe-nousage.png", busy = true, noUsage = true)

    /**
     * @param busy true = 四样都有内容；false = 后两张收边。
     *   空闲那张同时把上下文推到 92%，顺手验证警示色。
     * @param noUsage true = 上下文没有测量值（`contextCardOf(null)`）。
     */
    private fun render(
        path: String,
        busy: Boolean,
        connectionText: String? = null,
        noUsage: Boolean = false,
    ) {
        SwingUtilities.invokeAndWait {
            val cards = StatusCardsRow(onOpenTodos = {}, onOpenRunning = {}).apply {
                connection.setModel(
                    connectionCardOf(connectionText ?: if (busy) "已连接" else "会话已断开")
                )
                context.setModel(
                    when {
                        noUsage -> contextCardOf(null)
                        busy -> contextCardOf(ContextUsage(usedTokens = 12300, windowTokens = 200000))
                        else -> contextCardOf(ContextUsage(usedTokens = 184000, windowTokens = 200000))
                    }
                )
                todos.setModel(
                    if (busy) {
                        todoCardOf(
                            TaskList(
                                listOf(
                                    TodoItem("定位调用点", TodoState.Completed),
                                    TodoItem("替换指纹函数", TodoState.Completed),
                                    TodoItem("跑测试", TodoState.InProgress),
                                    TodoItem("更新文档", TodoState.Pending),
                                )
                            )
                        )
                    } else {
                        todoCardOf(null)
                    }
                )
                running.setModel(
                    if (busy) runningCardOf(listOf(stub("t1"), stub("t2"))) else runningCardOf(emptyList())
                )
            }

            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(8)
                add(cards, BorderLayout.NORTH)
            }

            // 420px 是工具窗口的真实宽度
            val w = 420
            val h = outer.preferredSize.height
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun stub(id: String) =
        RunningTask(id = id, kind = null, label = null, detail = null, tokens = 0, durationMs = 0)

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
