package com.ccoder.settings

import com.ccoder.sidecar.SkillCommand
import com.ccoder.sidecar.SkillUsageLine
import com.ccoder.sidecar.SkillsOnceReply
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * 技能页（方案一：分组表格 + 行上开关）。
 *
 * 服务用**注入了假枚举的实例**：`post` / `runAsync` 都换成"当场执行"，
 * 于是整页的行为在用例里是同步的 —— 与 `RuntimeDepsServiceTest` 同一个做法。
 * 真起 CLI 的那条路（3～5 秒一个进程）不能进单测，它由 `probe-skills.mjs` 管。
 */
class SkillsSettingsPageTest {

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun clickOn(target: Component) {
        val e = MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)
        target.mouseListeners.forEach { it.mouseClicked(e) }
    }

    private fun textsOf(root: Container): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Container) {
            for (child in c.components) {
                when (child) {
                    is JLabel -> out += child.text
                    is JTextComponent -> out += child.text
                    else -> Unit
                }
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out.filter { it.isNotBlank() }
    }

    private fun switchesIn(root: Container): List<SkillSwitch> {
        val out = mutableListOf<SkillSwitch>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is SkillSwitch) out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    /** 记下写盘调用的假写手。 */
    private class Edits {
        val calls = mutableListOf<Triple<String, String, SkillState>>()
    }

    private fun catalog(
        base: Path?,
        reply: SkillsOnceReply,
        edits: Edits,
    ): SkillsCatalog = SkillsCatalog(
        baseDir = { base },
        home = { Path.of(System.getProperty("java.io.tmpdir"), "ccoder-skills-home-none") },
        enumerate = { reply },
        edit = { _, key, state ->
            edits.calls += Triple("", key, state)
            ProcessOutcome(0, """{"ok":true,"written":{"name":"x","state":"y"},"changed":true}""", "")
        },
        pluginCommand = { _, _, _ -> ProcessOutcome(0, "ok", "") },
        post = { it() },
        runAsync = { it() },
    )

    private fun replyOf(vararg commands: SkillCommand) = SkillsOnceReply.Ok(
        commands = commands.toList(),
        usage = commands.map { SkillUsageLine(it.name, sourceOf(it.name), 30) },
    )

    private fun command(name: String, description: String = "做点什么") = SkillCommand(name, description, false)

    private class Fixture(val root: Component, val page: SkillsSettingsPage, val edits: Edits)

    // 真机上"来源"是 CLI 给的一个字段；用例里靠名字造出四种来源（见 SkillSource.wire）
    private fun sourceOf(name: String): String = when {
        name.startsWith("plugin:") -> "plugin"
        name.startsWith("builtin:") -> "built-in"
        name.startsWith("proj:") -> "projectSettings"
        else -> "userSettings"
    }

    @Test
    fun `按来源分组，插件那组标着「由插件决定」`() {
        lateinit var fx: Fixture
        onEdt {
            val edits = Edits()
            val page = SkillsSettingsPage(
                project = null,
                catalog = catalog(
                    null,
                    SkillsOnceReply.Ok(
                        commands = listOf(
                            SkillCommand("caveman", "省话", false),
                            SkillCommand("plugin:code-review", "审代码", false),
                        ),
                        usage = listOf(
                            SkillUsageLine("caveman", "userSettings", 28),
                            SkillUsageLine("plugin:code-review", "plugin", 90),
                        ),
                    ),
                    edits,
                ),
            )
            fx = Fixture(page.component(), page, edits)
        }

        val texts = textsOf(fx.root as Container)
        assertTrue(texts.any { it.contains("我的") }, "缺「我的」那一组：$texts")
        assertTrue(texts.any { it.contains("插件") }, "缺「插件」那一组：$texts")
        // "本地覆盖不了它"那句在**分组标题**右边（行上那枚标记只有两个字，见 lockChip 的注释）
        assertTrue(texts.any { it.contains("本地覆盖不了它") }, "插件那一组该说清本地改不了：$texts")

        // 插件那条**没有开关** —— 写 off 也没用（实测），给了就是一颗假开关
        assertEquals(listOf("caveman"), switchesIn(fx.root as Container).map { it.key })
    }

    @Test
    fun `拨开关写的是设置键`(@TempDir dir: Path) {
        // 项目根是必需的：写手是 `claude edit-skill-overrides`，它得有 cwd 才知道往
        // 哪个项目的 `.claude/settings.local.json` 写（没有项目根时页面只报一句错）
        lateinit var fx: Fixture
        onEdt {
            val edits = Edits()
            val page = SkillsSettingsPage(project = null, catalog = catalog(dir, replyOf(command("dataviz")), edits))
            fx = Fixture(page.component(), page, edits)
        }

        val sw = switchesIn(fx.root as Container).single()
        assertEquals("dataviz", sw.key)
        clickOn(sw)

        val call = fx.edits.calls.single()
        assertEquals("dataviz", call.second)
        assertEquals(SkillState.OFF, call.third, "开着的拨一下应该写 off")
    }

    @Test
    fun `关掉的技能从设置里补出来，并说清它是补出来的`(@TempDir dir: Path) {
        // CLI 的清单里没有它（off 的技能会整个消失），而设置里记着 —— 两处合起来才是完整的
        val settings = dir.resolve(".claude").resolve("settings.local.json")
        Files.createDirectories(settings.parent)
        Files.writeString(settings, """{"skillOverrides":{"lazy-senior-dev":"off"}}""")

        lateinit var root: Component
        onEdt {
            val page = SkillsSettingsPage(
                project = null,
                catalog = catalog(dir, replyOf(command("dataviz")), Edits()),
            )
            root = page.component()
        }

        val texts = textsOf(root as Container)
        assertTrue(texts.any { it.contains("lazy-senior-dev") }, "设置里关掉的那条必须还看得见：$texts")
        assertTrue(texts.any { it.contains("清单里已经没有它了") }, "得说清它是从设置补出来的：$texts")
    }

    @Test
    fun `搜索框过滤行`() {
        lateinit var root: Component
        onEdt {
            val page = SkillsSettingsPage(
                project = null,
                catalog = catalog(
                    null,
                    replyOf(command("dataviz"), command("caveman")),
                    Edits(),
                ),
            )
            root = page.component()
        }

        assertEquals(2, switchesIn(root as Container).size)

        onEdt {
            val field = findTextField(root as Container) ?: error("没有搜索框")
            field.text = "cave"
        }
        assertEquals(listOf("caveman"), switchesIn(root as Container).map { it.key })
    }

    @Test
    fun `问不到时给一句人话和一个重试`() {
        lateinit var root: Component
        onEdt {
            val page = SkillsSettingsPage(
                project = null,
                catalog = catalog(null, SkillsOnceReply.Failed("claude 40 秒内没有就绪"), Edits()),
            )
            root = page.component()
        }

        val texts = textsOf(root as Container)
        assertTrue(texts.any { it.contains("claude 40 秒内没有就绪") }, "原因要原样带出来：$texts")
        assertTrue(texts.any { it == "重试" }, "要有重试：$texts")
        assertNotNull(findLabel(root as Container, "重试"))
        assertTrue(switchesIn(root as Container).isEmpty())
        // 顶部那句「还没有问过技能清单」此刻是假话（问过了，失败了）—— 真机截图里
        // 它和「问不到技能清单：…」并排出现过，所以钉住"失败时不说这句"
        assertFalse(texts.any { it.contains("还没有问过") }, "失败时不该说「还没有问过」：$texts")
    }

    @Test
    fun `拿不到项目服务时只说一句，不炸`() {
        lateinit var root: Component
        onEdt {
            val page = SkillsSettingsPage(project = null, catalog = null)
            root = page.component()
        }

        val texts = textsOf(root as Container)
        assertTrue(texts.any { it.contains("没有项目") }, "该说清为什么没有清单：$texts")
        assertFalse(texts.any { it.contains("刷新") })
    }

    private fun findTextField(root: Container): JTextField? {
        for (child in root.components) {
            if (child is JTextField) return child
            if (child is Container) findTextField(child)?.let { return it }
        }
        return null
    }
}
