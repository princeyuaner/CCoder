package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.ccoder.settings.ModelProfile
import com.ccoder.settings.PermissionModeSetting
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把模型标签与切换弹层画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"文字对不对""用的是哪一档颜色"，
 * 钉不住"这个标签在 420px 里挤不挤""弹层会不会宽得离谱"。而这两条只能看。
 *
 * 标签一律**连同权限模式标签和发送键一起画**：它站在那一行里，单独画一个
 * 标签好看没有意义，得看它跟邻居会不会打架。
 *
 * 产物在 `build/probe/composer-model-*.png`。动了观感就跑一下看一眼。
 */
class ComposerModelRenderProbe {

    // 一条配置挂好几个模型是常态（同一个网关下换个档），所以这里就按常态画
    private val opus = ModelProfile(
        id = "p1", name = "中转 Opus",
        modelIds = mutableListOf("claude-opus-4-6", "claude-opus-4-6[1m]", "claude-sonnet-4-5"),
        modelId = "claude-opus-4-6",
        baseUrl = "https://api.relay.example.com",
    )
    private val qwen = ModelProfile(
        id = "p2", name = "本地 Qwen",
        modelIds = mutableListOf("qwen3-coder-30b"),
        modelId = "qwen3-coder-30b",
        baseUrl = "http://localhost:11434",
    )
    private val official = ModelProfile(
        id = "p3", name = "官方",
        modelIds = mutableListOf("claude-sonnet-4-5"),
        modelId = "claude-sonnet-4-5",
    )

    /** 一条没指定模型的配置 —— 它也得有一行可选，那一行长什么样要看一眼。 */
    private val bare = ModelProfile(id = "p5", name = "裸配置")

    /**
     * 两个标签 × 悬停。
     *
     * **两个标签的悬停要长得一样** —— 它们并排站在同一行，一个有一个没有
     * 会很扎眼（第一轮审查就是这么看出来的）。行序：
     * 常态 / 模型悬停 / 权限悬停 / 没配置 / 没配置+模型悬停 / 绕过+权限悬停。
     */
    @Test
    fun `把两个标签的悬停画成图片`() = renderLabel(
        "build/probe/composer-model-label.png",
        listOf(
            Shot(opus, PermissionModeSetting.DEFAULT),
            Shot(opus, PermissionModeSetting.DEFAULT, hoverModel = true),
            Shot(opus, PermissionModeSetting.DEFAULT, hoverMode = true),
            Shot(null, PermissionModeSetting.DEFAULT),
            Shot(null, PermissionModeSetting.DEFAULT, hoverModel = true),
            // 绕过的警示色被悬停顶掉是什么样，也得看一眼
            Shot(opus, PermissionModeSetting.BYPASS_PERMISSIONS, hoverMode = true),
            // 思考深度不是「默认」时长什么样：这是它最长的一档
            Shot(opus, PermissionModeSetting.DEFAULT, effort = EffortSetting.XHIGH),
        ),
    )

    /**
     * 最长的那一版单独出一张。
     *
     * 工具栏左侧是和权限模式标签**共享**宽度的，名字一长就会把模式标签挤出去 ——
     * 这条不看图发现不了。第二行两个标签一起悬停，顺带看挤在一起时的对比。
     */
    @Test
    fun `把最长的模型名画成图片`() = renderLabel(
        "build/probe/composer-model-label-long.png",
        listOf(
            Shot(longName, PermissionModeSetting.BYPASS_PERMISSIONS),
            Shot(longName, PermissionModeSetting.BYPASS_PERMISSIONS, hoverModel = true, hoverMode = true),
            // 最挤的一版：最长的模型名 + 最长的模式 + 最长的档位。
            // 三个都拉到极限还塞得下，才是真的塞得下
            Shot(
                longName,
                PermissionModeSetting.BYPASS_PERMISSIONS,
                effort = EffortSetting.XHIGH,
            ),
        ),
    )

    /** 一行的样子：选中的配置、模式、思考深度，以及哪个标签被鼠标压着。 */
    private data class Shot(
        val profile: ModelProfile?,
        val mode: PermissionModeSetting,
        val effort: EffortSetting = EffortSetting.DEFAULT,
        val hoverModel: Boolean = false,
        val hoverMode: Boolean = false,
    )

