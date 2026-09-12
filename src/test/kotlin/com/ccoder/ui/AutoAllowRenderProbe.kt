package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonParser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把「本会话不再询问」的两个落点画成 PNG。
 *
 * 没有断言，也不该有 —— 单测钉得住"文案里含这几个字"，钉不住
 * "这行按钮挤不挤""警示色够不够显眼"。前者是 `assertTrue(text.contains(...))`
 * 能回答的，后者只有**看**才知道。
 *
 * 产物在 `build/auto-allow-probe.png`。改了这两个地方的观感就跑一下看一眼。
 */
class AutoAllowRenderProbe {

    @Test
    fun `把停问按钮与标签画成图片`() = SwingUtilities.invokeAndWait {
        val perm = SidecarMessage.Permission(
            requestId = "r1",
            toolName = "Bash",
            input = JsonParser.parseString(
                """{"command":"npm test","description":"跑一遍单元测试"}"""
            ).asJsonObject,
            title = "Claude 想运行 npm test",
            displayName = "允许",
            description = "在工作目录下执行命令",
            blockedPath = null,
            decisionReason = null,
            defaultToNo = false,
            suppressAlwaysAllowRule = false,
            suggestions = JsonParser.parseString(
                """[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"npm test *"}],""" +
                    """"behavior":"allow","destination":"localSettings"}]"""
            ).asJsonArray,
        )

        val label = ModeLabel {}.apply { setAutoAllow() }

        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(10)
            add(PermissionCard(perm, queuedCount = 0) {})
            add(Box.createVerticalStrut(12))
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(label, BorderLayout.WEST)
                }
            )
        }

        val w = 520
        column.setSize(w, column.preferredSize.height)
        layoutAll(column)

        val h = column.preferredSize.height
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        column.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File("build/auto-allow-probe.png"))
    }

    private fun layoutAll(c: java.awt.Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is java.awt.Container) layoutAll(child)
        }
    }
}
