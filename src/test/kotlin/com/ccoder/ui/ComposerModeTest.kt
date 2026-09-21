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
    private fun labelContaining(root: Container, text: String): JLabel {
        fun walk(c: Container): JLabel? {
            for (child in c.components) {
                if (child is JLabel && child.text.contains(text)) return child
                if (child is Container) walk(child)?.let { return it }
            }
            return null
        }
        return walk(root) ?: error("没有找到写着「$text」的标签")
    }

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

    /**
     * 2026-09-21 起勾是**单独一个标签**（好让它用强调色），所以断言从
     * "哪段文字里有勾"改成"勾落在哪一行里" —— 同一个意思，而且更贴题。
     */
    @Test
    fun `列表标出当前模式`() {
        val list = buildModeList(PermissionModeSetting.PLAN) {}

        assertEquals(1, textsIn(list).count { it == MARK }, "应当且只应当标一个当前项：${textsIn(list)}")

        val planRow = labelContaining(list, PermissionModeSetting.PLAN.label).parent as Container
        assertTrue(textsIn(planRow).contains(MARK), "当前项没打勾：${textsIn(planRow)}")

        val bypass = labelContaining(list, PermissionModeSetting.BYPASS_PERMISSIONS.label).parent as Container
        assertTrue(!textsIn(bypass).contains(MARK), "非当前项也打了勾：${textsIn(bypass)}")
    }

    // ---- 分组（2026-09-21：列表分成了「常规」与「不再问你」两张卡）----

    /**
     * 分组**不重排**：每一组在枚举里必须是连着的一段。
     *
     * 这是分卡片的前提。若哪天有人把某一档挪进另一组，列表顺序就会与枚举
     * （也就是用户熟悉的那一版）不同，而卡片看上去仍然"对" —— 没有这一条，
     * 那种改动会静默通过。
     */
    @Test
    fun `分组在枚举顺序里是连着的`() {
        ModeGroup.entries.forEach { group ->
            val at = PermissionModeSetting.entries.indices
                .filter { modeGroup(PermissionModeSetting.entries[it]) == group }
            assertTrue(at.isNotEmpty(), "$group 一组都没分到")
            assertEquals(
                at.size, at.last() - at.first() + 1,
                "$group 在枚举里不是连着的一段：$at",
            )
        }
    }

    /** 每一档都得归到某一组 —— 漏掉的后果是它在弹层里**根本不显示**。 */
    @Test
    fun `每个模式都出现在弹层里，且组名不是空白`() {
        val texts = textsIn(buildModeList(PermissionModeSetting.DEFAULT) {}).joinToString("\n")

        PermissionModeSetting.entries.forEach {
            assertTrue(texts.contains(it.label), "${it.label} 没出现在列表里：\n$texts")
        }
        ModeGroup.entries.forEach {
            assertTrue(it.title.isNotBlank(), "$it 没有组名")
            assertTrue(texts.contains(it.title), "少了组名「${it.title}」：\n$texts")
        }
    }

    /**
     * 弹层的宽度是内容撑开的，而说明是文案里最长的那些 —— 卡片又比原来
     * 平铺多占了内边距。这条钉住它塞得进工具窗口（420px）。
     */
    @Test
    fun `弹层不会比工具窗口宽`() {
        val list = buildModeList(PermissionModeSetting.DEFAULT) {}

        assertTrue(list.preferredSize.width < 420, "弹层宽 ${list.preferredSize.width}px，比工具窗口还宽")
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
