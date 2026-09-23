package com.ccoder.ui

import com.ccoder.settings.SendShortcut
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.border.CompoundBorder

/**
 * 输入区的规则：高度、发送快捷键、布局。
 *
 * 抽成纯函数/纯布局函数是因为 ClaudePanel 依赖 Project，起不了单测；
 * 而这三处正是这次重设计里最容易写错、也最该被钉住的。
 */
class ComposerRulesTest {

    // ---- 高度 ----

    @Test
    fun `输入框竖向撑满视口，拖高输入区才会真的变大`() {
        val area = ComposerTextArea(COMPOSER_MIN_ROWS, 40)

        assertTrue(
            area.scrollableTracksViewportHeight,
            "返回 false 时拖高高输入区只会多出空白，输入框本身不变 —— 看起来像拖了没用",
        )
    }

    /**
     * 上一条钉的是"视口高的时候要撑满"，这一条钉**反面**（2026-09-23 用户报的那条）。
     *
     * 输入框不做自动长高（高度由用户拖分隔条决定），所以文本超出可视区是常态。
     * 那时候要是还"撑满"，滚动条就认定装得下、永远不出现 —— 后面的文本
     * **既看不见也够不着**。
     */
    @Test
    fun `文本多到装不下时就不再撑满 —— 那是滚动条出场的条件`() {
        IdeLaf.withRealLaf {
            val area = ComposerTextArea(COMPOSER_MIN_ROWS, 40)
            val scroll = JBScrollPane(area)
            scroll.setSize(400, 60)
            scroll.doLayout()

            assertTrue(area.scrollableTracksViewportHeight, "空的时候该撑满（上一条的规矩）")

            area.text = (1..40).joinToString("\n") { "第 $it 行" }
            scroll.doLayout()

            assertFalse(
                area.scrollableTracksViewportHeight,
                "装不下还撑满 = 滚动条永远不会出现 = 后面的文本看不见",
            )
        }
    }

    /** 上一条管"能不能滚"，这条管**滚动条真的出现在屏幕上**（用户看得见的那件事）。 */
    @Test
    fun `文本装不下时竖滚动条真的出现`() {
        IdeLaf.withRealLaf {
            val area = ComposerTextArea(COMPOSER_MIN_ROWS, 40)
            val scroll = JBScrollPane(area)
            scroll.setSize(400, 60)
            scroll.doLayout()
            assertFalse(scroll.verticalScrollBar.isVisible, "空的时候不该有滚动条")

            area.text = (1..40).joinToString("\n") { "第 $it 行" }
            scroll.doLayout()

            assertTrue(
                scroll.verticalScrollBar.isVisible,
                "滚动条没出现 —— 后面的文本就没有任何办法够到",
            )
        }
    }

    @Test
    fun `最小行数是一行 —— 它同时决定输入框能拖到多矮`() {
        assertEquals(1, COMPOSER_MIN_ROWS)
    }

    // ---- 换行（Shift+Enter）----

