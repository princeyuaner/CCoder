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
 * 渲染探针：任务面板画成 PNG，好让人眼看一眼。
 *
 * 2026-09-18 加，为两颗东西出图：那张卡的两行（任务名 + 进行时）与行右端那颗
 * 「终止」方块。单测钉得住"点了会报 task id"，钉不住"方块挤不挤、提亮
 * 看不看得出来" —— 而后者正是这颗钮的全部。
 *
 * 2026-09-24 卡塌成**一行**；2026-09-28 第四次改版又变回**两排**，并且这一屏
 * 从"在跑清单"变成三段（在跑 / 暂停 / 刚结束）。
 *
 * 产物在 `build/probe/running-detail*.png` 与 `build/probe/tasks-three-stage.png`。
 * 改了这层的观感就跑一下看一眼。
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
     * **空闲那一版**。
     *
     * 空闲时那一屏只有一句实话：「现在没有在跑的任务」。
     * 从前它下面还跟着一行「看已结束的 N 个 ›」，2026-09-24 用户说
     * "查看已结束的不要了"，整段删掉了 —— 这一格现在看的是"只剩一句话"会不会太空。
     */
    @Test
    fun `把空闲时的浮层画成图片`() = renderIdle("build/probe/running-detail-idle.png")

    /**
     * **第四次改版的主图**：在跑两条（一条带暂停）+ 刚结束两条（一成一致）+
     * 一句管家任务。要看的是四件事：三段标题的层级够不够清楚；两排之间的行距；
     * ✓/✕ 两个记号在深色底上认不认得出；「看输出」那两颗小按钮挤不挤。
     */
    @Test
    fun `把三段齐全的样子画成图片`() = renderThreeStage("build/probe/tasks-three-stage.png")

    private fun renderIdle(path: String) = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val content = buildTasksDetail(
                emptyList(),
                emptyList(),
                0,
                listOf(
                    SubagentInfo("a1", "Explore", "Map the composer input path", "call_1"),
                    SubagentInfo("a2", "Plan", "Design the drag-drop plan", "call_2"),
                    SubagentInfo("a3", "general-purpose", "Settings area i18n migration", "call_3"),
                ),
                {},
                {},
                {},
            )
            paint(content, path, hoverStop = false)
        }
    }

    private fun render(path: String, hoverStop: Boolean) = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val content = buildTasksDetail(
                listOf(
                    // **把 detail / lastTool / usage 喂满**（真实事件流里它们就是这样）
                    RunningTask(
                        id = "call_9",
                        kind = "Explore",
                        label = "找一下 token 刷新的调用点",
                        detail = "正在核对 TokenStore 的刷新路径",
                        tokens = 12_400,
                        durationMs = 80_000,
                        toolUses = 7,
                        lastTool = "Read",
                    ),
                    // 后台命令：没有类型（local_bash 那类）、不可点开转写 ——
                    // 终止钮一样要在，下排只有时长（没有 lastTool）
                    RunningTask(
                        id = "t2",
                        kind = "local_bash",
                        label = "./gradlew test --tests *Token*",
                        detail = null,
                        tokens = 0,
                        durationMs = 8_000,
                    ),
                ),
                emptyList(),
                0,
                listOf(SubagentInfo("a1", "Explore", "找一下 token 刷新的调用点", "call_9")),
                {},
                {},
                {},
            )

            paint(content, path, hoverStop)
        }
    }

    private fun renderThreeStage(path: String) = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val content = buildTasksDetail(
                listOf(
                    RunningTask(
                        id = "call_9",
                        kind = "Explore",
                        label = "调研 SDK 的后台任务面",
                        detail = "正在读 sidecar/tools/sdk-surface.mjs，前一份表已对完",
                        tokens = 48_200,
                        durationMs = 102_000,
                        toolUses = 12,
                        lastTool = "Read",
                    ),
                    RunningTask(
                        id = "t2",
                        kind = "local_bash",
                        label = "pnpm test --watch",
                        detail = "第 3 轮 · 上次 316 passed",
                        tokens = 0,
                        durationMs = 192_000,
                        paused = true,
                    ),
                ),
                listOf(
                    FinishedTask(
                        id = "t1",
                        kind = "Explore",
                        label = "校验 plugin.xml 的十个通道",
                        detail = "10 条通道全绿，一条空态缺说明",
                        outcome = TaskOutcome.Done,
                        error = null,
                        durationMs = 128_000,
                        toolUses = 38,
                        tokens = 31_000,
                        outputFile = "C:/Users/CY/AppData/Local/Temp/ccoder/task-t1/output.txt",
                        // 与上面那份 SubagentInfo 的 toolUseId 对上 —— 图上才会出现「看转写」
                        toolUseId = "call_1",
                    ),
                    FinishedTask(
                        id = "t3",
                        kind = "local_bash",
                        label = "node tools/probe-x.mjs",
                        detail = null,
                        outcome = TaskOutcome.Failed,
                        error = "Error: Cannot find module 'undici'",
                        durationMs = 41_000,
                        toolUses = 2,
                        tokens = 0,
                        outputFile = "C:/Users/CY/AppData/Local/Temp/ccoder/task-t3/output.txt",
                        toolUseId = null,
                    ),
                ),
                3,
                listOf(
                    SubagentInfo("a1", "Explore", "调研 SDK 的后台任务面", "call_9"),
                    SubagentInfo("a9", "Explore", "校验 plugin.xml 的十个通道", "call_1"),
                ),
                {},
                {},
                {},
            )

            println("[任务面板探针] 三段齐全 → 首选高 ${content.preferredSize.height}px")
            paint(content, path, hoverStop = false)
        }
    }

    // ---- 2026-09-24：卡 + 一条线（方案甲）----

    /**
     * **这一格是改版的主图**：三张在跑的卡，各一行。
     *
     * 要看的是三件事：卡与卡之间那道缝够不够（6px，见 AGENT_CARD_GAP）；
     * 卡底只比浮层底亮 5%（`cardFill`），够不够看出"这是一张卡"；
     * 以及塌成一行之后这一屏到底还有多高 —— 上限那个数（MAX_AGENT_CARDS）
     * 就是照这张图量的。
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
    fun `把超过上限的样子画成图片`() =
        renderCards("build/probe/running-detail-cards-over.png", extra = MAX_AGENT_CARDS - 1)

    private fun renderCards(path: String, extra: Int = 0, hoverCard: Boolean = false) = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val tasks = listOf(
                RunningTask("call_1", "Explore", "Survey unbuilt design mockups", "Running Extract text from flagged design HTMLs", 47_500, 19_000),
                RunningTask("call_2", "Explore", "Survey CLI surface vs plugin", "Reading RunStatusTracker.kt", 54_500, 15_000),
                RunningTask("call_3", "Explore", "Survey frontend render gaps", "Reading FailureHint.kt", 64_900, 13_000),
            ) + (1..extra).map {
                RunningTask("x$it", "general-purpose", "Extra agent $it", "Working…", 10_000, 5_000)
            }
            val content = buildTasksDetail(
                tasks,
                emptyList(),
                0,
                listOf(
                    SubagentInfo("a1", "Explore", "Survey unbuilt design mockups", "call_1"),
                    SubagentInfo("a2", "Explore", "Find EDT freeze risks", "call_9"),
                ),
                {},
                {},
                {},
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
