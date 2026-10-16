package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 源码扫描：**MCP 那几处上 EDT 必须显式带 `ModalityState.any()`**。
 *
 * ## 为什么需要它
 *
 * 设置对话框是**模态**的，而 MCP 的实时状态正是显示在那张页上。不带模态参数时
 * `invokeLater` 用的是 `ModalityState.defaultModalityState()`，**模态开着时任务
 * 根本不执行** —— 表现就是"MCP 页里一直显示连接中，一关对话框立刻变已连接"
 * （2026-10-15 用户报的）。
 *
 * 这个坑仓库里已经量到过三次，都有注释在（`SyncService` / `RuntimeDepsService` /
 * `SkillsCatalog`），唯独 MCP 这条路当初漏了。行为型的用例盖不到它（要真起一个
 * 模态框 + 一条会话 + 一个 sidecar），所以钉在源码上。
 *
 * ## 判据
 *
 * 每个锚点之后**第一处** `invokeLater(` 起的一段里必须出现 `ModalityState.any()`。
 * 这是**启发式**，不是证明 —— 它拦的是"照抄了不带参数的老写法"，不是所有可能写法
 * （那需要真解析 Kotlin）。同 `LocalizedTextScanTest` 的口径。
 */
class McpEdtModalityScanTest {

    private val source = File("src/main/kotlin/com/ccoder/ui/ClaudePanel.kt").readText()

    /**
     * 锚点 → 这一处是干什么的。
     *
     * 用方法签名当锚点：**改名了这条就该红**（那是"名单要跟着改"的信号），
     * 而不是静悄悄地变成空转。同 `LocalizedTextScanTest` 第二条用例的理由。
     */
    private val anchors = listOf(
        "override fun addNotify()" to "切标签时重新发布 MCP 状态",
        "private fun requestMcpStatus()" to "状态回执落进 McpStatus，并点着续问定时器",
        "private fun requestMcpControl(" to "重连 / 启停的回执",
    )

    /**
     * 锚点之后、第一处 `invokeLater(` 起的一段。
     *
     * 2000 字符足够包住那三种 lambda 体与它们尾上的模态参数（最长的一处约 1300）。
     */
    private fun callAfter(anchor: String): String? {
        val i = source.indexOf(anchor)
        if (i < 0) return null
        val j = source.indexOf("invokeLater(", i)
        if (j < 0) return null
        return source.substring(j, minOf(source.length, j + 2000))
    }

    @Test
    fun `MCP 上 EDT 的那几处都显式给了模态参数`() {
        val offenders = mutableListOf<String>()

        for ((anchor, what) in anchors) {
            val call = callAfter(anchor)
            if (call == null) {
                offenders += "$anchor —— 找不到锚点，或它后面没有 invokeLater（改名了？锚点要跟着改）"
                continue
            }
            if (!call.contains("ModalityState.any()")) offenders += "$anchor（$what）"
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "这些 MCP 更新没带 ModalityState.any() —— 设置对话框（模态）开着时它们不执行，" +
                "表现是「MCP 页一直显示连接中，一关就好」。加 `, ModalityState.any()`" +
                "（同 SyncService / RuntimeDepsService）：$offenders",
        )
    }

    @Test
    fun `锚点都找得到 —— 别写成空转`() {
        // 上面那条是"没问题就绿"，改名之后很容易变成永远绿。这条把两头钉住。
        val missing = anchors.map { it.first }.filter { source.indexOf(it) < 0 }

        assertEquals(emptyList<String>(), missing, "锚点在 ClaudePanel.kt 里找不到了：$missing")
    }
}
