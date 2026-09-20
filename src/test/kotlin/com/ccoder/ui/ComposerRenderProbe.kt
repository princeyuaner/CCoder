package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.ccoder.settings.ModelProfile
import com.ccoder.settings.PermissionModeSetting
import com.intellij.ui.components.JBScrollPane
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * 渲染探针：把输入区连同它上方的四张状态卡画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测能钉住"收边的卡 alpha 是 0""四张等宽"，
 * 钉不住"四张卡和输入框叠在一起整体好不好看"。而后者正是这次改版的
 * **全部理由**，不该只靠信念。
 *
 * 状态卡也不再是输入卡内部的一行：它们是独立的一排，挂在输入卡**外面**的
 * 上方，所以这一张图里两样都得画出来才看得出关系。
 *
 * 产物在 `build/composer-probe*.png`。改了输入区或状态卡的观感就跑一下看一眼。
 * 绕过权限那张单独出一张，因为警示色只有**看**才知道够不够显眼。
 */
class ComposerRenderProbe {

    @Test
    fun `把输入区与状态卡画成图片`() = render("build/composer-probe.png", PermissionModeSetting.DEFAULT)

    @Test
    fun `把绕过权限时的输入区画成图片`() =
        render("build/composer-probe-bypass.png", PermissionModeSetting.BYPASS_PERMISSIONS)

    /**
     * 真机 LAF（New UI 深色）底下画。
     *
     * 2026-09-17 补的：这个探针此前跑在测试 JVM 默认的 Metal 下，出的是**浅色**图 ——
     * 而用户那台 PyCharm 是深色。底色一变，"工具栏底带够不够、显不显得脏"这类判断
     * 就全都不作数了（[IdeLaf] 的注释里写着同一条教训）。
     */
    private fun render(path: String, mode: PermissionModeSetting) {
        IdeLaf.withRealLaf { renderUnder(path, mode) }
    }

    private fun renderUnder(path: String, mode: PermissionModeSetting) {
        SwingUtilities.invokeAndWait {
            val cards = StatusCardsRow(onClear = {}, onCompact = {}, onOpenContext = {}, onOpenTodos = {}, onOpenRunning = {}).apply {
                connection.setModel(connectionCardOf(ConnectionState.Connected))
                context.setModel(contextCardOf(ContextUsage(usedTokens = 12300, windowTokens = 200000)))
                todos.setModel(
                    todoCardOf(
                        TaskList(
                            listOf(
                                TodoItem("定位调用点", TodoState.Completed),
                                TodoItem("替换指纹函数", TodoState.InProgress),
                                TodoItem("跑测试", TodoState.Pending),
                            )
                        )
                    )
                )
                running.setModel(runningCardOf(listOf(stub("t1"), stub("t2"))))
            }

            val input = ComposerTextArea(COMPOSER_MIN_ROWS, 40).apply {
                lineWrap = true
                styleComposerInput(this)
                text = "输入消息，Enter 发送"
            }
            val inputScroll = JBScrollPane(input).apply {
                border = com.intellij.util.ui.JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }

            // 工具栏不再提供模型标签的工厂 —— 标签由 ClaudePanel 构造后传进来
            // （与 ModeLabel 一样），所以这里也自己造一个
            val model = ModelLabel {}.apply { setProfile(ModelProfile(name = "Sonnet 4.5")) }
            val modeLabel = ModeLabel {}.apply { setMode(mode) }
            val effortLabel = EffortLabel {}.apply { setEffort(EffortSetting.HIGH) }
            val send = RoundSendButton().apply {
                setState(mainButtonState(ready = true, busy = false, disconnected = false))
            }
            val toolbar = buildComposerToolbar(AttachButton(), model, modeLabel, effortLabel, send)

            val card = buildComposerCard(inputScroll, toolbar)

            val outer = javax.swing.JPanel(BorderLayout()).apply {
                border = com.intellij.util.ui.JBUI.Borders.empty(6, 8, 8, 8)
                background = com.intellij.util.ui.UIUtil.getPanelBackground()
                add(cards, BorderLayout.NORTH)
                add(card, BorderLayout.CENTER)
            }

            val w = 430
            val h = 230
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
