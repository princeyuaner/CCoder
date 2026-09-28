package com.ccoder.sync

import java.nio.file.Path

/**
 * 一轮同步的编排。逐条对应参考实现 `sync_remote.py` 的 `run()`，砍掉了 svn 通道之后
 * 剩下的那部分：**复制串行、删除深的先、只清真的空了的目录、基线落盘时失败项不记**。
 *
 * 报告沿用参考实现的五段分法（待复制 / 待删除 / 跳过 / 提醒 / 失败）——那五段是踩出来的：
 * 用户问"我改的东西怎么没同步"时，答案一定在"跳过"或"提醒"里，而且**必须写清原因**
 * （"不在同步范围内"和"目标端那份已被改动过"是完全不同的两件事）。
 *
 * ## 为什么引擎只依赖 [SyncTarget] 接口
 *
 * 这样整个编排就能对着一个内存假目标端跑单测（不用碰真磁盘，也不用为了测"复制失败"
 * 去真的制造一个坏文件），而将来接 SSH 时**引擎一个字都不用改**。
 */
internal class SyncEngine(
    private val srcRoot: Path,
    private val target: SyncTarget,
    /**
     * **每次读**，不是构造时取一次。设置页是"改动即写"的，取一次意味着改一个字段就得
     * 重启整套（在映射网络盘上重启一次看盘要 3.4 秒，而用户在"静默期"框里敲三个字符
     * 就是三次）。结构性的改动（源/目标目录、范围、排除项）仍然要重启看盘，
     * 那个判定在 `SyncService` 里（`needsRestart`）。
     */
    private val cfg: () -> SyncConfig,
    private val baselinePath: Path,
    private val dryRun: Boolean = false,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** 源端的身份串：绝对路径归一化。与 [SyncTarget.stateKey] 一起构成基线的身份。 */
    private val srcKey: String = srcRoot.toAbsolutePath().normalize().toString()

    /**
     * 跑一轮。
     *
     * @param reconcileTarget 要不要**遍历目标端**。false = 只对账本地侧（事件轮，秒级响应）；
     *   true = 两侧都对账（基线轮与巡检轮）。注意这与"本地内容对账"无关：本地文件内容变了
     *   在任何一轮都会被发现并推送，见 [contentPlan]。
     * @throws SyncError 目标端不可访问（调用方退避后重试，进程不退出）
     */
    fun runRound(reconcileTarget: Boolean): RoundReport {
        val t0 = nowMs()
        val conf = cfg()
        target.check()

        val srcNow = scanTree(srcRoot, conf)
        val baseline = SyncBaseline.load(baselinePath, srcKey, target.stateKey)
        val dstNow = if (reconcileTarget) target.scan() else null

        if (baseline.isEmpty()) {
            log("[内容对账] 尚无基线（首次运行，或基线被删）：要对 ${srcNow.size} 个文件做一次全量核对，可能要一两分钟")
        }
        val plan = contentPlan(
            srcNow = srcNow,
            baseline = baseline,
            dstNow = dstNow,
            scanDst = { target.scan() },
            statDst = { target.stat(it) },
            compareMany = { rels ->
                target.compareMany(srcRoot, rels) { done, total -> log("[内容对账] 已比对 $done/$total …") }
            },
            cfg = conf,
        )

        // ---- 删除：先过安全闸（[mayDelete]），过不去的进"提醒" ----
        val deletes = mutableListOf<String>()
        val kept = mutableListOf<KeptItem>()
        for ((rel, rec) in plan.vanished) {
            if (!conf.deleteMissing) {
                kept += KeptItem(rel, KeptWhy.AUTO_DELETE_OFF)
                continue
            }
            val current = target.stat(rel)
            if (current == null) continue          // 目标端本来就没有，没什么可删的
            if (mayDelete(rec, current)) {
                deletes += rel
            } else {
                kept += KeptItem(rel, KeptWhy.TARGET_MODIFIED)
            }
        }

        logPending(plan, deletes)

        if (dryRun) {
            log("[dry-run] 以上为预览，未执行任何复制或删除。")
            return RoundReport(
                plannedCopies = plan.copies.size,
                plannedDeletes = deletes.size,
                copied = 0,
                deleted = 0,
                absent = 0,
                failedCopies = emptyList(),
                failedDeletes = emptyList(),
                pruned = emptyList(),
                kept = kept,
                unchanged = plan.unchangedCount,
                firstFullReconcile = plan.firstFullReconcile,
                copies = plan.copies,
                deletes = deletes,
                copiedRel = emptyList(),
                deletedRel = emptyList(),
                baselineSaved = false,
                baselineSize = baseline.size,
                costMs = nowMs() - t0,
            )
        }

        // ---- 复制：**串行，单写者** ----
        // 并发只用在只读的内容比对（[LocalTarget.compareMany]）上。写这条路上同时只有一个
        // 写者，是"不会出现两轮扫描/复制并发"这条保证的最后一道 —— 见 SyncScheduler 的
        // "只有一个消费者"。
        var copied = 0
        val failedCopies = mutableListOf<Failed>()
        val copiedRel = mutableListOf<String>()
        for (item in plan.copies) {
            try {
                target.copyFrom(srcRoot.resolve(item.rel), item.rel)
                copied++
                copiedRel += item.rel
            } catch (e: Exception) {
                failedCopies += Failed(item.rel, e.message ?: e.toString())
            }
        }

        // ---- 删除：深的先（`svn delete` 目录时目录与内容都会列出，先删文件更干净）----
        var deleted = 0
        var absent = 0
        val failedDeletes = mutableListOf<Failed>()
        val deletedRel = mutableListOf<String>()
        for (rel in deletes.sortedByDescending { it.length }) {
            try {
                if (target.remove(rel)) {
                    deleted++
                    deletedRel += rel
                } else {
                    absent++
                }
            } catch (e: Exception) {
                failedDeletes += Failed(rel, e.message ?: e.toString())
            }
        }

        // ---- 清空目录：只清"因此变空的"那些 ----
        val pruned = if (deletes.isEmpty()) emptyList() else target.prune(pruneCandidates(deletes, conf))

        // ---- 基线落盘 ----
        // 记的是**复制完之后双方的真实状态**，而不是计划时的推断：这样下一轮凭
        // (大小, mtime) 就能直接跳过。三条例外：
        //   - 失败的项**不记** → 下一轮重新发现并重试
        //   - 删失败的项**留着旧记录** → 同理，下一轮重试
        //   - dry-run 不写 → 预览不该改状态
        val next = LinkedHashMap(plan.nextBaseline)
        val failedRels = HashSet<String>()
        failedCopies.forEach { failedRels += it.rel }
        failedDeletes.forEach { failedRels += it.rel }
        for (item in plan.copies) {
            if (item.rel in failedRels) continue
            val s = stampOf(srcRoot.resolve(item.rel)) ?: continue
            val d = target.stat(item.rel) ?: continue
            next[item.rel] = FilePair(s, d)
        }
        for (f in failedDeletes) {
            plan.vanished[f.rel]?.let { if (f.rel !in next) next[f.rel] = it }
        }
        val saved = next != baseline
        if (saved) SyncBaseline.save(baselinePath, srcKey, target.stateKey, next)

        val report = RoundReport(
            plannedCopies = plan.copies.size,
            plannedDeletes = deletes.size,
            copied = copied,
            deleted = deleted,
            absent = absent,
            failedCopies = failedCopies,
            failedDeletes = failedDeletes,
            pruned = pruned,
            kept = kept,
            unchanged = plan.unchangedCount,
            firstFullReconcile = plan.firstFullReconcile,
            copies = plan.copies,
            deletes = deletes,
            copiedRel = copiedRel,
            deletedRel = deletedRel,
            baselineSaved = saved,
            baselineSize = next.size,
            costMs = nowMs() - t0,
        )
        logResult(report)
        return report
    }

    /** 把"要做什么"先说出来 —— 参考实现的顺序：先列清单，再动手。 */
    private fun logPending(plan: ContentPlan, deletes: List<String>) {
        log("[待复制] ${plan.copies.size} 个文件，[待删除] ${deletes.size} 项")
        logSample("复制", plan.copies.map { "(${it.why}) ${it.rel}" })
        logSample("删除", deletes.map { "(本地已删) $it" })
        if (plan.unchangedCount > 0) log("[已核实] 两侧一致 ${plan.unchangedCount} 个")
    }

    private fun logResult(r: RoundReport) {
        if (r.nothingToDo) {
            log("[完成] 没有需要同步的内容，目标端与本地一致（用时 ${r.costMs} ms）")
            return
        }
        val prunedNote = if (r.pruned.isNotEmpty()) "，顺带清掉 ${r.pruned.size} 个空目录" else ""
        log(
            "[完成] 复制 ${r.copied}/${r.plannedCopies}，删除 ${r.deleted} 项" +
                "（${r.absent} 项目标端本不存在）$prunedNote，失败 ${r.failedTotal} 项（用时 ${r.costMs} ms）",
        )
        for (f in r.failedCopies) log("  [失败] 复制 ${f.rel} — ${f.reason}")
        for (f in r.failedDeletes) log("  [失败] 删除 ${f.rel} — ${f.reason}")
    }

    /** 清单只列前几条：一次几百个文件时，全列出来会把日志和界面都淹掉。 */
    private fun logSample(title: String, items: List<String>) {
        val head = items.take(SAMPLE_LIMIT)
        for (line in head) log("  $line")
        if (items.size > head.size) log("  … 其余 ${items.size - head.size} 个${title}略")
    }

    private companion object {
        /** 同参考实现 `[提醒]` 段那个截断（它截 10 条）。 */
        const val SAMPLE_LIMIT = 10
    }
}

