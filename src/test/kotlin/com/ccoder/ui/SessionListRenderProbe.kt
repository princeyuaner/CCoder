package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把会话列表画成 PNG，好让人眼看一眼；
 * 另有一条**不带图**的量尺，量的是"弹层会占多大"。
 *
 * 没有断言，也不该有 —— 单测钉得住"标题回退到 firstPrompt""忙时没有监听器"，
 * 钉不住"420px 下右侧的时间会不会被长标题挤掉"。
 *
 * 那一句计划里原本写的是"理论上不会，但理论上不等于测过"。
 * 这个探针就是把它变成"看过"。
 *
 * 产物在 `build/session-list-probe.png`。改了列表观感就跑一下看一眼。
 */
class SessionListRenderProbe {

    private val now = System.currentTimeMillis()

    /**
     * 量尺：**弹层会占多大**。
     *
     * 上面几条都强制 420 宽，而弹层的真实宽度与高度**不由 420 决定** ——
     * 宽度是"最长标题 + 行内其余部件"撑出来的，高度是"行高 × 会话数"。
     * 设计稿 `docs/design/session-list-v2.html` 里那两个数（737 / 1702）
     * 就是这里量的，改完列表观感后可以重跑对一遍。
     *
     * 打印而不断言：这两个数**现在**是超标的，把它们写成断言等于把缺陷钉死。
     * 等尺寸上限落地了，再按那时的目标改成断言。
     */
    @Test
    fun `量一遍弹层的真实尺寸（77 条会话）`() {
        SwingUtilities.invokeAndWait {
            val long = "帮我看看这个插件为什么在恢复历史会话之后模型名标签没有更新，顺便确认一下子代理的记录是不是也一起没了"
            val sessions = (1..77).map { i ->
                SessionInfo(
                    sessionId = "s$i",
                    summary = if (i == 2) long else "会话编号 $i",
                    firstPrompt = null,
                    lastModified = now - i * 3_600_000L,
                )
            }
            val list = buildSessionList(sessions, currentSessionId = "s1", block = SwitchBlock.None)

            // 封顶之后返回的是**滚动面板**，内容在它里面 —— 直接读 list.components
            // 拿到的是视口和滚动条，量出来的数会张冠李戴（踩过一次）
            val content: java.awt.Container =
                ((list as? JBScrollPane)?.viewport?.view as? java.awt.Container) ?: list
            val rowH = content.components.first().preferredSize.height
            println("[会话列表量尺] 77 条：弹层首选=${list.preferredSize.width} x ${list.preferredSize.height}")
            println("[会话列表量尺]   内容真实高=${content.preferredSize.height}（没封顶时就是这么高）行高=$rowH")
        }
    }

    @Test
    fun `把会话列表画成图片`() = render("build/session-list-probe.png", SwitchBlock.None)

    @Test
    fun `把忙时的会话列表画成图片`() =
        render("build/session-list-probe-blocked.png", SwitchBlock.PermissionPending)

    @Test
    fun `把悬停强调的那一行画成图片`() =
        render("build/session-list-probe-hover.png", SwitchBlock.None, hoverRow = 1)

    /**
     * **有会话正被别的标签跑着**那一版（多标签，2026-09-16）。
     *
     * 「已打开」是替换时间那一格的文字，而这一行本来就只剩 420px ——
     * 长标题叠上它会不会把 ✕ 顶出画面，只能看。
     */
    @Test
    fun `把有会话已被打开的那一版画成图片`() =
        render("build/session-list-probe-taken.png", SwitchBlock.None, taken = setOf("s1", "s3"))

    /**
     * **改过名、打过标签**的那一版单独出一张。
     *
     * 行尾多了个标签 chip，而那一行本来就只剩 420px —— 最长的标题 + 最长的时间
     * 再叠一个标签，时间会不会被挤掉、✕ 会不会顶出画面，只能看。
     */
    @Test
    fun `把有名字与标签的那一版画成图片`() =
        render("build/session-list-probe-tagged.png", SwitchBlock.None, tagged = true)

