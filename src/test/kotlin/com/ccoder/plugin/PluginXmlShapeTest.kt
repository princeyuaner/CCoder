package com.ccoder.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `plugin.xml` 的**结构**：顶层允许放什么、服务必须写在哪儿。
 *
 * ## 为什么值得单开一条（2026-09-20 事故）
 *
 * 我把界面语言那个服务的注册写在了**顶层**：
 *
 * ```xml
 * <applicationService serviceImplementation="…" preload="true"/>
 * ```
 *
 * 平台（261 实测）把它当成 `Unknown element: applicationService` **静默丢掉** ——
 * 服务从未注册，`getService()` 回 null，于是建工具窗口时 NPE，
 * **整个 CCoder 窗口打不开**。用户装的就是这一版。
 *
 * 而 1510 条单测全绿、三侧测试全绿、英文探针出图也正常：**描述符的错只有真机启动才看得见**。
 * 能在这里拦住它的理由很实在 —— "顶层能写哪些元素"是有名单的，名单外的会被丢掉，
 * 所以拿真机验证过的描述符统计一份名单，就能把这类错钉死在单测里。
 *
 * ## 名单从哪来（别凭印象改）
 *
 * 扫 IDE 自带的 21 个能正常加载的 `META-INF/plugin.xml`（PyCharm 2026.1 / PY-261）得到：
 * `extensions`(360) · `actions`(37) · `vendor`(22) · `id`/`version`/`idea-version`/`name`/
 * `description`/`resource-bundle`(各 21) · `applicationListeners`(18) · `category`(16) ·
 * `projectListeners`(14) · `depends`(7) · `incompatible-with`(6) · `change-notes`(4)。
 * **`applicationService` / `projectService` 在这 21 个里一次都没出现在顶层** ——
 * 它们只会出现在 `<extensions>` 里。
 */
class PluginXmlShapeTest {

    private val pluginXml = File("src/main/resources/META-INF/plugin.xml")

    /**
     * 顶层允许出现的元素。
     *
     * 名单外的元素不会报错、只会**被丢掉** —— 所以这里宁可宽一点（收录见过的写法），
     * 但服务那两个（`applicationService` / `projectService`）**刻意不收录**：
     * 它们必须是 `<extensions>` 的子元素，写顶层是这次那个 bug。
     */
    private val allowedTopLevel = setOf(
        "id", "name", "vendor", "description", "change-notes", "version", "idea-version",
        "depends", "dependencies", "incompatible-with", "extensions", "extensionPoints",
        "actions", "applicationListeners", "projectListeners", "resource-bundle", "category",
    )

    private fun parse(): Document {
        assertTrue(pluginXml.isFile, "找不到 ${pluginXml.absolutePath}")
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(pluginXml)
    }

    private fun childElements(parent: Element): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    @Test
    fun `顶层元素必须都是平台认的 —— 名单外的会被静默丢掉`() {
        val names = childElements(parse().documentElement).map { it.tagName }

        assertEquals(
            emptyList<String>(),
            names.filterNot { it in allowedTopLevel },
            "这些顶层元素平台不认（会被丢掉）：${names}",
        )
    }

    @Test
    fun `服务 EP 必须在 extensions 里 —— 顶层会被静默丢掉`() {
        val doc = parse()
        val services = setOf("applicationService", "projectService")

        val atTopLevel = childElements(doc.documentElement).map { it.tagName }.filter { it in services }
        assertEquals(
            emptyList<String>(),
            atTopLevel,
            "服务 EP 写到顶层会被平台丢掉（2026-09-20 那次就是这么把工具窗口弄没的）",
        )

        // 反向那半同样要紧：把注册**删掉**也能让上面那条变绿，而语言服务就废了。
        // 所以再钉一条"它确实注册着，且在 extensions 里"。
        val registered = doc.getElementsByTagName("applicationService").let { list ->
            (0 until list.length).map { list.item(it) as Element }
        }
        assertTrue(registered.isNotEmpty(), "界面语言服务没注册 —— 设置里那个下拉会写进虚空")

        val insideExtensions = registered.filter { el ->
            var p = el.parentNode
            var found = false
            while (p is Element) {
                if (p.tagName == "extensions") { found = true; break }
                p = p.parentNode
            }
            found
        }
        assertEquals(registered.size, insideExtensions.size, "有服务 EP 不在 <extensions> 里")
    }
}
