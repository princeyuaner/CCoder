package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ClaudeSettingsTest {

    @Test
    fun `默认值的权限模式是 default`() {
        assertEquals(PermissionModeSetting.DEFAULT, ClaudeSettings().permissionMode)
    }

    @Test
    fun `默认提醒阈值是 30 秒`() {
        assertEquals(30, ClaudeSettings().pendingReminderSeconds)
    }

    @Test
    fun `权限模式与 CLI 取值逐字对齐`() {
        // 必须与 SDK 的 PermissionMode 联合类型一致（sdk.d.ts:2327），
        // 拼错不会报错，只会在运行时被 CLI 静默忽略
        assertEquals("default", PermissionModeSetting.DEFAULT.wireValue)
        assertEquals("acceptEdits", PermissionModeSetting.ACCEPT_EDITS.wireValue)
        assertEquals("plan", PermissionModeSetting.PLAN.wireValue)
        assertEquals("dontAsk", PermissionModeSetting.DONT_ASK.wireValue)
        assertEquals("bypassPermissions", PermissionModeSetting.BYPASS_PERMISSIONS.wireValue)
        assertEquals(5, PermissionModeSetting.entries.size)
    }

    @Test
    fun `bypassPermissions 需要显式确认`() {
        // SDK 要求同时设 allowDangerouslySkipPermissions（sdk.d.ts:1852-1856）
        assertTrue(PermissionModeSetting.BYPASS_PERMISSIONS.requiresDangerousOptIn)
    }

    @Test
    fun `其余权限模式不需要显式确认`() {
        assertTrue(!PermissionModeSetting.DEFAULT.requiresDangerousOptIn)
        assertTrue(!PermissionModeSetting.ACCEPT_EDITS.requiresDangerousOptIn)
        assertTrue(!PermissionModeSetting.PLAN.requiresDangerousOptIn)
        assertTrue(!PermissionModeSetting.DONT_ASK.requiresDangerousOptIn)
    }

    @Test
    fun `toStartParams 把空字符串映射为 null`() {
        // 空路径传给 sidecar 会被解读为"显式指定了空路径"，
        // 触发 CLAUDE_NOT_FOUND 而非回退到 PATH 解析
        val s = ClaudeSettings().apply {
            claudePath = ""
            model = ""
        }
        val p = s.toStartParams(Path.of("/proj"))
        assertEquals("default", p.permissionMode)
        assertNull(p.claudePath)
        assertNull(p.model)
    }

    @Test
    fun `toStartParams 把纯空白也视为未指定`() {
        val s = ClaudeSettings().apply {
            claudePath = "   "
            model = "\t"
        }
        val p = s.toStartParams(Path.of("/proj"))
        assertNull(p.claudePath)
        assertNull(p.model)
    }

    @Test
    fun `toStartParams 传递完整配置`() {
        val s = ClaudeSettings().apply {
            claudePath = "C:\\bin\\claude.exe"
            model = "claude-opus-5"
            permissionMode = PermissionModeSetting.ACCEPT_EDITS
            extraDirs = mutableListOf("C:\\other")
            envOverrides = mutableMapOf("MY_VAR" to "1")
        }
        val p = s.toStartParams(Path.of("/proj"))
        assertEquals("C:\\bin\\claude.exe", p.claudePath)
        assertEquals("claude-opus-5", p.model)
        assertEquals("acceptEdits", p.permissionMode)
        assertEquals(listOf("C:\\other"), p.extraDirs)
        assertEquals(mapOf("MY_VAR" to "1"), p.envOverrides)
    }

    @Test
    fun `toStartParams 过滤空白目录与空值环境变量`() {
        val s = ClaudeSettings().apply {
            extraDirs = mutableListOf("/a", "", "  ", "/b")
            envOverrides = mutableMapOf("K" to "V", "EMPTY" to "")
        }
        val p = s.toStartParams(Path.of("/proj"))
        assertEquals(listOf("/a", "/b"), p.extraDirs)
        assertEquals(mapOf("K" to "V"), p.envOverrides)
    }

    @Test
    fun `cwd 使用绝对路径`() {
        val p = ClaudeSettings().toStartParams(Path.of("relative/dir"))
        assertTrue(Path.of(p.cwd).isAbsolute, "实际：${p.cwd}")
    }

    @Test
    fun `状态往返不丢失`() {
        val s = ClaudeSettings().apply {
            claudePath = "/x/claude"
            permissionMode = PermissionModeSetting.PLAN
            model = "m"
            pendingReminderSeconds = 45
            extraDirs = mutableListOf("/a")
            envOverrides = mutableMapOf("K" to "V")
        }
        val restored = ClaudeSettings().apply { loadState(s.state) }
        assertEquals("/x/claude", restored.claudePath)
        assertEquals(PermissionModeSetting.PLAN, restored.permissionMode)
        assertEquals("m", restored.model)
        assertEquals(45, restored.pendingReminderSeconds)
        assertEquals(listOf("/a"), restored.extraDirs)
        assertEquals(mapOf("K" to "V"), restored.envOverrides)
    }

    @Test
    fun `持久化的模式名无法识别时回退到 default`() {
        // 手工编辑配置文件或降级插件都可能留下不认识的值，
        // 不能因此让设置页炸掉
        val s = ClaudeSettings().apply {
            loadState(ClaudeSettings.State(permissionMode = "某个未来版本的模式"))
        }
        assertEquals(PermissionModeSetting.DEFAULT, s.permissionMode)
    }
}
