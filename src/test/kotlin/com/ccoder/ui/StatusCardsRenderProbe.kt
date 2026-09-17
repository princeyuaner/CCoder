package com.ccoder.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.GridLayout
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把四张状态卡画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"收边的卡 alpha 是 0""四张等宽"，
 * 钉不住"四张卡放在 420px 里整体好不好看"。而后者正是这次改版的
 * **全部理由**（用户要的就是"更美观"），不该只靠信念。
 *
 * 产物在 `build/status-cards-probe*.png`。改了卡的观感就跑一下看一眼。
 */
class StatusCardsRenderProbe {

    @Test
    fun `把忙时的四张卡画成图片`() = render("build/status-cards-probe.png", busy = true)

    @Test
    fun `把空闲的四张卡画成图片`() = render("build/status-cards-probe-idle.png", busy = false)

    /**
     * **最长的那条连接文字**单独出一张。
     *
     * 每张卡在 420px 里只有约 81px，减掉状态点只剩 70px —— 而
     * "载入中…"有七个字。这条不单独看的话，"放不下被截成
     * 正在载入…"要等装到 IDE 里才发现。
     */
    @Test
    fun `把最长的连接文字画成图片`() =
        render("build/status-cards-probe-longstatus.png", busy = true, connectionText = "载入中…")

    /**
     * **还没测到用量**那一版单独出一张。
     *
     * 这条是**会话刚建立、还没问回用量**那一拍的样子。它以前是收边的「空闲」，
     * 现在是一个正常字号带边框的 `0` —— 后者是不是看着像一句"这个会话是空的"
     * 的断言，得看图才知道。
     */
    @Test
    fun `把还没测到用量的上下文卡画成图片`() =
        render("build/status-cards-probe-nousage.png", busy = true, noUsage = true)

    /**
     * **正在干活时那一版**：连接卡改说"在干什么"。
     *
     * 用户要的就是这一句（「我希望能实时显示当前在做什么」）。它和八种连接文字
     * 挤的是同一格，所以必须单独看一眼宽度够不够。
     */
    @Test
    fun `把正在运行指令时的四张卡画成图片`() =
        render("build/status-cards-probe-activity.png", busy = true, activity = ACTIVITY_RUNNING)

    /**
     * **等待响应那一版**：动作词让到标签行、秒数进值行（[waitingCardOf]）。
     *
     * 这一格只有约 95px，换行放就是为了不省略 —— 但"换过来之后还读得通吗"
     * 只有看图才知道。
     */
    @Test
    fun `把等待响应时的四张卡画成图片`() =
        render("build/status-cards-probe-waiting.png", busy = true, waitingSeconds = 42)

