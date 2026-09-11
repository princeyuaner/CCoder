package com.ccoder.sidecar

import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class SidecarNotFoundException(message: String) : Exception(message)

/**
 * 定位可直接运行的 sidecar 目录。
 *
 * 开发模式：直接用源码树里的 sidecar/ —— runIde 时改 JS 重启插件即生效，
 *          无需每次重新提取。
 * 生产模式：见 [ProductionSidecarResolver]。
 */
object SidecarLocator {

    private const val MAX_ASCENT = 5

    fun resolve(projectBasePath: String?): Path {
        devModeSidecar(projectBasePath)?.let { return it }
        return ProductionSidecarResolver.resolve()
    }

    fun isRunnableSidecar(dir: Path): Boolean {
        val index = dir.resolve("index.js")
        // 必须是文件：同名目录会让 node 启动时报错，但目录存在性检查会放过它
        return dir.isDirectory() && index.isRegularFile()
    }

    private fun devModeSidecar(projectBasePath: String?): Path? {
        val starts = buildList {
            projectBasePath?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
            System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
        }

        for (start in starts) {
            var dir: Path? = runCatching { start.toAbsolutePath() }.getOrNull()
            repeat(MAX_ASCENT) {
                val candidate = dir?.resolve("sidecar")
                if (candidate != null && isRunnableSidecar(candidate)) return candidate
                dir = dir?.parent
            }
        }
        return null
    }
}

/**
 * 生产模式：从插件包内提取 sidecar 到系统缓存目录。
 *
 * 该实现由 Task 14 接入打包后替换 —— 在此之前只会抛错，
 * 因为只跑源码时不存在插件包资源。
 */
object ProductionSidecarResolver {
    fun resolve(): Path = throw SidecarNotFoundException(
        "未找到 sidecar 目录。开发模式下请在项目根目录运行；" +
            "若已打包发布，这是插件资源缺失，属打包配置问题。"
    )
}
