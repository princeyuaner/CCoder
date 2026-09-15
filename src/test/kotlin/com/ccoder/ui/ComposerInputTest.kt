package com.ccoder.ui

import com.intellij.ui.components.JBTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 输入框的外观。
 *
 * **这里曾经断言的是反面**：早先用户反馈"输入框与转写区糊在一起分不清"，
 * 于是给输入框自己画了一条线边框。方案 A 把那条线挪到了外层卡片上——
 * 卡片包住的是整个输入区（上下文行 + 输入框 + 工具栏），边界比只框住
 * 文字那一块更明确。
 *
 * 所以"分不清"那个问题仍然是被回答的，只是换了地方回答：
 * 见 [ComposerRulesTest] 里对卡片描边的断言。
 */
class ComposerInputTest {

    private fun styled() = JBTextArea(3, 40).also { styleComposerInput(it) }

    @Test
    fun `输入框自己不画线 —— 线归外层卡片`() {
        // 这里再画一层就退回成"框里套框"，那是方案 A 要付的代价，
        // 不能连它换来的好处一起丢掉
        val area = styled()

        val insets = area.border.getBorderInsets(area)
        assertEquals(
            0, listOf(insets.top, insets.left, insets.bottom, insets.right).count { it > 7 },
            "边框内衬过大，像是画了线：$insets",
        )
        assertFalse(
            area.border is javax.swing.border.CompoundBorder,
            "输入框的边框应当只剩内边距，实际是 ${area.border.javaClass.simpleName}",
        )
    }

    @Test
    fun `文字与边缘之间有内边距`() {
        val area = styled()

        val padding = area.border.getBorderInsets(area)
        assertTrue(
            padding.top >= 2 && padding.left >= 2,
            "没有内边距的话文字会贴着卡片的描边，看着像被框住的段落，实际 $padding",
        )
    }

    @Test
    fun `记号会被刷上底色`() {
        // 底色是"它是个东西、不是乱码"的全部依据 —— 纯文本组件改不了字符颜色，
        // 能用的就只有 Highlighter 这一层
        val area = JBTextArea(3, 40)
        area.text = "看 " + refToken("a/B.kt", 1..2) + " 这个"

        applyRefHighlights(area)

        assertEquals(1, area.highlighter.highlights.size)
    }

    @Test
    fun `记号被删掉之后底色跟着没`() {
        // 每来一次文本变化就整批重刷，就是为了这件事 ——
        // 逐段维护高亮的生命周期，删字/粘贴那几条路太难覆盖全
        val area = JBTextArea(3, 40)
        area.text = refToken("a/B.kt", 1..2)
        applyRefHighlights(area)

        area.text = "没了"
        applyRefHighlights(area)

        assertEquals(0, area.highlighter.highlights.size)
    }

    @Test
    fun `没有记号时一层底色都不画`() {
        val area = JBTextArea(3, 40)
        area.text = "普通的一句话"

        applyRefHighlights(area)

        assertEquals(0, area.highlighter.highlights.size)
    }

    @Test
    fun `输入框不填底 —— 底色由卡片统一决定`() {
        // 不透明会把卡片的底色盖掉，视觉上又变成嵌了一层。
        // 这是"卡片是一个整体"的必要条件：内部的几块不能各自有底
        val area = styled()

        assertFalse(area.isOpaque, "输入框不该自己填底")
    }

    // ---- 空着时的用法提示（2026-09-15 用户要求写上三个符号的用法）----

    @Test
    fun `空着的时候给文案，有字或禁用就不给`() {
        assertEquals(COMPOSER_PLACEHOLDER, placeholderTextOf(composerLaidOut()))
        assertNull(placeholderTextOf(composerLaidOut(text = "看一下 ")))
        assertNull(placeholderTextOf(composerLaidOut(enabled = false)), "发不出去时挂一行「你可以打 #」是撒谎")
    }

    @Test
    fun `文案里写着三个触发符号 —— 与能力同步，砍掉哪个都要改它`() {
        assertEquals("@ 文件 · # 符号 · / 命令", COMPOSER_PLACEHOLDER)
    }

    @Test
    fun `空着的时候真的画上去了 —— 数像素`() {
        // 纯属性测不出"没画"：那正是用户看到的形式（什么提示都没有）。所以数像素
        assertEquals(0, inkCount(composerLaidOut(enabled = false)), "禁用时一个字都不该画")
        assertTrue(inkCount(composerLaidOut()) > 0, "空着时该看得见那行提示")
    }

    /** 一个排好版的 ComposerTextArea —— 提示画在它上面。 */
    private fun composerLaidOut(text: String = "", enabled: Boolean = true): ComposerTextArea {
        lateinit var area: ComposerTextArea
        onEdt {
            area = ComposerTextArea(1, 40).apply {
                lineWrap = true
                styleComposerInput(this)
                this.text = text
                this.isEnabled = enabled
            }
            val holder = JPanel(BorderLayout()).apply { add(area) }
            holder.setSize(420, 60)
            layoutAll(holder)
        }
        return area
    }

