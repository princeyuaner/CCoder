package com.ccoder.sidecar

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * 把插件包内的 sidecar 目录提取到磁盘。
 *
 * jar 内的 node_modules 无法被 node 直接执行，必须落到真实文件系统。
 *
 * 目录布局：`<targetDir>/<version>/` —— 以版本号分目录，
 * 使升级后不会残留旧代码，同时避免每次启动都重写磁盘。
 */
object SidecarExtractor {

    /** 判定一个版本目录是否已完整提取。 */
    private fun isComplete(versioned: Path): Boolean =
        versioned.resolve("index.js").exists()

    /**
     * @param resourceRoot 插件包内的 sidecar 源目录
     * @param targetDir    提取根目录（各版本作为其子目录）
     * @param version      sidecar 版本号，取自 sidecar/package.json
     * @return 可直接运行的 sidecar 目录
     */
    fun extract(resourceRoot: Path, targetDir: Path, version: String): Path {
        val versioned = targetDir.resolve(version)

        // 半成品目录（上次提取中断）与完整目录必须区分对待：
        // 只看目录是否存在会把残缺目录当成可用版本
        if (isComplete(versioned)) {
            cleanOtherVersions(targetDir, version)
            return versioned
        }

        if (versioned.exists()) deleteRecursively(versioned)

        Files.createDirectories(versioned)
        copyDirectory(resourceRoot, versioned)

        cleanOtherVersions(targetDir, version)
        return versioned
    }

    private fun cleanOtherVersions(targetDir: Path, keep: String) {
        if (!targetDir.isDirectory()) return
        Files.list(targetDir).use { stream ->
            stream.filter { it.isDirectory() && it.fileName.toString() != keep }
                .forEach { deleteRecursively(it) }
        }
    }

    private fun deleteRecursively(dir: Path) {
        if (!dir.exists()) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun copyDirectory(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { src ->
                val target = to.resolve(from.relativize(src).toString())
                if (Files.isDirectory(src)) {
                    Files.createDirectories(target)
                } else {
                    target.parent?.let { Files.createDirectories(it) }
                    Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
