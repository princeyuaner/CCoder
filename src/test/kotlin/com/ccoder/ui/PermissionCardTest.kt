package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonArray
import com.google.gson.JsonParser
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

    private fun perm(suggestions: JsonArray? = null) = SidecarMessage.Permission(
        requestId = "r1",
        toolName = "Bash",
        input = JsonParser.parseString("""{"command":"npm test"}""").asJsonObject,
        title = "Claude 想运行 npm test",
        displayName = "允许",
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
