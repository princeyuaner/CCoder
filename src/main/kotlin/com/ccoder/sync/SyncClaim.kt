package com.ccoder.sync

import com.ccoder.settings.ProjectJson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance("com.ccoder.sync.SyncClaim")

/**
 * 同一对 (源目录 → 目标目录) **只许一个 IDE 窗口同步**。
 *
 * ## 为什么需要它（参考实现不需要，而我们需要）
 *
 * 桌面那个工具靠 `watch.pid` 保证单实例，因为它就是个单实例程序。而 IDE 里天然会开
 * 多个项目窗口 —— 两个窗口同步同一对目录会**重复删除**：A 窗口判定"本地已删→镜像删除"，
 * B 窗口同时也判一遍，两边各自去删对方刚写的东西。不报错，只是文件时有时无。
 *
 * ## 认领怎么判活
 *
 * 认领文件里记 `pid` + 项目路径 + 起始时刻。判活靠 [ProcessHandle.of]（JDK 9+，不引依赖）。
 * 进程已经不在的陈旧认领**自动接管** —— 上一轮 IDE 崩掉留下的认领不该把用户永久挡在外面。
 *
 * pid 复用（Windows 上换了个进程恰好拿到同一个号）理论上会让我们误判"有人占着"。
 * 代价是"这一次启动不工作、并写明是谁占着"，而反向的误判（放行两个窗口同时删）严重得多，
 * 所以这里偏向保守。
 */
internal object SyncClaim {

    /** 谁占着。 */
    internal data class Owner(val pid: Long, val project: String, val sinceMs: Long)

    internal sealed interface Result {
        /** 拿到了（或接管了陈旧认领）。 */
        data object Acquired : Result

        /** 已被另一个活着的窗口占着。 */
        data class Occupied(val owner: Owner) : Result
    }

    /**
     * 试着认领。
     *
     * @param isAlive 判活。注入是为了能确定性地测"陈旧认领自动接管"——真去造一个死进程
     *   既慢又难写对。生产一律用 [defaultIsAlive]。
     */
    internal fun tryAcquire(
        path: Path,
        projectPath: String,
        myPid: Long = ProcessHandle.current().pid(),
        nowMs: Long = System.currentTimeMillis(),
        isAlive: (Long) -> Boolean = ::defaultIsAlive,
    ): Result {
        val existing = read(path)
        if (existing != null && existing.pid != myPid && isAlive(existing.pid)) {
            return Result.Occupied(existing)
        }
        // 没主 / 陈旧 / 还是我们自己（服务重启过）→ 接管
        write(path, Owner(myPid, projectPath, nowMs))
        return Result.Acquired
    }

    /**
     * 释放。**只删自己那一份** —— 否则一个刚被挡下的窗口退出时会顺手抹掉占用者的认领，
     * 第三个窗口就能挤进来，于是"只许一个"这条规矩自己就破了。
     */
    internal fun release(path: Path, myPid: Long = ProcessHandle.current().pid()) {
        val existing = read(path)
        if (existing != null && existing.pid != myPid) return
        runCatching { Files.deleteIfExists(path) }
            .onFailure { LOG.warn("CCoder 同步：释放认领失败（不影响同步，只是别的窗口可能多等一会儿）：$path", it) }
    }

    /** 现在是谁占着（读不到就是 null）。状态卡用它讲"被谁占着"。 */
    internal fun readOwner(path: Path): Owner? = read(path)

    /** JDK 自带的判活。pid 不存在时 `ProcessHandle.of` 给空。 */
    internal fun defaultIsAlive(pid: Long): Boolean =
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    // ---------------------------------------------------------------- 文件读写

    private fun read(path: Path): Owner? {
        val root = ProjectJson.read(path)
        if (root.entrySet().isEmpty()) return null
        val pid = root.get("pid")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
        return Owner(
            pid = pid,
            project = root.get("project")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
            sinceMs = root.get("since")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
        )
    }

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    private fun write(path: Path, owner: Owner) {
        val obj = JsonObject().apply {
            addProperty("pid", owner.pid)
            addProperty("project", owner.project)
            addProperty("since", owner.sinceMs)
            addProperty(
                "_comment",
                "CCoder 同步的『这一对目录正在被哪个 IDE 窗口同步』。同一对目录同时只允许一个窗口，" +
                    "否则两边会互相删对方刚写的文件。进程不在了这份认领会被下一次启动自动接管。",
            )
        }
        // 原子写：半个认领文件会被当成"没有认领"，于是两个窗口都以为自己独占
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(tmp, gson.toJson(obj) + "\n", StandardCharsets.UTF_8)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { LOG.warn("CCoder 同步：写认领文件失败（另一个窗口可能也会启动）：$path", it) }
    }
}
