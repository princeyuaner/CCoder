package com.ccoder.ui

import com.ccoder.text.CcoderText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * 工具栏那颗 MCP 缓存徽章（2026-10-14，选型台乙案）。
 *
 * 钉的是**什么时候露头、头上写什么、可不可点**这三件 —— "琥珀色够不够看得清"
 * 那种交给 `McpCacheBadgeRenderProbe` 出图。状态与文案的映射在
 * `McpCacheWarn.kt` 的纯函数里已经钉过一轮，这里补的是组件自己那道显隐门：
 * pending 看值当、失败常驻、绿的收尾要先"开过口"。
 *
 * 文案一律和 `CcoderText.text(键)` 对，不写字面量 —— 测试 JVM 的语言随
 * `-PtestLang` 变（同仓库里其它探针的惯例）。
 */
class McpCacheBadgeTest {

    @Test
    fun `pending 没到值当门槛就不露头`() {
        val badge = McpCacheBadge {}
        badge.setState(CacheWarnState.Pending("a"), worthwhile = false)
        assertFalse(badge.isVisible, "新会话第一发为它开口是纯噪音（用户选定「仅恢复/长会话」）")
    }

    @Test
    fun `pending 值当就显示，字是徽章那句短的`() {
        val badge = McpCacheBadge {}
        badge.setState(CacheWarnState.Pending("a"), worthwhile = true)
        assertTrue(badge.isVisible)
        assertEquals(CcoderText.text("mcp.cacheWarn.badge.pending"), badge.badgeText())
    }

    @Test
    fun `失败不受值当门槛管 —— 配置坏了就得说，且整颗可点`() {
        val badge = McpCacheBadge {}
        // worth 传 false 也照样露头：值当那条判据（cacheWarnWorthwhile）只认 pending，
        // 它管的是"缓存贵不贵"；失败是"配置出了问题"，跟会话长短无关
        badge.setState(CacheWarnState.Failed("broken"), worthwhile = false)
        assertTrue(badge.isVisible, "失败藏着才是骗人（旧带子的设计注释原话）")
        assertEquals(CcoderText.text("mcp.cacheWarn.badge.failed"), badge.badgeText())
        assertEquals(Cursor.HAND_CURSOR, badge.cursor?.type, "失败态整颗可点开设置")
    }

    @Test
    fun `没显示过就落定 —— 凭空冒一条绿的在屏幕上没人看得懂`() {
        val badge = McpCacheBadge {}
        badge.setState(CacheWarnState.Settled, worthwhile = false)
        assertFalse(badge.isVisible)
    }

    @Test
    fun `先前显示过才轮到绿的收尾`() {
        val badge = McpCacheBadge {}
        badge.setState(CacheWarnState.Pending("a"), worthwhile = true)
        badge.setState(CacheWarnState.Settled, worthwhile = false)
        assertTrue(badge.isVisible)
        assertEquals(CcoderText.text("mcp.cacheWarn.badge.settled"), badge.badgeText())
        // 收尾会留一个单发 Timer 在跑 —— 出完就撤掉（同探针纪律：别让它攥着组件）
        badge.dismiss()
    }

    @Test
    fun `撤下之后什么都不显示`() {
        val badge = McpCacheBadge {}
        badge.setState(CacheWarnState.Pending("a"), worthwhile = true)
        badge.dismiss()
        assertFalse(badge.isVisible)
    }

    @Test
    fun `窄窗让位 —— 行放不下时退成点，宽回来就撑回去`() {
        val badge = McpCacheBadge {}
        // 行 = 工具栏那只 BorderLayout（WEST 左排 / EAST 右组），徽章住在右组里 ——
        // 复刻 buildComposerToolbar 的装配
        val row = JPanel(BorderLayout())
        val west = JPanel().apply { preferredSize = Dimension(320, 30) }
        val east = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(badge)
        }
        row.add(west, BorderLayout.WEST)
        row.add(east, BorderLayout.EAST)

        row.setSize(560, 40)
        badge.setState(CacheWarnState.Pending("a"), worthwhile = true)
        layoutAll(row)
        assertFalse(badge.isCompact(), "560 放得下，不该退")

        row.setSize(360, 40)
        layoutAll(row)
        assertTrue(badge.isCompact(), "360 放不下了，字该退成点")
        assertEquals(
            CcoderText.text("mcp.cacheWarn.badge.pending"),
            badge.badgeText(),
            "退成点只是不画字，字还在（悬停要用）",
        )

        row.setSize(560, 40)
        layoutAll(row)
        assertFalse(badge.isCompact(), "判据对当前形态不变，宽回来就撑回 —— 不会来回抖")
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
