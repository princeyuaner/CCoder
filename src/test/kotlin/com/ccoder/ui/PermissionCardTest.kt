package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JButton

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
