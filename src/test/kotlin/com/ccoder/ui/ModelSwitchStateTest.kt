package com.ccoder.ui

import com.ccoder.settings.AuthKind
import com.ccoder.settings.ModelProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 点一个模型会走哪条路。
 *
 * 抽成纯函数的理由同 [SessionSwitchState]：ClaudePanel 依赖 Swing 与平台、
 * 起不了单测，而"这一下是热切还是重开"正是这次改动里最容易写错的地方 ——
 * 弹层上那句〔会重开会话〕与真正发生的事**必须**是同一个判定。
 */
class ModelSwitchStateTest {

    private val relay = ModelProfile(
        name = "中转",
        baseUrl = "https://api.example.com",
        modelIds = mutableListOf("a", "b"),
        modelId = "a",
        authKind = AuthKind.AUTH_TOKEN.name,
    )

    @Test
    fun `同一条配置里换模型是热切`() {
        assertEquals(
            PickEffect.Hot,
            pickEffect(hasSession = true, current = relay, currentSecret = "tok", to = relay, toSecret = "tok"),
        )
    }

    @Test
    fun `换了端点只能重开`() {
        val other = relay.copy(baseUrl = "https://other.example.com")

        assertEquals(
            PickEffect.Restart,
            pickEffect(hasSession = true, current = relay, currentSecret = "tok", to = other, toSecret = "tok"),
        )
    }

    /**
     * 没有会话时一律 [PickEffect.NoSession]，**哪怕端点一模一样** ——
     * 那时没有"切"这个动作，选中的值只是落到设置里。这一支必须先判，
     * 否则会在一个根本没有会话的面板上标"会重开会话"，而那时没有东西可重开。
     */
    @Test
    fun `没有会话时一律是按设置改，不是重开`() {
        assertEquals(
            PickEffect.NoSession,
            pickEffect(hasSession = false, current = relay, currentSecret = "tok", to = relay, toSecret = "tok"),
        )
        assertEquals(
            PickEffect.NoSession,
            pickEffect(
                hasSession = false, current = relay, currentSecret = "tok",
                to = relay.copy(baseUrl = "https://other.example.com"), toSecret = "tok",
            ),
        )
    }

    @Test
    fun `切到一条没配模型的配置只能重开`() {
        val bare = ModelProfile(name = "裸配置", baseUrl = "https://api.example.com", authKind = AuthKind.AUTH_TOKEN.name)

        assertEquals(
            PickEffect.Restart,
            pickEffect(hasSession = true, current = relay, currentSecret = "tok", to = bare, toSecret = "tok"),
        )
    }

    // ---- 那行标记 ----

    @Test
    fun `只有要重开才标出来`() {
        assertEquals("会重开会话", restartBadge(PickEffect.Restart))
        assertNull(restartBadge(PickEffect.Hot), "热切不丢东西，标出来反而像警告")
        assertNull(restartBadge(PickEffect.NoSession), "没有会话时标重开是吓唬人 —— 没有上下文可丢")
    }
}
