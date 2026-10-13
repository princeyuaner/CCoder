package com.ccoder.ui

import com.ccoder.settings.ModelProfile
import com.intellij.util.ui.JBUI
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

    /**
     * 显示**模型 ID** 而不是配置名：一条配置可以挂好几个模型，而它们跑起来
     * 不是一回事 —— 标签还写着配置名的话，切完模型看不出来切了没有。
     */
    @Test
    fun `选中时标签写当前模型 ID`() {
        val p = ModelProfile(name = "中转 Opus", modelId = "deepseek-flash", modelIds = mutableListOf("deepseek-flash"))

        assertEquals("deepseek-flash", modelLabelText(p))
    }

    /** 这条配置没指定模型时，配置名才是唯一能说明"用的是哪条"的东西。 */
    @Test
    fun `配置没指定模型时退回写配置名`() {
        assertEquals("中转 Opus", modelLabelText(ModelProfile(name = "中转 Opus")))
    }

    @Test
    fun `名字与 ID 都空时写未命名`() {
        assertEquals("未命名", modelLabelText(ModelProfile()))
    }

    /**
     * 模型 ID 是用户填的、没有上限，而这一行还并排站着权限与思考两个标签 ——
     * 不截断的话一个长名字会把那两个挤出工具窗口。
     */
    @Test
    fun `超长的模型 ID 会截断`() {
        val p = ModelProfile(modelId = "claude-opus-4-6-20250929-with-a-very-long-suffix")

        val text = modelLabelText(p)

        assertTrue(text.endsWith("…"), "没有截断：$text")
        assertTrue(text.length <= 25, "截完还是太长：$text")
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
     * 悬停反馈不是装饰：这行标签看着就是普通文字，不给反馈就没人知道它能点。
     * [ModeLabel] 里后来也补了同一套（两个标签并排站着，手感得一样），
     * 所以这条同时也是那条约束的一半 —— 另一半在 [ComposerModeTest] 里。
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

    // ---- 弹层：分组 ----

    /**
     * 两栏（2026-10-13）：左栏是**配置索引**（全都列出来），右栏只画
     * **浏览中那条**的模型。光有模型名认不出是哪来的 —— 两条配置完全可以
     * 是同一个网关、同一族模型名，所以卡头与左栏都得带着配置的身份。
     */
    @Test
    fun `左栏列配置、右栏只画浏览那条的模型`() {
        val list = buildModelList(
            profiles = listOf(
                ModelProfile(id = "a", name = "中转", modelIds = mutableListOf("flash", "pro")),
                ModelProfile(id = "b", name = "本地", modelIds = mutableListOf("qwen")),
            ),
            current = null,
            browsedProfileId = "a",
            effectOf = { PickEffect.Hot },
            onPick = {},
            onSelectProfile = {},
            onManage = {},
        )
        val texts = textsIn(list)

        assertTrue(texts.any { it.contains("中转") }, "少了浏览那条的配置名：$texts")
        assertTrue(texts.any { it.contains("本地") }, "左栏漏了别的配置：$texts")
        assertTrue(texts.any { it.contains("flash") }, "少了 flash：$texts")
        assertTrue(texts.any { it.contains("pro") }, "少了 pro：$texts")
        assertTrue(texts.none { it.contains("qwen") }, "没浏览的那条不该画出模型：$texts")
    }

    /**
     * 点左栏只换右栏看什么（浏览），**不动**当前选择 —— 想看看别条有什么模型，
     * 不该顺手把会话换了。切换只有点模型行那一条路。
     */
    @Test
    fun `点左栏只报浏览，不报切换`() {
        val browsed = mutableListOf<String>()
        val picked = mutableListOf<ModelPick>()
        val list = buildModelList(
            profiles = listOf(
                ModelProfile(id = "a", name = "中转", modelIds = mutableListOf("flash")),
                ModelProfile(id = "b", name = "本地", modelIds = mutableListOf("qwen")),
            ),
            current = ModelPick("a", "flash"),
            browsedProfileId = "a",
            effectOf = { PickEffect.Hot },
            onPick = { picked += it },
            onSelectProfile = { browsed += it },
            onManage = {},
        )

        click(labelContaining(list, "本地"))

        assertEquals(listOf("b"), browsed, "点左栏要报出浏览目标")
        assertTrue(picked.isEmpty(), "点左栏不该切换任何模型：$picked")
    }

    /**
     * 勾判的是**两级**：选中的那条配置、以及它当前那个模型。
     *
     * 2026-09-21 起勾是**单独一个标签**（好让它用强调色），所以断言从
     * "哪段文字里有勾"改成"勾落在哪一行里" —— 同一个意思，而且更贴题。
     */
    @Test
    fun `当前项只打一个勾`() {
        val list = buildModelList(
            profiles = listOf(
                ModelProfile(id = "a", name = "中转", modelId = "pro", modelIds = mutableListOf("flash", "pro")),
                ModelProfile(id = "b", name = "本地", modelId = "qwen", modelIds = mutableListOf("qwen")),
            ),
            current = ModelPick("a", "pro"),
            browsedProfileId = null,
            effectOf = { PickEffect.Hot },
            onPick = {},
            onSelectProfile = {},
            onManage = {},
        )

        assertEquals(1, textsIn(list).count { it == MARK }, "应当且只应当标一个当前项：${textsIn(list)}")

        val proRow = labelContaining(list, "pro").parent as Container
        assertTrue(textsIn(proRow).contains(MARK), "当前项没打勾：${textsIn(proRow)}")

        val flashRow = labelContaining(list, "flash").parent as Container
        assertTrue(!textsIn(flashRow).contains(MARK), "非当前项也打了勾：${textsIn(flashRow)}")
    }

    /**
     * 一条没指定模型的配置（官方端点用 CLI 默认模型那种）也是合法配置，
     * 所以它也得有一行可以选 —— 只显示组头的话，它就成了纯装饰。
     */
    @Test
    fun `没指定模型的配置也有一行可选`() {
        val picked = mutableListOf<ModelPick>()
        val list = buildModelList(
            profiles = listOf(ModelProfile(id = "a", name = "官方")),
            current = null,
            browsedProfileId = "a",
            effectOf = { PickEffect.Hot },
            onPick = { picked += it },
            onSelectProfile = {},
            onManage = {},
        )

        assertTrue(textsIn(list).any { it.contains(NO_MODEL_LABEL) }, "少了那一行：${textsIn(list)}")
        click(labelContaining(list, NO_MODEL_LABEL))
        assertEquals(listOf(ModelPick("a", "")), picked)
    }

    @Test
    fun `点某一行报出配置与模型两级`() {
        val picked = mutableListOf<ModelPick>()
        val list = buildModelList(
            profiles = listOf(ModelProfile(id = "a", name = "中转", modelIds = mutableListOf("flash", "pro"))),
            current = null,
            browsedProfileId = "a",
            effectOf = { PickEffect.Hot },
            onPick = { picked += it },
            onSelectProfile = {},
            onManage = {},
        )

        click(labelContaining(list, "pro"))

        assertEquals(listOf(ModelPick("a", "pro")), picked)
    }

    // ---- 弹层：后果写出来 ----

    /**
     * 跨配置切换会重开会话（端点或凭证变了），这件事要在**点之前**就说清楚。
     * 今天只在忙的时候弹确认框，空闲时一个字都不说，用户切完才发现上下文没了。
     *
     * 两栏版里它跟着**浏览**走：右栏画谁就标谁 —— 你正看着的这条点下去什么
     * 后果，正好是点之前要知道的那件事。
     */
    @Test
    fun `重开标记跟着浏览的那条走`() {
        val profiles = listOf(
            ModelProfile(id = "a", name = "中转", modelIds = mutableListOf("flash")),
            ModelProfile(id = "b", name = "本地", modelIds = mutableListOf("qwen")),
        )
        val effectOf: (ModelProfile) -> PickEffect =
            { p -> if (p.id == "a") PickEffect.Hot else PickEffect.Restart }

        val onA = textsIn(buildModelList(profiles, null, "a", effectOf, {}, {}, {}))
        assertEquals(0, onA.count { it == "会重开会话" }, "热切换那条不该标：$onA")

        val onB = textsIn(buildModelList(profiles, null, "b", effectOf, {}, {}, {}))
        assertEquals(1, onB.count { it == "会重开会话" }, "浏览到会重开的那条要标：$onB")
    }

    /** 没有会话时标"会重开会话"是吓唬人 —— 那时根本没有上下文可丢。 */
    @Test
    fun `没有会话时不标重开`() {
        assertTrue(restartBadge(PickEffect.NoSession) == null)
        assertTrue(restartBadge(PickEffect.Hot) == null)
        assertEquals("会重开会话", restartBadge(PickEffect.Restart))
    }

    // ---- 弹层：其它 ----

    @Test
    fun `没有配置时列表说明现状`() {
        val texts = textsIn(buildModelList(emptyList(), null, null, { PickEffect.Hot }, {}, {}, {}))
            .joinToString("\n")

        assertTrue(texts.contains("还没有配置任何模型"), "实际：$texts")
    }

    @Test
    fun `列表里有去管理配置的入口`() {
        var managed = 0
        val list = buildModelList(
            profiles = listOf(ModelProfile(id = "a", name = "中转", modelIds = mutableListOf("flash"))),
            current = null,
            browsedProfileId = null,
            effectOf = { PickEffect.Hot },
            onPick = {},
            onSelectProfile = {},
            onManage = { managed++ },
        )

        click(labelContaining(list, MANAGE_LABEL))

        assertEquals(1, managed)
    }

    /**
     * 弹层**只负责切**，不负责改 —— 编辑是设置页的事。塞进表单会让
     * "点一下切换"变成"点一下进表单"，高频动作被低频动作拖累。
     */
    @Test
    fun `操作行只说管理，不出现编辑表单的字段`() {
        val texts = textsIn(
            buildModelList(
                profiles = listOf(ModelProfile(id = "a", name = "中转", modelIds = mutableListOf("flash"))),
                current = null,
                browsedProfileId = null,
                effectOf = { PickEffect.Hot },
                onPick = {},
                onSelectProfile = {},
                onManage = {},
            )
        ).joinToString("\n")

        assertTrue(texts.contains(MANAGE_LABEL), "实际：$texts")
        listOf("Base URL", "密钥", "认证方式").forEach {
            assertTrue(!texts.contains(it), "弹层里不该出现「$it」：\n$texts")
        }
    }

    // ---- 卡头与左栏写什么 ----

    @Test
    fun `卡头写配置名与端点主机`() {
        // 两条配置可以都叫「中转」，光看名字选不出来 —— 得让用户认出是哪条。
        // 2026-09-21 起这两个是**两个标签**（主次不同色），所以断言看渲染出来的文字
        val p = ModelProfile(name = "中转", baseUrl = "https://api.deepseek.com/v1")

        val texts = textsIn(buildModelList(listOf(p), null, null, { PickEffect.Hot }, {}, {}, {}))

        assertTrue(texts.contains("中转"), "少了配置名：$texts")
        assertTrue(texts.contains("api.deepseek.com"), "少了端点主机：$texts")
    }

    /**
     * 左栏名字按**像素**截，不按字数：名字里中英混着（「公司tokenhub」），
     * 一个汉字宽与一个字母宽差一倍 —— 按字数截必然一边浪费一边溢出。
     */
    @Test
    fun `左栏名字按像素截断`() {
        val font = UIUtil.getLabelFont()
        val budget = JBUI.scale(76)

        val cut = ellipsizeToWidth("公司tokenhub极度冗长的名字", budget, font)

        assertTrue(cut.endsWith("…"), "没有截断：$cut")
        assertTrue(
            JLabel().getFontMetrics(font).stringWidth(cut) <= budget,
            "截完还是太宽：$cut",
        )
        assertEquals("我的ds", ellipsizeToWidth("我的ds", budget, font), "放得下的名字不该动")
    }

    @Test
    fun `端点不出现空白：没填写官方端点`() {
        assertEquals("官方端点", endpointLabel(""))
    }

    @Test
    fun `主机名再长也会截断`() {
        val host = endpointLabel("https://api.anthropic-relay.internal.corp.example.com")

        assertTrue(host.endsWith("…"), "没有截断：$host")
        assertTrue(host.length < 40, "截完还是太长：$host")
    }

    /**
     * 弹层的宽度是内容撑开的，而主机名与配置名是用户填的 —— 不设上限的话，
     * 一条配置就能把弹层撑得比工具窗口（420px）还宽。两栏版又多了一条左栏，
     * 这条钉的是**两栏加起来**的上限，取的是最宽的那种组合：长配置名 +
     * 长主机名 + 长模型 ID + 重开标记 + 左栏最长的名字。
     */
    @Test
    fun `弹层不会比工具窗口宽`() {
        val long = ModelProfile(
            id = "a",
            name = "公司中转",
            modelIds = mutableListOf("claude-opus-4-6-20250929-thinking-with-extra-suffix"),
            modelId = "claude-opus-4-6-20250929-thinking-with-extra-suffix",
            baseUrl = "https://api.anthropic-relay.internal.corp.example.com",
        )

        val list = buildModelList(
            profiles = listOf(
                long,
                ModelProfile(id = "b", name = "公司tokenhub很久", modelIds = mutableListOf("qwen")),
            ),
            current = null,
            browsedProfileId = "a",
            effectOf = { PickEffect.Restart },
            onPick = {},
            onSelectProfile = {},
            onManage = {},
        )

        // 列表自己是 preference 撑开的，父面板的边距不算在内
        assertTrue(list.preferredSize.width < 420, "弹层宽 ${list.preferredSize.width}px，比工具窗口还宽")
    }

    // ---- 工具 ----

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
