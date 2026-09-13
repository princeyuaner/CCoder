package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SidecarProcessTest {

    private fun writeScript(dir: Path, body: String) {
        Files.writeString(dir.resolve("index.js"), body)
    }

    private fun start(dir: Path): SidecarProcess {
        val proc = SidecarProcess(dir, nodePath = "node")
        proc.start()
        return proc
    }

    @Test
    @Timeout(60)
    fun `启动并读取 stdout`(@TempDir tmp: Path) {
        writeScript(
            tmp,
            """
            process.stdout.write('{"type":"ready","sessionId":"s1"}\n');
            setInterval(() => {}, 1000);
            """.trimIndent()
        )

        val proc = start(tmp)
        try {
            val line = proc.stdout!!.bufferedReader().readLine()
            assertTrue(line.contains("\"ready\""), "实际读到：$line")
        } finally {
            proc.shutdown()
        }
    }

    @Test
    @Timeout(60)
    fun `stderr 被单独读取且不与 stdout 混淆`(@TempDir tmp: Path) {
        writeScript(
            tmp,
            """
            process.stderr.write('这是一条错误\n');
            process.stdout.write('{"type":"ready"}\n');
            setInterval(() => {}, 1000);
            """.trimIndent()
        )

        val proc = start(tmp)
        try {
            val out = proc.stdout!!.bufferedReader().readLine()
            assertTrue(out.contains("ready"), "stdout 只应含 stdout 的内容，实际：$out")

            // 等待 stderr 读取线程处理完
            val deadline = System.currentTimeMillis() + 5000
            while (proc.stderrTail.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue(
                proc.stderrTail.any { it.contains("这是一条错误") },
                "stderr 应被单独收集，实际：${proc.stderrTail}"
            )
        } finally {
            proc.shutdown()
        }
    }

    @Test
    @Timeout(60)
    fun `shutdown 在宽限期内自行退出时不强杀`(@TempDir tmp: Path) {
        writeScript(
            tmp,
            """
            process.stdout.write('{"type":"ready"}\n');
            process.on('SIGTERM', () => process.exit(0));
            setInterval(() => {}, 1000);
            """.trimIndent()
        )

        val proc = start(tmp)
        proc.stdout!!.bufferedReader().readLine()

        val begin = System.currentTimeMillis()
        proc.shutdown(graceMillis = 5000)
        val elapsed = System.currentTimeMillis() - begin

        assertFalse(proc.isAlive, "shutdown 后进程必须已终止")
        assertTrue(elapsed < 5000, "能自行退出就不该等满宽限期，实际 ${elapsed}ms")
    }

    @Test
    @Timeout(60)
    fun `忽略 SIGTERM 的进程在宽限期后被强杀`(@TempDir tmp: Path) {
        writeScript(
            tmp,
            """
            process.stdout.write('{"type":"ready"}\n');
            process.on('SIGTERM', () => { /* 故意忽略 */ });
            setInterval(() => {}, 1000);
            """.trimIndent()
        )

        val proc = start(tmp)
        proc.stdout!!.bufferedReader().readLine()

        proc.shutdown(graceMillis = 800)

        assertFalse(proc.isAlive, "宽限期后必须强杀，否则会留下孤儿进程")
    }

    @Test
    @Timeout(60)
    fun `未启动时 shutdown 不抛错`(@TempDir tmp: Path) {
        val proc = SidecarProcess(tmp, nodePath = "node")
        proc.shutdown()
        assertFalse(proc.isAlive)
    }

    @Test
    @Timeout(60)
    fun `重复 shutdown 不抛错`(@TempDir tmp: Path) {
        writeScript(tmp, """process.stdout.write('{"type":"ready"}\n'); setInterval(() => {}, 1000);""")

        val proc = start(tmp)
        proc.stdout!!.bufferedReader().readLine()
        proc.shutdown(graceMillis = 500)
        proc.shutdown(graceMillis = 500)

        assertFalse(proc.isAlive)
    }

    @Test
    @Timeout(60)
    fun `子进程退出后 isAlive 变为 false`(@TempDir tmp: Path) {
        writeScript(tmp, """process.stdout.write('{"type":"ready"}\n'); process.exit(0);""")

        val proc = start(tmp)
        proc.stdout!!.bufferedReader().readLine()

        val deadline = System.currentTimeMillis() + 5000
        while (proc.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertFalse(proc.isAlive)
        proc.shutdown()
    }

    @Test
    @Timeout(60)
    fun `子进程自行退出时回调带上退出码与 stderr`(@TempDir tmp: Path) {
        // 2026-09-13 的现场：package.json 被写坏，node 报 ERR_INVALID_PACKAGE_CONFIG
        // 后立刻退出。ProcessBuilder.start() 对"建得起来、随即就死"不抛异常，
        // 于是没有任何一条路会把这件事告诉界面 —— 面板停在「正在启动…」，
        // 按钮禁用，用户只能重启 IDE。stderr 里那条报错是唯一的线索。
        writeScript(
            tmp,
            """
            process.stderr.write('Invalid package config\n');
            process.exit(3);
            """.trimIndent()
        )

        val latch = CountDownLatch(1)
        var seen: SidecarExit? = null
        val proc = SidecarProcess(tmp, nodePath = "node") {
            seen = it
            latch.countDown()
        }
        proc.start()

        assertTrue(latch.await(10, TimeUnit.SECONDS), "进程自行退出必须触发回调")
        assertEquals(3, seen!!.code, "退出码要原样带出来")
        assertTrue(
            seen!!.stderr.any { it.contains("Invalid package config") },
            "stderr 必须带出来 —— 它是用户唯一看得懂的线索，实际：${seen.stderr}"
        )
    }

    @Test
    @Timeout(60)
    fun `坏掉的 package json 被如实报出来`(@TempDir tmp: Path) {
        // 2026-09-13 现场的最小复刻。node 在读 package.json 判定模块类型时就崩，
        // 压根走不到 index.js —— 所以这里不需要真的 sidecar 代码
        Files.writeString(tmp.resolve("package.json"), "{\n  \"type\": \"module\"\n}\n2")
        Files.writeString(tmp.resolve("index.js"), "// 到不了这里\n")

        val latch = CountDownLatch(1)
        var seen: SidecarExit? = null
        val proc = SidecarProcess(tmp, nodePath = "node") {
            seen = it
            latch.countDown()
        }
        proc.start()

        assertTrue(latch.await(10, TimeUnit.SECONDS), "启动即崩必须触发回调")
        assertTrue(
            seen!!.stderr.any { it.contains("ERR_INVALID_PACKAGE_CONFIG") },
            "node 的报错要一路带到用户眼前，实际：${seen.stderr}"
        )
    }

    @Test
    @Timeout(60)
    fun `主动 shutdown 不触发退出回调`(@TempDir tmp: Path) {
        // 用户停会话、换会话、关面板都会杀这个进程，那是预期路径。
        // 报成故障会在转写区留下一条并不存在的错误
        writeScript(tmp, "setInterval(() => {}, 1000);")

        val latch = CountDownLatch(1)
        val proc = SidecarProcess(tmp, nodePath = "node") { latch.countDown() }
        proc.start()
        proc.shutdown(graceMillis = 500)

        assertFalse(latch.await(2, TimeUnit.SECONDS), "主动关闭不该报成故障")
    }
}

class ProcessTreeKillerTest {

    @Test
    fun `对不存在的 pid 不抛错`() {
        // kill 一个不存在的 pid 必然失败，但不该把异常抛给调用方 ——
        // 它是在 shutdown 的收尾路径上，那里抛错会掩盖真正的问题
        ProcessTreeKiller.killTree(999_999_999L)
    }

    @Test
    @Timeout(60)
    fun `能杀掉存活进程的整棵树`(@TempDir tmp: Path) {
        val proc = ProcessBuilder("node", "-e", "setInterval(() => {}, 1000)").start()
        try {
            assertTrue(proc.isAlive)
            ProcessTreeKiller.killTree(proc.pid())

            val deadline = System.currentTimeMillis() + 5000
            while (proc.isAlive && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertFalse(proc.isAlive, "killTree 必须真的终止进程")
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
            proc.waitFor(3, TimeUnit.SECONDS)
        }
    }
}
