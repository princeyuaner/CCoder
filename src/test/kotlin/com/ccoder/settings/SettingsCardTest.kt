package com.ccoder.settings

import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * 卡片那三件套的用例（2026-09-20 方案 B）。
 *
 * 这里钉的是**别处会静默坏掉**的几件事：控件必须是行的直接子件（`McpSettingsPageTest`
 * 与定位器都指望它）、卡不能变成页里的弹簧、卡的底与边同一个圆角。
 * "好不好看"不归它管 —— 那看探针出的图。
 */
class SettingsCardTest {

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun layoutAll(c: Container) {
        c.invalidate()
        c.doLayout()
        c.components.filterIsInstance<Container>().forEach { layoutAll(it) }
    }

    @Test
    fun `一行里控件是直接子件，说明挂在标签下面`() {
        lateinit var row: javax.swing.JPanel
        lateinit var field: JTextField
        onEdt {
            field = JTextField()
            row = settingsRow("名称", field, hint = "这句说明不该顶掉输入框")
        }

        assertSame(
            row,
            field.parent,
            "控件不是这一行的直接子件 —— 用例断的 inputOf(...).parent.isVisible 会永远为真（收起那件事看不见）",
        )
        assertSame(field, inputOf(row, "名称"), "定位器在卡片行里取错了控件（是不是把说明那块 JTextArea 当成了输入）")
    }

    @Test
    fun `标签列按最长的那个对齐 —— 控件成一竖线，标签不许被截`() {
        lateinit var short: javax.swing.JPanel
        lateinit var long: javax.swing.JPanel
        onEdt {
            short = settingsRow("名称", JTextField())
            long = settingsRow("没有选中配置时用的模型", JTextField())
            alignLabelColumns(short, long)
        }

        val a = labelWidthOf(short)
        val b = labelWidthOf(long)

        assertEquals(a, b, "两行标签列宽度不一样，控件左沿会参差")
        assertTrue(b >= 176, "对齐之后反而比默认宽度还窄：$b")
    }

    @Test
    fun `卡不是页里的弹簧 —— 高度永远等于当前首选高度`() {
        lateinit var card: CardPanel
        onEdt {
            card = settingsCard("配置", cardRows(settingsRow("名称", JTextField())))
        }

        assertEquals(card.preferredSize.height, card.maximumSize.height, "卡的高度上限不是首选高度：它会变成弹簧")
        assertTrue(card.maximumSize.width > card.preferredSize.width, "宽度得能铺满整列")
    }

    @Test
    fun `卡头写着的标题找得到`() {
        lateinit var card: CardPanel
        onEdt { card = settingsCard("配置", cardRows(settingsRow("名称", JTextField()))) }

        assertNotNull(findLabel(card, "配置"), "卡头那行没画出来")
    }

    /** 四角削圆、卡底与页底不同色 —— 都是"一眼看出这是卡片"的前提。 */
    @Test
    fun `卡的底是圆角，而且与页底不是同一个色`() {
        lateinit var card: CardPanel
        onEdt {
            card = settingsCard("配置", cardRows(settingsRow("名称", JTextField())))
            card.setSize(240, 90)
            layoutAll(card)
        }

        val img = BufferedImage(240, 90, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        onEdt { card.paint(g) }
        g.dispose()

        assertEquals(cardFill().rgb, img.getRGB(120, 45), "卡里没铺上卡底")
        assertNotEquals(cardFill().rgb, img.getRGB(0, 0), "四个角没削圆 —— (0,0) 也被铺上了底")
        assertNotEquals(
            UIUtil.getPanelBackground().rgb,
            cardFill().rgb,
            "卡底与页底是同一个颜色，卡片就只剩一圈线了",
        )
    }

    private fun labelWidthOf(row: javax.swing.JPanel): Int =
        row.components.filterIsInstance<javax.swing.JComponent>().first().preferredSize.width
}
