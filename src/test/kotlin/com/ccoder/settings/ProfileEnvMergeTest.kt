package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ProfileEnvMergeTest {

    @Test
    fun `profile 赢过 envOverrides`() {
        // profile 是显式选择（"现在用这个"），envOverrides 是背景设置。
        // 背景不该盖过当次选择
        val merged = mergeProfileEnv(
            base = mapOf("ANTHROPIC_BASE_URL" to "https://old", "OTHER" to "kept"),
            profileEnv = mapOf("ANTHROPIC_BASE_URL" to "https://new"),
        )

        assertEquals("https://new", merged["ANTHROPIC_BASE_URL"])
        assertEquals("kept", merged["OTHER"], "无关的键必须原样留着")
    }

    @Test
    fun `空 profileEnv 时原样返回`() {
        val base = mapOf("A" to "1")

        assertEquals(base, mergeProfileEnv(base, emptyMap()))
    }

    @Test
    fun `合并不产生重复键`() {
        val merged = mergeProfileEnv(
            base = mapOf("ANTHROPIC_API_KEY" to "old"),
            profileEnv = mapOf("ANTHROPIC_API_KEY" to "new"),
        )

        assertEquals(1, merged.size)
        assertEquals("new", merged["ANTHROPIC_API_KEY"])
    }

    @Test
    fun `只报出会和模型配置抢的那几个键`() {
        // profile 会赢，所以冲突不是错误 —— 但用户得知道，否则他会对着一个
        // "改了却不生效"的 envOverrides 发懵（spec §6 要求把提示做进模型页）
        val keys = conflictingEnvKeys(
            mapOf(
                "MY_VAR" to "1",
                "ANTHROPIC_AUTH_TOKEN" to "sk-x",
                "ANTHROPIC_BASE_URL" to "https://x",
            )
        )

        assertEquals(listOf("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL"), keys)
    }

    @Test
    fun `没有冲突时不报任何键`() {
        assertEquals(emptyList<String>(), conflictingEnvKeys(mapOf("MY_VAR" to "1")))
    }

    @Test
    fun `路由变量也算会和模型配置抢的键`() {
        // 选中第三方配置时别名会被指到配置的 modelId，手填的值同样被盖掉 ——
        // 不报出来的话，用户会对着一个"改了却不生效"的 SMALL_FAST 发懵
        val keys = conflictingEnvKeys(
            mapOf(
                "MY_VAR" to "1",
                "ANTHROPIC_SMALL_FAST_MODEL" to "cheap-model",
                "CLAUDE_CODE_SUBAGENT_MODEL" to "cheap-model",
            )
        )

        assertEquals(
            listOf("ANTHROPIC_SMALL_FAST_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL"),
            keys,
        )
    }
}

/** 内存版密钥库。**不能**碰 PasswordSafe —— 纯单测环境里它没有实现。 */
private class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, String>()
    override fun read(id: String): String = map[id] ?: ""
    override fun write(id: String, secret: String) {
        if (secret.isEmpty()) map.remove(id) else map[id] = secret
    }
}

/**
 * 接线那一段的用例。
 *
 * 这里刻意**不重复测纯函数**（上面那几条）—— 这些断言对着 `StartParams` 这个
 * 真正的产出物，跑通的是"选中 → 环境变量 → 消息参数"整条路。
 */
class ProfileEnvWiringTest {

    /**
     * 夹具里写的都是"一条配置一个模型"的老形状（`modelId = "glm-4.6"`），而现在的
     * 不变量是"`modelId` 必须落在 `modelIds` 里"。这**不是**为了绕开 upsert 的
     * 规范化 —— 那一步只认 `modelIds`，空列表时会把 `modelId` 清掉（见
     * [normalizeModelProfile] 的说明）。对话框写库时保证两者一致，夹具也该一致。
     * 老 XML 走的是 [ModelProfiles.loadState] 里那次显式迁移，这里补的是同一件事。
     */
    private fun ModelProfile.withModelList(): ModelProfile =
        if (modelIds.isEmpty() && modelId.isNotBlank()) {
            copy(modelIds = mutableListOf(modelId))
        } else {
            this
        }

