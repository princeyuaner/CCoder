package com.ccoder.sidecar

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

sealed interface NodeStatus {
    data class Ok(val version: String) : NodeStatus
    data object NotFound : NodeStatus
    data class TooOld(val version: String) : NodeStatus
}

/**
 * node 可用性检查。
 *
 * SDK 的 engines 要求 node >= 18（sidecar/package.json）。
 * spec §5.3 要求这一失败有独立错误码 NODE_NOT_FOUND，
 * 而不是笼统的"启动失败"——两者的修复动作完全不同。
 */
object NodeCheck {

    const val MIN_MAJOR = 18

    /**
     * 三级解析出 node 的绝对路径：显式路径 → PATH → 已知安装目录。
     * 找不到返回 null（调用方走"未找到"那条分支，不抛错）。
     *
     * ## 第三级是 2026-09-17 加的，理由不是"多找几个地方"
     *
     * **装完 node 之后，IDE 进程的 PATH 不会刷新**（那是启动时继承的），而 winget /
     * brew 装出来的 node 正落在已知目录里（`C:\Program Files\nodejs` 之类）。
     * 只认 PATH 的话有一条最气人的路径：用户照设置页的按钮把 node 装好了、
     * 检测也绿了，一按「启动会话」仍然说"未找到 node"。
     *
     * 同一张表在 `claude-path.js` 里也有一份（那份是真源），`ClaudePathTablesSyncTest` 钉住两边一致。
     */
    fun resolve(explicit: String? = null): String? {
        val os = hostOs()
        val env = System.getenv()
        val home = Path.of(System.getProperty("user.home") ?: ".")
        return candidatePaths(
            RuntimeDep.NODE,
            explicit,
            lookupEnv(env, "PATH").orEmpty(),
            os, env, home,
        ).firstOrNull { File(it).isFile }
    }

    fun verify(nodePath: String = "node"): NodeStatus {
        val raw = runCatching {
            val p = ProcessBuilder(nodePath, "--version").redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return NodeStatus.NotFound
            }
            if (p.exitValue() != 0) return NodeStatus.NotFound
            p.inputStream.bufferedReader().use { it.readText() }.trim()
        }.getOrNull() ?: return NodeStatus.NotFound

        // node --version 输出形如 "v24.13.1"
        val version = raw.removePrefix("v")
        val major = version.substringBefore('.').toIntOrNull() ?: return NodeStatus.NotFound
        return if (major >= MIN_MAJOR) NodeStatus.Ok(version) else NodeStatus.TooOld(version)
    }
}
