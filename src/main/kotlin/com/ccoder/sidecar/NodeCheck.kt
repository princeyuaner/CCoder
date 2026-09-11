package com.ccoder.sidecar

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
