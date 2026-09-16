package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 状态栏那份聚合记账。
 *
 * 守的是多标签引入的又一次互相擦：服务原先**单槽**（`set(Int)` 覆盖、
 * `restoreAsk` 只有一处），两个面板一起跑时后写的会把先写的擦掉 ——
 * 而"回到那个被最小化的提问"只有状态栏这一个入口，权限询问又没有超时
 * （`PermissionQueue` 里没有任何 deadline），擦掉等于那条路永远回不去。
 */
class PendingPermissionCountTest {

    private val a = Any()
    private val b = Any()

    @Test
    fun `计数按 owner 求和`() {
        val agg = PendingAggregate()
        agg.set(a, 2)
        agg.set(b, 3)

        assertEquals(5, agg.total)
    }

    @Test
    fun `同一个 owner 是覆盖不是累加`() {
        val agg = PendingAggregate()
        agg.set(a, 2)
        agg.set(a, 3)

        assertEquals(3, agg.total, "同一个面板报的是它那一刻的待决数，不是增量")
    }

    @Test
    fun `某个面板退场后只清它那一份`() {
        val agg = PendingAggregate()
        agg.set(a, 2)
        agg.set(b, 3)

        agg.clear(a)

        assertEquals(3, agg.total, "别人的计数不该被带走")
        assertTrue(agg.set(a, 1), "清掉之后它再报一次仍算变化")
        assertEquals(4, agg.total)
    }

    @Test
    fun `挂起提问取或，回来的路取最近登记的那一个`() {
        val agg = PendingAggregate()
        val backA = {}
        val backB = {}
        agg.setSuspended(a, suspended = true, restore = backA)
        agg.setSuspended(b, suspended = true, restore = backB)

        assertTrue(agg.anySuspended)
        assertSame(backB, agg.lastRestore, "两个都挂着时去最近动过的那个")
        assertSame(b, agg.restoreOwner, "而且要能说出它是谁的（状态栏要先切标签）")

        // a 再登记一次（改了自己的说明），最近性跟着它走
        val backA2 = {}
        agg.setSuspended(a, suspended = true, restore = backA2)
        assertSame(backA2, agg.lastRestore)
        assertSame(a, agg.restoreOwner)
    }

    @Test
    fun `没挂起时不认那条回来的路`() {
        val agg = PendingAggregate()
        agg.setSuspended(a, suspended = false, restore = {})

        assertFalse(agg.anySuspended)
        assertNull(agg.lastRestore, "没挂起就不该给出口")
        assertNull(agg.restoreOwner)
    }

    @Test
    fun `值没变就不该惊动状态栏`() {
        // 沿用原来那条短路：面板每次刷状态都会调进来，值没变还通知的话
        // 状态栏会跟着闪
        val agg = PendingAggregate()
        assertTrue(agg.set(a, 1), "从 0 到 1 是变化")
        assertFalse(agg.set(a, 1), "还是 1，不算变化")
        assertFalse(agg.setSuspended(a, false, null), "没挂起过，也没挂起，不算变化")
    }
}