    /**
     * **会滚的那种**（2026-09-15 加的上限）：30 条会话、超长标题。
     *
     * 这两样正是改之前把弹层撑到 731 × 1702 的原因 —— 出图看一眼"收住了没有"、
     * 滚动条有没有把行尾那个「删除」挤掉。
     */
    @Test
    fun `把 30 条会话（会滚的那种）画成图片`() {
        run {
            val many = (1..30).map {
                SessionInfo("s$it", "会话编号 $it：这一段标题故意写长一点，好看看它会不会把行撑开", null, now - it * 600_000L)
            }
            draw("build/session-list-probe-scroll.png", many, SwitchBlock.None, current = "s3")
        }
    }

    @Test
    fun `把超长标题的那一版画成图片`() {
        run {
            val one = listOf(
                SessionInfo(
                    "s1",
                    "帮我看看这个插件为什么在恢复历史会话之后模型名标签没有更新，顺便确认一下子代理的记录是不是也一起没了",
                    null,
                    now - 60_000,
                ),
                SessionInfo("s2", "短标题", null, now - 3_600_000),
            )
            draw("build/session-list-probe-verylong.png", one, SwitchBlock.None, current = "s1")
        }
    }

    private fun render(
        path: String,
        block: SwitchBlock,
        hoverRow: Int = -1,
        tagged: Boolean = false,
        /** 正被**别的标签**跑着的那些会话（多标签，2026-09-16）。 */
        taken: Set<String> = emptySet(),
        /** 指针停在**删除按钮本身**上（三档强调里的 danger 档），见 [draw]。 */
        dangerRow: Int = -1,
        /** 点一下顶部的「清空全部」，停在确认态，见 [draw]。 */
        clearConfirm: Boolean = false,
    ) {
        val sessions = listOf(
            // s1 **两句都有**：显示的是自己说的第一句（firstPrompt 优先于 summary，
            // 2026-09-15 改的顺序）。这一条是那张图上唯一能看出该规则的样本 ——
            // 其余都只有一句，改前改后长得一样
            SessionInfo("s1", "还可以做什么功能", "这是什么项目", now - 30_000),
            // 这一条是全列表最长的标题 —— 挤掉时间的嫌疑就落在它身上
            SessionInfo("s2", "PyCharm插件调用Claude Code", null, now - 3_600_000),
            SessionInfo("s3", null, "你好", now - 90_000_000),
            // 最挤的一条：最长的标题 + 自定义名字 + 标签 + 一周前
            if (tagged) {
                SessionInfo(
                    "s4", "自动摘要被名字盖住", null, now - 5 * 86_400_000,
                    customTitle = "重构 extractor 的指纹计算，顺便把跨块的 CR 边界也处理掉",
                    tag = "重构",
                )
            } else {
                SessionInfo("s4", "重构 extractor 的指纹计算，顺便把跨块的 CR 边界也一起处理掉", null, now - 5 * 86_400_000)
            },
        )
        draw(
            path, sessions, block,
            current = "s2", hoverRow = hoverRow, taken = taken,
            dangerRow = dangerRow, clearConfirm = clearConfirm,
        )
    }

    /**
     * **指针停在删除按钮上**那一版（2026-09-17，用户报的"轮廓太矮"）。
     *
     * 与上面那张悬停图的差别是**档位**：悬停整行只提亮文字，停在按钮上才画出
     * 那个按钮框。框画得对不对（包不包得住这两个字）只有这一档看得出来 ——
     * 用户截的图就是这一档。
     */
    @Test
    fun `把指针停在删除按钮上的那一行画成图片`() =
        render("build/session-list-probe-delete-hover.png", SwitchBlock.None, dangerRow = 1)

    /**
     * **顶部「清空全部」的确认态**（2026-09-17）。
     *
     * 要看的：那句问句有没有把这一行撑变形（它是定宽 HTML，会换行）、
     * 两颗按钮与下面的行是不是一家人、下面那些会话行有没有被挤掉。
     */
    @Test
    fun `把清空全部的确认态画成图片`() =
        render("build/session-list-probe-clear.png", SwitchBlock.None, clearConfirm = true)

