package com.ccoder.sync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 扫描量级探针（2026-09-24）。设计稿 §2.2 第 1 条。
 *
 * ## 为什么需要它
 *
 * 参考实现量到 "16,743 个文件，本地 0.15 秒、映射网络盘 1.7 秒，而 `os.walk` 要 33 秒"
 * ——**差 20 倍那个优势来自 `os.scandir`**：Windows 的目录项本身就带 size/mtime，
 * Python 直接取用，不逐文件发系统调用。
 *
 * **JVM 没有对应物。** `Files.newDirectoryStream` 只给名字，size/mtime 得另外要。
 * 于是"巡检周期开多大"不能靠猜：`sweepMs` 的默认值等这组数出来再定。
 *
 * ## 没有断言，也不该有
 *
 * 这是个**量尺**，不是闸门：数字随机器、盘、缓存状态变，钉不住也不该钉。
 * 同 [com.ccoder.ui.CompletionRenderProbe] 那条注释的理由。
 *
 * ## 怎么读结果
 *
 * 一棵树一个用例、**量一格写一格**（`build/probe/sync-scan-*.txt`）——
 * 网络盘那棵树可能要几分钟，中途超时也留下已经量到的数，不至于白跑。
 *
 * | 写法 | 目录项来源 | 属性来源 |
 * |---|---|---|
 * | A-walkTree | `walkFileTree` | `readAttributes`（平台顺手给的） |
 * | B-stream | `newDirectoryStream` | `readAttributes` |
 * | C-stream-io | `newDirectoryStream` | `java.io.File` 的 length/lastModified |
 * | D-listFiles | `File.listFiles()` | 同上 |
 *
 * 每格跑两遍：第一遍冷、第二遍热。**只报一个会骗人**，所以两个都记。
 * A 与 B 都要能剪枝（A 靠 `preVisitDirectory` 返回 SKIP_SUBTREE），所以这四格只比速度。
 *
 * 网络盘只量 A 与 B：它慢在往返延迟上（参考实现的原话），四格一起跑会把时间拉得没必要地长，
 * 而真正要选的只在 A 与 B 之间。
 */
class SyncScanProbe {

    /** 本地盘：四格全量，用来选写法。 */
    @Test
    @Timeout(300)
    fun `本地盘 四种取属性的写法各量两遍`() = measureTree("local-c-m71", "C:\\M71\\server", ALL)

    /** 映射网络盘：只量 A 与 B。参考实现量的就是这棵树（`Z:\m71\server`）。 */
    @Test
    @Timeout(900)
    fun `映射网络盘 量 A 与 B 两种`() = measureTree("net-z-m71", "Z:\\m71\\server", listOf(A, B))

    /** 一个目录都不在时就什么都不写，别的机器上跑也不该红。 */
    @Test
    fun `目录不存在时安静跳过`() {
        measureTree("missing", "C:\\definitely\\not\\here", ALL)
    }

    // ---------------------------------------------------------------- 量法

    /**
     * 一棵树跑一遍：每种写法冷热各一次，**每量完一格就落一段盘**。
     *
     * 落盘用追加，文件在该用例开头先清空 —— 于是中途超时也留下已经量到的数，
     * 而不是"跑了两分钟，什么都没有"（第一版就是这么废掉的）。
     */
    private fun measureTree(slug: String, rootPath: String, variants: List<Pair<String, Walk>>) {
        val root = Path.of(rootPath)
        val out = File("build/probe/sync-scan-$slug.txt")
        out.parentFile?.mkdirs()

        if (!Files.isDirectory(root)) {
            out.writeText("$rootPath 不存在（或不是目录），跳过。\n", Charsets.UTF_8)
            println("[探针] $rootPath 不存在，跳过")
            return
        }

        val header = buildString {
            appendLine("扫描量级探针　${rootPath}")
            appendLine("JVM ${System.getProperty("java.version")}　OS ${System.getProperty("os.name")}")
            appendLine(String.format("%-12s %10s %14s %10s %10s", "写法", "文件数", "总字节", "冷(ms)", "热(ms)"))
        }
        out.writeText(header, Charsets.UTF_8)
        print(header)

        for ((name, walk) in variants) {
            val cold = timeOf { walk(root) }
            val warm = timeOf { walk(root) }
            val line = String.format(
                "%-12s %10d %14d %10d %10d%n",
                name, warm.second.first, warm.second.second, cold.first, warm.first,
            )
            // 每格立刻追加 —— 这是这个探针唯一"非标准"的地方，理由见类头注
            out.appendText(line, Charsets.UTF_8)
            print(line)
        }
    }

    /** 跑一次，返回 (毫秒, (文件数, 总字节))。 */
    private fun timeOf(body: () -> Pair<Long, Long>): Pair<Long, Pair<Long, Long>> {
        val t0 = System.nanoTime()
        val counts = body()
        return (System.nanoTime() - t0) / 1_000_000 to counts
    }

    private companion object {

        /** 文件数 / 总字节。 */
        private typealias Walk = (Path) -> Pair<Long, Long>

        /** A：`walkFileTree` + `readAttributes`（[BasicFileAttributes] 是平台顺手给的，不必再问一次）。 */
        private val A = "A-walkTree" to fun(root: Path): Pair<Long, Long> {
            var files = 0L
            var bytes = 0L
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    files++
                    bytes += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
            return files to bytes
        }

        /** B：手写栈 + `newDirectoryStream` + `readAttributes`。要剪枝时选它（栈里能直接跳过）。 */
        private val B = "B-stream" to fun(root: Path): Pair<Long, Long> {
            var files = 0L
            var bytes = 0L
            val stack = ArrayDeque<Path>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val children = try {
                    Files.newDirectoryStream(stack.removeLast()).use { it.toList() }
                } catch (_: Exception) {
                    continue   // 单个目录读不了不该让整趟失败（网络盘偶发）
                }
                for (child in children) {
                    val attrs = try {
                        Files.readAttributes(child, BasicFileAttributes::class.java)
                    } catch (_: Exception) {
                        continue
                    }
                    if (attrs.isDirectory) stack.addLast(child) else {
                        files++
                        bytes += attrs.size()
                    }
                }
            }
            return files to bytes
        }

        /** C：与 B 只差属性来源 —— 用来回答"`File.length()` 是不是另发一次系统调用"。 */
        private val C = "C-stream-io" to fun(root: Path): Pair<Long, Long> {
            var files = 0L
            var bytes = 0L
            val stack = ArrayDeque<Path>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val children = try {
                    Files.newDirectoryStream(stack.removeLast()).use { it.toList() }
                } catch (_: Exception) {
                    continue
                }
                for (child in children) {
                    val f = child.toFile()
                    if (f.isDirectory) stack.addLast(child) else if (f.isFile) {
                        files++
                        bytes += f.length()
                    }
                }
            }
            return files to bytes
        }

        /** D：`File.listFiles()` 递归。最土的写法，当下界。 */
        private val D = "D-listFiles" to fun(root: Path): Pair<Long, Long> {
            var files = 0L
            var bytes = 0L
            val stack = ArrayDeque<File>()
            stack.addLast(root.toFile())
            while (stack.isNotEmpty()) {
                val children = stack.removeLast().listFiles() ?: continue
                for (f in children) {
                    if (f.isDirectory) stack.addLast(f) else if (f.isFile) {
                        files++
                        bytes += f.length()
                    }
                }
            }
            return files to bytes
        }

        private val ALL = listOf(A, B, C, D)
    }
}
