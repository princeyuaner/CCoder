package com.ccoder.ui

import com.ccoder.sidecar.SidecarExit
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SidecarExitReportTest {

    @Test
    fun `文案带退出码与 stderr 内容`() {
        val text = sidecarExitReport(SidecarExit(3, listOf("boom")))

        assertTrue(text.contains("退出码 3"), text)
        assertTrue(text.contains("boom"), text)
    }

    @Test
    fun `没有 stderr 时明说没留下线索`() {
        // 不能只报一句"进程已退出"就完事 —— 那和卡在「启动中…」一样无从下手
        val text = sidecarExitReport(SidecarExit(1, emptyList()))

        assertTrue(text.contains("没有留下任何错误信息"), text)
    }

    @Test
    fun `超过上限时只留末尾并说明总数`() {
        val lines = (1..40).map { "line$it" }

        val text = sidecarExitReport(SidecarExit(1, lines), maxLines = 5)

        assertTrue(text.contains("共 40 行，只显示最后 5 行"), text)
        assertTrue(text.contains("line40"), text)
        assertFalse(text.contains("line34"), text)
    }

    @Test
    fun `node 的包配置报错必须保住 Error 那行`() {
        // 2026-09-13 现场的真实输出（package.json 被写坏那次）。
        // 取末 8 行会把 "Error: ..." 切掉，只剩一堆 at ... 堆栈，
        // 用户看完还是不知道发生了什么。这是 15 这个数字的全部理由
        val real = listOf(
            "node:internal/modules/run_main:76",
            "  const type = getNearestParentPackageJSONType(mainPath);",
            "               ^",
            "",
            "Error: Invalid package config \\\\?\\C:\\Users\\CY\\Desktop\\CCoder\\sidecar\\package.json.",
            "    at shouldUseESMLoader (node:internal/modules/run_main:76:16)",
            "    at Module.executeUserEntryPoint [as runMain] (node:internal/modules/run_main:147:20)",
            "    at node:internal/main/run_main_module:33:47 {",
            "  code: 'ERR_INVALID_PACKAGE_CONFIG'",
            "}",
            "",
            "Node.js v24.13.1",
        )

        val text = sidecarExitReport(SidecarExit(1, real))

        assertTrue(text.contains("Error: Invalid package config"), "关键的 Error 行被切掉了：\n$text")
        assertTrue(text.contains("ERR_INVALID_PACKAGE_CONFIG"), text)
        assertFalse(text.contains("只显示最后"), "13 行不该触发截断：\n$text")
    }
}
