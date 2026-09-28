package com.ccoder.sync

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 目标端不可访问 / 前置检查没过。调用方把它当"这一轮失败"：记下来、退避后重试，
 * **进程绝不退出**（参考实现那条"掉线与自愈"就是这么要求的）。
 */
internal class SyncError(message: String) : Exception(message)

/**
 * 目标端后端 —— 本版只有 [LocalTarget]，SSH 将来落在这里。
 *
 * 接口刻意很小：引擎对目标端的全部需求就是"能否访问、扫全树、问单个文件、比内容、
 * 写文件、删文件、清空目录"，加一个给内容基线用的身份串。参考实现的原话：
 * "换传输方式只换后端"。
 *
 * **`compareMany` 要源目录**：比对是"两侧内容是否一致"，一侧是目标端（后端自带），
 * 另一侧在源端 —— 参考实现的 `compare_many(src_root, rels)` 也是这么传的。
 */
internal interface SyncTarget {

    /**
     * 内容基线的身份串。**换了目标端 → 基线自动作废 → 重做一次全量核对**，
     * 这是安全且自愈的行为（参考实现的 `dst_key` 就是干这个的）。
     * 必须是稳定值：同一个目标端每次算出来要一样，否则每轮都在重做全量核对。
     */
    val stateKey: String

    /** 前置检查：目标端不可访问就抛 [SyncError]。每轮开头调。 */
    fun check()

    /** 扫全树，**应用与源端同一套过滤规则**（垃圾目录不下探、排除项跳过）。 */
    fun scan(): Map<String, FileStamp>

    /** 单个文件的状态；不存在 / 读不到返回 null。 */
    fun stat(rel: String): FileStamp?

    /** 并发比对一批文件的内容，返回 `rel → 是否完全一致`。只读，所以并发是安全的。 */
    fun compareMany(srcRoot: Path, rels: List<String>, onProgress: (done: Int, total: Int) -> Unit): Map<String, Boolean>

    /** 复制一个文件过去（父目录按需创建，覆盖已存在的）。失败要抛。 */
    fun copyFrom(srcFile: Path, rel: String)

    /** 删除。返回 true 表示确实删掉了，false 表示目标端原本就不存在。 */
    fun remove(rel: String): Boolean

    /** 清空目录：**只删确实是空的那些**，返回真删掉的。 */
    fun prune(rels: List<String>): List<String>

    /** 收尾（本地后端无事可做；SSH 后端在这里断连接）。 */
    fun close() {}
}

/** 内容比对的并发度：瓶颈在往返延迟而不是带宽，所以并发收益很直接（参考实现实测）。 */
private const val SCAN_WORKERS = 8

/** 候选数超过这个阈值才值得开线程池；少量文件直接串行，省掉线程开销。 */
private const val PARALLEL_THRESHOLD = 32

/**
 * 目标端 = 本地路径 / 映射网络盘（`Z:\m71\server`）。参考实现里那个 `LocalTarget` 的移植。
 *
 * 需要 [cfg] 是因为 [scan] 要走**与源端同一份**过滤规则 —— 两份规则各自演化
 * 是参考实现专门用结构排除掉的一类 bug（"不会出现两个后端规则逐渐漂移"）。
 */
internal class LocalTarget(private val root: Path, private val cfg: () -> SyncConfig) : SyncTarget {

    override val stateKey: String = root.toAbsolutePath().normalize().toString()

    override fun check() {
        if (!Files.isDirectory(root)) {
            throw SyncError("目标目录不可访问：$root（盘没挂上，或网络断了）")
        }
    }

    override fun scan(): Map<String, FileStamp> = scanTree(root, cfg())

    override fun stat(rel: String): FileStamp? = stampOf(root.resolve(rel))

