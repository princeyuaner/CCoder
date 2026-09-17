package com.ccoder.settings

import com.ccoder.sidecar.DepStatus
import com.ccoder.sidecar.Os
import com.ccoder.sidecar.RunEvent
import com.ccoder.sidecar.RuntimeDep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RuntimeDepsService] 的用例。
 *
 * 三个口子全注入：**手动调度器**（任务攒着，用例决定何时跑 —— 竞态因此是确定的，
 * 不靠 sleep）、**假探针**（一个队列里取结果）、**假执行器**（不真起进程）。
 * `post` 直接同步跑：测试 JVM 里没有 EDT。
 */
class RuntimeDepsServiceTest {

    private val scheduler = FakeScheduler()
    private val probeResults = ArrayDeque<DepStatus>()
    private val runs = mutableListOf<FakeRun>()
    private var probed = mutableListOf<RuntimeDep>()

    private fun service(): RuntimeDepsService = depsService(
        probe = { dep, _ ->
            probed.add(dep)
            probeResults.removeFirst()
        },
        runAsync = scheduler::schedule,
        newRunner = { _, onEvent -> FakeRun(onEvent).also { runs.add(it) } },
    )

    private fun runPlan(): InstallPlan = installPlan(
        RuntimeDep.NODE, DepStatus.NotFound, ToolSet("winget", null, null), Os.WINDOWS,
    )

    /** 开一次安装，并把它排在队尾那个"启动执行器"的任务跑掉，返回假执行器。 */
    private fun startInstall(s: RuntimeDepsService, plan: InstallPlan = runPlan()): FakeRun {
        s.startInstall(plan)
        scheduler.runNext()   // 队列里那个 { runner.start() }
        return runs.last()
    }

    @Test
    fun `查过之前是 Unknown，查的过程中是 Checking，结果回来才落定`() {
        val s = service()
        assertEquals(DepStatus.Unknown, s.statusOf(RuntimeDep.NODE))

        probeResults.add(DepStatus.Ok("C:\\node.exe", "24.13.1"))
        s.refresh(RuntimeDep.NODE)
        assertEquals(DepStatus.Checking, s.statusOf(RuntimeDep.NODE), "查的过程中界面该说「检测中」")

        scheduler.runNext()
        assertEquals(DepStatus.Ok("C:\\node.exe", "24.13.1"), s.statusOf(RuntimeDep.NODE))
    }

    @Test
    fun `同一个依赖连查两次，先发后到的那个结果被丢弃`() {
        val s = service()
        // 结果按**执行**顺序排：后发的那次先回来（找到），先发的那次随后才回来（没找到）
        probeResults.add(DepStatus.Ok("C:\\node.exe", "24.13.1"))
        probeResults.add(DepStatus.NotFound)

        s.refresh(RuntimeDep.NODE)   // 先发
        s.refresh(RuntimeDep.NODE)   // 后发

        scheduler.runLast()   // 后发的先回来
        assertEquals(DepStatus.Ok("C:\\node.exe", "24.13.1"), s.statusOf(RuntimeDep.NODE))

        scheduler.runNext()   // 先发的这才迟到 —— 必须被丢掉
        assertEquals(
            DepStatus.Ok("C:\\node.exe", "24.13.1"),
            s.statusOf(RuntimeDep.NODE),
            "迟到的旧结果把新的盖掉了",
        )
    }

    @Test
    fun `两个依赖各查各的，互不作废`() {
        val s = service()
        probeResults.add(DepStatus.NotFound)
        probeResults.add(DepStatus.Ok("C:\\claude.exe", "2.1.268"))

        s.refreshAll()          // 同时排了两个任务
        scheduler.runNext()
        scheduler.runNext()

        assertEquals(DepStatus.NotFound, s.statusOf(RuntimeDep.NODE))
        assertEquals(DepStatus.Ok("C:\\claude.exe", "2.1.268"), s.statusOf(RuntimeDep.CLAUDE))
    }

    @Test
    fun `开安装会作废在飞的检测结果`() {
        val s = service()
        probeResults.add(DepStatus.NotFound)
        s.refresh(RuntimeDep.CLAUDE)

        s.startInstall(runPlan())
        scheduler.runNext()   // 检测结果现在才回来 —— 它答的是"安装之前"的问题
        scheduler.runNext()   // 队列里那个 { runner.start() }
        assertEquals(DepStatus.Checking, s.statusOf(RuntimeDep.CLAUDE), "安装期间不该被旧检测结果改写")

        runs.single().emit(RunEvent.Exited(0))
        // 装完自动复检：又排出两个任务（node 与 claude）
        assertEquals(2, scheduler.pending)
    }

    @Test
    fun `安装全过程：Running 收行，成功后退到 Finished 并自动复检`() {
        val s = service()

        val fake = startInstall(s)
        assertTrue(s.installState() is InstallState.Running, "实际：${s.installState()}")

        fake.emit(RunEvent.Line("added 1 package"))
        val running = s.installState() as InstallState.Running
        assertEquals(listOf("added 1 package"), running.log)

        fake.emit(RunEvent.Exited(0))
        val done = s.installState() as InstallState.Finished
        assertEquals(InstallResult.SUCCEEDED, done.result)
        assertEquals(2, scheduler.pending, "装完必须自动复检两个依赖")
    }

