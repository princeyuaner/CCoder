package com.ccoder.ui

import com.ccoder.text.CcoderText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * 「看输出」那个框的正文（[TaskOutputContent]）。
 *
 * 钉的是 2026-09-28 真机上看到的两处：
 *
 * 1. **状态文字画在 UI 字体的标签里**，不塞进等宽区 —— 编辑器的等宽字体常常没有中文字形，
 *    一句"读取中…"会渲染成一排方块（用户截图上那一串 `▯▯▯▯…` 就是它）；
 * 2. **不因为"还没显示"就丢掉读到的结果**（那条在 `TaskOutputDialog.load` 里，
 *    这里钉住的是它依赖的契约：`set()` 任何时候调都算数）。
 */
class TaskOutputContentTest {

    private fun task(outputFile: String? = "C:/tmp/out.txt") = FinishedTask(
        id = "t1",
        kind = "local_bash",
        label = "跑一条命令",
        detail = null,
        outcome = TaskOutcome.Done,
        error = null,
        durationMs = 1_000,
        toolUses = 1,
        tokens = 0,
        outputFile = outputFile,
        toolUseId = null,
    )

    private fun labelsIn(root: Component): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Component) {
            if (c is JLabel) out += c.text
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return out
    }

    @Test
    fun `读取中那句画在标签里，等宽区是空的`() = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val content = TaskOutputContent(task())
            content.set(OutputState.Reading)

            assertEquals("", content.body(), "等宽区里不该有中文")
            assertTrue(
                labelsIn(content.panel).contains(CcoderText.text("transcript.detail.output.reading")),
                "那句该画在标签上：${labelsIn(content.panel)}",
            )
        }
    }

    @Test
    fun `文件不在时同样走标签，并且说清是"不在了"`() = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val content = TaskOutputContent(task())
            content.set(OutputState.Missing)

            assertEquals("", content.body())
            assertTrue(
                labelsIn(content.panel).contains(CcoderText.text("transcript.detail.output.missing")),
                "要说清输出文件不在了：${labelsIn(content.panel)}",
            )
        }
    }

    @Test
    fun `读到内容时进等宽区，脚注写末尾多少行`() = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val content = TaskOutputContent(task())
            content.set(OutputState.Loaded(OutputTail(lines = listOf("第一行", "第二行"), truncatedHead = true)))

            assertEquals("第一行\n第二行", content.body())
            assertTrue(
                labelsIn(content.panel).contains(
                    CcoderText.text("transcript.detail.output.tailCut", 2),
                ),
                "要写明前面还有内容没显示：${labelsIn(content.panel)}",
            )
        }
    }
}
