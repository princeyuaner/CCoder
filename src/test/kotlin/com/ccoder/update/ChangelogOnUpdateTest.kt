package com.ccoder.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * "更新之后弹一次更新日志"的判断逻辑。
 *
 * 这一段全是纯函数（描述符解析 / 版本比较 / 该不该弹 / 先记再弹），
 * 所以用例不需要平台、也不需要 UI —— 真正的边界都在"存了什么、现在是什么"上。
 */
class ChangelogOnUpdateTest {

    private fun descriptor(
        id: String = PLUGIN_ID,
        version: String? = "0.2.19",
        notes: String? = "<h3>0.2.19</h3><ul><li>环境页能一键装依赖了</li></ul>",
    ): ByteArrayInputStream {
        val idTag = if (id.isEmpty()) "" else "<id>$id</id>"
        val versionTag = if (version == null) "" else "<version>$version</version>"
        val notesTag = if (notes == null) "" else "<change-notes><![CDATA[$notes]]></change-notes>"
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <idea-plugin>
              $idTag
              <name>CCoder</name>
              $versionTag
              <description><![CDATA[<p>有 <b>HTML</b> 的描述，里面还提到 <id>别人的 id</id></p>]]></description>
              $notesTag
              <depends>com.intellij.modules.platform</depends>
            </idea-plugin>
        """.trimIndent().byteInputStream()
    }

    // ---- 描述符解析 ----

    @Test
    fun `从描述符里取出 id、版本与更新日志`() {
        val c = parsePluginDescriptor(descriptor())!!

        assertEquals("0.2.19", c.version)
        assertTrue(c.notesHtml.contains("一键装依赖"), "更新日志没读出来：${c.notesHtml}")
    }

    @Test
    fun `id 对不上就放弃 —— 别弹别人的更新日志`() {
        // /META-INF/plugin.xml 这个路径不止我们有（平台自带插件、别的第三方插件都可能有），
        // classloader 万一先撞上别人的，就会把别人的更新日志弹出来
        assertNull(parsePluginDescriptor(descriptor(id = "com.example.other")))
    }

    @Test
    fun `只认直接子节点 —— 描述里的 id 不算数`() {
        // 描述是 CDATA 包着的 HTML 片段，里面出现 <id> 这种字样完全可能；
        // 按标签名全局找的话，那个会不会被当成插件 id，全看谁先出现
        val c = parsePluginDescriptor(descriptor())

        assertNotNull(c, "描述里那个 <id> 把解析带偏了")
    }

    @Test
    fun `没有版本号就不弹 —— dev 模式（Run Plugin）正是这种`() {
        // 源文件 src/main/resources/META-INF/plugin.xml 里没有 <version>，
        // 它由构建期注入（pluginConfiguration.version = gradle.properties 的 pluginVersion）
        assertNull(parsePluginDescriptor(descriptor(version = null)))
    }

    @Test
    fun `描述符坏掉时返回 null，不抛`() {
        assertNull(parsePluginDescriptor("<idea-plugin><id>".byteInputStream()))
        assertNull(parsePluginDescriptor("".byteInputStream()))
    }

    @Test
    fun `占位文案不弹 —— 写好之后自动开始弹，不用改代码`() {
        // 0.2.19 的 change-notes 眼下就是 TODO 占位。没有这道闸，这一版装上去
        // 就会弹出一个写着 "TODO: this version's changes…" 的框
        val todo = descriptor(notes = "<h3>0.2.19</h3><ul><li>TODO: written as the delta</li></ul>")
        assertEquals(
            ChangelogDecision.NoContent,
            changelogDecision(stored = "0.2.18", changelog = parsePluginDescriptor(todo)!!),
        )

        val chineseTodo = descriptor(notes = "<li>待写：本版相对 0.2.18 的增量</li>")
        assertEquals(
            ChangelogDecision.NoContent,
            changelogDecision(stored = "0.2.18", changelog = parsePluginDescriptor(chineseTodo)!!),
        )

        val empty = descriptor(notes = "")
        assertEquals(
            ChangelogDecision.NoContent,
            changelogDecision(stored = "0.2.18", changelog = parsePluginDescriptor(empty)!!),
        )
    }

    // ---- 版本比较 ----

    @Test
    fun `版本号按数值比，不是按字符串`() {
        // 字符串比较下 "0.2.10" < "0.2.9" —— 而本仓 0.2.9 / 0.2.10 都真实存在过
        assertTrue(compareVersions("0.2.10", "0.2.9") > 0, "0.2.10 该比 0.2.9 新")
        assertTrue(compareVersions("0.2.19", "0.2.18") > 0)
        assertTrue(compareVersions("0.3.0", "0.2.99") > 0)
        assertTrue(compareVersions("1.0.0", "0.9.9") > 0)
    }

    @Test
    fun `段数不一样时缺的当 0`() {
        assertEquals(0, compareVersions("0.2", "0.2.0"))
        assertEquals(0, compareVersions("0.2.0", "0.2"))
        assertTrue(compareVersions("0.2.1", "0.2") > 0)
    }

    @Test
    fun `相同版本返回 0`() {
        assertEquals(0, compareVersions("0.2.19", "0.2.19"))
        assertEquals(0, compareVersions(" 0.2.19 ", "0.2.19"))
    }

    @Test
    fun `非数字后缀不炸，方向也一致`() {
        // pluginVersion 一直是纯数字的（0.2.19），所以 dev/EAP 这类后缀**不承诺**
        // 谁新谁旧；这里只要求它给一个确定的方向、两个方向还互为相反数
        val forward = compareVersions("0.2.20", "0.2.20-dev")
        val backward = compareVersions("0.2.20-dev", "0.2.20")

        assertEquals(0, forward + backward, "两个方向不一致：$forward / $backward")
    }

    // ---- 该不该弹 ----

    private val changelog = PluginChangelog("0.2.19", "<h3>0.2.19</h3><li>新的东西</li>")

    @Test
    fun `没记过版本也弹 —— 老用户第一次跑这套逻辑`() {
        // 用户拍板："老用户也弹"。代价是新装用户会多看一次，接受 ——
        // 不然这一版的所有老用户都看不到它（他们并没有记过任何版本）
        assertEquals(ChangelogDecision.Show, changelogDecision(stored = null, changelog = changelog))
    }

    @Test
    fun `升级了就弹一次`() {
        assertEquals(ChangelogDecision.Show, changelogDecision(stored = "0.2.18", changelog = changelog))
    }

    @Test
    fun `同一个版本不弹第二次`() {
        assertEquals(
            ChangelogDecision.SameVersion,
            changelogDecision(stored = "0.2.19", changelog = changelog),
        )
    }

    @Test
    fun `回滚到旧版不再弹一次`() {
        // 用户从 0.2.19 装回 0.2.18，再装回来 —— 不该把 0.2.18 的日志弹一遍
        assertEquals(
            ChangelogDecision.Older,
            changelogDecision(stored = "0.2.20", changelog = changelog),
        )
    }

    @Test
    fun `描述符读不到时安静跳过`() {
        assertEquals(ChangelogDecision.NoContent, changelogDecision(stored = "0.2.18", changelog = null))
    }

    // ---- 先记再弹 ----

    private class FakeStore(var value: String? = null) : ChangelogStore {
        val marks = mutableListOf<String>()
        override fun shownVersion(): String? = value
        override fun markShown(version: String) {
            value = version
            marks += version
        }
    }

    @Test
    fun `该弹时弹一次并记下版本`() {
        val store = FakeStore("0.2.18")
        val shown = mutableListOf<String>()

        val decision = maybeShowChangelog(store, load = { changelog }, show = { shown += it.version })

        assertEquals(ChangelogDecision.Show, decision)
        assertEquals(listOf("0.2.19"), shown)
        assertEquals("0.2.19", store.value)
    }

    @Test
    fun `第二次调用不再弹`() {
        val store = FakeStore("0.2.18")
        var count = 0

        maybeShowChangelog(store, load = { changelog }, show = { count++ })
        val second = maybeShowChangelog(store, load = { changelog }, show = { count++ })

        assertEquals(1, count, "弹了不止一次")
        assertEquals(ChangelogDecision.SameVersion, second)
    }

    @Test
    fun `标记在弹之前就写下 —— 弹框开着时又一次上屏不会再弹一个`() {
        // 面板"上屏"有两个触发点（SHOWING_CHANGED 与 addNotify 里让出的那一拍），
        // 谁先到谁弹；**先记再弹**保证另一个到此为止。反过来的话，弹框还开着
        // 就会再弹一个出来
        val store = FakeStore("0.2.18")
        val marksWhenShown = mutableListOf<String?>()

        maybeShowChangelog(
            store,
            load = { changelog },
            show = { marksWhenShown += store.value },
        )

        assertEquals(listOf<String?>("0.2.19"), marksWhenShown, "弹的那一刻标记还没写上")
    }

    @Test
    fun `弹框抛异常也不回滚标记 —— 宁可不弹，也不要每次开窗都弹`() {
        val store = FakeStore("0.2.18")

        runCatching {
            maybeShowChangelog(store, load = { changelog }, show = { error("构造失败") })
        }

        assertEquals("0.2.19", store.value)
    }

    // ---- 与真实描述符对齐 ----

    @Test
    fun `源码里的描述符：id 对得上，版本靠构建期注入`() {
        // 这份文件是"单一真源"：市场页、插件管理器、这个弹窗都读它。
        // 这条钉的是**id 没写错** —— 写错的话真机上是"更新完了什么都不弹"，
        // 而那里没有日志可看（描述符不是我们的代码）
        val file = File("src/main/resources/META-INF/plugin.xml")
        assertTrue(file.isFile, "找不到 ${file.absolutePath}")
        val xml = file.readText()

        assertTrue(xml.contains("<id>$PLUGIN_ID</id>"), "plugin.xml 的 id 与 PLUGIN_ID 对不上了")
        // 版本是构建期由 pluginConfiguration.version 注入的，**源文件里本来就没有**它 ——
        // 所以 dev 模式（Run Plugin）读不到版本、也就不弹，见设计稿 §2 事实 4
        assertFalse(xml.contains("<version>"), "源文件里冒出 <version> 了：注入规则变了？")
    }

    @Test
    fun `打包后的描述符读得出名字与内容`() {
        // 测试跑在平台的沙箱里，classpath 上那份是**打过包的**描述符（版本已注入），
        // 真机上装好的插件走的是同一条路。读不到就跳过 —— 这条是"再确认一遍打包结果"，
        // 主逻辑在上面的合成样本里
        val loaded = loadOwnChangelog()
        assumeTrue(loaded != null, "这个环境里读不到打包后的描述符（只挂了源资源目录？）")

        assertTrue(loaded!!.version.isNotBlank(), "打包后的描述符里没有版本号")
        assertTrue(loaded.notesHtml.isNotBlank(), "打包后的描述符里没有 change-notes")
    }

    @Test
    fun `源码里的 change-notes 还是我们认的那个形状`() {
        // 解析读的是 <change-notes><![CDATA[…]]></change-notes> 这一段。
        // 哪天有人把它改成别的写法（比如挪进 description），这里会红 ——
        // 而不是等到真机上"更新完了什么都不弹"
        val xml = File("src/main/resources/META-INF/plugin.xml").readText()

        assertTrue(
            xml.contains("<change-notes><![CDATA["),
            "plugin.xml 的 change-notes 换写法了，解析要跟着改",
        )
    }
}
