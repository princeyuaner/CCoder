package com.ccoder.ui

import com.ccoder.sidecar.ContextDetail
import com.ccoder.sidecar.ContextOverLimit
import com.ccoder.sidecar.ContextRow
import com.intellij.openapi.editor.colors.EditorColorsManager
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
 * 渲染探针：把上下文详情框的正文画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"分类行在""页签只给有内容的"，
 * 钉不住"这张框摆在 420px 里整体读不读得下去"。而选型稿里挑的正是**排版**（乙），
 * 这种事只靠信念不行（2026-09-13 那批"DOM 全对、屏幕上不对"就是这么来的）。
 *
 * 产物在 `build/context-detail-probe*.png`。改了版式就跑一下看一眼，**深浅两色都要看**。
 */
class ContextDetailRenderProbe {

    @Test
    fun `把常用的那一版画成图片`() = render("build/context-detail-probe.png", full())

    /** 窗口外的东西 + 超窗提示挤在一起那一版：行最多、最容易出问题的样子。 */
    @Test
    fun `把超窗那一版画成图片`() = render("build/context-detail-probe-over.png", overLimit())

    /** 老版本 sidecar / 那一次没拿到：只有总数 + 一句实话。 */
    @Test
    fun `把没有明细那一版画成图片`() = render("build/context-detail-probe-nodetail.png", null)

    private fun row(name: String, tokens: Long, kind: String = "", sub: String = "") =
        ContextRow(label = name, sub = sub, tokens = tokens, kind = kind)

    private fun full() = ContextDetail(
        model = "deepseek-v4-flash",
        percentage = 27,
        overLimit = null,
        categories = listOf(
            row("Messages", 250_000, kind = "used"),
            row("System prompt", 12_400, kind = "used"),
            row("Memory files", 3_200, kind = "used"),
            row("Agents", 1_600, kind = "used"),
            row("Skills", 800, kind = "used"),
            row("Compaction reserve", 33_000, kind = "buffer"),
            row("Free space", 699_000, kind = "free"),
            row("MCP tools (deferred)", 41_800, kind = "deferred"),
        ),
        mcpTools = listOf(
            row("mcp__codegraph__explore", 8_100, sub = "codegraph"),
            row("mcp__codegraph__search", 4_200, sub = "codegraph"),
            row("mcp__review__semantic_search", 3_600, sub = "code-review-graph"),
            row("mcp__review__impact", 2_900, sub = "code-review-graph"),
        ),
        memoryFiles = listOf(
            row("C:/Users/CY/.claude/CLAUDE.md", 2_100, sub = "User"),
            row("C:/Users/CY/Desktop/CCoder/CLAUDE.md", 1_100, sub = "Project"),
        ),
        agents = listOf(row("explore", 1_600, sub = "projectSettings")),
        skills = listOf(row("code-review", 800, sub = "plugin")),
    )

    private fun overLimit() = full().copy(
        overLimit = ContextOverLimit(12_400, ContextOverLimit.HARD_LIMIT),
        categories = full().categories + row("Some new category", 4_000, kind = "used"),
    )

    private fun render(path: String, detail: ContextDetail?) {
        SwingUtilities.invokeAndWait {
            val panel = buildContextDetailPanel(
                ContextUsage(usedTokens = 268_000, windowTokens = 1_000_000),
                detail,
            )
            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                // 照实画在对话框的底色上（拿不到就退回面板色）
                background = runCatching {
                    EditorColorsManager.getInstance().globalScheme.defaultBackground
                }.getOrDefault(UIUtil.getPanelBackground())
                add(panel, BorderLayout.CENTER)
            }

            val w = panel.preferredSize.width
            val h = panel.preferredSize.height
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        c.components.forEach { if (it is Container) layoutAll(it) }
    }
}
