package com.ccoder.ui

import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * **量词**用的探针（2026-09-24）：把候选文案塞进真实的卡里，打印"要多少 px / 有多少 px"。
 *
 * 为什么要有它：`StatusCardsRowTest` 只会报**当前那套词**里谁撑破了格子 ——
 * 而那正是"挑词"的最后一关，不是挑词时的尺子。写英文那几个词时得先看一批候选
 * （Offline / Linked / Lost / Failed…）各自多宽，否则一次改一个词、跑一遍测试，
 * 二十次才收敛。
 *
 * 与 `StatusCardsRowTest` 那两条量法一致：404px 的一行、真机 LAF、量值行那个 JLabel。
 * 用法：`./gradlew test --tests "com.ccoder.ui.CardTextMeasureProbe"`，读测试报告里的
 * system-out（或者 `-i` 直接看）。
 */
class CardTextMeasureProbe {

    @Test
    fun `量一批候选词`() = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val row = StatusCardsRow(
                onClear = {}, onCompact = {}, onOpenContext = {}, onOpenTodos = {},
                onOpenRunning = {}, onOpenSync = {}, onToggleSync = {},
            )
            // 先灌一轮模型（不然卡里那些标签是空文字，量出来的是"没有内容"的宽度）
            row.connection.setModel(connectionCardOf(ConnectionState.Connected))
            row.sync.setModel(syncCardOf(null))
            row.context.setModel(contextCardOf(ContextUsage(12300, 200000)))
            row.todos.setModel(todoCardOf(null))
            row.running.setModel(runningCardOf(emptyList()))
            row.setSize(404, 100)
            layoutAll(row)

            val available = valueLabelOf(row.connection).width
            println("可用宽度（值行，404px 五张卡）：$available px")

            // 连接卡那八档的候选
            measure("连接", row.connection, available, listOf(
                "No session", "Offline", "No chat", "Not yet", "Unstarted",
                "Loading…", "Loading",
                "Starting…", "Starting", "Booting", "Warming",
                "Connected", "Linked", "Ready", "Live", "Online",
                "No resume", "Lost", "No tab", "Fresh", "Not resumed",
                "Won't start", "Failed", "No start", "Won't run", "Not started",
                "Dropped", "Lost link",
                "Ended",
            ))
            // 动作词（值行 / 标签行都过一遍）
            measure("动作词", row.connection, available, Activity.entries.map { it.text() } + "Search")
            // 上下文卡：压缩中那句
            measure("压缩中", row.context, available, listOf(
                "Compress", "Compacting", "Shrink", "Shrinking", "Packing", "Squeeze", "Tight",
            ))
            // 恢复失败那一档：几个短候选
            measure("恢复失败", row.connection, available, listOf("Lost", "Missing", "Not found", "No tab"))
            // 同步卡那五个词（列出来是让这份表自己站得住：它们本来就在 58px 以内）
            measure("同步", row.sync, available, SyncStateWords)
        }
    }

    private val SyncStateWords = listOf("Running", "Stopped", "In use", "Not set", "Failed")

    private fun measure(cardTitle: String, card: StatusCardView, available: Int, words: List<String>) {
        println("---- $cardTitle ----")
        for (word in words) {
            card.setModel(StatusCardModel(label = CARD_LINK, value = word))
            layoutAll(card)
            val label = valueLabelOf(card)
            val flag = if (label.preferredSize.width > available) "✗" else "✓"
            println("$flag ${label.preferredSize.width.toString().padStart(3)}px  $word")
        }
    }

    private fun valueLabelOf(card: StatusCardView): JLabel {
        val valueRow = card.components
            .filterIsInstance<javax.swing.JPanel>()
            .first { it.layout is java.awt.BorderLayout }
        return (valueRow.layout as java.awt.BorderLayout)
            .getLayoutComponent(java.awt.BorderLayout.CENTER) as JLabel
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
