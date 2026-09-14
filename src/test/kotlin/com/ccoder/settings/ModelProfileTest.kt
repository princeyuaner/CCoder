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
    fun `认证方式有可读的标签`() {
        // 设置页那个下拉直接显示它（对话框里靠 renderer 取 label），
        // 枚举名 API_KEY / AUTH_TOKEN 是给代码看的，不该出现在界面上
        // —— 照 ClaudeSettingsTest 里 PermissionModeSetting.label 的先例
        assertEquals("API Key", AuthKind.API_KEY.label)
        assertEquals("Bearer", AuthKind.AUTH_TOKEN.label)
    }

    @Test
    fun `没有密钥的报错用显示名点名模型`() {
        // 名字是想起来才补的，只填 modelId 的配置很常见。
        // 插值 profile.name 的话这条消息会变成「模型「」填了 Base URL…」，
        // 而它恰恰是产品里最需要"说人话"的那条
        val ex = assertThrows(ModelProfileIncomplete::class.java) {
            modelProfileEnv(
                ModelProfile(baseUrl = "https://x", modelId = "glm-4.6"),
                secret = "",
            )
        }

        assertTrue(ex.message!!.contains("glm-4.6"), "实际：${ex.message}")
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

    // ---- 模型列表的规范化 ----

    @Test
    fun `规范化会去掉空行空白与重复`() {
        val p = ModelProfile(
            modelIds = mutableListOf("  a  ", "", "   ", "b", "a"),
            modelId = "b",
        )

        val n = normalizeModelProfile(p)

        assertEquals(listOf("a", "b"), n.modelIds, "顺序要留：第一项是新建时的默认")
    }

    @Test
    fun `当前模型不在列表里时落到第一项`() {
        val n = normalizeModelProfile(
            ModelProfile(modelIds = mutableListOf("a", "b"), modelId = "很久以前那个")
        )

        assertEquals("a", n.modelId)
    }

    /**
     * **空列表不会把 `modelId` 变成唯一的候选。**
     *
     * 反过来做看着更"宽容"，实际会让"删掉最后一个模型"当场把它复活 ——
     * 用户删不掉东西是最难解释的一类 bug。代价是老 XML 的迁移必须在
     * [ModelProfiles.loadState] 里显式做（那条路有专门的用例）。
     */
    @Test
    fun `列表为空时当前模型被清掉，不是被补回列表`() {
        val n = normalizeModelProfile(ModelProfile(modelIds = mutableListOf(), modelId = "唯一那个"))

        assertTrue(n.modelIds.isEmpty(), "空列表不该被补出东西来：${n.modelIds}")
        assertEquals("", n.modelId)
    }

    @Test
    fun `规范化返回的是新列表，不与入参共享`() {
        // data class 的 copy() 会共享同一个 MutableList —— 就地改会让两份 profile 一起变
        val p = ModelProfile(modelIds = mutableListOf("a"), modelId = "a")

        val n = normalizeModelProfile(p)
        n.modelIds.add("b")

        assertEquals(listOf("a"), p.modelIds, "规范化动到了入参的列表")
    }

    // ---- 能不能热切换 ----

    private val relay = ModelProfile(
        name = "中转",
        baseUrl = "https://api.example.com",
        modelIds = mutableListOf("a", "b"),
        modelId = "a",
        authKind = AuthKind.AUTH_TOKEN.name,
    )

    @Test
    fun `同一条配置里换模型可以热切换`() {
        assertTrue(canHotSwitch(relay, "tok", relay.copy(modelId = "b"), "tok"))
    }

    /**
     * 两条配置填了同样的端点与密钥时也该热切换。
     *
     * 这不是顺手放宽：把一个网关拆成几条来管是合理的用法，那种切换没理由丢上下文 ——
     * 而判据（端点与凭证那批环境变量）本来就说明不需要换进程。
     */
    @Test
    fun `同样的端点与密钥之间可以热切换`() {
        val other = ModelProfile(
            name = "同一个网关的另一条",
            baseUrl = "https://api.example.com",
            modelIds = mutableListOf("c"),
            modelId = "c",
            authKind = AuthKind.AUTH_TOKEN.name,
        )

        assertTrue(canHotSwitch(relay, "tok", other, "tok"))
    }

    @Test
    fun `端点不同只能重开`() {
        val other = relay.copy(baseUrl = "https://other.example.com")

        assertFalse(canHotSwitch(relay, "tok", other, "tok"))
    }

    @Test
    fun `密钥不同只能重开`() {
        // 换条配置同端点但是另一把钥匙：热切会继续用旧的密钥，那是一次
        // 静默的错凭证 —— 比丢上下文严重
        assertFalse(canHotSwitch(relay, "tok", relay.copy(modelId = "b"), "另一把"))
    }

    @Test
    fun `认证方式不同只能重开`() {
        // 两个变量语义不同（x-api-key vs Bearer），换了就是另一个环境
        val other = relay.copy(modelId = "b", authKind = AuthKind.API_KEY.name)

        assertFalse(canHotSwitch(relay, "tok", other, "tok"))
    }

    /**
     * 第三方配置没填密钥时 [modelProfileEnv] 会抛。这里必须**捕获后返回 false**，
     * 也就是"证明不了它一样，就走重开"—— 重开那条路会把这条错误原样报出来
     * （`startSession` 的 catch），不会吞掉。
     */
    @Test
    fun `证明不了相同时不抛，返回 false`() {
        val noSecret = ModelProfile(
            name = "没填密钥的网关",
            baseUrl = "https://api.example.com",
            modelIds = mutableListOf("a"),
            modelId = "a",
            authKind = AuthKind.AUTH_TOKEN.name,
        )

        assertFalse(canHotSwitch(relay, "tok", noSecret, ""))
        assertFalse(canHotSwitch(noSecret, "", relay, "tok"))
    }

    /**
     * 目标模型是空串时不能热切：`setModel` 表达不了"不要模型"这个状态。
     * 切到一条没配模型的配置只能重开（那条路的 `toStartParams` 会把它变成
     * "不传 `--model`"，正是想要的语义）。
     */
    @Test
    fun `目标没有模型时不能热切`() {
        val bare = ModelProfile(name = "裸配置", baseUrl = "https://api.example.com", authKind = AuthKind.AUTH_TOKEN.name)

        assertFalse(canHotSwitch(relay, "tok", bare, "tok"))
    }

    /** 没选中任何配置时按空环境算 —— 起会话时确实什么都没给 CLI。 */
    @Test
    fun `从没选配置切到一条什么都不给的配置可以热切`() {
        val officialNoKey = ModelProfile(
            name = "官方",
            modelIds = mutableListOf("claude-sonnet-4-5"),
            modelId = "claude-sonnet-4-5",
        )

        assertTrue(canHotSwitch(null, "", officialNoKey, ""))
        // 但那条配置要给密钥的话，环境就变了
        assertFalse(canHotSwitch(null, "", officialNoKey, "sk-ant-x"))
    }
}