    private val longName = ModelProfile(
        name = "Claude Opus 4 中转（公司代理）",
        modelIds = mutableListOf("claude-opus-4-6-20250929-thinking-exp"),
        modelId = "claude-opus-4-6-20250929-thinking-exp",
    )

    /**
     * 弹层：多配置 × 多模型，而且**后果两种都有**。
     *
     * 当前那条是热切换（不标），别的都标〔会重开会话〕—— 这两个信号在同
     * 一张图里才看得出会不会打架（组头本来就长，再挂一个标记有可能越宽）。
     */
    @Test
    fun `把切换弹层画成图片`() = renderPopup(
        "build/probe/composer-model-popup.png",
        profiles = listOf(opus, longDetail, qwen, official, bare),
        currentId = "p1",
        effectOf = { p -> if (p.id == "p1") PickEffect.Hot else PickEffect.Restart },
    )

    /** 一条配置都没有时。这是新用户点开看到的第一眼。 */
    @Test
    fun `把空弹层画成图片`() =
        renderPopup("build/probe/composer-model-popup-empty.png", profiles = emptyList(), currentId = null)

    /** 弹层最宽的那一版：模型名与主机名都很长。 */
    private val longDetail = ModelProfile(
        id = "p4",
        name = "公司中转",
        modelIds = mutableListOf("claude-opus-4-6-20250929", "deepseek-v4-flash-vision-exp[1m]"),
        modelId = "claude-opus-4-6-20250929",
        baseUrl = "https://api.anthropic-relay.internal.corp.example.com",
    )

    private fun renderLabel(path: String, shots: List<Shot>) {
        SwingUtilities.invokeAndWait {
            val rows = shots.map { labelRow(it) }

            val outer = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(8)
                rows.forEach { add(it) }
            }

            // 420px 是工具窗口的真实宽度
            val w = 420
            val h = outer.preferredSize.height
            outer.setSize(w, h)
            layoutAll(outer)
            write(outer, w, h, path)
        }
    }

    /** 一行真实的工具栏：模型标签 + 权限模式标签 + 发送键。 */
    private fun labelRow(shot: Shot): JPanel {
        val modelLabel = ModelLabel {}.apply { setProfile(shot.profile) }
        if (shot.hoverModel) hover(modelLabel)

        val modeLabel = ModeLabel {}.apply { setMode(shot.mode) }
        if (shot.hoverMode) hover(modeLabel)

        val effortLabel = EffortLabel {}.apply { setEffort(shot.effort) }

        val send = RoundSendButton().apply {
            setState(mainButtonState(ready = true, busy = false, disconnected = false))
        }

        val input = ComposerTextArea(COMPOSER_MIN_ROWS, 40).apply {
            lineWrap = true
            styleComposerInput(this)
            text = "输入消息，Enter 发送"
        }
        val inputScroll = JBScrollPane(input).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                buildComposerCard(
                    inputScroll,
                    buildComposerToolbar(AttachButton(), modelLabel, modeLabel, effortLabel, send),
                ),
                BorderLayout.NORTH,
            )
        }
    }

    private fun renderPopup(
        path: String,
        profiles: List<ModelProfile>,
        currentId: String?,
        effectOf: (ModelProfile) -> PickEffect = { PickEffect.Hot },
    ) {
        SwingUtilities.invokeAndWait {
            val list = buildModelList(profiles, currentId, effectOf, {}, {})
            val pad = JBUI.scale(10)
            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(pad)
                add(list, BorderLayout.CENTER)
            }

            val w = list.preferredSize.width + pad * 2
            val h = list.preferredSize.height + pad * 2
            // 长成什么样子是给人看的，宽度是给报告用的 ——
            // 弹层最宽不能超过它要去的那块地方
            println("probe: $path 首选尺寸 ${list.preferredSize.width} x ${list.preferredSize.height}")
            outer.setSize(w, h)
            layoutAll(outer)
            write(outer, w, h, path)
        }
    }

    private fun hover(c: java.awt.Component) {
        // 离屏组件收不到真实的鼠标进出事件，直接喊监听器
        val e = MouseEvent(c, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, 5, 5, 0, false)
        c.mouseListeners.forEach { it.mouseEntered(e) }
    }

    private fun write(c: Container, w: Int, h: Int, path: String) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        c.paint(g)
        g.dispose()
        val f = File(path)
        f.parentFile?.mkdirs()
        ImageIO.write(img, "png", f)
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
