package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JLabel

/**
 * 「群交流」页的用例。
 *
 * 这一页不读写配置，所以没什么"逻辑"可测 —— 真正会坏的是**资源**：
 * 图片没打进包、路径写错、缩放的算法把比例算错（码被拉扁就扫不出来）。
 */
class GroupChatSettingsPageTest {

    @Test
    fun `页签标题是「群交流」`() {
        assertEquals("群交流", GroupChatSettingsPage().title)
    }

    @Test
    fun `二维码能从资源里加载出来`() {
        val qr = GroupChatSettingsPage().loadScaledQr()
            ?: error("二维码没加载出来 —— 它在 src/main/resources/images/wechat-qr.jpg（随插件打包）")
        assertTrue(qr.width > 0 && qr.height > 0)
    }

    @Test
    fun `等比缩放：不会被拉变形，也不会超上限`() {
        val src = GroupChatSettingsPage().loadScaledQr() ?: error("加载失败")
        val page = GroupChatSettingsPage()

        // 原图 1036x1319（竖版）→ 高度先到上限
        assertTrue(src.width <= 360 && src.height <= 360, "尺寸超上限：${src.width}x${src.height}")
        // 长边贴上限（说明确实缩到位了），比例保持 1036:1319
        assertEquals(360, src.height, "高该顶到上限")
        assertEquals(1036.0 / 1319.0, src.width.toDouble() / src.height, 0.01, "比例变了 —— 码会被拉扁")
    }

    @Test
    fun `页面里挂着那张图`() {
        val root = GroupChatSettingsPage().component()

        val iconLabel = findLabelWithIcon(root)
        assertNotNull(iconLabel, "页里没有带图标的标签 —— 二维码没挂上去")
        assertTrue((iconLabel!!.icon.iconWidth) > 100, "图标太小，不像二维码")
    }

    private fun findLabelWithIcon(root: Container): JLabel? {
        for (child in root.components) {
            if (child is JLabel && child.icon != null) return child
            if (child is Container) findLabelWithIcon(child)?.let { return it }
        }
        return null
    }
}
