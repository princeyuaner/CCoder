package com.ccoder.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 对账计划的每个判定分支（2026-09-24）。
 *
 * 这是整个功能的判定中枢：**多复制一次是浪费，少删一个文件是残留，多删一个是事故**。
 * 所以每个分支都在这里有名字，尤其是三条"安静地做错"的路：
 *
 * 1. 目标端那份被人改过而我们把它删了（运行机的产物就这么没了）
 * 2. 用户改了排除项，而我们把这当成"文件没了"去镜像删除
 * 3. 不遍历目标端的那一轮，误以为目标端也没动过
 *
 * 三个 I/O 口子（[contentPlan] 的 `scanDst` / `statDst` / `compareMany`）全部注入假实现，
 * 于是每个分支都能精确构造，而且**能断言"该调的调了、不该调的没调"**——比如
 * "目标缺失时不许读内容"这一条，省下的正是每轮几 MB 的读盘。
 */
class SyncPlanTest {

    private val cfg = SyncConfig(syncRoots = listOf("trunk"))

    private fun s(size: Long, mtime: Long) = FileStamp(size, mtime)
    private fun pair(ss: Long, sm: Long, ds: Long, dm: Long) = FilePair(s(ss, sm), s(ds, dm))

    /** 记录调用过的比对与 stat，用来断言"不该调的没调"。 */
    private class Spies {
        val compared = mutableListOf<String>()
        val scanned = intArrayOf(0)
        val statted = mutableListOf<String>()

        fun compareMany(verdicts: Map<String, Boolean>): (List<String>) -> Map<String, Boolean> = { rels ->
            compared += rels
            rels.associateWith { verdicts[it] ?: true }
        }

        fun scanDst(result: Map<String, FileStamp>): () -> Map<String, FileStamp> = {
            scanned[0]++
            result
        }

        fun statDst(result: Map<String, FileStamp?>): (String) -> FileStamp? = { rel ->
            statted += rel
            result[rel]
        }
    }

    private fun plan(
        srcNow: Map<String, FileStamp>,
        baseline: Map<String, FilePair> = emptyMap(),
        dstNow: Map<String, FileStamp>? = null,
        spies: Spies = Spies(),
        dstScan: Map<String, FileStamp> = emptyMap(),
        dstStat: Map<String, FileStamp?> = emptyMap(),
        verdicts: Map<String, Boolean> = emptyMap(),
    ): Pair<ContentPlan, Spies> = contentPlan(
        srcNow = srcNow,
        baseline = baseline,
        dstNow = dstNow,
        scanDst = spies.scanDst(dstScan),
        statDst = spies.statDst(dstStat),
        compareMany = spies.compareMany(verdicts),
        cfg = cfg,
    ) to spies

    // ---------------------------------------------------------------- 遍历目标端的那一轮

    @Test
    fun `双方都没动过 → 沿用基线，不读内容、不碰网络`() {
        val (p, spies) = plan(
            srcNow = mapOf("trunk/a.kt" to s(10, 100)),
            baseline = mapOf("trunk/a.kt" to pair(10, 100, 10, 200)),
            dstNow = mapOf("trunk/a.kt" to s(10, 200)),
        )

        assertTrue(p.copies.isEmpty(), "两侧都与基线一致，没什么要做的")
        assertEquals(1, p.unchangedCount)
        assertEquals(pair(10, 100, 10, 200), p.nextBaseline["trunk/a.kt"], "整条原样沿用")
        assertTrue(spies.compared.isEmpty(), "稳态下不该读任何内容")
    }

    @Test
    fun `目标缺失 → 复制，且不读内容`() {
        val (p, spies) = plan(
            srcNow = mapOf("trunk/a.kt" to s(10, 100)),
            dstNow = emptyMap(),
        )

        assertEquals(listOf(CopyWhy.TARGET_MISSING_OR_SIZE), p.copies.map { it.why })
        assertTrue(spies.compared.isEmpty(), "大小都对不上就没必要读内容 —— 省下的正是每轮几 MB 的读盘")
    }

    @Test
    fun `大小不同 → 复制，且不读内容`() {
        val (p, spies) = plan(
            srcNow = mapOf("trunk/a.kt" to s(10, 100)),
            dstNow = mapOf("trunk/a.kt" to s(11, 100)),
        )

        assertEquals(listOf(CopyWhy.TARGET_MISSING_OR_SIZE), p.copies.map { it.why })
        assertTrue(spies.compared.isEmpty())
    }

    @Test
    fun `大小相同但状态变过 → 才读内容；一致就只更新基线`() {
        val (p, spies) = plan(
            srcNow = mapOf("trunk/a.kt" to s(10, 100)),
            baseline = mapOf("trunk/a.kt" to pair(10, 99, 10, 200)),
            dstNow = mapOf("trunk/a.kt" to s(10, 200)),
            verdicts = mapOf("trunk/a.kt" to true),
        )

        assertEquals(listOf("trunk/a.kt"), spies.compared, "只有这批才值得读内容")
        assertTrue(p.copies.isEmpty(), "内容其实一样，别重复复制一遍目标端")
        assertEquals(pair(10, 100, 10, 200), p.nextBaseline["trunk/a.kt"], "把新核实的一致记回基线")
    }