    @Test
    fun `非零退出码如实汇报，且不触发复检`() {
        val s = service()
        startInstall(s).emit(RunEvent.Exited(1))

        val done = s.installState() as InstallState.Finished
        assertEquals(InstallResult.FAILED, done.result)
        assertTrue(done.log.any { it.contains("退出码 1") }, "日志：${done.log}")
        assertEquals(0, scheduler.pending, "失败不该装作成功去复检")
    }

    @Test
    fun `取消说的是「可能留下未装完的文件」，不是一句失败`() {
        val s = service()
        startInstall(s).cancel()

        val done = s.installState() as InstallState.Finished
        assertEquals(InstallResult.CANCELLED, done.result)
        assertTrue(done.log.any { it.contains("建议重跑") }, "日志：${done.log}")
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun `起不来或超时这类 Failed 把理由写进日志`() {
        val s = service()
        startInstall(s).emit(RunEvent.Failed("超时（600 秒）"))

        val done = s.installState() as InstallState.Finished
        assertEquals(InstallResult.FAILED, done.result)
        assertTrue(done.log.any { it.contains("超时") }, "日志：${done.log}")
    }

    /**
     * 「装好了」和「找得到」是两件事。安装成功、复检却仍然找不到时，
     * 界面上会同时出现两个看起来矛盾的结论 —— 必须补一句把话说明白。
     */
    @Test
    fun `装成功但复检仍找不到 —— 补一句指路`() {
        val s = service()
        probeResults.add(DepStatus.NotFound)   // 复检 node
        probeResults.add(DepStatus.NotFound)   // 复检 claude

        startInstall(s).emit(RunEvent.Exited(0))
        scheduler.runAll()

        val done = s.installState() as InstallState.Finished
        assertEquals(InstallResult.SUCCEEDED, done.result)
        assertTrue(done.log.any { it.contains("仍然找不到") }, "日志：${done.log}")
        assertTrue(done.log.any { it.contains("where node") }, "得给出下一步：${done.log}")
    }

    @Test
    fun `npm 装完 claude 却仍找不到时，指向 npm 全局前缀`() {
        val s = service()
        val plan = installPlan(
            RuntimeDep.CLAUDE, DepStatus.NotFound, ToolSet(null, null, "npm.cmd"), Os.WINDOWS,
            nodeDir = null, nodeMajor = 24,
        )
        probeResults.add(DepStatus.NotFound)   // 复检 node
        probeResults.add(DepStatus.NotFound)   // 复检 claude

        startInstall(s, plan).emit(RunEvent.Exited(0))
        scheduler.runAll()

        val done = s.installState() as InstallState.Finished
        assertTrue(done.log.any { it.contains("npm prefix -g") }, "日志：${done.log}")
    }

    @Test
    fun `装成功且复检通过 —— 不多说一句`() {
        val s = service()
        probeResults.add(DepStatus.Ok("C:\\nodejs\\node.exe", "24.19.0"))   // 装的那个，复检通过
        probeResults.add(DepStatus.NotFound)                              // 另一个照旧没装

        startInstall(s).emit(RunEvent.Exited(0))
        scheduler.runAll()

        val done = s.installState() as InstallState.Finished
        assertTrue(done.log.none { it.contains("仍然找不到") }, "日志：${done.log}")
    }

    @Test
    fun `日志只留最后 500 行，别把整页撑爆`() {
        val s = service()
        val fake = startInstall(s)
        repeat(600) { fake.emit(RunEvent.Line("第 $it 行")) }

        val log = (s.installState() as InstallState.Running).log
        assertEquals(RuntimeDepsService.MAX_LOG_LINES, log.size)
        assertEquals("第 100 行", log.first())
        assertEquals("第 599 行", log.last())
    }

    @Test
    fun `MANUAL 计划不启动执行器`() {
        val s = service()
        val manualPlan = installPlan(RuntimeDep.NODE, DepStatus.NotFound, ToolSet(null, null, null), Os.WINDOWS)
        assertEquals(InstallRoute.MANUAL, manualPlan.route)

        s.startInstall(manualPlan)

        assertEquals(InstallState.Idle, s.installState())
        assertTrue(runs.isEmpty(), "兜底路径不该真跑命令")
    }

    @Test
    fun `退订之后不再被通知`() {
        val s = service()
        var calls = 0
        val listener: () -> Unit = { calls++ }
        s.addListener(listener)
        s.refresh(RuntimeDep.NODE)
        val afterRefresh = calls
        assertTrue(afterRefresh > 0, "订阅之后至少该收到一次")

        s.removeListener(listener)
        probeResults.add(DepStatus.NotFound)
        s.refresh(RuntimeDep.NODE)
        scheduler.runNext()

        assertEquals(afterRefresh, calls, "退订之后还在回调 —— 每开一次设置就漏一个监听器")
    }

    @Test
    fun `探针抛异常时收敛成 Broken，不让服务崩`() {
        val s = depsService(
            probe = { _, _ -> throw IllegalStateException("探针炸了") },
            runAsync = scheduler::schedule,
            newRunner = { _, _ -> error("不该走到这") },
        )

        s.refresh(RuntimeDep.NODE)
        scheduler.runNext()

        val status = s.statusOf(RuntimeDep.NODE)
        assertTrue(status is DepStatus.Broken, "实际：$status")
        assertEquals("探针炸了", (status as DepStatus.Broken).reason)
        assertFalse(s.installState() is InstallState.Running)
    }
}
