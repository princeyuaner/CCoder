package com.ccoder.sync

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * 遍历一棵树，产出 `{相对路径: (大小, mtime)}`，**只含参与同步的文件**。
 *
 * ## 为什么是 `walkFileTree`，以及为什么这里曾经写错过一次
 *
 * 第一版用的是"手写栈 + `Files.newDirectoryStream` + 逐文件 `Files.readAttributes`"，
 * 理由是"手写栈好剪枝"。**探针把它否掉了**（`build/probe/sync-scan-*.txt`，可重跑）：
 *
 * | 树 | `walkFileTree` | 流 + `readAttributes` | 倍差 |
 * |---|---|---|---|
 * | `C:\M71\server`（38,905 文件） | **155 ms** | 1309 ms | 8.8× |
 * | `Z:\m71\server`（42,650 文件） | **3209 ms** | 25617 ms | 8.0× |
 *
 * 原因是 `visitFile` 拿到的 [BasicFileAttributes] **是目录枚举时顺手带回来的**
 * （Windows 的 `FindNextFile` 返回的 `WIN32_FIND_DATA` 里就有 size 与时间），
 * 而 `Files.readAttributes` 是对一个已知路径**再单独发一次** `GetFileAttributesEx`。
 * 也就是说 Python `os.scandir` 那个 20 倍优势，JVM 里**有** —— 它藏在 `walkFileTree` 里。
 *
 * 剪枝照样能做：`preVisitDirectory` 返回 `SKIP_SUBTREE`（此时 `postVisitDirectory`
 * 不会被调用，所以下面的压栈出栈是配平的）。
 *
 * ## 遍历到的那条相对路径是我们自己拼的
 *
 * 不用 `root.relativize()`：在 Windows 上它给的是反斜杠，还得再换一遍，而且会为每个
 * 文件分配两个字符串。这里维护一个"当前目录"的栈，边下探边拼 —— 也正是上面那张表里
 * 155 ms 能站住的原因之一。**相对路径一律用 `/`**：它是基线的键，必须与平台无关。
 */
internal fun scanTree(root: Path, cfg: SyncConfig): Map<String, FileStamp> {
    val out = LinkedHashMap<String, FileStamp>()
    val dirStack = ArrayDeque<String>()

    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            // 第一次进来必然是根：压一个空串当基准，之后所有相对路径都从它拼起
            if (dirStack.isEmpty()) {
                dirStack.addLast("")
                return FileVisitResult.CONTINUE
            }
            val name = dir.fileName.toString()
            val relDir = joinRel(dirStack.last(), name)
            // 三条剪枝（与参考实现的 scan_tree 逐条对应）：垃圾目录不下探、范围外的
            // 不下探（但**范围的祖先**要下探，见 inScopeDir）、被排除的不下探
            if (isJunkDir(name, cfg.junk) ||
                !inScopeDir(relDir, cfg.syncRoots) ||
                isExcluded(relDir, cfg.exclude)
            ) {
                return FileVisitResult.SKIP_SUBTREE
            }
            dirStack.addLast(relDir)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (dirStack.size > 1) dirStack.removeLast()
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val rel = joinRel(dirStack.last(), file.fileName.toString())
            // 符号链接与 junction 一律不参与：既避免成环，也避免把链接当文件去比大小
            // （链接的大小是链接本身的长度，不是目标的，比对结果没有意义）
            if (!attrs.isSymbolicLink && syncable(rel, cfg)) {
                out[rel] = FileStamp(
                    size = attrs.size(),
                    // 纳秒：秒级精度不足以区分同一秒内的两次写，而 mtime 是"有没有变过"的判据
                    mtimeNs = attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                )
            }
            return FileVisitResult.CONTINUE
        }

        /** 单个文件/目录读不了（权限、网络盘抖动）不该让整趟失败 —— 跳过它继续走。 */
        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
            FileVisitResult.CONTINUE
    })

    return out
}

/** 拼相对路径：目录为空就是名字本身，否则用 `/` 连（**不是**平台分隔符，见文件头注）。 */
private fun joinRel(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"
