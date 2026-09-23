package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * 转写区起不来时的两件事（2026-09-23 用户报的那条）。
 *
 * 用户看到的是"连点五次「新建会话」，每次弹一个 Unhandled exception in EDT"。
 * 根子在平台（`RemoteMessageRouterImpl.create` 对着断掉的 RPC 通道读 `robj.isNull`），
 * 但**掀到 EDT 的后果归我们**：每次都是整个新会话没建起来。所以这里钉两个行为：
 *
 * 1. 建/接线 JCEF 时抛出来的错不许往外跑 —— 只是没有转写区；
 * 2. 那种降级页上要有「重试」（通道可能已经自己好了），而"未启用 JCEF"那种不给。
 *
 * **盖不到的一半**：`startTranscriptCef` 里"接线"那半边（`wire` 抛）没法单独喂一个
 * 失败进来 —— 那要真的 `JBCefBrowser`，用例里造不出来。两层共用一个 `catch`，
 * 所以第一条用例钉的是那个 `catch` 本身；接线的收尾（`teardown`）只有真机上看得见。
 */
class TranscriptFallbackTest {

    @Test
    fun `建 JCEF 时抛的错不往外跑，只是没有转写区`() = onEdt {
        // 平台那条的真身：RemoteMessageRouterImpl.create 第 37 行
        val platform = NullPointerException("Cannot read field \"isNull\" because \"robj\" is null")

        val cef = startTranscriptCef(
            create = { throw platform },
            wire = { error("零件都没建起来，不该走到接线") },
        )

        assertNull(cef, "抛出去了说明没兜住 —— EDT 上就是那个异常对话框")
    }

    @Test
    fun `起不来的那张页有个重试键，点了会再起一次`() = onEdt {
        var retries = 0
        val page = TranscriptFallback(TranscriptFallback.Reason.StartFailed) { retries += 1 }

        val retry = retryButtonOf(page)
        assertEquals("重试", retry?.text, "降级页上没有重试键")
        retry?.doClick()

        assertEquals(1, retries)
    }

    @Test
    fun `未启用 JCEF 的那种不给重试键 —— 那是设置，重试一百次也一样`() = onEdt {
        val page = TranscriptFallback(TranscriptFallback.Reason.NoJcef) { error("不该被点到") }
        assertNull(retryButtonOf(page))
    }

    /**
     * 说明那段折行之后**不许被裁**（2026-09-23 出图时当场看见过一次：第二段最后半句没了）。
     *
     * 判据是数像素，不是看谁报多少：给"它会拿到的那个高度"画一帧、再多给 24px 画一帧，
     * 两帧的墨迹行数必须一样 —— 多给就多出墨，说明少给的那帧**有字没画出来**。
     * HTML 的 JLabel 报的首选高度是"不折行那一版"的，宽度一窄就静默截行；这条把它钉住。
     *
     * 三个宽度：288 / 388 是工具窗口常见的档，480 是宽的那一档（不该越宽越坏）。
     */
    @Test
    fun `窄到 288 也不裁字`() {
        IdeLaf.withRealLaf {
            onEdt {
                val page = TranscriptFallback(TranscriptFallback.Reason.NoJcef) {}
                val label = page.components[0] as JLabel
                for (w in listOf(288, 388, 480)) {
                    label.setSize(w, 10)
                    val h = label.preferredSize.height
                    label.setSize(w, h)
                    val tight = inkRows(label, w, h)
                    label.setSize(w, h + 24)
                    val roomy = inkRows(label, w, h + 24)
                    assertTrue(roomy > 0, "宽 $w：一帧都没画出字来")
                    assertEquals(roomy, tight, "宽 $w：格子高 $h 装不下（多给 24px 就多出墨 = 被裁了）")
                }
            }
        }
    }

    /**
     * 上一条管的是"那段说明自己量得准"，这条管**布局有没有把它夹回去** ——
     * `JLabel` 的 min/max 直接返回 UI 算的那个高度，只重写 preferred 是白写的
     * （探针图上"preferred 68、实际 51"就是这么来的）。
     */
    @Test
    fun `窗口窄了说明折更多行，页跟着长高`() {
        IdeLaf.withRealLaf {
            onEdt {
                val page = TranscriptFallback(TranscriptFallback.Reason.NoJcef) {}

                // 排两遍：第一遍让标签拿到宽度（在那之前它报的是"不折行那一版"），
                // 第二遍才是宽度落定之后的高度 —— 真机上就是 revalidate 那一拍
                fun heightAt(w: Int): Int {
                    page.setSize(w, 400)
                    page.doLayout()
                    page.invalidate()
                    page.doLayout()
                    return page.preferredSize.height
                }

                val wide = heightAt(480)
                val narrow = heightAt(288)
                assertTrue(narrow > wide, "窄了页没长高（$narrow vs $wide）—— 高度被夹回不折行那一版了")
            }
        }
    }

    /** 把那段说明单独画一帧，数有几行有墨（结字、行距都无关，只问"少不少字"）。 */
    private fun inkRows(label: JLabel, w: Int, h: Int): Int {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        label.paint(g)
        g.dispose()
        val white = Color.WHITE.rgb
        return (0 until h).count { y -> (0 until w).any { x -> img.getRGB(x, y) != white } }
    }

    /** 页上唯一那颗按钮就是重试键；没有就是没给。 */
    private fun retryButtonOf(page: TranscriptFallback): AbstractButton? =
        page.components.filterIsInstance<AbstractButton>().firstOrNull()
}

/**
 * 在 EDT 上跑一段，并拆掉 `invokeAndWait` 那层包装 —— 否则断言失败只剩一句
 * InvocationTargetException（同 `LocalizedTextTest` / `AskSequenceTest` 的写法）。
 */
private fun onEdt(block: () -> Unit) {
    var thrown: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            block()
        } catch (t: Throwable) {
            thrown = t
        }
    }
    thrown?.let { throw it }
}