    @Test
    fun `内容确实不同 → 复制`() {
        val (p, _) = plan(
            srcNow = mapOf("trunk/a.kt" to s(10, 100)),
            baseline = mapOf("trunk/a.kt" to pair(10, 99, 10, 200)),
            dstNow = mapOf("trunk/a.kt" to s(10, 200)),
            verdicts = mapOf("trunk/a.kt" to false),
        )

        assertEquals(listOf(CopyWhy.CONTENT_DIFFERS), p.copies.map { it.why })
    }

    @Test
    fun `目标端在核实之后被改过 → 下一轮不会以为「它还是老样子」`() {
        // 基线里记的目标端状态，存在一个很窄的窗口：我们核实完之后、落盘之前，
        // 目标端自己把它改了。要钉住的是**这个设计会自愈** —— 下一轮 `rec.dst` 与目标端
        // 对不上，于是"双方都没动过"那条快路不被采纳，退回去重新比内容。
        // （参考实现的 README 里那条 FAQ「Z 盘被改过会不会被修回」讲的就是这条。）
        val round1 = plan(
            srcNow = mapOf("trunk/a.kt" to s(10, 100)),
            baseline = emptyMap(),
            dstNow = mapOf("trunk/a.kt" to s(10, 200)),
            verdicts = mapOf("trunk/a.kt" to true),
        ).first
        assertEquals(pair(10, 100, 10, 200), round1.nextBaseline["trunk/a.kt"], "第一轮核实出一致的基线")

        val (round2, spies) = plan(
            srcNow = mapOf("trunk/a.kt" to s(10, 100)),          // 本地一个字没动
            baseline = round1.nextBaseline,
            dstNow = mapOf("trunk/a.kt" to s(10, 999)),          // 目标端那份被改过（大小恰好相同）
            verdicts = mapOf("trunk/a.kt" to false),
        )

        assertEquals(listOf("trunk/a.kt"), spies.compared, "对不上就必须重新核实内容，不能走快路")
        assertEquals(
            listOf(CopyWhy.CONTENT_DIFFERS),
            round2.copies.map { it.why },
            "核实的结果是内容不同 → 用本地那份覆盖回去",
        )
    }

    // ---------------------------------------------------------------- 不遍历目标端的那一轮

    @Test
    fun `不遍历目标端：本地没动过的沿用基线，一个 stat 都不发`() {
        val (p, spies) = plan(
            srcNow = mapOf("trunk/a.kt" to s(10, 100)),
            baseline = mapOf("trunk/a.kt" to pair(10, 100, 10, 200)),
            dstNow = null,
        )

        assertTrue(spies.statted.isEmpty(), "本地与基线逐位相同 → 目标端沿用旧值，不碰网络")
        assertTrue(p.copies.isEmpty())
        assertEquals(pair(10, 100, 10, 200), p.nextBaseline["trunk/a.kt"])
    }

    @Test
    fun `不遍历目标端：本地动过的才去问目标端`() {
        val (p, spies) = plan(
            srcNow = mapOf("trunk/a.kt" to s(10, 101)),
            baseline = mapOf("trunk/a.kt" to pair(10, 100, 10, 200)),
            dstNow = null,
            dstStat = mapOf("trunk/a.kt" to s(10, 200)),
            verdicts = mapOf("trunk/a.kt" to false),
        )

        assertEquals(listOf("trunk/a.kt"), spies.statted)
        assertEquals(listOf(CopyWhy.CONTENT_DIFFERS), p.copies.map { it.why })
    }

    @Test
    fun `不遍历目标端时，目标端那份被人改过是发现不了的 —— 这是那条路的代价`() {
        // 本地没动、基线也没变，于是目标端沿用旧值。哪怕目标端那份真的被改了，
        // 这一轮也看不出来 —— 只有巡检轮（遍历目标端）才会发现。用例把这个"度数"钉住，
        // 免得将来有人以为事件轮也能发现它。
        val (p, spies) = plan(
            srcNow = mapOf("trunk/a.kt" to s(10, 100)),
            baseline = mapOf("trunk/a.kt" to pair(10, 100, 10, 200)),
            dstNow = null,
        )

        assertEquals(pair(10, 100, 10, 200), p.nextBaseline["trunk/a.kt"])
        assertTrue(spies.statted.isEmpty())
    }

