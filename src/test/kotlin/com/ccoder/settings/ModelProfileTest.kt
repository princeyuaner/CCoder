package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelProfileTest {

    @Test
    fun `官方端点不填密钥不产出任何变量`() {
        // 用户可能已经 claude login 过 —— 官方端点下这是完全合法的配置，
        // 不是"缺了密钥"。什么都不设，让 CLI 走它自己的登录态
        val env = modelProfileEnv(ModelProfile(name = "官方"), secret = "")

        assertTrue(env.isEmpty(), "官方端点 + 无密钥 = 用 CLI 登录态，实际：$env")
    }

    @Test
    fun `官方端点填了密钥产出 API_KEY 且不产出 BASE_URL`() {
        val env = modelProfileEnv(ModelProfile(name = "官方"), secret = "sk-ant-x")

        assertEquals(mapOf("ANTHROPIC_API_KEY" to "sk-ant-x"), env)
        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"), "空 baseUrl 不该产出 BASE_URL")
    }

    @Test
    fun `第三方端点产出 BASE_URL 与按 authKind 选的变量`() {
        val env = modelProfileEnv(
            ModelProfile(
                name = "中转",
                baseUrl = "https://api.example.com",
                authKind = AuthKind.AUTH_TOKEN.name,
            ),
            secret = "tok",
        )

        assertEquals(
            mapOf(
                "ANTHROPIC_BASE_URL" to "https://api.example.com",
                "ANTHROPIC_AUTH_TOKEN" to "tok",
            ),
            env,
        )
    }

    @Test
    fun `API_KEY 与 AUTH_TOKEN 产出不同的变量名`() {
        // 这两个变量语义不同（x-api-key vs Authorization: Bearer），
        // 猜错就是 401，而 401 看起来和"密钥填错了"一模一样
        val base = ModelProfile(name = "中转", baseUrl = "https://api.example.com")

        val asKey = modelProfileEnv(base.copy(authKind = AuthKind.API_KEY.name), "k")
        val asToken = modelProfileEnv(base.copy(authKind = AuthKind.AUTH_TOKEN.name), "k")

        assertTrue(asKey.containsKey("ANTHROPIC_API_KEY"), asKey.toString())
        assertFalse(asKey.containsKey("ANTHROPIC_AUTH_TOKEN"), asKey.toString())
        assertTrue(asToken.containsKey("ANTHROPIC_AUTH_TOKEN"), asToken.toString())
        assertFalse(asToken.containsKey("ANTHROPIC_API_KEY"), asToken.toString())
    }

    @Test
    fun `第三方端点没有密钥时报错并点名是哪个模型`() {
        val ex = assertThrows(ModelProfileIncomplete::class.java) {
            modelProfileEnv(ModelProfile(name = "中转站", baseUrl = "https://x"), secret = "")
        }

        assertTrue(ex.message!!.contains("中转站"), "错误信息要点名模型，实际：${ex.message}")
    }

    @Test
    fun `默认认证方式按 baseUrl 是否为空推断`() {
        assertEquals(AuthKind.API_KEY, defaultAuthKind(""))
        assertEquals(AuthKind.API_KEY, defaultAuthKind("   "))
        assertEquals(AuthKind.AUTH_TOKEN, defaultAuthKind("https://api.example.com"))
    }

    @Test
    fun `两端空白被裁掉`() {
        // authKind 必须显式给：认证方式不按 baseUrl 推断（[defaultAuthKind] 只管
        // 新建时的默认值），不给就是 API_KEY —— 那样测的还是裁剪，只是测错了变量
        val env = modelProfileEnv(
            ModelProfile(
                name = "x",
                baseUrl = "  https://api.example.com  ",
                authKind = AuthKind.AUTH_TOKEN.name,
            ),
            secret = "  tok  ",
        )

        assertEquals("https://api.example.com", env["ANTHROPIC_BASE_URL"])
        assertEquals("tok", env["ANTHROPIC_AUTH_TOKEN"])
    }

    @Test
    fun `authKind 存的名字坏了时不崩，退回 API_KEY`() {
        // XML 是手可改的，坏了不该让整个面板起不来
        val p = ModelProfile(name = "x", baseUrl = "https://y", authKind = "BOGUS")

        assertEquals(AuthKind.API_KEY, p.authKindEnum())
    }

    @Test
    fun `displayName 的两级回退`() {
        assertEquals("中转 Opus", ModelProfile(name = "中转 Opus").displayName())
        assertEquals(
            "deepseek-flash",
            ModelProfile(name = "  ", modelId = "deepseek-flash").displayName(),
            "只填了 modelId 就存了的配置，标签不该是空的",
        )
        assertEquals("未命名", ModelProfile().displayName())
    }
}
