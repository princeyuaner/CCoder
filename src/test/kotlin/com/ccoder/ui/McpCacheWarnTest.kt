package com.ccoder.ui

import com.ccoder.sidecar.McpServerStatus
import com.ccoder.text.CcoderText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 「MCP 还没连完 · 现在发送要全价重算」这条提示的判断。
 *
 * 钉的是 2026-10-13 实测那两发（78,536 / 92,545 个全新输入 token、缓存读只有
 * 8,704 / 16,128 ≈ 17 万 token 全价重算一次）的形状：**什么时候值得开口**
 * （只对恢复的/长起来的会话）、**该报哪一档**（pending 先于失败，disabled 不报）、
 * **什么时候该把用户的发送拦下来问一句**。
 */
class McpCacheWarnTest {

    @Test
    fun `还有在连的就报 pending，且优先于失败`() {
        val state = cacheWarnStateOf(
            listOf(status("codegraph", "pending"), status("broken", "failed")),
        )
        assertEquals(CacheWarnState.Pending("codegraph"), state, "还在连时报失败是早报")
    }

    @Test
    fun `没人 pending 时才报失败，要认证的也算失败`() {
        assertEquals(
            CacheWarnState.Failed("broken"),
            cacheWarnStateOf(listOf(status("broken", "failed"))),
        )
        assertEquals(
            CacheWarnState.Failed("login"),
            cacheWarnStateOf(listOf(status("login", "needs-auth"))),
        )
    }

    @Test
    fun `名字按词表的分隔符连，空名不进`() {
        val sep = CcoderText.text("common.listSeparator")
        assertEquals(
            CacheWarnState.Pending(listOf("a", "b").joinToString(sep)),
            cacheWarnStateOf(listOf(status("a", "pending"), status("b", "pending"))),
        )
        assertEquals(
            CacheWarnState.Pending("a"),
            cacheWarnStateOf(listOf(status("a", "pending"), status("", "pending"))),
        )
    }

    @Test
    fun `disabled 与认不出的状态都不参与 —— 不挂一条永远不消失的提示`() {
        // disabled 是我们自己关的：它会一直躺在名单里，报它等于永远挂着一句话
        assertEquals(CacheWarnState.Settled, cacheWarnStateOf(listOf(status("off", "disabled"))))
        assertEquals(CacheWarnState.Settled, cacheWarnStateOf(listOf(status("weird", "whatever"))))
        assertEquals(CacheWarnState.Settled, cacheWarnStateOf(emptyList()))
    }

    @Test
    fun `只对恢复的或长起来的会话值当开口`() {
        // 新开的空会话首发本来就只有一两万 token —— 为它弹提示是噪音（用户选定）
        val pending = CacheWarnState.Pending("a")
        assertFalse(cacheWarnWorthwhile(CacheWarnState.Settled, resumed = true, usedTokens = 150_000L))
        assertFalse(cacheWarnWorthwhile(pending, resumed = false, usedTokens = null))
        assertFalse(cacheWarnWorthwhile(pending, resumed = false, usedTokens = 5_000L))
        assertTrue(cacheWarnWorthwhile(pending, resumed = true, usedTokens = null))
        assertTrue(cacheWarnWorthwhile(pending, resumed = false, usedTokens = CACHE_WARN_TOKENS))
    }

    @Test
    fun `四个前提缺一不可 —— 这是唯一会把输入卡在手里的判断`() {
        val pending = CacheWarnState.Pending("a")
        assertTrue(shouldConfirmCacheWarn(pending, worth = true, muted = false, bypass = false))
        assertFalse(shouldConfirmCacheWarn(null, true, false, false), "没有读数不拦")
        assertFalse(shouldConfirmCacheWarn(CacheWarnState.Settled, true, false, false), "落定不拦")
        assertFalse(shouldConfirmCacheWarn(pending, worth = false, muted = false, bypass = false))
        assertFalse(shouldConfirmCacheWarn(pending, true, muted = true, bypass = false))
        assertFalse(shouldConfirmCacheWarn(pending, true, muted = false, bypass = true), "刚按过照发")
    }

    @Test
    fun `工具栏放不下带字的徽章时退成点（让位规则）`() {
        // 560 一行：左排 320 + 右排 65 + 徽章完整宽 90 = 475 ≤ 560 —— 放得下
        assertFalse(
            cacheWarnNeedsCompact(rowWidth = 560, westWidth = 320, eastWidthWithoutBadge = 65, badgeFullWidth = 90),
        )
        // 420 一行：475 > 420 —— 挤了，字该退成点（2026-10-14 选型台那张窄窗体检图的处境）
        assertTrue(
            cacheWarnNeedsCompact(rowWidth = 420, westWidth = 320, eastWidthWithoutBadge = 65, badgeFullWidth = 90),
        )
        // 恰好占满不算挤（判据是"超过"才退）
        assertFalse(
            cacheWarnNeedsCompact(rowWidth = 475, westWidth = 320, eastWidthWithoutBadge = 65, badgeFullWidth = 90),
        )
    }

    private fun status(name: String, status: String) =
        McpServerStatus(name = name, status = status, scope = null, error = null, tools = emptyList())
}
