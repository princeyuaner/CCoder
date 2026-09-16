package com.ccoder.ui

import com.ccoder.settings.PermissionModeSetting
import com.google.gson.JsonParser
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    /** 悬停/移开。直接调监听器：离屏组件收不到真实的鼠标进出事件。 */
    private fun hover(component: Component, entered: Boolean) {
        val e = MouseEvent(
            component,
            if (entered) MouseEvent.MOUSE_ENTERED else MouseEvent.MOUSE_EXITED,
            System.currentTimeMillis(), 0, 5, 5, 0, false,
        )
        component.mouseListeners.forEach {
            if (entered) it.mouseEntered(e) else it.mouseExited(e)
        }
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

    /**
     * 悬停反馈：模型标签那边也有一对对称的（[ComposerModelTest]）——
     * 两个标签并排站在同一行，一个有一个没有，看起来就像坏了。
     *
     * 这里额外钉一条**安全**性质：绕过的警示色不能因为鼠标划过来一趟就丢。
     * 这个标签的职责就是"Claude 现在被允许做什么"，丢色等于丢了那句话。
     */
    @Test
    fun `悬停时亮起来，移开把警示色原样放回来`() {
        val label = ModeLabel {}
        label.setMode(PermissionModeSetting.BYPASS_PERMISSIONS)

        hover(label, true)
        assertEquals(UIUtil.getLabelForeground(), label.foreground, "悬停没有反馈")

        hover(label, false)
        assertSame(warningColor(), label.foreground, "移开后警示色丢了")
    }

    @Test
    fun `普通模式悬停也亮，移开回到次要文字`() {
        val label = ModeLabel {}
        label.setMode(PermissionModeSetting.DEFAULT)

        hover(label, true)
        assertEquals(UIUtil.getLabelForeground(), label.foreground, "悬停没有反馈")

        hover(label, false)
        assertEquals(UIUtil.getInactiveTextColor(), label.foreground, "移开后没有恢复")
    }

    @Test
    fun `自动放行时悬停，移开同样放回警示色`() {
        val label = ModeLabel {}
        label.setAutoAllow()

        hover(label, true)
        assertEquals(UIUtil.getLabelForeground(), label.foreground)

        hover(label, false)
        assertSame(warningColor(), label.foreground, "自动放行的警示色丢了")
    }

    // ---- 弹层里的列表 ----

    @Test
    fun `列表列出全部模式，不只是安全的那些`() {
        // 用户明确选了"全都列出来"（枚举长一个，这里就自动多一条）
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

    // ---- status 事件里的**生效模式**（读回）----
    //
    // 2026-09-16 实测（tools/probe-auto-mode.mjs）：切完模式 CLI 会吐一条
    // `system/status`，里面带着 `permissionMode` —— 这是唯一一条能读回
    // "此刻真正在跑什么模式"的路。下面钉的是它的**不猜**：认不出就 null。

    @Test
    fun `status 事件里的 permissionMode 能读出来`() {
        val event = JsonParser
            .parseString("""{"type":"system","subtype":"status","status":null,"permissionMode":"auto"}""")
            .asJsonObject

        assertEquals(PermissionModeSetting.AUTO, permissionModeOfStatus(event))
    }

    @Test
    fun `只有 status 那一条才算 —— init 也带 permissionMode，但那条不是实时读数`() {
        val init = JsonParser
            .parseString("""{"type":"system","subtype":"init","permissionMode":"auto"}""")
            .asJsonObject

        assertNull(permissionModeOfStatus(init))
    }

    @Test
    fun `认不出的模式名给 null —— 显示一个自己不认识的模式不如保持原样`() {
        val event = JsonParser
            .parseString("""{"type":"system","subtype":"status","permissionMode":"somethingNew"}""")
            .asJsonObject

        assertNull(permissionModeOfStatus(event))
    }

    @Test
    fun `status 没带 permissionMode 时给 null（它是可选字段）`() {
        val event = JsonParser
            .parseString("""{"type":"system","subtype":"status","status":"compacting"}""")
            .asJsonObject

        assertNull(permissionModeOfStatus(event))
    }
}
