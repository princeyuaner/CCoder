package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 启动期取消闸。
 *
 * 这个类守的是多标签引入的那条真 bug：**启动期面板手里什么都没有**（进程已经起了，
 * `proc` 还是 null），此时被停掉的启动没有任何人会去收进程 —— 它连着 claude 一起
 * 活到天荒地老，静默烧额度。判定能在这里钉住，接线（池线程三处判空）只能靠冒烟。
 */
class SessionGateTest {

    @Test
    fun `刚开的这一趟是有效的`() {
        val gate = SessionGate()
        val token = gate.begin()
        assertTrue(gate.isCurrent(token))
    }

    @Test
    fun `作废之后旧令牌一律不认`() {
        val gate = SessionGate()
        val token = gate.begin()

        gate.invalidate()

        assertFalse(gate.isCurrent(token), "停过会话的启动不该再被认")
    }

    @Test
    fun `又开了一趟之后，前一趟的令牌失效`() {
        val gate = SessionGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first), "旧令牌不该认")
        assertTrue(gate.isCurrent(second), "新令牌要认")
    }

    @Test
    fun `走完之后旧令牌不认（成功与失败都算走完）`() {
        val gate = SessionGate()
        val ok = gate.begin()
        gate.finish()
        assertFalse(gate.isCurrent(ok))

        val failed = gate.begin()
        gate.invalidate()   // fail() 走的是这条
        assertFalse(gate.isCurrent(failed))
    }

    @Test
    fun `令牌是单调递增的，不会因为作废而回绕`() {
        val gate = SessionGate()
        val tokens = (1..5).map { gate.begin() }
        assertEquals(tokens.sorted(), tokens)
        assertEquals(tokens.toSet().size, tokens.size, "令牌不许重复")
    }
}
