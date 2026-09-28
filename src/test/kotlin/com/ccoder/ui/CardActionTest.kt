package com.ccoder.ui

import com.ccoder.sync.SyncRun
import com.ccoder.sync.SyncSnapshot
import com.ccoder.text.CcoderText
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

    // ---- 同步那颗开关（2026-09-24）----

    @Test
    fun `在跑的时候给关闭那颗`() {
        val action = syncActionOf(SyncSnapshot(run = SyncRun.RUNNING), pathsReady = true)

        assertEquals(CardActionKind.TurnOff, action.kind)
        assertTrue(action.enabled)
        assertFalse(action.danger, "关同步不删东西 —— 已经复制过去的留在原地")
    }

    @Test
    fun `有失败项也是在跑，照样给关闭那颗`() {
        assertEquals(CardActionKind.TurnOff, syncActionOf(SyncSnapshot(run = SyncRun.FAILED), true).kind)
    }

    @Test
    fun `被别的窗口占着时也给关闭 —— 开关本身是开的`() {
        assertEquals(CardActionKind.TurnOff, syncActionOf(SyncSnapshot(run = SyncRun.OCCUPIED), true).kind)
    }

    @Test
    fun `起不来那一档必须给关闭，不能给开启`() {
        // 这条是这一组里最要紧的：STOPPED 的 `enabled` 已经是真，
        // 再"开启"一次 `SyncSettings.update` 会判"没变"直接返回 —— 那是个
        // **点了什么都不发生的按钮**（仓库里管这叫"画出可点的东西却点不动，是在骗人"）。
        // 给关闭则两个方向都真：关掉 → 再打开就是一次真正的重试
        val action = syncActionOf(SyncSnapshot(run = SyncRun.STOPPED), pathsReady = true)

        assertEquals(CardActionKind.TurnOff, action.kind)
        assertTrue(action.enabled)
    }

    @Test
    fun `没开的时候给开启那颗`() {
        val action = syncActionOf(SyncSnapshot(run = SyncRun.DISABLED), pathsReady = true)

        assertEquals(CardActionKind.TurnOn, action.kind)
        assertTrue(action.enabled)
    }

    @Test
    fun `还没拿到状态时按"没开"画`() {
        // 服务取不到（项目已关那一类的路径）时卡照常画 —— 不能因为拿不到状态
        // 就画一颗方向不明的按钮
        assertEquals(CardActionKind.TurnOn, syncActionOf(null, pathsReady = true).kind)
    }

    @Test
    fun `两端目录没选时开启那颗是灰的，并说清缺什么`() {
        // 没配置就开 = 白跑一次重建（`SyncConfig.isConfigured` 三个条件缺一不启动）。
        // 比的是**那个键翻出来的字**而不是字面量：这一条要钉的是"灰的时候换了一句
        // 解释"，中文那份具体怎么写由词表说了算（`-PtestLang=en` 那一遍也不该因为
        // 它多红一条 —— 那遍是量宽度的）
        val action = syncActionOf(SyncSnapshot(run = SyncRun.DISABLED), pathsReady = false)

        assertFalse(action.enabled)
        assertEquals(CcoderText.text("card.action.syncOnDisabled"), action.tooltip)
    }

    @Test
    fun `关闭那颗永远可说 —— 它不依赖会话，也不依赖目录还在不在`() {
        // 盘拔了、目录被删了，恰恰是最需要能把它关掉的时刻
        val action = syncActionOf(SyncSnapshot(run = SyncRun.STOPPED), pathsReady = false)

        assertEquals(CardActionKind.TurnOff, action.kind)
        assertTrue(action.enabled)
    }
}
