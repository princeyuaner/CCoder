package com.ccoder.sync

import com.intellij.openapi.application.PathManager
import java.nio.file.Path

/**
 * 这个功能自己的状态放在哪。
 *
 * **一律放在 IDE 的系统目录下（`PathManager.getSystemPath()/ccoder/sync/`），
 * 不往用户的项目里写任何东西** —— 这个仓库目前一个字节都不写项目目录，本功能不开这个先例。
 * 代价是换机器要重做一次全量核对（1 分钟量级），那一头是明摆着的。
 *
 * 文件按 `(src, dst)` 的哈希分：同一对目录一份基线、一份认领；换了一对就是另一份，
 * 互不干扰。
 */
internal object SyncPaths {

    /** 状态目录。 */
    internal fun dir(): Path = Path.of(PathManager.getSystemPath(), "ccoder", "sync")

    /** 这一对目录的内容基线。 */
    internal fun baseline(src: String, dst: String): Path = dir().resolve(key(src, dst) + ".json")

    /** 这一对目录的窗口认领。 */
    internal fun claim(src: String, dst: String): Path = dir().resolve(key(src, dst) + ".claim")

    /**
     * 哈希键。**先把路径归一**（绝对、去 `.`/`..`）再哈希：[SyncBaseline.keyFor] 只认字符串，
     * 而用户把 `Z:\m71\server` 写成 `Z:/m71/server/` 是同一对目录，不该换出一份新基线和
     * 一次全量核对。归一失败（路径畸形）就退回原样，反正只是文件名。
     */
    private fun key(src: String, dst: String): String =
        SyncBaseline.keyFor(normalize(src), normalize(dst))

    private fun normalize(raw: String): String =
        runCatching { Path.of(raw).toAbsolutePath().normalize().toString() }.getOrDefault(raw)
}
