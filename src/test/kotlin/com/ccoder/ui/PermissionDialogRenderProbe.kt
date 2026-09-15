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
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把权限审批那张卡片画成 PNG。
 *
 * 没有断言，也不该有 —— 单测钉得住"按钮文字等于「允许」"，钉不住
 * "这张卡片看起来像不像一个能一眼读懂的问句"。后者只有**看**才知道。
 *
 * **画的是卡片，不是整个框**：框（标题栏、模态、关窗语义）由 `DialogWrapper`
 * 负责，是平台画的标准外观；我们自己的样式全在这张卡片里
 * （`PermissionDialog.createCenterPanel()` 返回的就是它）。
 *
 * 夹具**照真机抄**（2026-09-15 用户截图）：`displayName` 是 CLI 给的 `"Bash"`，
 * 也就是工具名本身。原先各处的夹具都写 `displayName = "允许"`，比现实好看，
 * 于是"按钮上写着 Bash"这件事一直没人发现。留着这条现实一点的样本。
 *
 * 产物在 `build/permission-dialog-probe.png`。改了这张卡片的观感就跑一下看一眼。
 */
class PermissionDialogRenderProbe {

    private fun permission(displayName: String) = SidecarMessage.Permission(
        requestId = "r1",
        toolName = "Bash",
        input = JsonParser.parseString(
            """{"command":"grep -rn \"model-profiles-dialog\" src/test/kotlin/"}"""
        ).asJsonObject,
        // title 为 null 也是照真机：CLI 对内置工具没给 title，
        // 卡片于是回落到 displayName → toolName，标题就显示「Bash」
        title = null,
        displayName = displayName,
        description = "Find how existing probes render dialogs",
        blockedPath = null,
        decisionReason = "Contains command_substitution",
        defaultToNo = false,
        suppressAlwaysAllowRule = false,
        suggestions = null,
    )

    @Test
    fun `把权限卡片画成图片`() = SwingUtilities.invokeAndWait {
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(10)
        }

        fun caption(text: String) = column.add(
            JLabel(text).apply {
                foreground = UIUtil.getInactiveTextColor()
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(4)
            }
        )

        caption("① 真机那种：displayName = \"Bash\"（工具名）—— 按钮应写「允许」")
        column.add(PermissionCard(permission(displayName = "Bash"), queuedCount = 0) {})
        column.add(Box.createVerticalStrut(14))

        caption("② MCP 工具那种：displayName 是真的动作短语 —— 按钮用它")
        column.add(PermissionCard(permission(displayName = "Read file"), queuedCount = 0) {})
        column.add(Box.createVerticalStrut(14))

        caption("③ 队列里还排着两个（queuedCount > 0 时多一行说明）")
        column.add(PermissionCard(permission(displayName = "Bash"), queuedCount = 2) {})
        column.add(Box.createVerticalGlue())

        val w = 460
        column.setSize(w, column.preferredSize.height)
        layoutAll(column)

        val h = column.preferredSize.height
        println("[权限卡片探针] 首选尺寸 ${column.preferredSize}")

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        column.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File("build/permission-dialog-probe.png"))
    }

    private fun layoutAll(c: java.awt.Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is java.awt.Container) layoutAll(child)
        }
    }
}