    /**
     * 画的那一半：建列表、可选地制造悬停态、**按 420px 的真实宽度**排版、出图。
     *
     * 宽度取 420 而不是"内容多宽就多宽"：列表自己现在会把宽度钉在这个上限上
     * （见 `SESSION_LIST_WIDTH`），而弹层就是按这一份首选尺寸开的
     * （`showTogglePopup` → `content.preferredSize`）。
     */
    private fun draw(
        path: String,
        sessions: List<SessionInfo>,
        block: SwitchBlock,
        current: String? = "s2",
        hoverRow: Int = -1,
        taken: Set<String> = emptySet(),
        dangerRow: Int = -1,
        clearConfirm: Boolean = false,
    ) {
        SwingUtilities.invokeAndWait {
            val list = buildSessionList(
                sessions,
                currentSessionId = current,
                block = block,
                takenIds = taken,
            )

            // 悬停态：往那一行派发 MOUSE_ENTERED，删除按钮就会提亮
            if (hoverRow >= 0) {
                val row = list.components.filterIsInstance<java.awt.Component>()[hoverRow]
                row.dispatchEvent(enterEvent(row))
            }

            // 清空确认态：真点一下顶部那颗（走的是与真机同一条路径：
            // doClick → ActionListener → enterConfirm）
            if (clearConfirm) {
                val action = findButton(list) { it.text == CLEAR_ALL_TEXT }
                    ?: error("顶部没有「$CLEAR_ALL_TEXT」—— 探针的假设不成立了")
                action.doClick()
            }

            // 危险态：把 MOUSE_ENTERED 派给**按钮自己**（不是它所在这行）。
            // 按钮的监听器收到后调 paintDelete(danger = true)，画出按钮框
            if (dangerRow >= 0) {
                val row = list.components.filterIsInstance<java.awt.Component>()[dangerRow]
                val button = (row as? java.awt.Container)?.let { findDeleteButton(it) }
                    ?: error("第 $dangerRow 行里没有「$DELETE_TEXT」按钮 —— 探针的假设不成立了")
                button.dispatchEvent(enterEvent(button))
                // 量尺（打印不断言）：按钮**首选**与**槽**是不是同一个数。2026-09-17
                // 那次"轮廓太矮"就是这两个数不一样 —— 槽高写的是字号，按钮要被压扁
                println(
                    "[删除按钮量尺] 首选=${button.preferredSize} 槽=${(button.parent as java.awt.Component).preferredSize} " +
                        "文字宽=${button.getFontMetrics(button.font).stringWidth(DELETE_TEXT)} " +
                        "字高=${button.getFontMetrics(button.font).height}",
                )
            }

            // 420px 是工具窗口的真实宽度
            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(8)
                add(list, BorderLayout.NORTH)
                add(Box.createVerticalGlue(), BorderLayout.CENTER)
            }

            val w = 420
            val h = outer.preferredSize.height
            outer.setSize(w, h)
            layoutAll(outer)

            println("[会话列表探针] ${File(path).name} 列表首选=${list.preferredSize}")
            if (clearConfirm) {
                // 量尺：问句**要**多宽、行**给了**多宽。这两个数一相等才没被裁
                // （2026-09-17 第一版就是"要 325、给 250"，图上切掉一截）
                fun walk(c: java.awt.Container, out: MutableList<java.awt.Component>) {
                    c.components.forEach { out += it; if (it is java.awt.Container) walk(it, out) }
                }
                val all = mutableListOf<java.awt.Component>()
                walk(outer, all)
                all.filterIsInstance<javax.swing.JLabel>()
                    .filter { it.text?.contains("清空") == true }
                    .forEach { l ->
                        println("[确认行量尺] 问句要 ${l.preferredSize.width}px，给了 ${l.width}px")
                    }
            }

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun layoutAll(c: java.awt.Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is java.awt.Container) layoutAll(child)
        }
    }

    private fun enterEvent(target: java.awt.Component) = java.awt.event.MouseEvent(
        target, java.awt.event.MouseEvent.MOUSE_ENTERED,
        System.currentTimeMillis(), 0, 5, 5, 0, false,
    )

    /** 递归找第一个满足条件的按钮（删除按钮藏在 `tail` → `deleteSlot` 两层壳里）。 */
    private fun findButton(
        c: java.awt.Container,
        match: (javax.swing.JButton) -> Boolean,
    ): javax.swing.JButton? {
        for (child in c.components) {
            if (child is javax.swing.JButton && match(child)) return child
            if (child is java.awt.Container) findButton(child, match)?.let { return it }
        }
        return null
    }

    private fun findDeleteButton(c: java.awt.Container) = findButton(c) { it.text == DELETE_TEXT }
}
