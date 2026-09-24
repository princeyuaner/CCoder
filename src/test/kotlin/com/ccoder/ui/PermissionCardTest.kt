package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JButton
import javax.swing.JEditorPane

/**
 * 权限卡片。
 *
 * 卡片上每个按钮按下去都等于一次授权，所以这里盯的是「按下去回传的到底是什么」——
 * 按钮上写的话必须和它真正授权的范围一致。写「本会话」就不能顺带往设置里
 * 落一条持久规则：那比按钮承诺的范围大。
 */
class PermissionCardTest {

    private val someSuggestions: JsonArray = JsonParser.parseString(
        """[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"npm test *"}],""" +
            """"behavior":"allow","destination":"localSettings"}]"""
    ).asJsonArray

    private fun perm(suggestions: JsonArray? = null, displayName: String? = "允许") =
        SidecarMessage.Permission(
            requestId = "r1",
            toolName = "Bash",
            input = JsonParser.parseString("""{"command":"npm test"}""").asJsonObject,
            title = "Claude 想运行 npm test",
            displayName = displayName,
            description = null,
            blockedPath = null,
            decisionReason = null,
            defaultToNo = false,
            suppressAlwaysAllowRule = false,
            suggestions = suggestions,
        )

    private fun buttonsIn(root: Container): List<JButton> {
        val out = mutableListOf<JButton>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JButton) out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    @Test
    fun `按钮文案写明范围是本会话`() {
        val card = PermissionCard(perm(someSuggestions), queuedCount = 0) {}

        val texts = buttonsIn(card).map { it.text }
        assertTrue(texts.any { it.contains(AUTO_ALLOW_LABEL) }, "实际按钮：$texts")
    }

    // ---- 「允许」那颗按钮上写什么（2026-09-15 用户报"第二个按钮叫 Bash"）----

    @Test
    fun `真机那种 displayName 下，卡片按钮写「允许」而不是工具名`() {
        // CLI 对内置工具给的 displayName 就是工具名本身 —— 用户真机上那颗按钮
        // 写着「Bash」，读起来像"点它就运行 Bash"。这条钉子就是照真机来的，
        // 旧夹具里写的是 displayName = "允许"，所以一路绿。
        val card = PermissionCard(perm(displayName = "Bash"), queuedCount = 0) {}

        val texts = buttonsIn(card).map { it.text }
        assertTrue(texts.contains("允许"), "实际按钮：$texts")
        assertTrue(texts.none { it == "Bash" }, "按钮上不该出现工具名：$texts")
    }

    @Test
    fun `displayName 退化成工具名时不采用，大小写无关`() {
        assertEquals("允许", PermissionOptions.allowLabel(perm(displayName = "Bash")))
        assertEquals("允许", PermissionOptions.allowLabel(perm(displayName = "bash")))
    }

    @Test
    fun `displayName 真的是动作短语时采用`() {
        // sdk.d.ts:234-238 的本意（例子就是 "Read file"）；MCP 工具会走到这一支
        assertEquals("Read file", PermissionOptions.allowLabel(perm(displayName = "Read file")))
    }

    @Test
    fun `displayName 缺失或空白时回落「允许」`() {
        assertEquals("允许", PermissionOptions.allowLabel(perm(displayName = null)))
        assertEquals("允许", PermissionOptions.allowLabel(perm(displayName = "   ")))
    }

    // ---- 改动预览（2026-09-24）----

    private fun editPerm(old: String, new: String) = SidecarMessage.Permission(
        requestId = "r2",
        toolName = "Edit",
        // 用 JsonObject 直接搭，不走 JSON 文本：内容里有换行、引号、反引号，
        // 手写转义迟早错一个（这条教训是本仓库探针里记着的）
        input = JsonObject().apply {
            addProperty("file_path", "a.txt")
            addProperty("old_string", old)
            addProperty("new_string", new)
        },
        title = null, displayName = null, description = null,
        blockedPath = null, decisionReason = null,
        defaultToNo = false, suppressAlwaysAllowRule = false, suggestions = null,
    )

    /**
     * 这条护的是"**看得见**"这件事，而不是"画了 diff"：diff 不能换行（一换就看不出
     * 对齐了，见 `diffHtml`），所以长行的出路只有横滚条 ——
     * 横滚条不出，那一行的结尾就永远够不着，正是这一版要修的那种毛病。
     */
    @Test
    fun `长行的结尾够得着 —— 横滚条必须出得来`() = IdeLaf.withRealLaf {
        val long = "x".repeat(400)
        val card = PermissionCard(editPerm(long, "$long y"), queuedCount = 0) {}
        card.setSize(PERMISSION_CARD_WIDTH, JBUI.scale(300))
        layoutAll(card)

        val scroll = requireNotNull(findScroll(card)) { "卡片里该有一个滚动区" }
        assertTrue(
            scroll.horizontalScrollBar.isVisible,
            "diff 不换行，横滚条不出就等于结尾看不见：" +
                "视口 ${scroll.viewport.extentSize.width} / 内容 ${scroll.viewport.viewSize.width}",
        )
    }

    @Test
    fun `改一个词的 Edit 走 HTML 面板，而不是那段 JSON`() {
        // 从前：两个字段以转义 JSON 铺出来，用户得自己在里面找"到底改了哪一句"。
        // 而这一屏是批准的依据 —— 看不到内容的审批不是审批
        val card = PermissionCard(editPerm("foo", "bar"), queuedCount = 0) {}

        val html = requireNotNull(editorsIn(card).firstOrNull()) { "改动预览该走 HTML 面板" }.text
        assertTrue(html.contains("foo"), "删的那一行要在：$html")
        assertTrue(html.contains("bar"), "加的那一行要在：$html")
        assertTrue(html.contains("bgcolor="), "要有底色带：$html")
        assertTrue(!html.contains("old_string"), "不该再是入参 JSON：$html")
    }

    private fun editorsIn(root: Container): List<JEditorPane> {
        val out = mutableListOf<JEditorPane>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JEditorPane) out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    private fun findScroll(root: Container): JBScrollPane? {
        for (child in root.components) {
            if (child is JBScrollPane) return child
            if (child is Container) findScroll(child)?.let { return it }
        }
        return null
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) if (child is Container) layoutAll(child)
    }

    @Test
    fun `停问回传停问标记，且不往设置里写规则`() {
        var got: PermissionDecision? = null
        val card = PermissionCard(perm(someSuggestions), queuedCount = 0) { got = it }

        // 用 doClick 而不是派发 MOUSE_CLICKED：JButton 的 ActionListener 是
        // 由 mouseReleased 触发的，派发 MOUSE_CLICKED 什么都不发生
        buttonsIn(card).first { it.text.contains(AUTO_ALLOW_LABEL) }.doClick()

        val decision = requireNotNull(got) { "按钮没有回传决定" }
        assertTrue(decision.allow, "停问的同时也得允许这一次")
        assertTrue(decision.stopAsking, "没有带上停问标记")
        assertNull(
            decision.updatedPermissions,
            "停问的作用范围是本会话，不该往 settings.local.json 里落规则",
        )
    }
}
