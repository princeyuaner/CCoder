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

    @Test
    fun `persist 出去的字符串里没有密钥`() {
        // spec §4 的硬要求：密钥不落盘明文。这条对着**产出的数据**断言，
        // 不是对着字段名断言 —— 后者证明不了什么
        val m = fresh()
        val a = ModelProfile(name = "A", baseUrl = "https://a")
        m.upsert(a)
        m.setSecret(a.id, "sk-should-not-appear")

        val serialized = m.serializedForTest()

        assertFalse(
            serialized.contains("sk-should-not-appear"),
            "密钥出现在了持久化数据里：$serialized",
        )
        assertTrue(serialized.contains(a.id), "id 必须留下 —— 密钥靠它找回")
    }

    /**
     * 上一条测的是一段手写的字符串；这一条才是平台真正会写进 XML 的那份数据。
     *
     * 密钥混进去就是明文落盘，而手写串那条**照样是绿的** —— 两条都要有。
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
}
