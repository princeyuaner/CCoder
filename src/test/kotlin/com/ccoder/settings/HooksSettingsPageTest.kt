package com.ccoder.settings

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
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

private fun findLabel(root: Container, text: String): Component? {
    for (child in root.components) {
        if (child is JLabel && child.text == text) return child
        if (child is Container) findLabel(child, text)?.let { return it }
    }
    return null
}

private fun inputOf(root: Container, label: String): JComponent {
    val lab = findLabel(root, label) ?: error("找不到字段标签「$label」")
    val panel = lab.parent as? Container ?: error("「$label」不在容器里")
    return panel.components.filterIsInstance<JComponent>().first { it !== lab }
}

/** 命令是多行框，真正的 `JTextArea` 在滚动壳的视口里。 */
private fun commandArea(root: Container, label: String): JTextComponent {
    val box = inputOf(root, label)
    return (box as? javax.swing.JScrollPane)?.viewport?.view as? JTextComponent
        ?: error("「$label」那栏不是多行框")
}

private fun clickRow(root: Container, event: String) {
    val lab = findLabel(root, event) ?: error("列表里找不到「$event」")
    clickOn(lab.parent)
}

private fun clickOn(target: Component) {
    val e = MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)
    target.mouseListeners.forEach { it.mouseClicked(e) }
}

private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

class HooksSettingsPageTest {

    private fun open(baseDir: Path?): JComponent {
        lateinit var root: JComponent
        onEdt { root = HooksSettingsPage(baseDir).component() }
        return root
    }

    private fun settingsPath(dir: Path) = projectSettingsPath(dir)

    private fun hooksIn(dir: Path) = hooksOf(ProjectJson.read(settingsPath(dir)))

    /** 预置一份 settings.json。**`.claude/` 要自己建** —— 临时目录里没有它。 */
    private fun writeSettings(dir: Path, json: String) {
        Files.createDirectories(settingsPath(dir).parent)
        Files.writeString(settingsPath(dir), json.trimIndent())
    }

    @Test
    fun `新加一条 hook 会写进文件`(@TempDir dir: Path) {
        val root = open(dir)

        onEdt { clickOn(findLabel(root, ADD_HOOK_LABEL)!!) }
        onEdt { commandArea(root, "命令").text = "echo 拦一下" }

        val rules = hooksIn(dir).rules
        assertEquals(1, rules.size)
        assertEquals("echo 拦一下", rules.first().command)
        // 默认事件是第一项（PreToolUse）—— 新建时给一个最常用的，不是空
        assertEquals(MANAGED_HOOK_EVENTS.first(), rules.first().event)
    }

    @Test
    fun `命令还没填时不落库 —— 否则文件里会留一条每次都会失败的 hook`(@TempDir dir: Path) {
        val root = open(dir)

        onEdt { clickOn(findLabel(root, ADD_HOOK_LABEL)!!) }
        onEdt { (inputOf(root, "工具匹配（留空 = 全部）") as JTextComponent).text = "Write" }

        assertTrue(hooksIn(dir).rules.isEmpty(), "空命令的 hook 被写进文件了")
        assertFalse(Files.exists(settingsPath(dir)), "不该凭空建出这个文件")
    }

    @Test
    fun `改一条不会弄丢文件里别人的东西`(@TempDir dir: Path) {
        writeSettings(
            dir,
            """
            {
              "hooks": {
                "PreToolUse": [
                  { "matcher": "Write", "hooks": [ { "type": "command", "command": "旧命令" } ] },
                  { "matcher": "Bash", "hooks": [ { "type": "prompt", "prompt": "看着点" } ] }
                ],
                "PostCompact": [ { "hooks": [ { "type": "command", "command": "别的" } ] } ]
              },
              "permissions": { "allow": ["Bash(git *)"] }
            }
            """.trimIndent(),
        )
        val root = open(dir)

        onEdt { clickRow(root, "PreToolUse") }
        onEdt { commandArea(root, "命令").text = "新命令" }

        val file = ProjectJson.read(settingsPath(dir))
        assertTrue(file.has("permissions"), "顶层别的键被弄丢了")
        assertTrue(file.getAsJsonObject("hooks").has("PostCompact"), "不归我们管的事件被弄丢了")
        val pre = file.getAsJsonObject("hooks").getAsJsonArray("PreToolUse")
        assertEquals(2, pre.size(), "认不得的那条 matcher 被弄丢了")
        assertTrue(
            // `get("command")` 可能为 null —— 保留下来的那条是 prompt 型，没有这个键
            pre.any {
                it.asJsonObject.getAsJsonArray("hooks")[0].asJsonObject
                    .get("command")?.asString == "新命令"
            },
            "改动没写进去",
        )
    }

    @Test
    fun `列表上如实报出有多少条不归这个面板管`(@TempDir dir: Path) {
        writeSettings(
            dir,
            """
            {
              "hooks": {
                "PostCompact": [ { "hooks": [ { "type": "command", "command": "x" } ] } ],
                "PreToolUse": [ { "matcher": "B", "hooks": [ { "type": "prompt", "prompt": "p" } ] } ]
              }
            }
            """.trimIndent(),
        )
        val root = open(dir)

        // 不报的话，"我原来配的那些哪去了"是必然会被问的问题
        assertNotNull(findLabel(root, "另有 2 处不归这个面板管，原样保留"))
    }

    @Test
    fun `删除会把这条从文件里去掉`(@TempDir dir: Path) {
        writeSettings(dir, """{ "hooks": { "Stop": [ { "hooks": [ { "type": "command", "command": "x" } ] } ] } }""")
        val root = open(dir)

        onEdt { clickRow(root, "Stop") }
        onEdt { clickOn(findLabel(root, "删除")!!) }

        assertTrue(hooksIn(dir).rules.isEmpty())
        assertFalse(
            ProjectJson.read(settingsPath(dir)).getAsJsonObject("hooks").has("Stop"),
            "留了个空数组",
        )
    }

    @Test
    fun `拿不到项目目录时只读，并说清楚`() {
        val root = open(null)

        assertNotNull(findLabel(root, "拿不到项目目录，改不了"))
    }

    @Test
    fun `空态文案指的是左栏那个按钮`() {
        val root = open(null)

        assertNotNull(findLabel(root, "在左边选一条 hook，或点「$ADD_HOOK_LABEL」"))
    }

    @Test
    fun `超时留空就是不写这一项`(@TempDir dir: Path) {
        val root = open(dir)
        onEdt { clickOn(findLabel(root, ADD_HOOK_LABEL)!!) }
        onEdt { commandArea(root, "命令").text = "echo hi" }

        assertEquals(0, hooksIn(dir).rules.first().timeout, "空超时该是 0（不写）")
    }
}
