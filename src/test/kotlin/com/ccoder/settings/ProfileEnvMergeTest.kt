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

    private fun settingsWith(
        profiles: ModelProfiles,
        selected: ModelProfile,
        secret: String,
        configure: ClaudeSettings.() -> Unit = {},
    ): ClaudeSettings {
        profiles.upsert(selected)
        profiles.setSecret(selected.id, secret)
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

        val p = s.toStartParams(Path.of("/proj"), profiles)

        assertEquals("glm-4.6", p.model, "选中了配置，模型就该由它说了算")
        assertEquals("https://api.example.com", p.envOverrides["ANTHROPIC_BASE_URL"])
        assertEquals("sk-live", p.envOverrides["ANTHROPIC_AUTH_TOKEN"])
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

        val p = s.toStartParams(Path.of("/proj"), profiles)

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

        val p = s.toStartParams(Path.of("/proj"), profiles)

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
            s.toStartParams(Path.of("/proj"), profiles),
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
            s.toStartParams(Path.of("/proj"), profiles)
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

        val p = s.toStartParams(Path.of("/proj"), profiles)

        assertEquals("claude-opus-5", p.model)
        assertNull(p.envOverrides["ANTHROPIC_API_KEY"], "没密钥就是没密钥，CLI 登录态走它自己")
        assertNull(p.envOverrides["ANTHROPIC_BASE_URL"], "空 URL 是官方端点，不该设 base url")
    }
}
