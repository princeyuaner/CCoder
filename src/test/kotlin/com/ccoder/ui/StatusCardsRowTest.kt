package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container

/** 四张卡怎么排。 */
class StatusCardsRowTest {

    private val quietTodos = StatusCardModel(label = "子任务", value = CARD_IDLE_TEXT, quiet = true)

    private fun row() = StatusCardsRow(onOpenTodos = {}, onOpenRunning = {})

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }

    @Test
    fun `四张卡始终都在，包括没内容的时候`() {
        // 这条钉的是 spec §4 那次推翻：旧原则是"取不到就不显示"，
        // 而这里必须常驻 —— 卡一会儿出现一会儿消失，输入框就会上下跳
        val r = row()
        r.todos.setModel(quietTodos)
        r.running.setModel(StatusCardModel(label = "子代理", value = CARD_IDLE_TEXT, quiet = true))

        assertEquals(4, r.componentCount, "四张卡必须常驻")
        assertTrue(r.components.all { it.isVisible }, "收边不等于隐藏")
    }

    @Test
    fun `只有子任务与子代理可点`() {
        val r = row()

        assertTrue(r.todos.mouseListeners.isNotEmpty(), "子任务卡该可点")
        assertTrue(r.running.mouseListeners.isNotEmpty(), "子代理卡该可点")
        assertTrue(r.connection.mouseListeners.isEmpty(), "连接卡没有更多可看的，不该可点")
        assertTrue(r.context.mouseListeners.isEmpty(), "上下文卡没有更多可看的，不该可点")
    }

    @Test
    fun `四张卡等宽，总量不超出可用宽度`() {
        val r = row()
        r.setSize(420, 60)
        layoutAll(r)

        val widths = r.components.map { it.width }
        assertEquals(1, widths.distinct().size, "四张卡宽度不一致：$widths")
        assertTrue(widths.sum() <= 420, "总宽超出面板：${widths.sum()}")
        assertTrue(widths.all { it > 80 }, "每张卡被压得太窄，内容会裁掉：$widths")
    }

    @Test
    fun `状态行有自己的最小高度，不会被压没`() {
        // 分隔条设了 honorComponentsMinimumSize，这个值决定底部最少占多高。
        // 计划里那个"约 58px"是估的，这条把它变成量出来的数。
        //
        // **必须先灌模型再量**：空模型的卡里四个标签都是空文字，首选高度
        // 只有 15px（边框 + 内边距）。生产里 setupUI 会先调一次
        // refreshStatusCards()，所以这里也得照做，否则量的是一个并不存在
        // 的"未配置"状态。
        //
        // 断言的是**关系**而不是绝对像素：绝对高度随 DPI 与 IDE 字体变，
        // 钉死它换台机器就红。真正要守的是"行的最小高度 = 最高那张卡的首选
        // 高度"，也就是分隔条最多把状态行压到刚好一张卡那么高。
        //
        // 注意不是拿任意一张卡来比：上下文卡有副值那一行，比另外三张高。
        // 2026-09-13 在 1x DPI 下实测 —— 上下文卡 67px，其余三张 49px，
        // 因而行的最小高度是 67px。计划里估的"约 58px"少算了 9px。
        val r = configured()
        val heights = r.components.map { it.preferredSize.height }

        assertEquals(
            heights.max(),
            r.minimumSize.height,
            "四张卡首选高度=$heights，行最小=${r.minimumSize.height}",
        )
    }

    @Test
    fun `卡的最小高度跟着首选高度走，不会被压扁`() {
        // JLabel 没有布局管理器，getMinimumSize() 落到 Component.size() 也就是 0。
        // 不覆写的话整行最小高度只剩边框那 2px，分隔条就能把它拖成一条缝
        val r = configured()
        val card = r.connection

        assertEquals(card.preferredSize.height, card.minimumSize.height, "卡会被压扁")
    }

    /** 跟生产一样：四张卡都灌上模型。 */
    private fun configured(): StatusCardsRow = row().apply {
        connection.setModel(connectionCardOf("已连接"))
        context.setModel(contextCardOf(ContextUsage(inputTokens = 12300, contextWindow = 200000)))
        todos.setModel(todoCardOf(null))
        running.setModel(runningCardOf(emptyList()))
    }
}
