package com.ccoder.sidecar

import com.intellij.openapi.application.PathManager
import java.nio.file.Path
import java.nio.file.Files
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
 * 类加载器只能按条目名读取 jar 内资源，无法遍历目录，因此构建时会生成
 * 一份 manifest.txt 列出全部文件（见 build.gradle.kts 的 generateSidecarManifest）。
 */
object ProductionSidecarResolver {

    private const val RESOURCE_ROOT = "sidecar"

    fun resolve(): Path {
        val version = readVersion()
        val staged = stageResourcesToTemp()
        val target = Path.of(PathManager.getSystemPath(), "ccoder", RESOURCE_ROOT)
        return SidecarExtractor.extract(staged, target, version)
    }

    /** 版本号来自构建时写入的 version.txt，与 sidecar/package.json 同步。 */
    private fun readVersion(): String =
        readResource("$RESOURCE_ROOT/version.txt")
            ?.trim()
            ?.ifBlank { null }
            ?: FALLBACK_VERSION

    private fun readResource(path: String): String? =
        ProductionSidecarResolver::class.java.getResourceAsStream("/$path")
            ?.bufferedReader()
            ?.use { it.readText() }

    /**
     * 把插件包内的 sidecar 资源释放到临时目录。
     * [SidecarExtractor] 需要真实的目录树，而 jar 内的资源只能逐个读出。
     */
    private fun stageResourcesToTemp(): Path {
        val manifest = readResource("$RESOURCE_ROOT/manifest.txt")
            ?: throw SidecarNotFoundException(
                "插件包内缺少 $RESOURCE_ROOT/manifest.txt —— 构建配置有误，" +
                    "请确认 generateSidecarManifest 任务已接入 processResources。"
            )

        val staging = Files.createTempDirectory("ccoder-sidecar-")
        val dest = staging.resolve(RESOURCE_ROOT)
        Files.createDirectories(dest)

        for (entry in manifest.lineSequence()) {
            val relative = entry.trim()
            if (relative.isEmpty()) continue

            val target = dest.resolve(relative)
            // 防御目录穿越：manifest 由构建生成，但不该假设它永远可信
            if (!target.normalize().startsWith(dest.normalize())) {
                throw SidecarNotFoundException("manifest 含非法路径：$relative")
            }
            Files.createDirectories(target.parent)

            ProductionSidecarResolver::class.java
                .getResourceAsStream("/$RESOURCE_ROOT/$relative")
                ?.use { input -> Files.newOutputStream(target).use { input.copyTo(it) } }
                ?: throw SidecarNotFoundException("manifest 列出的资源不存在：$relative")
        }

        return dest
    }

    private const val FALLBACK_VERSION = "0.0.0"
}
