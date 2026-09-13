package com.ccoder.ui

import com.ccoder.settings.ModelProfile
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JLabel

/**
 * 输入区左下角的模型标签与切换弹层。
 *
 * 它和权限模式标签并排站在同一行，所以除了"功能对不对"，还多一条
 * "手感一不一致"—— 同一行里两个标签，一个能点一个不能、一个有悬停
 * 一个没有，看起来就像坏了。用例照 [ComposerModeTest] 的样子写。
 */
class ComposerModelTest {

    // ---- 标签上写什么 ----

    @Test
    fun `没选中配置时标签写无模型`() {
        assertEquals("无模型", modelLabelText(null))
    }

    @Test
    fun `选中时标签写配置名`() {
        assertEquals("中转 Opus", modelLabelText(ModelProfile(name = "中转 Opus")))
    }

    @Test
    fun `名字空白的配置退回写模型 ID`() {
        // 用户可能只填了 modelId 就存了 —— 标签不该是空的
        val p = ModelProfile(name = "  ", modelId = "deepseek-flash")
        assertEquals("deepseek-flash", modelLabelText(p))
    }

    @Test
    fun `名字与 ID 都空时写未命名`() {
        assertEquals("未命名", modelLabelText(ModelProfile()))
    }

    // ---- 标签本身 ----

    @Test
    fun `标签带上展开箭头，看得出能点`() {
        val label = ModelLabel {}
        label.setProfile(ModelProfile(name = "中转 Opus"))

        assertTrue(label.text.endsWith(EXPAND_CARET), "实际：${label.text}")
    }

    @Test
    fun `点击把打开动作报出去`() {
        var opened = 0
        val label = ModelLabel { opened++ }

        click(label)

        assertEquals(1, opened)
    }

    /**
     * 三档颜色的分工：没配置 → 最淡、有配置 → 安静的次要文字、悬停 → 亮起来。
     *
     * 用 value 相等而不是同一性：平台这两个取值每次调用都可能给新实例
     * （只有 `getInactiveTextColor` 有缓存），同一性断言就成了在钉实现的细节。
     * 另外这两档淡色在默认浅色主题里**恰好是同一个灰**（都 #999999）——
     * 那不是这里要钉的东西，别把主题的巧合写进断言。
     */
    @Test
    fun `没配置时用最淡的那一档，有配置时回到次要文字`() {
        val label = ModelLabel {}

        label.setProfile(null)
        assertEquals(UIUtil.getLabelDisabledForeground(), label.foreground, "没配置时应当最淡")

        label.setProfile(ModelProfile(name = "中转 Opus"))
        assertEquals(UIUtil.getInactiveTextColor(), label.foreground, "有配置时回到安静的次要文字")
    }

    /**
     * 悬停反馈是这里**新加**的（[ModeLabel] 现在还没有），但它不是装饰：
     * 这行标签看着就是普通文字，不给反馈就没人知道它能点。
     */
    @Test
    fun `悬停时标签亮起来，移开就恢复`() {
        val label = ModelLabel {}
        label.setProfile(ModelProfile(name = "中转 Opus"))

        hover(label, true)
        assertEquals(UIUtil.getLabelForeground(), label.foreground, "悬停没有反馈")
        assertNotEquals(
            UIUtil.getInactiveTextColor(), UIUtil.getLabelForeground(),
            "悬停色得真的比平时亮，否则这个反馈等于没做",
        )

        hover(label, false)
        assertEquals(UIUtil.getInactiveTextColor(), label.foreground, "移开后没有恢复")
    }

    // ---- 弹层里的列表 ----

    @Test
    fun `列表列出每一条配置`() {
        val list = buildModelList(listOf(profile("a", "中转 Opus"), profile("b", "本地 Qwen")), "a", {}, {})
        val texts = textsIn(list).joinToString("\n")

        assertTrue(texts.contains("中转 Opus"), "少了第一条：\n$texts")
        assertTrue(texts.contains("本地 Qwen"), "少了第二条：\n$texts")
    }

