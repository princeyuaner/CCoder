package com.ccoder.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 内存版密钥库，让单测不必碰 PasswordSafe。 */
private class FakeSecretStore : SecretStore {
    val map = mutableMapOf<String, String>()
    override fun read(id: String): String = map[id] ?: ""
    override fun write(id: String, secret: String) {
        if (secret.isEmpty()) map.remove(id) else map[id] = secret
    }
}

class ModelProfilesTest {

    private fun fresh(store: SecretStore = FakeSecretStore()): ModelProfiles =
        ModelProfiles(store)

    /**
     * 按平台写盘的路子序列化一份 state。
     *
     * 必须带 [SkipDefaultsSerializationFilter]：文件存储的默认过滤器就是它的子类
     * （只多一个 `BaseState` 钩子，与我们这个 State 无关），而"跳过等于默认值的字段"
     * 恰恰是最可能把 id 吞掉的那一步 —— 用不过滤的写法测，等于绕开唯一有风险的地方。
     *
     * 实测：`modelId`、`authKind` 这些常量默认值确实被跳过了，`id` 因为默认值是
     * 随机 UUID 而留下 —— 这正是它跟别的字段不一样的地方。
     */
    private fun serializeState(m: ModelProfiles): Element {
        val element = Element("component")
        XmlSerializer.serializeInto(m.getState(), element, SkipDefaultsSerializationFilter())
        return element
    }

    @Test
    fun `增删改查走一遍`() {
        val m = fresh()
        val a = ModelProfile(name = "A", baseUrl = "https://a", modelId = "m-a")

        m.upsert(a)
        assertEquals(1, m.profiles().size)
        assertEquals("A", m.profiles().first().name)

        m.upsert(a.copy(name = "A2"))
        assertEquals(1, m.profiles().size, "同 id 是修改不是新增")
        assertEquals("A2", m.profiles().first().name)

        m.remove(a.id)
        assertTrue(m.profiles().isEmpty())
    }

    @Test
    fun `删掉当前选中的那条时选中态跟着清空`() {
        val m = fresh()
        val a = ModelProfile(name = "A")
        m.upsert(a)
        m.select(a.id)
        assertEquals(a.id, m.selected()?.id)

        m.remove(a.id)

        assertNull(m.selected(), "选中一条已经不存在的配置会让启动路径读到一个空引用")
        assertNull(m.selectedId())
    }

    @Test
    fun `删掉一条时密钥一并清掉`() {
        // 留着密钥不只是垃圾 —— 以后新增一条会拿到新的 UUID，
        // 旧密钥永远没人再读，也永远不会被发现
        val store = FakeSecretStore()
        val m = fresh(store)
        val a = ModelProfile(name = "A")
        m.upsert(a)
        m.setSecret(a.id, "top-secret")
        assertEquals("top-secret", m.secretOf(a.id))

        m.remove(a.id)

        assertEquals("", store.read(a.id), "密钥要跟着配置一起删")
    }

    @Test
    fun `选一个不存在的 id 等于没选`() {
        val m = fresh()
        m.select("nope")
        assertNull(m.selected())
    }

    /**
     * spec §4 的硬要求：密钥不落盘明文。这条对着**产出的数据**断言，
     * 不是对着字段名断言 —— 后者证明不了什么。
     */
    @Test
    fun `XmlSerializer 写出的 XML 里也没有密钥`() {
        val m = fresh()
        val a = ModelProfile(name = "A", baseUrl = "https://a")
        m.upsert(a)
        m.setSecret(a.id, "sk-should-not-appear")

        val xml = JDOMUtil.writeElement(serializeState(m))

        assertFalse(xml.contains("sk-should-not-appear"), "密钥进了 XML：$xml")
        assertTrue(xml.contains(a.id), "id 必须留下 —— 密钥靠它找回：$xml")
    }

    @Test
    fun `id 经 XmlSerializer 往返后不变`() {
        // 密钥按 id 存。id 要是被重新生成，重启后配置还在、密钥全没了 —— 而且悄无声息
        val store = FakeSecretStore()
        val m = ModelProfiles(store)
        val p = ModelProfile(name = "中转", baseUrl = "https://api.example.com")
        m.upsert(p)
        m.setSecret(p.id, "sk-keep-me")

        val element = serializeState(m)
        val loaded = XmlSerializer.deserialize(element, ModelProfiles.State::class.java)

        assertEquals(
            p.id,
            loaded.profiles.first().id,
            "id 变了，PasswordSafe 里的密钥就再也找不回来了",
        )
    }