    /** 数"不是底色的像素"。画到白底图上，提示是灰字 —— 画了就一定数得出来。 */
    private fun inkCount(area: ComposerTextArea): Int {
        var count = 0
        onEdt {
            val img = BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, img.width, img.height)
            area.paint(g)
            g.dispose()

            val white = Color.WHITE.rgb
            for (y in 0 until img.height) {
                for (x in 0 until img.width) if (img.getRGB(x, y) != white) count++
            }
        }
        return count
    }

    // ---- 底色铺多宽（用户报过："背景充满了整个聊天框"）----

    @Test
    fun `底色只铺在记号自己那一段上，不铺满整行`() {
        // 曾经铺满整行的原因：painter 拿到的 `bounds` 是 Swing 给的**整行**
        // （实测 392px），而字形只占 179px。
        //
        // 这里量的是**像素**，而且期望值来自字体自己量的字宽 —— 与实现无关：
        // 拿 `refRects` 反推的话，同一处算错两边会一起错，断言就成了摆设
        val area = laidOut(width = 420)
        val token = refToken("sidecar/session.js", 24..27)
        onEdt {
            area.text = token
            applyRefHighlights(area)
        }

        val painted = paintedSpan(area, refBackground)
        val glyph = onEdtGet { area.getFontMetrics(area.font).stringWidth(token) }

        assertTrue(painted.width > 0, "一层底色都没画出来")
        assertTrue(
            painted.width <= glyph + 3,
            "底色比字形宽：${painted.width}px vs ${glyph}px —— 又铺到整行去了",
        )
        assertTrue(
            painted.width >= glyph - 3,
            "底色比字形窄：${painted.width}px vs ${glyph}px —— 记号像是被切掉一截",
        )
    }

    @Test
    fun `记号被折行时，每一块都落在自己那一行里`() {
        // 记号里放的是**完整相对路径**（同名文件得区分得开），长路径会折成两行 ——
        // 折行时 painter 会被叫多次，每一块都必须在自己在的那一行上，不能跨行糊成一片
        val area = laidOut(width = 200)
        val long = refToken("src/main/kotlin/com/ccoder/ui/ComposerReferences.kt", 24..27)

        val rects = onEdtGet {
            area.text = long
            refRects(area, 0, area.text.length)
        }

        assertTrue(rects.size >= 2, "这么长的记号该折行了，实际只量到 ${rects.size} 块")
        assertEquals(rects.size, rects.map { it.y }.distinct().size, "有两个块落在了同一行上")
        // 每一块都不许超出输入框自己的宽度
        for (r in rects) {
            assertTrue(r.x + r.width <= area.width, "有一块超出了输入框：$r")
        }
    }

    @Test
    fun `量不出位置时什么都不画，不抛异常`() {
        // 文本正在变、组件还没排版时 modelToView 拿不到位置 —— 绘制这一层
        // 不该因此把整个界面炸掉
        val area = JBTextArea(3, 40).apply { text = refToken("a/B.kt", 1..2) }

        val rects = onEdtGet { refRects(area, 0, 3) }

        assertTrue(rects.isEmpty(), "没排版也量出了东西：$rects")
    }

    // ---- 排版与取色的工具 ----

    /** 按真实宽度排好版（不挂窗口），底色画在哪儿才有意义。 */
    private fun laidOut(width: Int, rows: Int = 1): JBTextArea {
        lateinit var area: JBTextArea
        onEdt {
            area = JBTextArea(rows, 40).apply {
                lineWrap = true
                wrapStyleWord = true
                styleComposerInput(this)
            }
            val holder = JPanel(BorderLayout()).apply { add(area) }
            holder.setSize(width, 160)
            layoutAll(holder)
        }
        return area
    }

    /** 把组件画到一张白底图上，量出 [color] 那些像素占的那一块。 */
    private fun paintedSpan(area: JBTextArea, color: Color): Rectangle {
        lateinit var box: Rectangle
        onEdt {
            val img = BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, img.width, img.height)
            area.paint(g)
            g.dispose()

            val want = color.rgb
            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            for (y in 0 until img.height) {
                for (x in 0 until img.width) {
                    if (img.getRGB(x, y) == want) {
                        minX = minOf(minX, x); maxX = maxOf(maxX, x)
                        minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    }
                }
            }
            box = if (maxX < 0) Rectangle() else Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
        }
        return box
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) if (child is Container) layoutAll(child)
    }
}

/** 在 EDT 上跑一段，并拆掉 `invokeAndWait` 那层包装 —— 否则断言失败只剩一句 InvocationTargetException。 */
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

/** 同上，但要带一个值出来。名字刻意不同：重载一对 `onEdt` 时，返回 Unit 的写法会撞上歧义。 */
private fun <T> onEdtGet(block: () -> T): T {
    var result: T? = null
    onEdt { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
