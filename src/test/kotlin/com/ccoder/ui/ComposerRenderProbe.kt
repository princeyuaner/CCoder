package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.ccoder.settings.ModelProfile
import com.ccoder.settings.PermissionModeSetting
import com.intellij.ui.components.JBScrollPane
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * 渲染探针：把输入区连同它上方的五张状态卡画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测能钉住"收边的卡 alpha 是 0""五张等宽"，
 * 钉不住"五张卡和输入框叠在一起整体好不好看"。而后者正是这次改版的
 * **全部理由**，不该只靠信念。
 *
 * 状态卡也不再是输入卡内部的一行：它们是独立的一排，挂在输入卡**外面**的
 * 上方，所以这一张图里两样都得画出来才看得出关系。
 *
 * 「使用量」常驻条（2026-10-13 加）：它真实的位置就在状态卡与输入卡**之间**，
 * 用户报的那条「这个卡片的样式和其他的不一样」正是这三样摆在一起才看得出来的
 * 事 —— 条不进这张图，圆角/描边一整类毛病就没人看得见（当时是靠真机截图
 * 才发现的；UsageRenderProbe 只画条自己，对比不出来）。
 *
 * 产物在 `build/composer-probe*.png`。改了输入区、状态卡或常驻条的观感就跑一下
 * 看一眼。绕过权限那张单独出一张，因为警示色只有**看**才知道够不够显眼。
 */
class ComposerRenderProbe {

    @Test
    fun `把输入区与状态卡画成图片`() = render("build/composer-probe.png", PermissionModeSetting.DEFAULT)

    @Test
    fun `把绕过权限时的输入区画成图片`() =
        render("build/composer-probe-bypass.png", PermissionModeSetting.BYPASS_PERMISSIONS)

    /**
     * 空输入框那一版。
     *
     * **占位（三个符号 + 发送说明）只有这张图里看得见** —— 上面两版都填着字，
     * 而那行字是自绘的、数像素的用例只看得出"画了没画"。改了那行文案（或
     * 换了发送约定）就跑一下，看一眼它有没有顶出输入框。
     */
    @Test
    fun `把空输入框的用法提示画成图片`() =
        render("build/composer-probe-empty.png", PermissionModeSetting.DEFAULT, empty = true)

    /**
     * 真机 LAF（New UI 深色）底下画。
     *
     * 2026-09-17 补的：这个探针此前跑在测试 JVM 默认的 Metal 下，出的是**浅色**图 ——
     * 而用户那台 PyCharm 是深色。底色一变，"工具栏底带够不够、显不显得脏"这类判断
     * 就全都不作数了（[IdeLaf] 的注释里写着同一条教训）。
     */
    /**
     * 文本多到装不下的时候（2026-09-23 用户："文本很多时不出滚动条，后面的看不见"）。
     *
     * 输入框**不做自动长高**（高度由用户拖分隔条决定），所以超出是常态 ——
     * 上面那两张图里文字都只有一行，这条才是"超出之后长什么样"。
     * 只看图能看出两件单测钉不住的事：滚动条**压没压到字**、右边那点宽度够不够。
     */
    @Test
    fun `文本多到装不下时画出滚动条`() = render(
        "build/composer-probe-overflow.png",
        PermissionModeSetting.DEFAULT,
        longText = true,
    )

    private fun render(
        path: String,
        mode: PermissionModeSetting,
        empty: Boolean = false,
        longText: Boolean = false,
    ) {
        IdeLaf.withRealLaf { renderUnder(path, mode, empty, longText) }
    }

    private fun renderUnder(
        path: String,
        mode: PermissionModeSetting,
        empty: Boolean,
        longText: Boolean = false,
    ) {
        SwingUtilities.invokeAndWait {
            val cards = StatusCardsRow(
                onClear = {}, onCompact = {}, onOpenContext = {}, onOpenTodos = {},
                onOpenRunning = {}, onOpenSync = {}, onToggleSync = {},
            ).apply {
                connection.setModel(connectionCardOf(ConnectionState.Connected))
                sync.setModel(syncCardOf(null))
                context.setModel(contextCardOf(ContextUsage(usedTokens = 12300, windowTokens = 200000)))
                todos.setModel(
                    todoCardOf(
                        TaskList(
                            listOf(
                                TodoItem("定位调用点", TodoState.Completed),
                                TodoItem("替换指纹函数", TodoState.InProgress),
                                TodoItem("跑测试", TodoState.Pending),
                            )
                        )
                    )
                )
                running.setModel(runningCardOf(listOf(stub("t1"), stub("t2"))))
            }

            val input = ComposerTextArea(COMPOSER_MIN_ROWS, 40).apply {
                lineWrap = true
                styleComposerInput(this)
                when {
                    longText -> text = (1..40).joinToString("\n") { "第 $it 行 —— 看看滚动条出不出来" }
                    !empty -> text = "输入消息，Enter 发送"
                }
            }
            val inputScroll = JBScrollPane(input).apply {
                border = com.intellij.util.ui.JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }

            // 工具栏不再提供模型标签的工厂 —— 标签由 ClaudePanel 构造后传进来
            // （与 ModeLabel 一样），所以这里也自己造一个
            val model = ModelLabel {}.apply { setProfile(ModelProfile(name = "Sonnet 4.5")) }
            val modeLabel = ModeLabel {}.apply { setMode(mode) }
            val effortLabel = EffortLabel {}.apply { setEffort(EffortSetting.HIGH) }
            val send = RoundSendButton().apply {
                setState(mainButtonState(ready = true, busy = false, disconnected = false))
            }
            val toolbar = buildComposerToolbar(AttachButton(), model, modeLabel, effortLabel, send)

            val card = buildComposerCard(inputScroll, toolbar)

            // 「使用量」常驻条，插在状态卡与输入卡之间 —— 真机上就是这么叠的。
            // 走势的示意数据与 UsageRenderProbe 同一形状（缓存读占大头的 14 天）
            val usage = UsageStrip(onOpen = {}).apply {
                submit(
                    UsageStripData(
                        todayTokens = 12_400_000,
                        todayCost = 14.9,
                        spark = (13 downTo 0).map { 2_000_000L + (it * 1_100_000L) % 9_000_000L },
                    )
                )
            }
            val header = javax.swing.JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                isOpaque = false
                // 一律左对齐：BoxLayout 摆不拉伸的子项用的是加权平均对齐点
                // （ClaudePanel 里记过这个坑），探针里同样不能混 0.5
                cards.alignmentX = java.awt.Component.LEFT_ALIGNMENT
                add(cards)
                add(
                    (javax.swing.Box.createVerticalStrut(com.intellij.util.ui.JBUI.scale(5))
                        as javax.swing.JComponent).apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT }
                )
                usage.alignmentX = java.awt.Component.LEFT_ALIGNMENT
                add(usage)
            }

            val outer = javax.swing.JPanel(BorderLayout()).apply {
                border = com.intellij.util.ui.JBUI.Borders.empty(6, 8, 8, 8)
                background = com.intellij.util.ui.UIUtil.getPanelBackground()
                add(header, BorderLayout.NORTH)
                add(card, BorderLayout.CENTER)
            }

            val w = 430
            val h = 264
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun stub(id: String) =
        RunningTask(id = id, kind = null, label = null, detail = null, tokens = 0, durationMs = 0)

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
