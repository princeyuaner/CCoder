package com.ccoder.ui

import com.ccoder.settings.PermissionModeSetting
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JLabel

/**
 * 输入区工具栏上的权限模式控件。
 *
 * 它是个**安全控件**：它显示的就是"Claude 现在被允许做什么"。所以
 * 这两条最要紧 —— 危险模式必须一直看得见，以及标签只在确认生效后才改。
 */
class ComposerModeTest {

    private fun click(component: Component) {
        component.dispatchEvent(
            MouseEvent(
                component, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 5, 5, 1, false, MouseEvent.BUTTON1,
            )
        )
    }

    /** 收集一棵组件树里所有 JLabel 的文字，用来断言弹层里显示了什么。 */
    private fun textsIn(root: Container): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JLabel) out += child.text
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    // ---- 标签本身 ----

    @Test
    fun `标签显示模式的中文名`() {
        val label = ModeLabel {}
        label.setMode(PermissionModeSetting.PLAN)

        assertTrue(label.text.contains(PermissionModeSetting.PLAN.label), "实际：${label.text}")
    }

    @Test
    fun `危险模式用警示色常驻显示`() {
        // 用户选了"进下拉框直接生效"、没有二次确认，那么"现在开着绕过"
        // 就必须一直看得见 —— 靠人记得是不可靠的
        val label = ModeLabel {}

        label.setMode(PermissionModeSetting.BYPASS_PERMISSIONS)
        assertSame(warningColor(), label.foreground, "绕过权限没有用警示色")

        label.setMode(PermissionModeSetting.DEFAULT)
        assertEquals(
            UIUtil.getInactiveTextColor(), label.foreground,
            "切回安全模式后应恢复成安静的次要文字",
        )
    }

    @Test
    fun `本会话不再询问时标签如实显示，并用警示色`() {
        // 开着自动放行却还显示「标准」，那是界面在撒谎 —— 而且是个要命的谎：
        // 用户会以为 Claude 还会问，实际不会
        val label = ModeLabel {}

        label.setAutoAllow()

        assertTrue(label.text.contains(AUTO_ALLOW_LABEL), "实际：${label.text}")
        assertSame(warningColor(), label.foreground, "自动放行没有用警示色")
    }

    @Test
    fun `切回某个模式后标签回到那个模式`() {
        val label = ModeLabel {}
        label.setAutoAllow()

        label.setMode(PermissionModeSetting.DEFAULT)

        assertTrue(label.text.contains(PermissionModeSetting.DEFAULT.label), "实际：${label.text}")
        assertEquals(UIUtil.getInactiveTextColor(), label.foreground)
    }

    @Test
    fun `点击把打开动作报出去`() {
        var opened = 0
        val label = ModeLabel { opened++ }

        click(label)

        assertEquals(1, opened)
    }

    // ---- 弹层里的列表 ----

    @Test
    fun `列表列出全部模式，不只是安全的那些`() {
        // 用户明确选了"5 个都列"
        val list = buildModeList(PermissionModeSetting.DEFAULT) {}
        val texts = textsIn(list).joinToString("\n")

        PermissionModeSetting.entries.forEach {
            assertTrue(texts.contains(it.label), "列表里没有 ${it.label}：\n$texts")
        }
    }

    @Test
    fun `列表标出当前模式`() {
        val list = buildModeList(PermissionModeSetting.PLAN) {}
        val lines = textsIn(list)

        val marked = lines.filter { it.contains(MARK) }
        assertEquals(1, marked.size, "应当且只应当标一个当前项：$lines")
        assertTrue(marked[0].contains(PermissionModeSetting.PLAN.label), "标错了：${marked[0]}")
    }

    @Test
    fun `每个模式都有一句说明`() {
        // 光有"仅规划""不询问"这种名字，用户不知道选下去意味着什么
        PermissionModeSetting.entries.forEach {
            assertTrue(modeDescription(it).isNotBlank(), "$it 没有说明")
        }
    }

    @Test
    fun `绕过的说明讲清后果而不只是重复名字`() {
        val text = modeDescription(PermissionModeSetting.BYPASS_PERMISSIONS)

        assertTrue(text.contains("不再询问") || text.contains("不询问"), "实际：$text")
    }

}