    private fun settingsWith(
        profiles: ModelProfiles,
        selected: ModelProfile,
        secret: String,
        configure: ClaudeSettings.() -> Unit = {},
    ): ClaudeSettings {
        profiles.upsert(selected.withModelList())
        profiles.setSecret(selected.id, secret)
        // 选中态 2026-09-21 起按会话标签分，起会话时由调用方把"这个会话用哪个"传给
        // toStartParams。这些用例测的是**由选中的配置推导 env**，所以下面各条用
        // `profiles.recent()` 顶上 —— 它就是改版前 toStartParams 自己会去读的那个值
        profiles.select(selected.id)
        return ClaudeSettings().apply(configure)
    }

    @Test
    fun `选中配置后端点密钥与模型都来自它`() {
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(
                name = "中转",
                baseUrl = "https://api.example.com",
                modelId = "glm-4.6",
                authKind = AuthKind.AUTH_TOKEN.name,
            ),
            secret = "sk-live",
        ) { model = "老字段" }

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        assertEquals("glm-4.6", p.model, "选中了配置，模型就该由它说了算")
        assertEquals("https://api.example.com", p.envOverrides["ANTHROPIC_BASE_URL"])
        assertEquals("sk-live", p.envOverrides["ANTHROPIC_AUTH_TOKEN"])
    }

    @Test
    fun `配置给了端点与密钥时，这条会话归它管`() {
        // 少了这个开关：CLI 拿 ~/.claude/settings.json 的 env 把端点盖掉，
        // 而配置的密钥照样发得出去 → 401 Invalid token（spec §6.1）
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "中转", baseUrl = "https://api.example.com", modelId = "glm-4.6"),
            secret = "sk-live",
        )

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        assertEquals("1", p.envOverrides[HOST_MANAGED_PROVIDER_VAR])
    }

    @Test
    fun `第三方配置把别名与后台任务的模型名给全`() {
        // 不补这些名字：CLI 落回它自带的 Claude 模型名，第三方网关上没有那些名字。
        // 症状很有欺骗性 —— 主对话一切正常，只有起标题 / 压缩上下文 / 子代理报模型不存在
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "中转", baseUrl = "https://api.example.com", modelId = "glm-4.6"),
            secret = "sk-live",
        )

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        for (key in ROUTING_MODEL_ENV_VARS) {
            assertEquals("glm-4.6", p.envOverrides[key], "$key 该指到配置的模型")
        }
        assertEquals(6, ROUTING_MODEL_ENV_VARS.size, "给全 —— 少一个就有一类后台活报错")
    }

    @Test
    fun `官方端点不给别名模型名`() {
        // 官方端点上那些 Claude 名字本来就有效；把 haiku 指到 opus
        // 只会让后台小活儿变贵
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "官方", baseUrl = "", modelId = "claude-opus-5"),
            secret = "sk-ant-live",
        )

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        for (key in ROUTING_MODEL_ENV_VARS) {
            assertNull(p.envOverrides[key], "$key 不该被设")
        }
    }

    @Test
    fun `第三方配置没填模型 ID 时没名字可给`() {
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "中转", baseUrl = "https://api.example.com", modelId = ""),
            secret = "sk-live",
        )

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        for (key in ROUTING_MODEL_ENV_VARS) {
            assertNull(p.envOverrides[key], "没填 modelId 就没得给，不该瞎指")
        }
    }

    @Test
    fun `选中配置时默认打开任务列表工具`() {
        // 那套工具 CLI 只对它认识的模型开放，第三方网关上的名字它不认识。
        // 不给这个变量，「任务列表」卡永远是空的 —— 不是模型不用，是它调不到
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "中转", baseUrl = "https://api.example.com", modelId = "glm-4.6"),
            secret = "sk-live",
        )

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        assertEquals("true", p.envOverrides[TASK_TOOLS_VAR])
    }

    @Test
    fun `手填过任务工具开关就听手填的`() {
        // 这一项是**默认值**语义，不是路由：想关就关。
        // 与端点/凭证那批刻意不同 —— 那些混搭会 401，必须由配置说了算
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "中转", baseUrl = "https://api.example.com", modelId = "m"),
            secret = "sk-live",
        ) { envOverrides = mutableMapOf(TASK_TOOLS_VAR to "false") }

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        assertEquals("false", p.envOverrides[TASK_TOOLS_VAR], "手填的没被默认值盖掉")
    }

    @Test
    fun `配置什么都没给时不碰任务工具开关`() {
        // 官方端点 + 空密钥：CLI 自己会给（模型名它认识），插件不必插话
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "官方", baseUrl = "", modelId = "claude-opus-5"),
            secret = "",
        )

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        assertNull(p.envOverrides[TASK_TOOLS_VAR])
    }

    @Test
    fun `官方端点 + 填了密钥也算配置给了东西`() {
        // 用户明确填了密钥，就是说了"用这个"。不设开关的话，settings.json 里的
        // 网关会把端点抢走，而这个密钥会被发到那个网关去
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "官方", baseUrl = "", modelId = "claude-opus-5"),
            secret = "sk-ant-live",
        )

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        assertEquals("sk-ant-live", p.envOverrides["ANTHROPIC_API_KEY"])
        assertEquals("1", p.envOverrides[HOST_MANAGED_PROVIDER_VAR])
    }

    @Test
    fun `配置什么都没给时不碰这个开关`() {
        // 官方端点 + 没填密钥 = 用 CLI 登录态 / 跟着 settings.json 走。
        // 这里打上开关会把 settings.json 的凭证也剥掉 → authentication_failed
        // （spec §11.1 那次实测），等于把没配模型的人全打挂
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "官方", baseUrl = "", modelId = "claude-opus-5"),
            secret = "",
        ) { envOverrides = mutableMapOf("ANTHROPIC_AUTH_TOKEN" to "sk-user") }

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        assertNull(
            p.envOverrides[HOST_MANAGED_PROVIDER_VAR],
            "配置什么都没给，就不该去动 settings.json 里的凭证",
        )
        assertEquals("sk-user", p.envOverrides["ANTHROPIC_AUTH_TOKEN"], "用户手填的照样留着")
    }

    @Test
    fun `profile 的环境变量盖过 envOverrides，无关的键留着`() {
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "中转", baseUrl = "https://new.example.com", modelId = "m"),
            secret = "sk-live",
        ) {
            envOverrides = mutableMapOf("ANTHROPIC_BASE_URL" to "https://old", "MY_VAR" to "1")
        }

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        assertEquals("https://new.example.com", p.envOverrides["ANTHROPIC_BASE_URL"])
        assertEquals("1", p.envOverrides["MY_VAR"])
    }

    @Test
    fun `选中了配置但模型 ID 为空时不回退到老字段`() {
        // spec §6 的优先级表：`ClaudeSettings.model` **只在没选中任何 profile 时**生效。
        // 回退的话，用户选中一条没填模型 ID 的第三方配置之后，请求会带上一个当初给
        // 官方端点写的模型名发给网关 —— 而网关多半只回一句看不懂的报错
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "中转", baseUrl = "https://api.example.com", modelId = ""),
            secret = "sk-live",
        ) { model = "claude-opus-5" }

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        assertNull(p.model, "选中了配置就该由它说了算，老字段不该被读到，实际：${p.model}")
        assertEquals("https://api.example.com", p.envOverrides["ANTHROPIC_BASE_URL"], "其它字段照常来自配置")
    }

    @Test
    fun `一条配置都没有时产出与从前一字不差`() {
        // 这不是迁移：没配任何模型的人升级后看到的必须还是原来那份参数
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = ClaudeSettings().apply {
            model = "claude-opus-5"
            envOverrides = mutableMapOf("K" to "V")
        }

        assertEquals(
            s.toStartParams(Path.of("/proj")),
            s.toStartParams(Path.of("/proj"), profiles, profiles.recent()),
        )
    }

    @Test
    fun `配置不全时抛错，而不是发一个空令牌出去`() {
        // 症状必须是一句说人话的报错，而不是一次没有解释的 401
        // （ClaudePanel.startSession 的 catch 会接住它并走 fail）
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "中转", baseUrl = "https://api.example.com", modelId = "m"),
            secret = "",
        )

        assertThrows(ModelProfileIncomplete::class.java) {
            s.toStartParams(Path.of("/proj"), profiles, profiles.recent())
        }
    }

    @Test
    fun `官方端点不填密钥是合法的，什么都不设`() {
        val profiles = ModelProfiles(InMemorySecretStore())
        val s = settingsWith(
            profiles,
            ModelProfile(name = "官方", baseUrl = "", modelId = "claude-opus-5"),
            secret = "",
        )

        val p = s.toStartParams(Path.of("/proj"), profiles, profiles.recent())

        assertEquals("claude-opus-5", p.model)
        assertNull(p.envOverrides["ANTHROPIC_API_KEY"], "没密钥就是没密钥，CLI 登录态走它自己")
        assertNull(p.envOverrides["ANTHROPIC_BASE_URL"], "空 URL 是官方端点，不该设 base url")
    }
}