    @Test
    fun `空白密钥不写入`() {
        val store = FakeSecretStore()
        val m = fresh(store)
        val a = ModelProfile(name = "A")
        m.upsert(a)

        m.setSecret(a.id, "   ")

        assertEquals("", store.read(a.id), "空白密钥该当成清除，不是一个空口令")
    }

    // ---- 一族模型（modelIds）----

    /**
     * 老 XML 里一条配置只有一个模型，存在 `modelId` 里、没有 `modelIds`。
     *
     * 这条是升级路径的证据：`normalizeModelProfile` **不会**把空的列表当成
     * "那就用 modelId 吧"（那样"删掉最后一个模型"会当场复活），所以补列表
     * 这一步必须由 [ModelProfiles.loadState] 显式做。漏了它，用户升级完会
     * 发现模型名没了。
     */
    @Test
    fun `读老形状的配置时把 modelId 补成单项列表`() {
        val m = fresh()
        val old = ModelProfile(name = "中转", baseUrl = "https://a", modelId = "glm-4.6")

        m.loadState(ModelProfiles.State(profiles = mutableListOf(old), selectedId = old.id))

        val loaded = m.profiles().single()
        assertEquals(listOf("glm-4.6"), loaded.modelIds)
        assertEquals("glm-4.6", loaded.modelId, "升级不该把用户填的模型名丢掉")
        assertEquals(old.id, m.selectedId())
    }

    /** 写入时同样收敛，免得绕开 loadState 的那几条路（对话框、热切换回执）留下坏状态。 */
    @Test
    fun `写入时把不在列表里的当前模型落到第一项`() {
        val m = fresh()

        m.upsert(ModelProfile(name = "中转", modelIds = mutableListOf("a", "b"), modelId = "c"))

        assertEquals("a", m.profiles().single().modelId)
    }

    /**
     * 多个模型要经得起平台序列化。
     *
     * `ClaudeSettings.State.extraDirs` 是先例（顶层 `MutableList<String>`），
     * 但**嵌在列表元素里的** `MutableList<String>` 没有先例 —— 这条就是那个证据。
     * 顺序也得留住：第一项是新建时的默认。
     */
    @Test
    fun `多个模型经 XmlSerializer 往返后逐字不变`() {
        val m = fresh()
        val p = ModelProfile(
            name = "中转",
            baseUrl = "https://api.example.com",
            modelIds = mutableListOf("deepseek-v4-flash[1m]", "deepseek-v4-pro[1m]", "glm-4.6"),
            modelId = "deepseek-v4-pro[1m]",
        )
        m.upsert(p)

        val loaded = XmlSerializer
            .deserialize(serializeState(m), ModelProfiles.State::class.java)
            .profiles
            .single()

        assertEquals(p.modelIds, loaded.modelIds, "列表没往返回来")
        assertEquals("deepseek-v4-pro[1m]", loaded.modelId)
    }

    // ---- 换当前模型 ----

    @Test
    fun `pick 同时改选中态与当前模型`() {
        val m = fresh()
        val a = ModelProfile(name = "A", modelIds = mutableListOf("x", "y"), modelId = "x")
        val b = ModelProfile(name = "B", modelIds = mutableListOf("z"), modelId = "z")
        m.upsert(a)
        m.upsert(b)
        m.select(a.id)

        m.pick(a.id, "y")

        assertEquals("y", m.profiles().first { it.id == a.id }.modelId)
        assertEquals(a.id, m.selectedId(), "选中态也得跟着 —— 两件事分开写会留下半截状态")
    }

    @Test
    fun `pick 认不出的模型或配置什么都不改`() {
        val m = fresh()
        val a = ModelProfile(name = "A", modelIds = mutableListOf("x"), modelId = "x")
        m.upsert(a)
        m.select(a.id)

        m.pick(a.id, "不存在")
        m.pick("不存在的配置", "x")

        assertEquals("x", m.profiles().single().modelId)
        assertEquals(a.id, m.selectedId())
    }
}
