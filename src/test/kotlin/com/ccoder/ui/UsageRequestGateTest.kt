package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 用量请求的闸。
 *
 * 它挡的是 2026-09-14 那次事故的形状：**同一条控制通道被自己的请求灌满**。
 * 位置已经修好，这条闸是"再犯一次也只付一条请求的代价"的保险。
 */
class UsageRequestGateTest {

    @Test
    fun `第一个请求放行`() {
        assertTrue(UsageRequestGate().acquire())
    }

    @Test
    fun `在途时挡住后来的，不排队也不丢弃`() {
        // 挡掉是**对**的：同一秒问两遍用量，答案只会一样；
        // 而每条请求都要过一趟控制通道，而事件流和它共用那根管道
        val gate = UsageRequestGate()
        assertTrue(gate.acquire())
        assertFalse(gate.acquire())
        assertFalse(gate.acquire())
    }

    @Test
    fun `放闸之后又能发了`() {
        // 超时放闸这一条尤其要紧：不放的话，第一次超时之后就再也问不到用量
        val gate = UsageRequestGate()
        gate.acquire()
        gate.release()
        assertTrue(gate.acquire())
    }
}
