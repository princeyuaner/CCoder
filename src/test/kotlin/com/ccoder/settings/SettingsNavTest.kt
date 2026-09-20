package com.ccoder.settings

import com.ccoder.ui.focusColor
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 左栏导航的用例（2026-09-20 方案 B）。
 *
 * 两件事单看代码看不出来，只能画出来数：**八枚图标每枚都真画得出东西**（路径是手写的
 * 16×16 坐标，抄错一条就是一枚空图标，而界面上只是"少了个小图"），以及**选中那枚
 * 真的铺了强调色**（选中态由 `setSelected` 一处决定，画不画得出来是另一回事）。
 */
class SettingsNavTest {

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private class FakePage(override val title: String) : SettingsPage {
        override fun component(): JComponent = JPanel()
        override fun reload() = Unit
    }

    private fun item(selected: Boolean): SettingsNavItem {
        lateinit var nav: SettingsNavItem
        onEdt {
            nav = SettingsNavItem(FakePage("通用"), NavIcon.General, "通用") {}
            nav.setSize(JBUI.scale(140), JBUI.scale(26))
            nav.doLayout()
            nav.setSelected(selected)
        }
        return nav
    }

    private fun paint(nav: SettingsNavItem): BufferedImage {
        val img = BufferedImage(nav.width, nav.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        onEdt { nav.paint(g) }
        g.dispose()
        return img
    }

    private fun countColor(img: BufferedImage, color: Color): Int {
        var n = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                if (img.getRGB(x, y) == color.rgb) n++
            }
        }
        return n
    }

    @Test
    fun `选中那一枚铺的是强调色胶囊`() {
        val on = paint(item(selected = true))

        assertTrue(
            countColor(on, focusColor()) > 100,
            "选中项上数不到强调色像素（数出来 ${countColor(on, focusColor())}）—— 胶囊没画出来",
        )
    }

    @Test
    fun `没选中也没悬停时，底上什么都不画`() {
        val off = paint(item(selected = false))

        assertEquals(0, countColor(off, focusColor()), "没选中的页签上也有强调色")
    }

    /** 八条路径逐条画一遍：漏掉任何一枚都只会表现为"那个页签少了个小图"。 */
    @Test
    fun `八枚图标每枚都画得出东西`() {
        val side = JBUI.scale(NAV_ICON_SIDE)
        for (kind in NavIcon.entries) {
            val img = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            NavIconImpl(kind) { Color.WHITE }.paintIcon(null, g, 0, 0)
            g.dispose()

            var lit = 0
            for (y in 0 until img.height) {
                for (x in 0 until img.width) {
                    if (img.getRGB(x, y) != 0) lit++
                }
            }
            assertTrue(lit > 8, "${kind.name} 那枚图标画出来几乎是空的（只有 $lit 个像素）—— 路径抄错了？")
        }
    }
}
