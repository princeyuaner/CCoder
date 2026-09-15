package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.ccoder.settings.PermissionModeSetting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 设置对话框关掉之后，"要不要推给正在跑的会话"那一步的判定。
 *
 * 为什么值得单独测：真正干活的 `pickPermissionMode` / `pickEffort` 要发协议、
 * 要弹提示，在单测里跑不动（`show()` 在无头 JVM 里会立刻返回并关闭）——
 * 而**边界全在这个纯函数里**，接线那一层只剩几行 [ClaudePanel]。
 */
class SettingsApplyTest {

    private fun plan(
        ready: Boolean = true,
        currentMode: PermissionModeSetting = PermissionModeSetting.DEFAULT,
        currentEffort: EffortSetting = EffortSetting.DEFAULT,
        savedMode: PermissionModeSetting = PermissionModeSetting.DEFAULT,
        savedEffort: EffortSetting = EffortSetting.DEFAULT,
    ) = settingsApplyPlan(ready, currentMode, currentEffort, savedMode, savedEffort)

    /**
     * **这条是隐藏的正确性要求。**
     *
     * `pickPermissionMode` 的第一件事就是在相等判断**之前**清掉 `autoAllow`
     * （用户想关掉"本会话不再询问"时就是这么关的）。所以这里要是无脑推一次，
     * "打开设置、什么都没改、关掉"就会把那个开关静默清掉 —— 用户完全不知道。
     */
    @Test
    fun `什么都没改时一个字节都不推`() {
        val p = plan()

        assertNull(p.permissionMode, "没改却要推权限模式 —— 那会把「本会话不再询问」静默清掉")
        assertNull(p.effort)
        assertFalse(p.deferred, "没改就不该插一句「下次生效」——那是噪音")
    }

    /**
     * 会话没就绪时两条都不推。
     *
     * `client != null` 但 `ready == false` 的窗口期真实存在（`sendStart` 之后、
     * `Ready` 之前）。那时推过去，消息会落到一个**启动参数早已发出去**的会话上 ——
     * 标签显示一个这条会话实际没在用的模式，正是"控件撒谎"。
     */
    @Test
    fun `会话没就绪时两条都不推，但记着用户改过`() {
        val p = plan(
            ready = false,
            savedMode = PermissionModeSetting.PLAN,
            savedEffort = EffortSetting.HIGH,
        )

        assertNull(p.permissionMode)
        assertNull(p.effort)
        assertTrue(p.deferred, "改过就得说一声「下次生效」，否则用户以为白改了")
    }

    /**
     * 没就绪 **且** 什么都没改 —— 也不该插提示。
     *
     * 这条与上一条是一对：少了它，"每开一次设置都冒一句「下次生效」"这个毛病
     * 没有任何东西会红（而它正是那句 `mode == null && effort == null` 的唯一理由）。
     */
    @Test
    fun `会话没就绪、但值也没变时不插提示`() {
        val p = plan(ready = false)

        assertNull(p.permissionMode)
        assertFalse(p.deferred, "什么都没改，凭什么说「下次生效」——这句话每次开设置都会冒一遍")
    }

    @Test
    fun `两个都变了时两条都给`() {
        val p = plan(
            savedMode = PermissionModeSetting.ACCEPT_EDITS,
            savedEffort = EffortSetting.MAX,
        )

        assertEquals(PermissionModeSetting.ACCEPT_EDITS, p.permissionMode)
        assertEquals(EffortSetting.MAX, p.effort)
        assertFalse(p.deferred)
    }

    @Test
    fun `只变了一个时只推那一个`() {
        val p = plan(savedEffort = EffortSetting.LOW)

        assertNull(p.permissionMode)
        assertEquals(EffortSetting.LOW, p.effort)
    }

    /**
     * **忙不是障碍。** 这两项改的都是"下一轮"的行为，回合进行中照样该生效 ——
     * `pickEffort` 那边的注释明说了"忙时也允许改，也不弹确认框"。
     *
     * 所以这个判定里**没有**、也不该有 `busy` 这个参数。这条用例钉着这件事：
     * 哪天有人想加一个 `if (busy) return`，先得把这里改红。
     */
    @Test
    fun `判定里根本没有忙这一项 —— 它就两个状态：就绪与否`() {
        val p = plan(ready = true, savedMode = PermissionModeSetting.DONT_ASK)

        assertEquals(PermissionModeSetting.DONT_ASK, p.permissionMode)
    }
}
