package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 两颗动作按钮的纯逻辑（spec `2026-09-17-card-actions-design.md` §3.5/§3.3）。
 *
 * 钉住的是三件最容易写错的事：**什么时候能点**、**点了落哪儿**、
 * **压缩中什么时候开始与结束**（判据是 CLI 的 status，不是"我们发了什么"）。
 */
class CardActionTest {

    // ---- 三态 ----

    @Test
    fun `就绪且空闲时两颗都能点`() {
        val clear = clearActionOf(ready = true, busy = false)
        val compact = compactActionOf(ready = true, busy = false, compacting = false)!!
        assertTrue(clear.enabled)
        assertTrue(compact.enabled)
    }

    @Test
    fun `忙时两颗都灰`() {
        assertFalse(clearActionOf(ready = true, busy = true).enabled)
        assertFalse(compactActionOf(ready = true, busy = true, compacting = false)!!.enabled)
    }

    @Test
    fun `未就绪时两颗都灰`() {
        // 启动中 / 启动失败 / 已断开：会话都不在，点了也没得发
        assertFalse(clearActionOf(ready = false, busy = false).enabled)
        assertFalse(compactActionOf(ready = false, busy = false, compacting = false)!!.enabled)
    }

    @Test
    fun `压缩中不给动作 —— 值行让给压缩中那三个字`() {
        assertNull(compactActionOf(ready = true, busy = true, compacting = true))
        // 清空那颗不受影响：它只说自己的忙闲
        assertTrue(clearActionOf(ready = true, busy = false).kind == CardActionKind.Clear)
    }

    @Test
    fun `灰着的时候提示说清为什么`() {
        val busyClear = clearActionOf(ready = true, busy = true)
        assertEquals("会话空闲时可清空", busyClear.tooltip)
        assertFalse(busyClear.tooltip.contains("磁盘"), "灰着时不该还在讲清空的语义")
    }

    @Test
    fun `清空是 danger，压缩不是`() {
        // 颜色是"这个动作危不危险"的唯一载体（spec §3.4）
        assertTrue(clearActionOf(ready = true, busy = false).danger)
        assertFalse(compactActionOf(ready = true, busy = false, compacting = false)!!.danger)
    }

    // ---- 点击路由（spec §3.3）----

    @Test
    fun `点中右上角的动作图标 → 执行动作`() {
        assertEquals(
            CardClickTarget.Action,
            cardClickTargetOf(onActionIcon = true, hasAction = true, hasDetail = true),
        )
    }

    @Test
    fun `点卡片别处 → 照旧开详情（有动作也一样）`() {
        // 图标常驻，但动作只认那一块 16×16；其余部分仍归详情
        assertEquals(
            CardClickTarget.OpenDetail,
            cardClickTargetOf(onActionIcon = false, hasAction = true, hasDetail = true),
        )
    }

    @Test
    fun `那张格子没有动作 → 点它什么也不做`() {
        assertEquals(
            CardClickTarget.None,
            cardClickTargetOf(onActionIcon = true, hasAction = false, hasDetail = false),
        )
    }

    @Test
    fun `连接卡（没有详情）点别处什么也不做`() {
        assertEquals(
            CardClickTarget.None,
            cardClickTargetOf(onActionIcon = false, hasAction = true, hasDetail = false),
        )
    }

    // ---- 压缩中的判据（spec 事实 9/11）----

    private fun status(json: String) = compactStateOfStatus(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `compacting 进入压缩中`() {
        assertEquals(
            CompactState.Compacting,
            status("""{"type":"system","subtype":"status","status":"compacting","compact_result":null}"""),
        )
    }

    @Test
    fun `status 为 null 退出压缩中`() {
        // 实测：退出是 status:null + compact_result:"success"，**比 compact_boundary 早**
        // （boundary 在 init 之后）。卡上不该多挂一秒
        assertEquals(
            CompactState.Idle,
            status("""{"type":"system","subtype":"status","status":null,"compact_result":"success"}"""),
        )
    }

    @Test
    fun `requesting 不改状态 —— 每一回合都有它`() {
        // 拿它当压缩中的话，普通问答也会让卡上写"压缩中…"
        assertNull(status("""{"type":"system","subtype":"status","status":"requesting"}"""))
    }

    @Test
    fun `别的事件一律 null（不猜）`() {
        assertNull(status("""{"type":"assistant","status":"compacting"}"""))
        assertNull(status("""{"type":"system","subtype":"init","status":"compacting"}"""))
        assertNull(status("""{"type":"system","subtype":"status"}"""))
        assertNull(status("""{"type":"system","subtype":"status","status":"认不出的值"}"""))
    }
}
