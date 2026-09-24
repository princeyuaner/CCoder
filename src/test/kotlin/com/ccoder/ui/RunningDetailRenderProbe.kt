package com.ccoder.ui

import com.ccoder.sidecar.SubagentInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：「运行中」浮层画成 PNG，好让人眼看一眼。
 *
 * 2026-09-18 加，为两颗东西出图：B2 的两行（任务名 + 进行时）与行右端那颗
 * 「终止」方块。单测钉得住"点了会报 task id"，钉不住"方块挤不挤、提亮
 * 看不看得出来" —— 而后者正是这颗钮的全部。
 *
 * 产物在 `build/probe/running-detail*.png`。改了这层的观感就跑一下看一眼。
 *
 * **跑在真机 LAF 下**（[IdeLaf.withRealLaf]）—— 测试 JVM 默认的 Metal
 * 哪个 IDE 都不用（见 [IdeLaf] 的说明）。浅色出不了图（同 [IdeLaf] 里
 * 记的那条），这里只画深色。
 */
class RunningDetailRenderProbe {

    @Test
    fun `把运行中浮层画成图片`() = render("build/probe/running-detail.png", hoverStop = false)

    /** 指针压在那颗方块上：一层浅底 + 方块提亮。 */
    @Test
    fun `把压着终止钮的样子画成图片`() =
        render("build/probe/running-detail-hover.png", hoverStop = true)

    /**
     * **空闲那一版**（2026-09-20）。
     *
     * 用户看着"空闲"的卡问"子代理都没了点开为什么还有内容" —— 收着的版本先只答一句
     * 「当前没有在跑的子代理」加一行「看已结束的 N 个 ›」。这一版要看的是：
     * 那句话与那一行挨着好不好看、"›"会不会像坏了。
     */
    @Test
    fun `把空闲时的浮层画成图片`() = renderIdle("build/probe/running-detail-idle.png")

    /** 展开之后（记录摊开、每一行可点看转写）。 */
    @Test
    fun `把展开记录的浮层画成图片`() =
        renderIdle("build/probe/running-detail-idle-open.png", expanded = true)

