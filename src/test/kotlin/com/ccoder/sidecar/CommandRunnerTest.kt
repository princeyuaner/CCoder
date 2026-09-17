package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * [CommandRunner] 的真进程用例。
 *
 * **故意用真进程**（`node -e` 造可控输出 / 退出码 / 不退出的进程）：这一层的全部价值
 * 就在"真的起得来、真的杀得掉"，拿假的 `Process` 测等于什么都没测。
 * 上层（服务、页面）注入的是假 runner（见设计稿 §6「测试里真跑进程」）。
 */
class CommandRunnerTest {

    private fun events(): MutableList<RunEvent> = Collections.synchronizedList(mutableListOf())

    /** 轮询等待某个条件。超时返回 false，由调用方断言 —— 别用固定 sleep。 */
    private fun waitUntil(timeoutMillis: Long = 10_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    @Test
    @Timeout(60)
    fun `逐行回吐，stderr 与 stdout 的时序保持原样`() {
        val ev = events()
        val runner = CommandRunner(
            Command("node", listOf("-e", "console.log('a'); console.error('b'); console.log('c')")),
            { ev.add(it) },
        )
        runner.start()

        assertTrue(waitUntil { ev.any { it is RunEvent.Exited } }, "没有收到终态事件：$ev")
        assertEquals(listOf("a", "b", "c"), ev.filterIsInstance<RunEvent.Line>().map { it.text })
        assertEquals(0, (ev.last() as RunEvent.Exited).code)
        assertFalse(runner.isAlive)
    }

    @Test
    @Timeout(60)
    fun `非零退出码如实上报`() {
        val ev = events()
        CommandRunner(Command("node", listOf("-e", "process.exit(3)")), { ev.add(it) }).start()

        assertTrue(waitUntil { ev.any { it is RunEvent.Exited } }, "没有收到终态事件：$ev")
        assertEquals(3, (ev.last() as RunEvent.Exited).code)
    }

    @Test
    @Timeout(60)
    fun `起不来的程序报 Failed，不抛异常`() {
        val ev = events()
        CommandRunner(Command("definitely-not-a-real-binary-xyz"), { ev.add(it) }).start()

        assertTrue(waitUntil { ev.isNotEmpty() }, "什么都没有回调")
        assertTrue(ev.single() is RunEvent.Failed, "实际：$ev")
    }

    @Test
    @Timeout(60)
    fun `超时走 Failed，理由里带超时二字`() {
        val ev = events()
        CommandRunner(
            Command("node", listOf("-e", "setInterval(() => {}, 1000)")),
            { ev.add(it) },
            maxMillis = 700,
        ).start()

        assertTrue(waitUntil { ev.any { it is RunEvent.Failed } }, "没有被超时掐掉：$ev")
        assertTrue((ev.last() as RunEvent.Failed).reason.contains("超时"), "理由：${ev.last()}")
    }

    @Test
    @Timeout(60)
    fun `取消报 Cancelled —— 不是「失败（退出码 1）」`() {
        val ev = events()
        val runner = CommandRunner(Command("node", listOf("-e", "setInterval(() => {}, 1000)")), { ev.add(it) })
        runner.start()
        assertTrue(waitUntil { runner.isAlive }, "进程没起来")

        runner.cancel()

        assertTrue(waitUntil { ev.any { it is RunEvent.Cancelled } }, "没有 Cancelled：$ev")
        assertTrue(waitUntil { !runner.isAlive }, "取消之后进程还活着")
        assertTrue(ev.none { it is RunEvent.Exited }, "取消不该再补一条 Exited：$ev")
    }

    @Test
    @Timeout(60)
    fun `还没起就取消 —— 不真跑，直接给 Cancelled`() {
        val ev = events()
        val runner = CommandRunner(Command("node", listOf("-e", "console.log('不该出现')")), { ev.add(it) })

        runner.cancel()
        runner.start()

        assertEquals(listOf<RunEvent>(RunEvent.Cancelled), ev)
    }

    /**
     * 进程树那条：`npm.cmd` 的真实结构是 `cmd.exe → node.exe → …`，
     * 只杀直接子进程的话，孙进程会留在后台继续干活 —— 而界面说"已取消"。
     *
     * 判据用"孙进程还在不在写文件"，因为它跨平台且不依赖 tasklist 的输出格式。
     */
    @Test
    @Timeout(60)
    fun `取消之后孙进程也真的停了`(@TempDir tmp: Path) {
        val ticks = tmp.resolve("ticks.txt")
        Files.writeString(ticks, "")
        val grandchild = tmp.resolve("grandchild.cjs")
        Files.writeString(
            grandchild,
            "setInterval(() => require('node:fs').appendFileSync(process.argv[2], 'x'), 200);",
        )
        val parent = tmp.resolve("parent.cjs")
        Files.writeString(
            parent,
            """
            const { spawn } = require('node:child_process');
            const path = require('node:path');
            spawn(process.execPath, [path.join(__dirname, 'grandchild.cjs'), process.argv[2]], { stdio: 'ignore' });
            setInterval(() => {}, 1000);
            """.trimIndent(),
        )

        val ev = events()
        val runner = CommandRunner(Command("node", listOf(parent.toString(), ticks.toString())), { ev.add(it) })
        runner.start()

        assertTrue(waitUntil { Files.size(ticks) > 0 }, "孙进程没起来，这个用例就没意义了")

        runner.cancel()
        assertTrue(waitUntil { ev.any { it is RunEvent.Cancelled } }, "没有 Cancelled：$ev")

        val frozen = Files.size(ticks)
        Thread.sleep(1500)
        assertEquals(frozen, Files.size(ticks), "取消之后孙进程还在写 —— 进程树没杀干净")
    }
}