    override fun compareMany(
        srcRoot: Path,
        rels: List<String>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Map<String, Boolean> {
        if (rels.size < PARALLEL_THRESHOLD) {
            return rels.associateWith { filesIdentical(srcRoot.resolve(it), root.resolve(it)) }
        }
        val result = HashMap<String, Boolean>(rels.size)
        val pool = Executors.newFixedThreadPool(SCAN_WORKERS) { r ->
            Thread(r, "ccoder-sync-compare").apply { isDaemon = true }
        }
        try {
            val futures = pool.invokeAll(rels.map { rel ->
                java.util.concurrent.Callable { rel to filesIdentical(srcRoot.resolve(rel), root.resolve(rel)) }
            })
            var done = 0
            for (f in futures) {
                // 单个文件读失败按"不同"处理（于是会走复制）—— 与参考实现一致
                val pair = runCatching { f.get() }.getOrNull()
                if (pair != null) result[pair.first] = pair.second
                done++
                if (done % 64 == 0) onProgress(done, rels.size)
            }
        } finally {
            pool.shutdown()
        }
        return result
    }

    override fun copyFrom(srcFile: Path, rel: String) {
        val dst = root.resolve(rel)
        dst.parent?.let { Files.createDirectories(it) }
        Files.copy(srcFile, dst, StandardCopyOption.REPLACE_EXISTING)

        // **保留 mtime 是有意的**（参考实现用 `shutil.copy2`）：目标端那份的 mtime 因此
        // 等于源端的，于是"它有没有被那台机器自己改过"这个判断（[mayDelete] 那道闸）
        // 才是准的。设置失败**不算复制失败** —— 内容已经过去了，属性差一点只影响下次
        // 比对的判定（下一轮会退回读内容），没有正确性代价。
        runCatching { Files.setLastModifiedTime(dst, Files.getLastModifiedTime(srcFile)) }

        // 网络盘上值得复核一下大小：写完就断的情况不罕见，而且它无声。
        val want = runCatching { Files.size(srcFile) }.getOrNull()
        val got = runCatching { Files.size(dst) }.getOrNull()
        if (want != null && got != null && want != got) {
            throw IOException("复制后大小不一致（源 $want，目标 $got）")
        }
    }

    override fun remove(rel: String): Boolean {
        val p = root.resolve(rel)
        if (Files.isDirectory(p)) {
            deleteRecursively(p)
            return true
        }
        return Files.deleteIfExists(p)
    }

    override fun prune(rels: List<String>): List<String> {
        val removed = mutableListOf<String>()
        for (rel in rels) {
            val d = root.resolve(rel)
            try {
                // 只删**确实是空的**：目标端那些装着运行产物的目录因此绝不会被误伤
                if (Files.isDirectory(d) && isEmptyDir(d)) {
                    Files.delete(d)
                    removed += rel
                }
            } catch (_: Exception) {
                // 删不掉就算了（被占用 / 权限），下一轮还会再看到它
            }
        }
        return removed
    }
}

/** 单个文件的状态；不存在 / 读不到返回 null。**不抛** —— 调用方一律按"没有"处理。 */
internal fun stampOf(path: Path): FileStamp? = try {
    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
    if (attrs.isDirectory) null else FileStamp(attrs.size(), attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS))
} catch (_: Exception) {
    null
}

/**
 * 两个文件内容是否完全一致。
 *
 * 自己比而不是用 `Files.mismatch`（那个要先判大小，行为其实一样）或 `filecmp` 式的
 * stat 缓存（同一进程里反复比较会失效 —— 参考实现专门记了这个坑）。分块读，遇到不同
 * 立刻返回：稳态下大多数文件其实是"大小相同、内容也相同"，那些要读满。
 */
internal fun filesIdentical(a: Path, b: Path): Boolean = try {
    val sa = Files.size(a)
    val sb = Files.size(b)
    if (sa != sb) {
        false
    } else if (sa == 0L) {
        true
    } else {
        Files.newInputStream(a).use { ia ->
            Files.newInputStream(b).use { ib ->
                val bufA = ByteArray(1 shl 20)
                val bufB = ByteArray(1 shl 20)
                var same = true
                while (true) {
                    val na = ia.read(bufA)
                    val nb = ib.read(bufB)
                    if (na != nb) {
                        same = false
                        break
                    }
                    if (na <= 0) break
                    if (!bufA.copyOf(na).contentEquals(bufB.copyOf(nb))) {
                        same = false
                        break
                    }
                }
                same
            }
        }
    }
} catch (_: Exception) {
    false   // 读不了就当"不同" → 会走复制，让下一轮去处理真问题
}

private fun isEmptyDir(dir: Path): Boolean =
    Files.newDirectoryStream(dir).use { !it.iterator().hasNext() }

/** 删一棵树。仓库里 `SidecarExtractor` 有同样的一段，但那个是私有的（且职责不同）。 */
private fun deleteRecursively(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
    }
}
