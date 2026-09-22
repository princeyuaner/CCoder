package com.ccoder.ui

import com.ccoder.sidecar.ContextDetail
import com.ccoder.sidecar.ContextOverLimit
import com.ccoder.sidecar.ContextRow
import com.ccoder.text.CcoderText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.util.Locale
import javax.swing.JLabel

/**
 * 详情框那张面板**摆出来之后**说了什么。
 *
 * 判类/求和/翻译的规矩在 [ContextDetailTest]（纯函数）；这一层钉的是"有没有摆上去"：
 * 数字都在、条数都对、该有的页签有、不该有的一行都没有。断言走 JLabel 的文字，
 * 不比对像素 —— 观感归 `ContextDetailRenderProbe`（出 PNG 给人眼看）。
 *
 * 三条是从旧的浮层用例**搬过来**的（那时点开只有"已用/窗口/剩余"三行）：
 * 分母必须在、窗口未知时不编造、快写满要有提醒。它们说的不是"哪一行"，是这张卡
 * 一直以来的承诺，换壳不该丢掉。
 */
class ContextDetailPanelTest {

    @BeforeEach
    fun pinChinese() = CcoderText.setOverride(Locale.SIMPLIFIED_CHINESE)

    @AfterEach
    fun unpin() = CcoderText.setOverride(null)

    private fun labelsIn(root: Component): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Component) {
            if (c is JLabel) out += c.text
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return out
    }

    private fun row(name: String, tokens: Long, kind: String = "", sub: String = "") =
        ContextRow(label = name, sub = sub, tokens = tokens, kind = kind)

    private fun detail(categories: List<ContextRow>) = ContextDetail(
        model = "deepseek-v4-flash",
        percentage = 27,
        overLimit = null,
        categories = categories,
        mcpTools = listOf(row("mcp__codegraph__explore", 8_100, sub = "codegraph")),
        memoryFiles = listOf(row("C:/proj/CLAUDE.md", 3_200, sub = "Project")),
        agents = emptyList(),
        skills = emptyList(),
    )

    @Test
    fun `分母必须在 —— 它就是"还能聊多久"`() {
        val texts = labelsIn(
            buildContextDetailPanel(
                ContextUsage(usedTokens = 268_000, windowTokens = 1_000_000),
                detail(listOf(row("Messages", 250_000, kind = "used"))),
            )
        )

        assertTrue("268k / 1M" in texts, "少了总量：$texts")
        assertTrue("27%" in texts, "少了百分比：$texts")
        assertTrue("deepseek-v4-flash" in texts, "少了模型：$texts")
        assertTrue(CcoderText.text("context.row.messages") in texts, "分类行没摆上：$texts")
        assertTrue(CcoderText.text("context.deferredHint") !in texts, "没有窗口外的行就不该有那句：$texts")
    }

    @Test
    fun `窗口未知时不编造分母`() {
        val texts = labelsIn(
            buildContextDetailPanel(
                ContextUsage(usedTokens = 500, windowTokens = 0),
                detail(listOf(row("Messages", 500, kind = "used"))),
            )
        )

        assertTrue("500" in texts, "至少要给出已用量：$texts")
        // 判据是"比例那串"的写法（`12.3k / 200k`，斜杠两边有空格）——
        // 不能拿"有没有斜杠"当判据：记忆文件的路径里本来就有斜杠
        assertTrue(
            texts.none { it.contains(" / ") },
            "窗口未知时不该出现分母：$texts",
        )
        assertTrue(
            texts.any { it == CcoderText.text("transcript.detail.noWindowMeasured") },
            "该说清楚为什么只有一半信息：$texts",
        )
    }

    @Test
    fun `快写满时给一句提醒`() {
        val texts = labelsIn(
            buildContextDetailPanel(
                ContextUsage(usedTokens = 190_000, windowTokens = 200_000),
                detail(listOf(row("Messages", 190_000, kind = "used"))),
            )
        )

        assertTrue(
            CcoderText.text("transcript.detail.hint.over90") in texts,
            "写满九成该提醒：$texts",
        )
    }

    @Test
    fun `窗口外的行带上那句"不占窗口"`() {
        val texts = labelsIn(
            buildContextDetailPanel(
                ContextUsage(usedTokens = 268_000, windowTokens = 1_000_000),
                detail(
                    listOf(
                        row("Messages", 250_000, kind = "used"),
                        row("MCP tools (deferred)", 41_800, kind = "deferred"),
                    )
                ),
            )
        )

        assertTrue(CcoderText.text("context.row.mcpToolsDeferred") in texts, "行名要翻：$texts")
        assertTrue(CcoderText.text("context.deferredHint") in texts, "得说清它不占窗口：$texts")
    }

    @Test
    fun `超窗时顶上多一行，措辞按 kind 走`() {
        val over = detail(listOf(row("Messages", 250_000, kind = "used"))).copy(
            overLimit = ContextOverLimit(12_400, ContextOverLimit.HARD_LIMIT)
        )
        val texts = labelsIn(
            buildContextDetailPanel(ContextUsage(usedTokens = 1_012_400, windowTokens = 1_000_000), over)
        )

        assertTrue(texts.any { it.startsWith("⚠") && "拒绝" in it }, "硬顶要说清后果：$texts")
    }

    @Test
    fun `没有明细时照实说，而不是一张空表`() {
        // 老版本 sidecar / 那一次没拿到：框里只有总数 + 一句实话
        val texts = labelsIn(
            buildContextDetailPanel(ContextUsage(usedTokens = 12_300, windowTokens = 200_000), null)
        )

        assertTrue("12.3k / 200k" in texts, "总数照给：$texts")
        assertTrue(CcoderText.text("context.noDetail") in texts, "该说清没拿到明细：$texts")
        assertTrue(CcoderText.text("context.tab.categories") !in texts, "没有明细就不该有页签：$texts")
    }

    @Test
    fun `清单页签只给有内容的那些，且带条数`() {
        val texts = labelsIn(
            buildContextDetailPanel(
                ContextUsage(usedTokens = 268_000, windowTokens = 1_000_000),
                detail(listOf(row("Messages", 250_000, kind = "used"))),
            )
        )

        assertTrue(CcoderText.text("context.tab.categories") in texts, "分类页签必在：$texts")
        // 明细里 MCP 一条、记忆一条、子代理与技能空 —— 页签只该有前两个 + 分类
        assertTrue("${CcoderText.text("context.list.mcpTools")} 1" in texts, "MCP 页签该带条数：$texts")
        assertTrue("${CcoderText.text("context.list.memoryFiles")} 1" in texts, "记忆页签该带条数：$texts")
        assertTrue(
            texts.none { it.startsWith(CcoderText.text("context.list.agents")) },
            "空的清单不该有页签：$texts",
        )
    }
}
