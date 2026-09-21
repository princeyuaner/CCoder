package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JLabel

/**
 * 输入区工具栏上的思考深度控件。
 *
 * 它显示的是「这一轮 Claude 要想多深」—— 用户拿它换延迟和 token 花销，
 * 所以两件事最要紧：标签得如实反映当前档位（不能撒谎），以及**分模型的
 * 那两档必须把话讲在明面上**（xhigh / max 在不支持的模型上会被静默降级，
 * 界面没有别的办法看出来）。
 */
class ComposerEffortTest {

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
    fun `标签带「思考」前缀，不会跟权限模式读混`() {
        // 它旁边就是权限模式的「标准」「仅规划」，一个孤零零的「高」
        // 会被连着读成模式的一部分
        val label = EffortLabel {}
        label.setEffort(EffortSetting.HIGH)

        assertTrue(label.text.startsWith("思考"), "实际：${label.text}")
        assertTrue(label.text.contains(EffortSetting.HIGH.label), "实际：${label.text}")
    }

    @Test
    fun `标签末尾有展开箭头 —— 挤掉它就看不出能点`() {
        val label = EffortLabel {}
        label.setEffort(EffortSetting.DEFAULT)

        assertTrue(label.text.endsWith(EXPAND_CARET), "实际：${label.text}")
    }

    @Test
    fun `默认档也照常显示，不用更淡的颜色藏起来`() {
        // 「默认」是一个正常的档位（不干预），不是"没配置"。
        // 三个标签并排站着，就它一个颜色不一样会显得像坏了
        val label = EffortLabel {}
        label.setEffort(EffortSetting.DEFAULT)

        assertEquals(UIUtil.getInactiveTextColor(), label.foreground)
    }

    @Test
    fun `点击把打开动作报出去`() {
        var opened = 0
        val label = EffortLabel { opened++ }

        click(label)

        assertEquals(1, opened)
    }

    /** 悬停反馈：与 [ModeLabel]、[ModelLabel] 对称 —— 三个标签并排，手感得一样。 */
    @Test
    fun `悬停时亮起来，移开还原`() {
        val label = EffortLabel {}
        label.setEffort(EffortSetting.DEFAULT)

        hover(label, true)
        assertEquals(UIUtil.getLabelForeground(), label.foreground, "悬停没有反馈")

        hover(label, false)
        assertEquals(UIUtil.getInactiveTextColor(), label.foreground, "移开后没还原")
    }

    // ---- 弹层 ----

    @Test
    fun `列表列出全部六档，不只是常用的那几档`() {
        // 用户明确选了"五档全给 + 默认"
        val list = buildEffortList(EffortSetting.DEFAULT) {}
        val texts = textsIn(list).joinToString("\n")

        EffortSetting.entries.forEach {
            assertTrue(texts.contains(it.label), "列表里没有 ${it.label}：\n$texts")
        }
    }

    /**
     * 2026-09-21 起勾是**单独一个标签**（好让它用强调色），所以断言从
     * "哪段文字里有勾"改成"勾落在哪一行里"。
     */
    @Test
    fun `列表只给当前档位打勾`() {
        val list = buildEffortList(EffortSetting.MEDIUM) {}

        assertEquals(1, textsIn(list).count { it == MARK }, "打勾的应该只有一行：${textsIn(list)}")

        val mediumRow = labelContaining(list, EffortSetting.MEDIUM.label).parent as Container
        assertTrue(textsIn(mediumRow).contains(MARK), "当前档没打勾：${textsIn(mediumRow)}")

        val maxRow = labelContaining(list, EffortSetting.MAX.label).parent as Container
        assertTrue(!textsIn(maxRow).contains(MARK), "非当前档也打了勾：${textsIn(maxRow)}")
    }

    // ---- 分组（2026-09-21：列表分成了「通用档」与「仅部分模型认」两张卡）----

    /**
     * 分组**不重排**：每一组在枚举里必须是连着的一段。
     *
     * 这是分卡片的前提。若哪天有人把某一档挪进另一组，列表顺序就会与枚举
     * （也就是用户熟悉的那一版：默认/低/中/高/极高/最大）不同，而卡片看上去
     * 仍然"对" —— 没有这一条，那种改动会静默通过。
     */
    @Test
    fun `分组在枚举顺序里是连着的`() {
        EffortGroup.entries.forEach { group ->
            val at = EffortSetting.entries.indices
                .filter { effortGroup(EffortSetting.entries[it]) == group }
            assertTrue(at.isNotEmpty(), "$group 一组都没分到")
            assertEquals(
                at.size, at.last() - at.first() + 1,
                "$group 在枚举里不是连着的一段：$at",
            )
        }
    }

