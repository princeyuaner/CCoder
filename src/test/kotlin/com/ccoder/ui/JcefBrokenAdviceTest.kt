package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 「提前说一句」的判据（2026-09-23 用户问"打开会话时不会提示我吗"）。
 *
 * 这一条的价值几乎全在**什么时候不说**：在好好的 IDE 上白指一条路，用户以后就
 * 不看我们的提示了。所以下面三个"不说"的场合各自一条用例，和"说"那条一样重要。
 *
 * **盖不到的一半**：弹没弹、气泡长什么样、点了按键之后 Registry 到底写进去没有 ——
 * 那些都要真 IDE 才验得了。这里钉的是"该不该弹"这个纯判断。
 */
class JcefBrokenAdviceTest {

    @Test
    fun `版本串认得出主版本 —— 真机上出现过的两种写法都认`() {
        // idea.log 里那种（out-of-process 时带 remote_ 前缀和长 hash）
        assertEquals(
            144,
            jcefMajorVersion("remote_144.0.15.3416_becc5ec31adc7ab1101f74a2920246842c462713"),
        )
        // jcef.version 文件里那种（带 +hash，不带 remote_）
        assertEquals(144, jcefMajorVersion("144.0.15.1191+gbecc5ec"))
        // PyCharm 2026.1 那条：同一种模式，但这版没事
        assertEquals(137, jcefMajorVersion("remote_137.0.17.3190"))
    }

    @Test
    fun `认不出主版本就给 null，不许瞎猜`() {
        assertNull(jcefMajorVersion(null))
        assertNull(jcefMajorVersion(""))
        assertNull(jcefMajorVersion("jcef"))
        // 光有个整数、没有点分形式：不当版本看 —— 免得把串里随便一个数字当主版本
        assertNull(jcefMajorVersion("build 144"))
    }

    @Test
    fun `模式开着、版本正好是坏的那条，才说`() {
        assertTrue(
            shouldWarnAboutOutOfProcessJcef(
                remoteEnabled = true,
                nativeVersion = "remote_144.0.15.3416_becc5ec",
                dismissed = false,
            ),
        )
    }

    @Test
    fun `PyCharm 那种（同模式、137）一个字都不说`() {
        // 这条是整件事的**为什么**：只按"模式开着"弹，会在好好的 IDE 上乱报
        assertFalse(
            shouldWarnAboutOutOfProcessJcef(
                remoteEnabled = true,
                nativeVersion = "remote_137.0.17.3190",
                dismissed = false,
            ),
        )
    }

    @Test
    fun `没跑那个模式就别说`() {
        assertFalse(
            shouldWarnAboutOutOfProcessJcef(
                remoteEnabled = false,
                nativeVersion = "remote_144.0.15.3416_becc5ec",
                dismissed = false,
            ),
        )
    }

    @Test
    fun `版本取不到（JCEF 还没起来、平台换了 API）就不猜`() {
        assertFalse(
            shouldWarnAboutOutOfProcessJcef(
                remoteEnabled = true,
                nativeVersion = null,
                dismissed = false,
            ),
        )
    }

    @Test
    fun `用户说了不再提示，就不提了`() {
        assertFalse(
            shouldWarnAboutOutOfProcessJcef(
                remoteEnabled = true,
                nativeVersion = "remote_144.0.15.3416_becc5ec",
                dismissed = true,
            ),
        )
    }
}
