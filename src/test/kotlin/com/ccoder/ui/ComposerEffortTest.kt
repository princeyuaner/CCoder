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

    @Test
    fun `列表只给当前档位打勾`() {
        val list = buildEffortList(EffortSetting.MEDIUM) {}
        val checked = textsIn(list).filter { it.startsWith(MARK) }

        assertEquals(1, checked.size, "打勾的应该只有一行：$checked")
        assertTrue(checked.single().contains(EffortSetting.MEDIUM.label), "勾打错了：$checked")
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

        // 最后一行是 MAX；点它的**说明**那个标签
        val row = list.components.last() as Container
        click(row.components.last())

        assertEquals(listOf(EffortSetting.MAX), picked)
    }

    @Test
    fun `勾位在未选中的行上也占着，文字不会左右跳`() {
        // 打勾时行首多一个字符宽度，若不给未选中的行补上，切换时
        // 整列文字会横向跳一下
        val list = buildEffortList(EffortSetting.DEFAULT) {}
        val rows = (0 until list.componentCount).map { list.getComponent(it) as Container }
        val nameTexts = rows.map { (it.components.first() as JLabel).text }

        // 未打勾的行以空格开头，打勾的以 MARK 开头 —— 两者都是两个字符
        assertTrue(nameTexts.any { it.startsWith(MARK) }, "没有一行打勾：$nameTexts")
        assertFalse(nameTexts.any { it.isNotEmpty() && !it.startsWith(MARK) && !it.startsWith(" ") },
            "有行的首字符既不是勾也不是空格，缩进对不齐：$nameTexts")
    }
}
