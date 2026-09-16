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

    /**
     * 真机那份 `ExitPlanMode` 入参的形状（从会话记录里抄的字段名与量级）：
     * `{"plan": "<几千字的计划>", "planFilePath": "…"}`。
     */
    private fun planPermission() = SidecarMessage.Permission(
        requestId = "r2",
        toolName = "ExitPlanMode",
        input = JsonParser.parseString(
            """
            {"plan":"# 提问弹框：长文本换行 + 最小化\n\n## Context\n\n用户报了两个问题，都在提问弹框这条链上（AskQuestionCard / AskQuestionDialog / AskSequence）：\n\n1. **长题干不换行，把整个弹框撑宽。** 根因是两件事叠在一起：题干、选项 label、选项说明全是 JLabel（Swing 的 JLabel 从不自动换行），而 AskQuestionCard.getPreferredSize() 只把 preferred 宽度钉在 CARD_WIDTH(420)，没有覆写 getMinimumSize()。\n\n2. **没有最小化。** 框是全模态、且唯一的退路是 X/Esc，而 spec 6.2 明确把\"关窗\"定义成整条拒绝。\n\n## 验证\n\n- Kotlin 1081 全过\n- 渲染探针出图并量了宽度\n","planFilePath":"C:\\Users\\CY\\.claude\\plans\\swirling-finding-kettle.md"}
            """
        ).asJsonObject,
        // 真机就是这样：title 与 displayName 都把工具名念了一遍
        title = "ExitPlanMode",
        displayName = "ExitPlanMode",
        description = null,
        blockedPath = null,
        decisionReason = null,
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
        column.add(Box.createVerticalStrut(14))

        // ④ 2026-09-15 用户截图那一张：ExitPlanMode 的审批框。入参里装的是一份
        // 三千多字的计划，改之前它被压成一行转义 JSON 塞在 3 行高的框里 ——
        // 用户的原话是"里面的内容都看不到"。这一格就是盯着这件事的
        caption("④ ExitPlanMode：入参是整份计划 —— 标题说人话、正文按段落铺开")
        column.add(PermissionCard(planPermission(), queuedCount = 0) {})
        column.add(Box.createVerticalGlue())

        // 画布跟着卡片宽度走（2026-09-16 方案 B：卡片 420 → 640）。
        // 写死数字的话，改宽度那次会把右边整条裁掉 —— 而探针恰恰是唯一能看见这件事的地方
        val w = PERMISSION_CARD_WIDTH + JBUI.scale(20)
        column.setSize(w, column.preferredSize.height)
        layoutAll(column)

        val h = column.preferredSize.height
        println("[权限卡片探针] 首选尺寸 ${column.preferredSize}")

        // 高度留一点余量：preferred 量的是内容，画的时候卡片描边还占几个像素，
        // 正好贴边时最后一行会被切掉一条
        val img = BufferedImage(w, h + JBUI.scale(10), BufferedImage.TYPE_INT_RGB)
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
