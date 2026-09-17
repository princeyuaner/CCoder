package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 跨语言钉子：**单一真源在 JS 那边**（`sidecar/claude-path.js` 导出的
 * `CANDIDATE_NAMES` / `KNOWN_DIRS`），Kotlin 的 [CANDIDATE_NAMES] / [KNOWN_DIRS]
 * 是副本。这条用例跑一次 node 把 JS 那两张表打印出来，与 Kotlin 侧**逐字**比对。
 *
 * 为什么值得一条用例：
 *
 * - 只改一边的后果不是"少个候选名"，而是**检测绿了、开会话仍然 CLAUDE_NOT_FOUND** ——
 *   设置页说"都装好了"，而按启动按钮得到一句"未找到 claude"。这比没有这个功能更糟。
 * - 靠注释提醒很容易漏（`NodeCheck.MIN_MAJOR` 与 `package.json` 的 engines 就是这么
 *   变成两份拷贝的）—— 这里改成让机器来提醒。
 *
 * 打印成 `key=v1|v2` 的纯文本而不是 JSON：比对要的是逐字，省掉一个 JSON 解析器。
 */
class ClaudePathTablesSyncTest {

    @Test
    @Timeout(60)
    fun `候选名与已知目录两张表与 JS 侧逐字相同`() {
        val src = Path.of("sidecar", "claude-path.js").toAbsolutePath()
        assertTrue(
            Files.isRegularFile(src),
            "找不到 $src —— 用例的工作目录应当是仓库根（Gradle 默认如此）",
        )

        // 用 file:// URL 动态 import：ESM 模块不能 require
        val url = src.toUri().toString()
        val nl = "String.fromCharCode(10)"
        val script = "import('" + url + "').then(m => {" +
            "const fmt = o => Object.keys(o).sort().map(k => k + '=' + o[k].join('|')).join($nl);" +
            "console.log('NAMES' + $nl + fmt(m.CANDIDATE_NAMES) + $nl + 'DIRS' + $nl + fmt(m.KNOWN_DIRS));" +
            "});"

        val p = ProcessBuilder("node", "-e", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val exited = p.waitFor(60, TimeUnit.SECONDS)
        if (!exited) p.destroyForcibly()
        assertEquals(0, p.exitValue(), "node 打印两张表失败：\n$out")

        val lines = out.trim().lines()
        val namesAt = lines.indexOf("NAMES")
        val dirsAt = lines.indexOf("DIRS")
        assertTrue(namesAt >= 0 && dirsAt > namesAt, "输出形状不对：\n$out")

        assertEquals(
            fmt(CANDIDATE_NAMES),
            lines.subList(namesAt + 1, dirsAt).joinToString("\n"),
            "claude 的候选名两边不一致 —— 只改了一边（真源在 sidecar/claude-path.js）",
        )
        assertEquals(
            fmt(KNOWN_DIRS),
            lines.subList(dirsAt + 1, lines.size).joinToString("\n"),
            "已知安装目录两边不一致 —— 只改了一边（真源在 sidecar/claude-path.js）",
        )
    }

    /** 与 JS 那侧同一个格式：键排序，值用 `|` 连。 */
    private fun fmt(table: Map<String, List<String>>): String =
        table.keys.sorted().joinToString("\n") { key -> "$key=" + table.getValue(key).joinToString("|") }
}