    /** 每一档都得归到某一组 —— 漏掉的后果是它在弹层里**根本不显示**。 */
    @Test
    fun `每一档都出现在弹层里，且组名不是空白`() {
        val texts = textsIn(buildEffortList(EffortSetting.DEFAULT) {}).joinToString("\n")

        EffortSetting.entries.forEach {
            assertTrue(texts.contains(it.label), "${it.label} 没出现在列表里：\n$texts")
        }
        EffortGroup.entries.forEach {
            assertTrue(it.title.isNotBlank(), "$it 没有组名")
            assertTrue(texts.contains(it.title), "少了组名「${it.title}」：\n$texts")
        }
    }

    /**
     * 弹层的宽度是内容撑开的，而「极高/最大」那两句说明是最长的 ——
     * 卡片又比原来平铺多占了内边距。这条钉住它塞得进工具窗口（420px）。
     */
    @Test
    fun `弹层不会比工具窗口宽`() {
        val list = buildEffortList(EffortSetting.DEFAULT) {}

        assertTrue(list.preferredSize.width < 420, "弹层宽 ${list.preferredSize.width}px，比工具窗口还宽")
    }

    @Test
    fun `每一档都有说明，且互不重样`() {
        val all = EffortSetting.entries.map { effortDescription(it) }

        all.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(all.size, all.toSet().size, "两档共用一句说明，等于没说：$all")
    }

    @Test
    fun `分模型的两档把降级讲在明面上`() {
        // xhigh 与 max 在不支持的模型上会被 CLI **静默降级**（sdk.d.ts:597），
        // 界面上没有别的办法看出来。弹层这行说明是唯一的告知渠道 ——
        // 少了它，用户会以为自己选的「最大」真的生效了
        assertTrue(
            effortDescription(EffortSetting.XHIGH).contains("降级"),
            "「极高」没讲清楚会降级：${effortDescription(EffortSetting.XHIGH)}",
        )
        assertTrue(
            effortDescription(EffortSetting.MAX).contains("降级"),
            "「最大」没讲清楚会降级：${effortDescription(EffortSetting.MAX)}",
        )
    }

    @Test
    fun `整行可点，不只是文字那一小块`() {
        // 一行里拆成两个标签，点到说明上没反应会显得很钝
        val picked = mutableListOf<EffortSetting>()
        val list = buildEffortList(EffortSetting.DEFAULT) { picked += it }

        // 点「最大」那一行的**说明**标签 —— 它在一行里是另一个组件，
        // 而 Swing 的鼠标事件不冒泡：不给它挂监听，点上去就毫无反应
        click(labelContaining(list, effortDescription(EffortSetting.MAX)))

        assertEquals(listOf(EffortSetting.MAX), picked)
    }

    /**
     * 鼠标压在**名字或说明**上，这一行也要亮。
     *
     * Swing 的鼠标事件**不冒泡** —— 一行里几乎每一寸都被子标签盖着，只把
     * 悬停挂在行自己的话，鼠标压在文字上时这一行根本不亮，只有压在行边那几
     * 像素上才亮，看起来就像悬停是坏的。**渲染探针看不出这一条**（它是直接
     * 喊监听器的，绕过了真实的命中测试），所以只能在这儿钉。
     */
    @Test
    fun `鼠标压在文字上这一行也要亮`() {
        val list = buildEffortList(EffortSetting.DEFAULT) {}
        val name = labelContaining(list, EffortSetting.HIGH.label)
        val desc = labelContaining(list, effortDescription(EffortSetting.HIGH))

        assertFalse(rowOf(name).isHovered, "还没碰它就已经是悬停态了")
        hover(name, true)
        assertTrue(rowOf(name).isHovered, "压着名字时这一行不亮")

        hover(name, false)
        hover(desc, true)
        assertTrue(rowOf(desc).isHovered, "压着说明时这一行不亮")

        hover(desc, false)
        assertFalse(rowOf(desc).isHovered, "移开之后没灭")
    }

    /** 从行里的某个标签往上找那一行。 */
    private fun rowOf(c: Component): RoundedRow {
        var at: Container? = c.parent
        while (at != null) {
            if (at is RoundedRow) return at
            at = at.parent
        }
        error("这个组件上面没有行")
    }

    @Test
    fun `勾位在未选中的行上也占着，文字不会左右跳`() {
        // 打勾时行首多一个勾的宽度，若不给未选中的行补上，切换时整列文字会横向跳。
        // 2026-09-21 卡片版：勾住在一个**固定宽度的面板**里（[tickGutter]），
        // 所以直接钉它的宽度，不用再去翻组件树的形状
        val on = tickGutter(selected = true).preferredSize
        val off = tickGutter(selected = false).preferredSize

        assertEquals(on.width, off.width, "选中与未选中的勾位宽度不一样，文字会左右跳")
        assertTrue(off.width > 0, "未选中时勾位没有宽度")
    }
}