/** 一项失败：相对路径 + 原因。原因要上屏，不能只留"失败了"三个字。 */
internal data class Failed(val rel: String, val reason: String)

/**
 * 一轮报告。
 *
 * "计划 N"与"实际 M"分开记：只报实际的话，"计划了 500 个但只复制了 3 个"这种
 * （中途目标盘掉线）看起来会像"本来就没事做"。
 */
internal data class RoundReport(
    val plannedCopies: Int,
    val plannedDeletes: Int,
    val copied: Int,
    val deleted: Int,
    /** 删除时目标端原本就不存在 —— 既不是成功也不是失败，单列。 */
    val absent: Int,
    val failedCopies: List<Failed>,
    val failedDeletes: List<Failed>,
    val pruned: List<String>,
    /** 本地已删却**没删**目标端的（带原因）：这是最需要被看见的一段。 */
    val kept: List<KeptItem>,
    val unchanged: Int,
    val firstFullReconcile: Boolean,
    val copies: List<CopyItem>,
    val deletes: List<String>,
    /**
     * **真的复制过去的**那些（相对路径，按做的顺序）。
     *
     * 与 [copies] 是两回事：那个是**计划**（含后来失败的），这个是做成的。
     * 加它是因为同步气泡要说"同步了哪几个文件"—— 拿计划去说就会把没过去的也报成过去了
     * （"我改的东西怎么没同步"这种问题，最忌讳界面替引擎撒谎）。
     */
    val copiedRel: List<String>,
    /** **真的删掉的**那些。[deletes] 里还含"目标端本来就没有"与删失败的。 */
    val deletedRel: List<String>,
    val baselineSaved: Boolean,
    /** 落盘后基线的大小（没落盘时是上一轮的）。 */
    val baselineSize: Int,
    val costMs: Long,
) {
    val failedTotal: Int get() = failedCopies.size + failedDeletes.size

    /** 这一轮算不算干净 —— 调度器据此决定要不要退避重试。 */
    val ok: Boolean get() = failedTotal == 0

    val nothingToDo: Boolean get() = plannedCopies == 0 && plannedDeletes == 0
}