    /**
     * 2026-09-23 用户报的那条：**Shift+Enter 不换行**。
     *
     * 输入框的键表里从前**只有裸 Enter 绑了换行，Shift+Enter 是 `null`**
     * （探针把表倒出来看过：`shift pressed ENTER -> null`）。普通 Swing 里它能换行
     * 靠的是按键之后那个 KEY_TYPED 的 `'\n'` 兜底，而那条路在 IDE 里被掐掉，
     * 于是两头落空。修法是 [ComposerTextArea.updateUI] 里挂一条**和裸 Enter 一模一样**
     * 的绑定 —— 这条用例钉的就是那个"一模一样"：不是"有绑定就行"，而是
     * **必须是裸 Enter 那个动作对象**，那样它走的路才是被验证过的那条。
     */
    @Test
    fun `Shift+Enter 的绑定与裸 Enter 是同一个 —— 那才是换行`() {
        val area = ComposerTextArea(COMPOSER_MIN_ROWS, 40)
        val map = area.getInputMap(JComponent.WHEN_FOCUSED)

        val plain = map.get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
        assertTrue(plain != null, "裸 Enter 都不绑换行了？那整条路都得重看")

        val shift = map.get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK))
        assertEquals(
            plain,
            shift,
            "Shift+Enter 没绑上（或绑到了别的动作）—— 它在这张表里本来就是 null，得自己挂",
        )

        // Ctrl+Enter 仍然不绑：它是"发送"，归 isSendKey 判，不该由输入框自己插换行
        assertNull(map.get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)))
    }

    /** 上一条管"绑对了没有"，这条管**绑到的那个动作真的会插一个换行**。 */
    @Test
    fun `Shift+Enter 绑到的动作真的插一个换行`() {
        val area = ComposerTextArea(COMPOSER_MIN_ROWS, 40)
        area.text = "ab"
        area.caretPosition = 2

        val name = area.getInputMap(JComponent.WHEN_FOCUSED)
            .get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK))
        val action = area.actionMap.get(name) ?: error("Shift+Enter 没绑到可调的动作：$name")

        action.actionPerformed(ActionEvent(area, ActionEvent.ACTION_PERFORMED, null as String?))
        assertEquals("ab\n", area.text)
    }

    // ---- 发送快捷键 ----

    private fun enter(shift: Boolean = false, ctrl: Boolean = false, shortcut: SendShortcut) =
        isSendKey(KeyEvent.VK_ENTER, shiftDown = shift, ctrlDown = ctrl, shortcut = shortcut)

    @Test
    fun `聊天惯例下 Enter 发送、Shift+Enter 换行`() {
        assertTrue(enter(shortcut = SendShortcut.ENTER))
        assertFalse(enter(shift = true, shortcut = SendShortcut.ENTER), "Shift+Enter 必须留给换行")
    }

    @Test
    fun `编辑器惯例下 Ctrl+Enter 发送、Enter 换行`() {
        assertTrue(enter(ctrl = true, shortcut = SendShortcut.CTRL_ENTER))
        assertFalse(enter(shortcut = SendShortcut.CTRL_ENTER), "裸 Enter 必须留给换行")
        assertFalse(
            enter(shift = true, shortcut = SendShortcut.CTRL_ENTER),
            "Shift+Enter 在两种惯例下都该是换行",
        )
    }

    @Test
    fun `其他按键一律不发送`() {
        val other = KeyEvent.VK_A
        assertFalse(isSendKey(other, false, false, SendShortcut.ENTER))
        assertFalse(isSendKey(other, false, true, SendShortcut.CTRL_ENTER))
    }

    @Test
    fun `两种惯例的取值可持久化且默认是聊天惯例`() {
        assertEquals("ENTER", SendShortcut.ENTER.name)
        assertEquals("CTRL_ENTER", SendShortcut.CTRL_ENTER.name)
        assertEquals(SendShortcut.ENTER, SendShortcut.DEFAULT)
    }

    // ---- 布局 ----

    @Test
    fun `输入框居中、工具栏在下，NORTH 空着`() {
        val scroll = JPanel()
        val toolbar = JPanel()

        val card = buildComposerCard(scroll, toolbar)

        val layout = card.layout as BorderLayout
        assertSame(scroll, layout.getLayoutComponent(BorderLayout.CENTER), "输入框应在中间区域")
        assertSame(
            toolbar, layout.getLayoutComponent(BorderLayout.SOUTH),
            "工具栏必须在下方；摆到 EAST 就退回成「按钮挤在输入框右边」，右侧放不下以后的控件",
        )
        assertNull(
            layout.getLayoutComponent(BorderLayout.NORTH),
            "NORTH 该空着 —— 四张状态卡是独立的一排，在输入卡外面",
        )
    }

    // ---- 工具栏底带（2026-09-17）----

    /** 哨兵色：画布先铺满它，之后"颜色还是它"就等于"这里没被画过"。 */
    private val SENTINEL = Color(255, 0, 255)
    private val BAND = Color(0, 200, 0)

    /**
     * 只画那条底带（不套卡片、不带描边和子控件）。
     *
     * 形状这件事就该在这层量：混上边框与控件之后，角上那几个像素量到的是
     * 描边的抗锯齿混色，说不清"到底是不是圆角"。
     */
    private fun paintBand(width: Int = 120, height: Int = 100, bandTop: Int = 60): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = SENTINEL
        g.fillRect(0, 0, width, height)
        paintComposerFooter(g, width, height, bandTop, BAND)
        g.dispose()
        return img
    }

    @Test
    fun `底带从带子上沿开始铺 —— 上面一个像素都不碰`() {
        // 第一版用"圆角矩形上移一个半径、再用直角矩形盖掉上边"画的，方向写反了：
        // 带子从 `上沿 - 半径` 就开始铺，整整多出 16px 压在输入区上（探针图上
        // 一条凭空多出来的浅色横带把它暴露了）
        val img = paintBand(bandTop = 60)

        assertEquals(SENTINEL, colorAt(img, 60, 59), "上沿之上一个像素都不该画")
        assertEquals(BAND, colorAt(img, 60, 61), "上沿之下就该是带子")
        assertEquals(BAND, colorAt(img, 60, 98), "要一直铺到卡片下沿（留最外 1px 给描边）")
        assertEquals(BAND, colorAt(img, 2, 61), "左边留 1px，不是 2px")
    }

    @Test
    fun `底带下面两个角是圆的，上沿是直的`() {
        val img = paintBand(width = 120, height = 100, bandTop = 60)

        // 两个下角落在圆角之外：若是直角矩形，这两处会是 BAND —— 角上会露出一块
        // "底比边方"的补丁
        assertEquals(SENTINEL, colorAt(img, 2, 98), "左下角该被圆角切掉")
        assertEquals(SENTINEL, colorAt(img, 117, 98), "右下角同理")
        // 上沿那一条是直的：紧贴左上角（圆角之外）的那一点仍是带子
        assertEquals(BAND, colorAt(img, 2, 61), "上沿不该有弧度")
    }

    @Test
    fun `卡片把底带的上沿对到工具栏的上沿 —— 现场问布局要，不写死数`() {
        val (img, top) = renderCard()
        val fill = composerFooterFill()

        assertEquals(UIUtil.getPanelBackground(), colorAt(img, 150, top - 1), "工具栏上沿之上不该有底带")
        assertEquals(fill, colorAt(img, 150, top + 3), "上沿之下就该是底带")
    }

    /**
     * 把卡片画进一张图，返回 (图, 工具栏上沿的 y)。
     *
     * 底色先铺面板色 —— 卡片本身不透明，只画那一条带子；带子以外的地方
     * "没被画过"，量出来就等于这层底色。
     *
     * 滚动面板要**照生产那样透明**（`ClaudePanel` 里就是这两行）：默认的
     * viewport 是不透明的白底，会盖住卡片那一片（第一次就是这么量到 255 的）。
     */
    private fun renderCard(width: Int = 300, height: Int = 140): Pair<BufferedImage, Int> {
        val area = ComposerTextArea(COMPOSER_MIN_ROWS, 40).apply { styleComposerInput(this) }
        val toolbar = buildComposerToolbar(
            AttachButton(), JPanel(), JPanel(), JPanel(), RoundSendButton(),
        )
        val scroll = JBScrollPane(area).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
        }
        val card = buildComposerCard(scroll, toolbar)
        val host = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            add(card, BorderLayout.CENTER)
        }
        host.setSize(width, height)
        layoutAll(host)

        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        host.paint(g)
        g.dispose()
        return img to toolbar.bounds.y
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) if (child is Container) layoutAll(child)
    }

    private fun colorAt(img: BufferedImage, x: Int, y: Int) = Color(img.getRGB(x, y))

    @Test
    fun `卡片自己画圆角描边，边框不在输入框上`() {
        // 方案 A 的核心取舍：边框包住**整个输入区**（输入框 + 工具栏），
        // 输入框自己是裸的。若边框回到输入框上，就退回成"一个直角矩形框住文字"
        val card = buildComposerCard(JPanel(), JPanel())

        val outer = card.border as CompoundBorder
        assertTrue(outer.outsideBorder is RoundedLineBorder, "卡片应有圆角描边")
    }

    @Test
    fun `聚焦只换描边颜色，不加粗 —— 加粗会让正在输入的文字抖一下`() {
        val card = buildComposerCard(JPanel(), JPanel())
        val border = (card.border as CompoundBorder).outsideBorder as RoundedLineBorder

        val probe = JPanel()
        val colorBefore = border.color()
        val insetBefore = border.getBorderInsets(probe)
        card.setFocused(true)

        assertNotEquals(colorBefore, border.color(), "聚焦后描边颜色应变")
        assertEquals(
            insetBefore, border.getBorderInsets(probe),
            "内边距没变 —— 变了就是加粗了，正在输入的内容会位移一个像素",
        )
    }
}