    @Test
    fun `没有基线时强制整趟扫描目标端 —— 逐文件 stat 比整树遍历慢 20 倍`() {
        val (p, spies) = plan(
            srcNow = mapOf("trunk/a.kt" to s(10, 100)),
            baseline = emptyMap(),
            dstNow = null,
            dstScan = mapOf("trunk/a.kt" to s(10, 200)),
            verdicts = mapOf("trunk/a.kt" to true),
        )

        assertEquals(1, spies.scanned[0], "没有基线时必须遍历一遍目标端")
        assertTrue(p.firstFullReconcile, "而且要告诉报告：这一轮可能要一两分钟")
        assertTrue(p.copies.isEmpty(), "内容一致 → 不用复制")
    }

    // ---------------------------------------------------------------- 本地已删

    @Test
    fun `本地已删且仍在范围内 → 进候选删除，并连基线记录一起带着`() {
        val (p, _) = plan(
            srcNow = emptyMap(),
            baseline = mapOf("trunk/a.kt" to pair(10, 100, 10, 200)),
            dstNow = mapOf("trunk/a.kt" to s(10, 200)),
        )

        assertEquals(setOf("trunk/a.kt"), p.vanished.keys)
        assertEquals(
            pair(10, 100, 10, 200),
            p.vanished["trunk/a.kt"],
            "记录必须带着 —— 调用方要拿它验目标端那份有没有被动过",
        )
    }

    @Test
    fun `本地已删但已落到过滤范围外 → 静静遗忘，不删也不记`() {
        // 用户把排除项改成把 trunk/a 排掉了。这不是"文件没了"，
        // 当成删除去镜像的话，改一次配置就会让目标端掉一批文件。
        val cfg2 = cfg.copy(exclude = listOf("a.kt"))
        val p = contentPlan(
            srcNow = emptyMap(),
            baseline = mapOf("trunk/a.kt" to pair(10, 100, 10, 200)),
            dstNow = mapOf("trunk/a.kt" to s(10, 200)),
            scanDst = { emptyMap() },
            statDst = { null },
            compareMany = { emptyMap() },
            cfg = cfg2,
        )

        assertTrue(p.vanished.isEmpty(), "改配置不是删文件")
        assertTrue(p.nextBaseline.isEmpty(), "也不再记着了 —— 静静遗忘")
    }

    // ---------------------------------------------------------------- 删除安全闸

    @Test
    fun `安全闸：目标端那份还是我们同步过去的样子 → 可以删`() {
        assertTrue(mayDelete(pair(10, 100, 10, 200), s(10, 200)))
    }

    @Test
    fun `安全闸：目标端那份被人改过 → 不许删`() {
        assertFalse(mayDelete(pair(10, 100, 10, 200), s(10, 999)), "mtime 变了：那是运行机自己写的")
        assertFalse(mayDelete(pair(10, 100, 10, 200), s(11, 200)), "大小也变了")
    }

    @Test
    fun `安全闸：目标端本来就没有 → 不许删（没什么可删的）`() {
        assertFalse(mayDelete(pair(10, 100, 10, 200), null))
    }

    // ---------------------------------------------------------------- 空目录候选

    @Test
    fun `空目录候选：深的排在前面`() {
        val out = pruneCandidates(listOf("trunk/a/b/c.kt"), cfg)

        assertEquals(listOf("trunk/a/b", "trunk/a"), out, "先清 a/b 再清 a —— 反过来的话 a 那一刻还不空")
    }

    @Test
    fun `空目录候选：范围根自己不删`() {
        // trunk 是 syncRoots 里的那个根：那是用户在目标端的落脚点
        val out = pruneCandidates(listOf("trunk/a.kt"), cfg)

        assertTrue("trunk" !in out, "范围根不许进候选")
    }

    @Test
    fun `空目录候选：过滤命中的目录不进候选`() {
        val out = pruneCandidates(listOf("trunk/node_modules/x/y.js", "trunk/temp/a.txt"), cfg)

        assertTrue(out.none { it.startsWith("trunk/node_modules") }, "垃圾目录不参与同步，也就不去清它")
        assertEquals(
            listOf("trunk/temp"),
            out,
            "只剩 temp 自己那条 —— trunk 是范围根，不进候选（它不会因为我们清空目录而消失）",
        )
    }

    @Test
    fun `空目录候选：自己名字就是垃圾的目录也不进候选`() {
        // 这条钉的是 syncableDir 与 syncable 的差别：isJunk 只判祖先层，
        // 所以用 syncable 问 "trunk/build" 会说"不是垃圾"，这个目录就会溜进候选。
        val out = pruneCandidates(listOf("trunk/build/out.o"), cfg)

        assertTrue(out.none { it == "trunk/build" || it.startsWith("trunk/build/") }, "build 是垃圾目录名")
    }

    @Test
    fun `空目录候选：根自己永远不是候选`() {
        val out = pruneCandidates(listOf("a.kt"), cfg)

        assertTrue(out.none { it.isEmpty() }, "根目录是绝对不许碰的")
    }
}
