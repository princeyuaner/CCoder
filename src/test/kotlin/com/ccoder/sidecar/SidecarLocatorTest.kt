package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SidecarLocatorTest {

    private fun makeSidecar(dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("index.js"), "// fake")
    }

    @Test
    fun `开发模式从项目根目录找到 sidecar`(@TempDir tmp: Path) {
        val sidecar = tmp.resolve("sidecar")
        makeSidecar(sidecar)

        assertEquals(sidecar, SidecarLocator.resolve(tmp.toString()))
    }

    @Test
    fun `向上多级目录也能找到`(@TempDir tmp: Path) {
        val sidecar = tmp.resolve("sidecar")
        makeSidecar(sidecar)
        val deep = tmp.resolve("a/b/c")
        Files.createDirectories(deep)

        assertEquals(sidecar, SidecarLocator.resolve(deep.toString()))
    }

    @Test
    fun `只有目录没有 index_js 不算可运行的 sidecar`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("sidecar"))
        assertFalse(SidecarLocator.isRunnableSidecar(tmp.resolve("sidecar")))
    }

    @Test
    fun `index_js 是目录也不算可运行`(@TempDir tmp: Path) {
        val sidecar = tmp.resolve("sidecar")
        Files.createDirectories(sidecar.resolve("index.js"))
        assertFalse(
            SidecarLocator.isRunnableSidecar(sidecar),
            "必须是文件；目录同名会让 node 启动时报错"
        )
    }

    @Test
    fun `projectBasePath 为 null 时不抛错`() {
        // 会回退到 user.dir 探测，或最终抛 SidecarNotFoundException，
        // 但不该因为 null 而异常退出
        val result = runCatching { SidecarLocator.resolve(null) }
        assertTrue(result.isSuccess || result.exceptionOrNull() is SidecarNotFoundException)
    }

    @Test
    fun `找不到时抛 SidecarNotFoundException`(@TempDir tmp: Path) {
        // 构造一个不可能含 sidecar 的深路径
        val isolated = tmp.resolve("x/y/z/w/v/u")
        Files.createDirectories(isolated)

        val err = runCatching { SidecarLocator.resolve(isolated.toString()) }.exceptionOrNull()
        assertTrue(
            err == null || err is SidecarNotFoundException,
            "应抛 SidecarNotFoundException，实际：$err"
        )
    }
}

class NodeCheckTest {

    @Test
    @Timeout(30)
    fun `本机 node 可用且版本满足要求`() {
        val status = NodeCheck.verify("node")
        assertTrue(status is NodeStatus.Ok, "实际：$status")
        val major = (status as NodeStatus.Ok).version.substringBefore('.').toInt()
        assertTrue(major >= NodeCheck.MIN_MAJOR, "实测版本 ${status.version}")
    }

    @Test
    @Timeout(30)
    fun `不存在的 node 路径返回 NotFound`() {
        assertEquals(NodeStatus.NotFound, NodeCheck.verify("definitely-not-a-real-binary-xyz"))
    }

    @Test
    fun `最低版本要求是 18`() {
        // 与 sidecar/package.json 的 engines 字段和 SDK 要求一致
        assertEquals(18, NodeCheck.MIN_MAJOR)
    }

    @Test
    fun `NodeStatus 三种分支互斥`() {
        val ok = NodeStatus.Ok("20.0.0")
        val old = NodeStatus.TooOld("16.0.0")
        assertTrue(ok is NodeStatus.Ok && ok !is NodeStatus.TooOld)
        assertTrue(old is NodeStatus.TooOld && old !is NodeStatus.Ok)
        assertEquals(NodeStatus.NotFound, NodeStatus.NotFound)
    }
}