    @Test
    fun `列表标出当前配置`() {
        val list = buildModelList(listOf(profile("a", "中转 Opus"), profile("b", "本地 Qwen")), "b", {}, {})
        val marked = textsIn(list).filter { it.contains(MARK) }

        assertEquals(1, marked.size, "应当且只应当标一个当前项：$marked")
        assertTrue(marked[0].contains("本地 Qwen"), "标错了：${marked[0]}")
    }

    @Test
    fun `点某一行把那条配置报出去`() {
        val picked = mutableListOf<ModelProfile>()
        val target = profile("b", "本地 Qwen")
        val list = buildModelList(listOf(profile("a", "中转 Opus"), target), "a", { picked += it }, {})

        click(labelContaining(list, "本地 Qwen"))

        assertEquals(listOf(target), picked)
    }

    /**
     * 一条配置都没配时，弹层不能是空的 —— 那看起来像抽风。它得说清楚
     * 现状，并给出唯一的出路（去设置里加一条）。
     */
    @Test
    fun `没有配置时列表说明现状`() {
        val texts = textsIn(buildModelList(emptyList(), null, {}, {})).joinToString("\n")

        assertTrue(texts.contains("还没有配置任何模型"), "实际：$texts")
    }

    @Test
    fun `列表里有去管理配置的入口`() {
        var managed = 0
        val list = buildModelList(listOf(profile("a", "中转 Opus")), "a", {}, { managed++ })

        click(labelContaining(list, MANAGE_LABEL))

        assertEquals(1, managed)
    }

    /**
     * 弹层**只负责切**，不负责改 —— 编辑是设置页的事。塞进表单会让
     * "点一下切换"变成"点一下进表单"，高频动作被低频动作拖累。
     */
    @Test
    fun `操作行只说管理，不出现编辑表单的字段`() {
        val texts = textsIn(buildModelList(listOf(profile("a", "中转 Opus")), "a", {}, {})).joinToString("\n")

        assertTrue(texts.contains(MANAGE_LABEL), "实际：$texts")
        listOf("Base URL", "密钥", "认证方式").forEach {
            assertTrue(!texts.contains(it), "弹层里不该出现「$it」：\n$texts")
        }
    }

    // ---- 行里的第二行 ----

    @Test
    fun `第二行写明模型 ID 与端点主机`() {
        // 两条配置可以都叫「中转」，光看名字选不出来 —— 得让用户认出是哪条
        val p = ModelProfile(name = "中转", modelId = " deepseek-flash ", baseUrl = "https://api.deepseek.com/v1")

        assertEquals("deepseek-flash · api.deepseek.com", modelDetail(p))
    }

    @Test
    fun `第二行不出现空白：没填端点写官方端点，没填 ID 也说一声`() {
        assertEquals("未填写模型 ID · 官方端点", modelDetail(ModelProfile(name = "官方")))
    }

    @Test
    fun `主机名再长也会截断`() {
        val p = ModelProfile(name = "公司中转", modelId = "m", baseUrl = "https://api.anthropic-relay.internal.corp.example.com")

        val detail = modelDetail(p)

        assertTrue(detail.endsWith("…"), "没有截断：$detail")
        assertTrue(detail.length < 40, "截完还是太长：$detail")
    }

    /**
     * 弹层的宽度是内容撑开的，而主机名是用户填的 —— 不设上限的话，一条配置
     * 就能把弹层撑得比工具窗口（420px）还宽。这条钉的是那个上限。
     */
    @Test
    fun `弹层不会比工具窗口宽`() {
        val long = ModelProfile(
            name = "公司中转",
            modelId = "claude-opus-4-6-20250929",
            baseUrl = "https://api.anthropic-relay.internal.corp.example.com",
        )

        val list = buildModelList(listOf(long, profile("b", "本地 Qwen")), "b", {}, {})

        // 列表自己是 preference 撑开的，父面板的边距不算在内
        assertTrue(list.preferredSize.width < 420, "弹层宽 ${list.preferredSize.width}px，比工具窗口还宽")
    }

    // ---- 工具 ----

    private fun profile(id: String, name: String) =
        ModelProfile(id = id, name = name, modelId = "m-$id", baseUrl = "https://api.example.com")

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
}
