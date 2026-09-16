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

    /**
     * 解析并缓存结果。
     *
     * **为什么缓存**（2026-09-16，多标签）：每个标签都会自己起一次会话，于是
     * 每个标签都会问一次这里 —— 而这条路上有"把上千个文件摊进临时目录再复制"
     * 那两步（首次 1~2 秒），乘以标签数就是白白重复。提取本身是幂等的
     * （[SidecarExtractor] 会比对内容指纹），所以缓存只是省掉重复劳动，
     * 不改变结果。
     *
     * 缓存的生命周期 = 这一次 IDE 进程：插件换了版本得重启 IDE，那时自然重算。
     */
    fun resolve(): Path = cached ?: synchronized(this) {
        cached ?: doResolve().also { cached = it }
    }

    @Volatile
    private var cached: Path? = null

    private fun doResolve(): Path {
        val version = readVersion()
        val fingerprint = readFingerprint()
        val staged = stageResourcesToTemp()
        val target = Path.of(PathManager.getSystemPath(), "ccoder", RESOURCE_ROOT)
        return SidecarExtractor.extract(staged, target, version, fingerprint)
    }

    /**
     * 内容指纹，构建时写入；见 build.gradle.kts 的 generateSidecarManifest。
     *
     * 缺失就报错而不是降级成"总是复用"：那正是要修的那个 bug。
     * 与缺 manifest 同等对待 —— 都属于构建配置出错。
     */
    private fun readFingerprint(): String =
        readResource("$RESOURCE_ROOT/fingerprint.txt")?.trim()?.ifBlank { null }
            ?: throw SidecarNotFoundException(
                "插件包内缺少 $RESOURCE_ROOT/fingerprint.txt —— 构建配置有误，" +
                    "请确认 generateSidecarManifest 写入了内容指纹。"
            )

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