    private fun renderIdle(path: String, expanded: Boolean = false) = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val content = buildRunningDetail(
                emptyList(),
                listOf(
                    SubagentInfo("a1", "Explore", "Map the composer input path", "call_1"),
                    SubagentInfo("a2", "Plan", "Design the drag-drop plan", "call_2"),
                    SubagentInfo("a3", "general-purpose", "Settings area i18n migration", "call_3"),
                ),
                {},
                onOpen = {  },
                historyExpanded = expanded,
            )
            paint(content, path, hoverStop = false)
        }
    }

    private fun render(path: String, hoverStop: Boolean) = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val content = buildRunningDetail(
                listOf(
                    // 两行的那条（B2）：第一行任务名、第二行进行时、右边统计 + 终止
                    RunningTask(
                        id = "call_9",
                        kind = "Explore",
                        label = "找一下 token 刷新的调用点",
                        detail = "正在核对 TokenStore 的刷新路径",
                        tokens = 12_400,
                        durationMs = 80_000,
                    ),
                    // 单行的那条：后台命令，没有进行时，也不可点开转写 ——
                    // 终止钮一样要在
                    RunningTask(
                        id = "t2",
                        kind = "local_bash",
                        label = "./gradlew test --tests *Token*",
                        detail = null,
                        tokens = 0,
                        durationMs = 8_000,
                    ),
                ),
                listOf(SubagentInfo("a1", "Explore", "找一下 token 刷新的调用点", "call_9")),
                {},
                {},
            )

            paint(content, path, hoverStop)
        }
    }

    // ---- 2026-09-24：卡 + 一条线（方案甲）----

    /**
     * **这一格是改版的主图**：三张在跑的卡 + 一条「看已结束的 2 个 ›」。
     *
     * 要看的是"卡 = 在跑、线 = 记录"这件事一眼能不能读出来，以及卡与卡之间那道缝
     * 够不够（6px，见 AGENT_CARD_GAP）；卡片底色只比浮层底亮 5%（[cardFill]），
     * 线够不够看得见只有看图才知道。
     */
    @Test
    fun `把卡与记录画成图片`() = renderCards("build/probe/running-detail-cards.png")

    /** 指针压在第一张卡上：整块换底色（同会话列表那张卡的悬停）。 */
    @Test
    fun `把悬停在卡上的样子画成图片`() = renderCards("build/probe/running-detail-cards-hover.png", hoverCard = true)

    /**
     * 超过上限的那一版：`MAX_AGENT_CARDS + 2` 条在跑 → 4 张卡 + 「还有 2 个在跑」。
     *
     * 要看的是那一行放的位置对不对（它贴在最后一张卡下面），
     * 以及**这张图有多高** —— 高度那个数就是上限的依据（浮层向上弹，高了会顶出屏幕）。
     */
    @Test
    fun `把超过上限的样子画成图片`() = renderCards("build/probe/running-detail-cards-over.png", extra = 2)

    private fun renderCards(path: String, extra: Int = 0, hoverCard: Boolean = false) = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val tasks = listOf(
                RunningTask("call_1", "Explore", "Survey unbuilt design mockups", "Running Extract text from flagged design HTMLs", 47_500, 19_000),
                RunningTask("call_2", "Explore", "Survey CLI surface vs plugin", "Reading RunStatusTracker.kt", 54_500, 15_000),
                RunningTask("call_3", "Explore", "Survey frontend render gaps", "Reading FailureHint.kt", 64_900, 13_000),
            ) + (1..extra).map {
                RunningTask("x$it", "general-purpose", "Extra agent $it", "Working…", 10_000, 5_000)
            }
            val content = buildRunningDetail(
                tasks,
                listOf(
                    SubagentInfo("a1", "Explore", "Survey unbuilt design mockups", "call_1"),
                    SubagentInfo("a2", "Explore", "Find EDT freeze risks", "call_9"),
                ),
                {},
                onOpen = {  },
            )

            // 量一遍高度：上限那个数就是这么定的。**画之前**量，量的是内容
            println("[运行中探针] ${tasks.size} 条在跑 → 首选高 ${content.preferredSize.height}px")

            paint(content, path, hoverStop = false, hoverCard = hoverCard)
        }
    }

    /** 直接驱动监听器：离屏组件收不到真实的鼠标进出事件（同 [StatusCardsRenderProbe]）。 */
    private fun hoverCardOnly(component: Component) {
        val e = MouseEvent(component, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, 5, 5, 0, false)
        component.mouseListeners.forEach { it.mouseEntered(e) }
    }

    private fun findCard(root: Component): AgentCard? {
        if (root is AgentCard) return root
        if (root is Container) {
            for (c in root.components) findCard(c)?.let { return it }
        }
        return null
    }

    /** 摆版 + 画图。三个入口（运行中、空闲、卡那一版）共用。 */
    private fun paint(content: Component, path: String, hoverStop: Boolean, hoverCard: Boolean = false) {
        val outer = JPanel(BorderLayout()).apply {
            isOpaque = true
            // 照实画在浮层的底色上：那是个弹出列表，不是面板灰。
            // 拿不到时退回面板色，至少不崩
            background = runCatching {
                UIUtil.getListBackground()
            }.getOrDefault(UIUtil.getPanelBackground())
            border = JBUI.Borders.empty(8)
            add(content, BorderLayout.NORTH)
        }

        // 420px 是工具窗口的真实宽度（浮层比它窄，内容拉到这个宽即真实形态）
        val w = 420
        val h = outer.preferredSize.height
        outer.setSize(w, h)
        layoutAll(outer)

        if (hoverStop) hover(findStop(outer) ?: error("图里没有终止钮"))
        if (hoverCard) hoverCardOnly(findCard(outer) ?: error("图里没有卡"))

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        outer.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File(path))
    }

    /** 直接驱动监听器：离屏组件收不到真实的鼠标进出事件（同 [StatusCardsRenderProbe]）。 */
    private fun hover(component: Component) {
        val e = MouseEvent(component, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, 5, 5, 0, false)
        component.mouseListeners.forEach { it.mouseEntered(e) }
    }

    private fun findStop(root: Component): TaskStopButton? {
        if (root is TaskStopButton) return root
        if (root is Container) {
            for (c in root.components) findStop(c)?.let { return it }
        }
        return null
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