    /**
     * @param busy true = 四样都有内容；false = 后两张收边。
     *   空闲那张同时把上下文推到 92%，顺手验证警示色。
     * @param noUsage true = 上下文没有测量值（`contextCardOf(null)`）。
     * @param activity 非空 = 连接卡显示"正在做什么"（[activityCardOf]）。
     */
    private fun render(
        path: String,
        busy: Boolean,
        connectionText: String? = null,
        noUsage: Boolean = false,
        activity: String? = null,
        waitingSeconds: Int? = null,
    ) {
        SwingUtilities.invokeAndWait {
            val cards = StatusCardsRow(onClear = {}, onCompact = {}, onOpenContext = {}, onOpenTodos = {}, onOpenRunning = {}).apply {
                connection.setModel(
                    when {
                        waitingSeconds != null -> waitingCardOf(waitingSeconds)
                        activity != null -> activityCardOf(activity)
                        else -> connectionCardOf(connectionText ?: if (busy) "已连接" else "已断开")
                    }
                )
                context.setModel(
                    when {
                        noUsage -> contextCardOf(null)
                        busy -> contextCardOf(ContextUsage(usedTokens = 12300, windowTokens = 200000))
                        else -> contextCardOf(ContextUsage(usedTokens = 184000, windowTokens = 200000))
                    }
                )
                todos.setModel(
                    if (busy) {
                        todoCardOf(
                            TaskList(
                                listOf(
                                    TodoItem("定位调用点", TodoState.Completed),
                                    TodoItem("替换指纹函数", TodoState.Completed),
                                    TodoItem("跑测试", TodoState.InProgress),
                                    TodoItem("更新文档", TodoState.Pending),
                                )
                            )
                        )
                    } else {
                        todoCardOf(null)
                    }
                )
                running.setModel(
                    if (busy) runningCardOf(listOf(stub("t1"), stub("t2"))) else runningCardOf(emptyList())
                )
            }

            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                // **照实画在暗底上**：真实 IDE 里这排卡坐在转写区那一层的底色上，
                // 不是面板灰。画在面板灰上的话，"卡片跟背景糊成一片"在图上根本
                // 看不出来 —— 2026-09-14 用户报的就是这个（卡是黑的、和背景一样）。
                // 编辑器底色拿不到时退回面板色，至少不崩
                background = runCatching {
                    EditorColorsManager.getInstance().globalScheme.defaultBackground
                }.getOrDefault(UIUtil.getPanelBackground())
                border = JBUI.Borders.empty(8)
                add(cards, BorderLayout.NORTH)
            }

            // 420px 是工具窗口的真实宽度
            val w = 420
            val h = outer.preferredSize.height
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    // ---- 动作图标（2026-09-17）----

    /** 几张动作图各画什么。 */
    private enum class ActionShot { Available, IconHot, Disabled, Compacting, Waiting }

    /**
     * **两颗动作图标常驻在右上角**那一版。
     *
     * 注意：图上不是"悬停才出现"——常驻正是这一版的要点（悬停那一版被用户否掉，
     * 指针往按钮去的路上悬停就被子件偷走了）。这里画的是可用状态。
     */
    @Test
    fun `把常驻的两颗动作图标画成图片`() =
        renderActions("build/status-cards-probe-actions.png", ActionShot.Available)

    /** 指针压在图标上：只有底色亮起来，图标本身不动。 */
    @Test
    fun `把压着图标的悬停底色画成图片`() =
        renderActions("build/status-cards-probe-actions-hot.png", ActionShot.IconHot)

    /** 忙时：两颗**灰的**（点了没反应）—— 灰着不解释，用户只会以为坏了。 */
    @Test
    fun `把忙时灰掉的两颗动作图标画成图片`() =
        renderActions("build/status-cards-probe-actions-disabled.png", ActionShot.Disabled)

    /** 压缩中：上下文卡的值行让给「压缩中…」、图标整个消失；连接卡那颗照忙态灰着。 */
    @Test
    fun `把压缩中的上下文卡画成图片`() =
        renderActions("build/status-cards-probe-compacting.png", ActionShot.Compacting)

    /**
     * **「等待响应」那一版单独出一张**：连接卡的标签是四字（最长的一档），
     * 而右上角多了一颗 16px 的图标 —— 挤不挤只有看图才知道。
     *
     * 这一档只在等第一口 token 时出现（实测 P99 38.8s），但它是四张卡里
     * 标签行最宽的一刻，宽度问题都集中在这里暴露。
     */
    @Test
    fun `把等待响应时带动作图标的连接卡画成图片`() =
        renderActions("build/status-cards-probe-actions-waiting.png", ActionShot.Waiting)

    /**
     * 动作图标那一排。
     *
     * **不是 [StatusCardsRow]，是四张卡自己拼的**：[StatusCardsRow] 会把回调接上去，
     * 而这里只要画得出来 —— 真实接线由 ClaudePanel 做。
     *
     * 悬停直接驱动监听器（与 [hover] 同一条路）：走 `dispatchEvent` 会把事件送给
     * 平台注册的 `ToolTipManager`，它起的定时器会被测试夹具判成 "Not disposed"。
     *
     * **跑在真机 LAF 下**（[IdeLaf.withRealLaf]）—— 本文件里其它的 `render(...)` 是
     * 2026-09-14 写的，还没跟上 09-15 那条"探针不许在 Metal 下跑"的规矩（见
     * [IdeLaf] 的说明），新加的这一段按新规矩来。
     */
    private fun renderActions(path: String, shot: ActionShot) = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val busy = shot == ActionShot.Disabled
            val compacting = shot == ActionShot.Compacting

            val connection = StatusCardView(icon = CardIcon.Link, onAction = {})
            val context = StatusCardView(icon = CardIcon.Context, onOpen = {}, onAction = {})
            val todos = StatusCardView(icon = CardIcon.Tasks, onOpen = {})
            val running = StatusCardView(icon = CardIcon.Agents, onOpen = {})

            // 压缩中会话也是忙的（压缩是一个回合）—— 连接卡照忙态画，
            // 否则图上会出现"压缩中而清空还亮着"这种现实里不存在的组合
            val connBusy = busy || compacting
            connection.setModel(
                when {
                    shot == ActionShot.Waiting -> waitingCardOf(42)
                    connBusy -> activityCardOf(ACTIVITY_RUNNING)
                    else -> connectionCardOf("已连接")
                }
            )
            context.setModel(contextCardOf(ContextUsage(12300, 200000), compacting = compacting))
            todos.setModel(todoCardOf(null))
            running.setModel(runningCardOf(emptyList()))

            connection.setAction(clearActionOf(ready = true, busy = connBusy))
            context.setAction(compactActionOf(ready = true, busy = busy, compacting = compacting))

            val cards = JPanel(GridLayout(1, 4, JBUI.scale(5), 0)).apply {
                isOpaque = false
                add(connection); add(context); add(todos); add(running)
            }

            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = runCatching {
                    EditorColorsManager.getInstance().globalScheme.defaultBackground
                }.getOrDefault(UIUtil.getPanelBackground())
                border = JBUI.Borders.empty(8)
                add(cards, BorderLayout.NORTH)
            }

            val w = 420
            val h = outer.preferredSize.height
            outer.setSize(w, h)
            layoutAll(outer)

            // 指针压在连接卡那颗图标上（真实鼠标只能在一张上；一张图看全形态）
            if (shot == ActionShot.IconHot) {
                val b = connection.actionIconBoundsForTest()
                val e = MouseEvent(
                    connection, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
                    0, b.x + b.width / 2, b.y + b.height / 2, 0, false,
                )
                connection.mouseMotionListeners
                    .filterNot { it.javaClass.name.startsWith("javax.swing.") }
                    .forEach { it.mouseMoved(e) }
            }

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    /** 直接驱动监听器：离屏组件收不到真实的鼠标进出事件。 */
    private fun hover(component: Component) {
        val e = MouseEvent(component, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, 5, 5, 0, false)
        component.mouseListeners.forEach { it.mouseEntered(e) }
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
