package com.ccoder.ui

import com.ccoder.sidecar.UsageDay
import com.ccoder.sidecar.UsageModelTokens
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 统计框的**宽度护栏**（2026-10-13 用户报「这个框太宽了，缩小一半」）。
 *
 * 案发经过：图例第一版是一条不换行的 X 排 —— 13 个长名模型把内容的**最小宽**
 * 撑到 2358px，Windows 上弹窗被顶到近满屏（实测 1568）。修法：图例按内容宽
 * 折行；这里把「最小宽不许再爆」钉成回归线。
 *
 * **不是渲染探针**：探针管排版好不好看（要人眼看），这条只管几何爆没爆（机器判）。
 */
class UsageDialogLayoutTest {

    private val today = LocalDate.of(2026, 10, 10)

    /** 形状照用户实况：13 个模型、名字长（含 `[1m]` 与斜杠）。 */
    private fun manyModels(): List<UsageDay> {
        val names = listOf(
            "deepseek-v4-flash", "deepseek-flash", "deepseek-v4.1-flash[1m]", "deepseek-v4.1-flash",
            "deepseek-flash-guan[1m]", "deepseek-flash[1m]", "deepseek-v4-flash[1m]",
            "deepseek/deepseek-flash", "deepseek-v4-flash-vision-exp",
            "mimo-v2.6-pro", "mimo-v2.6-flash", "claude-sonnet-5-5-cc[1m]", "claude-opus-5-5-cc[1m]",
        )
        return (29 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val grown = 4_000_000L + ((29 - back) * 297_000L) % 6_000_000L
            UsageDay(
                d,
                names.mapIndexed { i, n ->
                    n to UsageModelTokens(grown / (i + 2), grown / (i + 50), 0L, 0L, 1.0)
                }.toMap(),
            )
        }
    }

    @Test
    fun `13 个模型：内容最小宽不许爆 —— 图例必须折行`() {
        val panel = buildUsagePanel(
            rows = manyModels(),
            scope = "project",
            gran = UsageGran.Day,
            today = today,
            onPickGran = {},
            onPickScope = {},
        )
        assertEquals(JBUI.scale(780), panel.preferredSize.width, "打开宽 = 设计宽")
        assertTrue(
            panel.minimumSize.width <= JBUI.scale(800),
            "内容最小宽 ${panel.minimumSize.width}：图例没折行 / 又有谁在撑宽",
        )
    }
}
