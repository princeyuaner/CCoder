package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ClaudeSettingsTest {

    // ---- 会话级的模型选中态（2026-09-21）----
    //
    // 选中态从"全应用一份"改成"每个会话标签各记各的"，落盘的那一份住在这里。
    // 这几条钉的是它**三级回退的顺序**：顺序错了的后果要么是升级上来的用户
    // 打开项目看到"无模型"，要么是手里跑着 A、标签写着 B。

    /** 不碰 PasswordSafe 的密钥库。 */
    private fun profilesWith(vararg ps: ModelProfile): ModelProfiles =
        ModelProfiles(
            object : SecretStore {
                override fun read(id: String): String = ""
                override fun write(id: String, secret: String) = Unit
            }
        ).apply { ps.forEach { upsert(it) } }

    @Test
    fun `本项目没记过时借全应用的最近一次选择`() {
        val p = ModelProfile(name = "中转", modelIds = mutableListOf("flash", "pro"), modelId = "pro")
        val profiles = profilesWith(p)
        profiles.pick(p.id, "pro")

        val chosen = ClaudeSettings().lastModel(profiles)

        assertEquals("pro", chosen?.modelId, "兜底没借到，升级上来的用户会看到「无模型」")
    }

    @Test
    fun `本项目记过就用它，且按记下的模型名重新挑`() {
        // 配置里存的 modelId 是全应用共享的，别的项目可能刚把它改过 ——
        // 本项目记下的是"哪条 + 哪个模型"，两个都得按记下的来
        val p = ModelProfile(name = "中转", modelIds = mutableListOf("flash", "pro"), modelId = "pro")
        val profiles = profilesWith(p)
        val settings = ClaudeSettings().apply { rememberModel(p.id, "flash") }

        val chosen = settings.lastModel(profiles)

        assertEquals(p.id, chosen?.id)
        assertEquals("flash", chosen?.modelId, "用了配置里存的 modelId（别的项目刚改过的那个）")
    }

    @Test
    fun `记下的配置或模型被删了，就当作没选`() {
        val p = ModelProfile(name = "中转", modelIds = mutableListOf("flash"))
        val profiles = profilesWith(p)
        val settings = ClaudeSettings().apply { rememberModel(p.id, "已经删掉的模型") }

        assertNull(settings.lastModel(profiles), "给不出一个不存在的模型，就该是「没选」")
    }

    @Test
    fun `重启 IDE 之后还记得这个项目用哪条`() {
        // loadState 是**逐字段复制**的（见那边注释）：新字段忘了写就会静默丢掉，
        // 症状是"重启 IDE 之后模型又变回去了"
        val p = ModelProfile(id = "p1", name = "中转", modelIds = mutableListOf("flash"))
        val loaded = ClaudeSettings().apply {
            loadState(ClaudeSettings.State(lastProfileId = "p1", lastModelId = "flash"))
        }

        assertEquals("flash", loaded.lastModel(profilesWith(p))?.modelId)
    }

    @Test
    fun `默认值的权限模式是 default`() {
        assertEquals(PermissionModeSetting.DEFAULT, ClaudeSettings().permissionMode)
    }

    @Test
    fun `默认提醒阈值是 30 秒`() {
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
        assertEquals("auto", PermissionModeSetting.AUTO.wireValue)
        assertEquals(6, PermissionModeSetting.entries.size)
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
        // auto 不设：它的闸门在 CLI 侧（disableAutoMode / 订阅档 / 断路器），
        // 与 bypassPermissions 那个"必须用户点头"的资格位是两回事
        assertTrue(!PermissionModeSetting.AUTO.requiresDangerousOptIn)
    }

    @Test
    fun `权限模式有可读的中文名`() {
        // 设置面板和输入区那个下拉都直接显示它（见 toString 那条）。
        // 枚举名 DEFAULT / ACCEPT_EDITS 是给代码看的，不该出现在界面上
        assertEquals("标准", PermissionModeSetting.DEFAULT.label)
        assertEquals("自动接受编辑", PermissionModeSetting.ACCEPT_EDITS.label)
        assertEquals("自动判定", PermissionModeSetting.AUTO.label)
        assertEquals("仅规划", PermissionModeSetting.PLAN.label)
        assertEquals("不询问", PermissionModeSetting.DONT_ASK.label)
        assertEquals("绕过权限", PermissionModeSetting.BYPASS_PERMISSIONS.label)
    }

    @Test
    fun `toString 就是标签 —— 下拉框直接用 toString 渲染`() {
        // ComboBox 拿 toString 当显示文本。不覆盖的话设置里显示的是
        // DEFAULT、ACCEPT_EDITS 这种枚举名
        PermissionModeSetting.entries.forEach {
            assertEquals(it.label, it.toString(), "枚举 $it 的显示名不对")
        }
    }

    @Test
    fun `没勾危险确认时 bypass 不生效，退回默认`() {
        // 改之前那个复选框是个摆设：apply() 从头到尾没读过 isSelected，
        // 勾不勾都照写 bypassPermissions
        assertEquals(
            PermissionModeSetting.DEFAULT,
            effectivePermissionMode(PermissionModeSetting.BYPASS_PERMISSIONS, optedIn = false),
        )
    }

    @Test
    fun `勾了危险确认后 bypass 才真的生效`() {
        assertEquals(
            PermissionModeSetting.BYPASS_PERMISSIONS,
            effectivePermissionMode(PermissionModeSetting.BYPASS_PERMISSIONS, optedIn = true),
        )
    }

    @Test
    fun `安全模式不受那个复选框影响`() {
        PermissionModeSetting.entries
            .filter { !it.requiresDangerousOptIn }
            .forEach {
                assertEquals(it, effectivePermissionMode(it, optedIn = false), "$it 被误降级了")
            }
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
            effort = EffortSetting.XHIGH
            extraDirs = mutableListOf("/a")
            envOverrides = mutableMapOf("K" to "V")
            rememberOpenTabs(listOf(OpenTab(sessionId = "s1", selected = true)))
        }
        val restored = ClaudeSettings().apply { loadState(s.state) }
        assertEquals("/x/claude", restored.claudePath)
        assertEquals(PermissionModeSetting.PLAN, restored.permissionMode)
        assertEquals("m", restored.model)
        assertEquals(EffortSetting.XHIGH, restored.effort)
        assertEquals(listOf("/a"), restored.extraDirs)
        assertEquals(mapOf("K" to "V"), restored.envOverrides)
        // 开关着的标签那一份也在里面：它在 loadState 里是**逐字段复制**的那一串
        // 之一，漏了就是"重启后页签又只剩一个"（见 OpenTabsTest 里那条同名的用例）
        assertEquals(listOf(OpenTab(sessionId = "s1", selected = true)), restored.openTabs())
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

    // ---- 思考深度 ----

    @Test
    fun `思考深度的取值与 SDK 逐字对齐`() {
        // wireValue 必须与 SDK 的 EffortLevel 一致（sdk.d.ts:594），
        // 拼错不会报错，只会被 CLI 静默忽略 —— 用户选了却什么都没发生
        assertEquals(null, EffortSetting.DEFAULT.wireValue)
        assertEquals("low", EffortSetting.LOW.wireValue)
        assertEquals("medium", EffortSetting.MEDIUM.wireValue)
        assertEquals("high", EffortSetting.HIGH.wireValue)
        assertEquals("xhigh", EffortSetting.XHIGH.wireValue)
        assertEquals("max", EffortSetting.MAX.wireValue)
        assertEquals(6, EffortSetting.entries.size)
    }

    @Test
    fun `只有默认档的 wireValue 是 null`() {
        // fromWire 靠"唯一一个 null"把「回到默认」的回执认出来。
        // 哪天给别的档位也写成 null，它会静默地认错档位 —— 标签就撒谎了
        assertEquals(
            listOf(EffortSetting.DEFAULT),
            EffortSetting.entries.filter { it.wireValue == null },
        )
    }

    @Test
    fun `回执里的 null 认成默认档，认不出的档位返回 null`() {
        // null 不是"缺数据"，是合法的「默认」（已从 flag 层清除）
        assertEquals(EffortSetting.DEFAULT, EffortSetting.fromWire(null))
        assertEquals(EffortSetting.XHIGH, EffortSetting.fromWire("xhigh"))

        // 认不出来时返回 null 而不是退回默认 —— 退回的话，一个未来版本的
        // 档位会把标签悄悄拨到「默认」，而用户明明什么都没选
        assertEquals(null, EffortSetting.fromWire("turbo"))
    }

    @Test
    fun `持久化的档位名无法识别时回退到默认`() {
        // 手工编辑配置文件或降级插件都可能留下不认识的值，
        // 不能因此让设置页炸掉
        val s = ClaudeSettings().apply {
            loadState(ClaudeSettings.State(effort = "某个未来版本的档位"))
        }
        assertEquals(EffortSetting.DEFAULT, s.effort)
    }

    @Test
    fun `选了思考深度也不改变启动参数`() {
        // 思考深度**刻意不走启动参数**（`Options.effort` 会被 SDK 翻成 CLI 的
        // `--effort`，与中途切换用的 flag 层是两个优先级来源；两条一起用的话，
        // 用户选「默认」只清得掉 flag 层、清不掉启动时那个）。
        // 这条是钉子：哪天有人"顺手"把它加回启动参数，这里会红，
        // 而注释就在告诉他该去读 session.js 里那段说明
        val start = ClaudeSettings().apply { effort = EffortSetting.MAX }
            .toStartParams(Path.of("/proj"))

        assertFalse(
            start.toString().contains("effort", ignoreCase = true),
            "思考深度不该出现在启动参数里：$start",
        )
    }
}
