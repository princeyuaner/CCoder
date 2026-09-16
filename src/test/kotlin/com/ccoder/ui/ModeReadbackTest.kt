package com.ccoder.ui

import com.ccoder.settings.PermissionModeSetting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * status 报回来的模式与界面显示不一致时走哪条分支。
 *
 * 这个判定管的是"控件撒谎"这件事的另一半：切换有回执所以向来可信，
 * 而**会话以什么模式起来**从前没有任何读回手段。判错的两个方向都糟 ——
 * 该说的不说（用户以为自己在自动判定下跑，其实每次都在被问），
 * 或者不该说的说（用户自己点的切换，转写区里冒出两条说明）。
 */
class ModeReadbackTest {

    @Test
    fun `读出来的和显示的一样 —— 什么都不做`() {
        assertEquals(
            ModeReadback.None,
            modeReadbackOf(PermissionModeSetting.AUTO, PermissionModeSetting.AUTO, requested = null),
        )
    }

    @Test
    fun `没读出来（认不出或没带字段）—— 保持原样`() {
        assertEquals(
            ModeReadback.None,
            modeReadbackOf(null, PermissionModeSetting.AUTO, requested = PermissionModeSetting.PLAN),
        )
    }

    @Test
    fun `就是用户刚点的那次 —— 改标签但不吭声（说明由回执那条负责）`() {
        assertEquals(
            ModeReadback.Silent,
            modeReadbackOf(PermissionModeSetting.AUTO, PermissionModeSetting.DEFAULT, requested = PermissionModeSetting.AUTO),
        )
    }

    @Test
    fun `用户没点过这个分歧 —— 改标签并说明`() {
        // 这一条正是从来看不见的那个场景：闸门在 CLI 侧把它换成了别的档
        assertEquals(
            ModeReadback.Announce,
            modeReadbackOf(PermissionModeSetting.DEFAULT, PermissionModeSetting.AUTO, requested = null),
        )
    }

    @Test
    fun `请求的是别的档、报回来的是这一档 —— 照样要说明`() {
        // 用户点的是「仅规划」，CLI 报回来「不询问」：这不是他点的那次，得说
        assertEquals(
            ModeReadback.Announce,
            modeReadbackOf(PermissionModeSetting.DONT_ASK, PermissionModeSetting.AUTO, requested = PermissionModeSetting.PLAN),
        )
    }
}
