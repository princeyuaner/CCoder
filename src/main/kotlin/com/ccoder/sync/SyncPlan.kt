package com.ccoder.sync

/**
 * 对账计划：**这一轮该复制什么、该删什么、新基线长什么样**。
 *
 * 这是整个功能的判定中枢，逐条照搬参考实现 `sync_remote.py` 的 `content_plan()`，
 * 只有一处结构上的改动：参考实现直接调 `target.compare_many()`（I/O 内建在里面），
 * 这里把三个 I/O 口子**注入**进来（`scanDst` / `statDst` / `compareMany`），
 * 于是这个函数的每一个判定分支都能用假数据跑到 —— 同仓库里 `scanTree(listdir)` 与
 * `FileResolver` 的既有做法。
 *
 * ## 判定表（顺序即优先级）
 *
 * | 情况 | 处理 |
 * |---|---|
 * | 两侧的 (大小, mtime) 都与基线**逐位相同** | 跳过。稳态下这是绝大多数 |
 * | 目标缺失 / **大小**不同 | 直接复制，**不读内容** |
 * | 大小相同但状态变过 | 才真读内容比对 |
 * | 内容一致、只是 mtime 不同 | 不动，只把"已核实一致"记回基线 |
 * | 基线里有、本地没了、**且仍在过滤范围内** | 候选镜像删除（还要过 [mayDelete] 那道闸） |
 * | 基线里有、本地没了、但已落到过滤范围外 | 静静遗忘——是用户改了配置，不是文件没了 |
 */

/** 一个文件在某一侧的 (大小, mtime)。mtime 用纳秒：**秒级精度不足以区分同一秒内的两次写**。 */
internal data class FileStamp(val size: Long, val mtimeNs: Long)

/** 基线表的一条：上次**已核实**的双方状态。 */
internal data class FilePair(val src: FileStamp, val dst: FileStamp)

/** 为什么要复制。两个子原因分开是因为**报告要讲人话**（"目标端没有"和"内容不一样"是两件事）。 */
internal enum class CopyWhy {
    /** 目标端没有这个文件，或大小就对不上——不必读内容就能定。 */
    TARGET_MISSING_OR_SIZE,

    /** 大小一样但状态变过，真读了内容才断定不同。 */
    CONTENT_DIFFERS,
}

/** 为什么要删。只有一个来源了（svn 通道已砍），留成枚举是为了报告与测试能指名道姓。 */
internal enum class DeleteWhy {
    /** 本地删了、基线里还有、且目标是"我们同步过去的样子"。 */
    VANISHED,
}

/** 明知本地删了却**没删**的原因。两种都要说出来——沉默地不删比删错更难查。 */
internal enum class KeptWhy {
    /** 目标端那份已被改动过（不是我们同步过去的样子）：可能是运行机的产物。 */
    TARGET_MODIFIED,

    /** 配置里关了镜像删除。 */
    AUTO_DELETE_OFF,
}

internal data class CopyItem(val rel: String, val why: CopyWhy)

internal data class KeptItem(val rel: String, val why: KeptWhy)

/** 一轮的计划。**只描述要做什么，不做任何 I/O**。 */
internal data class ContentPlan(
    val copies: List<CopyItem>,
    /** 候选删除（已过"仍在过滤范围内"那一关，**还没过** [mayDelete] 那道安全闸）。 */
    val vanished: Map<String, FilePair>,
    /** 双方状态都被核实过、可以直接写进新基线的。 */
    val nextBaseline: Map<String, FilePair>,
    /** 已核实一致的个数（稳态下就是 [nextBaseline] 的大小，单列出来是为了报告能单独说一句）。 */
    val unchangedCount: Int,
    /** 没有基线（首次运行，或基线被删）→ 这一轮无论如何都要全量核对，报告要提示"可能要一两分钟"。 */
    val firstFullReconcile: Boolean,
)

/**
 * 按内容核对本地与目标，产出一轮的计划。
 *
 * @param srcNow 本地扫描结果（已过三层过滤）。
 * @param baseline 上次的内容基线。
 * @param dstNow 目标端扫描结果；**null 表示本轮不遍历目标端**（事件轮，见 [SyncScheduler]）。
 * @param scanDst 兜底：没有基线时"不遍历目标端"反而更慢（逐文件 stat 一次 vs 整树遍历一次，
 *   参考实现实测 33 秒 vs 1.7 秒），所以那种情况下改成一整趟扫描。**只有需要时才会被调用。**
 * @param statDst 按需问目标端单个文件的状态。
 * @param compareMany 并发比对一批文件的内容，返回 `rel → 是否完全一致`。
 */
internal fun contentPlan(
    srcNow: Map<String, FileStamp>,
    baseline: Map<String, FilePair>,
    dstNow: Map<String, FileStamp>?,
    scanDst: () -> Map<String, FileStamp>,
    statDst: (String) -> FileStamp?,
    compareMany: (List<String>) -> Map<String, Boolean>,
    cfg: SyncConfig,
): ContentPlan {
    val first = baseline.isEmpty()
    val dst = dstNow ?: if (first) scanDst() else null

    val next = LinkedHashMap<String, FilePair>()
    val copies = mutableListOf<CopyItem>()
    /** 大小相同、状态变过 —— 必须读内容才能定夺的那批。 */
    val pairs = mutableListOf<String>()
    /** 本轮问过的目标端状态。仅"不遍历目标端"时用得上：定完 [pairs] 之后还要拿它记基线。 */
    val asked = HashMap<String, FileStamp>()

    for ((rel, s) in srcNow) {
        val rec = baseline[rel]

        val d: FileStamp?
        if (dst != null) {
            // 遍历过目标端：直接查表
            d = dst[rel]
        } else if (rec != null && rec.src == s) {
            // 不遍历目标端，而本地这边与基线逐位相同 → 目标端沿用基线里记的旧值，不碰网络。
            // **这正是"不遍历"的代价所在**：目标端那份被人改过就发现不了（见 SyncScheduler）。
            next[rel] = rec
            continue
        } else {
            d = statDst(rel)
            if (d != null) asked[rel] = d
        }

        if (d != null && rec != null && rec.src == s && rec.dst == d) {
            next[rel] = rec               // 双方都没动过 —— 稳态下走这条
            continue
        }
        if (d == null || d.size != s.size) {
            // 目标缺失 / 大小不同 → 直接复制。**不读内容**是刻意的：
            // 大小都对不上就没有读 1MB 去确认的必要了。
            copies += CopyItem(rel, CopyWhy.TARGET_MISSING_OR_SIZE)
            continue
        }
        pairs += rel                      // 大小相同但状态变过 → 读内容定夺
    }

    var same = next.size
    if (pairs.isNotEmpty()) {
        val verdict = compareMany(pairs)
        for (rel in pairs) {
            if (verdict[rel] != true) {
                copies += CopyItem(rel, CopyWhy.CONTENT_DIFFERS)
                continue
            }
            val s = srcNow[rel] ?: continue
            val d = if (dst != null) dst[rel] else asked[rel]
            // 防御：进 [pairs] 的前提就是"这一侧拿到了目标端状态"，所以 d 理论上必然非空。
            // 真为 null 就不记基线（下轮重新发现即可）—— 记一条对不上的基线比不记更糟。
            if (d == null) continue
            next[rel] = FilePair(s, d)
            same++
        }
    }

    val vanished = LinkedHashMap<String, FilePair>()
    for ((rel, rec) in baseline) {
        if (rel in srcNow) continue
        // 历史条目落到过滤规则之外 = **用户改了范围或排除项**，不是文件没了。
        // 这种情况静静遗忘（不删、也不再记着），否则改一次排除项就会让目标端掉一批文件。
        if (!syncable(rel, cfg)) continue
        vanished[rel] = rec
    }

    return ContentPlan(
        copies = copies,
        vanished = vanished,
        nextBaseline = next,
        unchangedCount = same,
        firstFullReconcile = first,
    )
}

/**
 * 镜像删除的安全闸：目标端那份**还是我们同步过去的样子**吗。
 *
 * 判据是"目标端现在的 (大小, mtime) 与基线里记的目标端值逐位相同"——参考实现的
 * `if rec[2] == st[0] and rec[3] == st[1]`。含义：自打我们把它复制过去之后，
 * 没有任何别的东西碰过它。
 *
 * **这是整个功能安全性的来源。** 目标端往往是有自己运行产物的机器（日志、缓存、
 * 运行中生成的文件）——那些东西从不在源端出现，因此从不在基线里，因此**永远不会被删**。
 * 而"曾经同步过去、后来被那台机器自己改过"的文件，也在这道闸上被拦下来（只提醒不删）。
 *
 * @param baselineRec 基线里记的那条（我们同步过去时的双方状态）
 * @param currentDst 目标端**现在**的状态；null = 目标端本来就没有，没什么可删的
 * @return true 表示可以删
 */
internal fun mayDelete(baselineRec: FilePair, currentDst: FileStamp?): Boolean =
    currentDst != null && baselineRec.dst == currentDst

/**
 * 把"这次删掉的路径"折算成"可能因此变空的父目录"列表（**深的排在前面**）。
 *
 * 三道收窄，全是为了"只删真正因此变空的目录"：
 * 1. 只考虑**这次删除涉及到的**父目录链——不碰别的目录
 * 2. 范围根（`trunk` 这种）自己不删：那是用户在目标端的落脚点
 * 3. 过滤规则命中的目录不删：它们本来就不参与同步
 *
 * 真正"是不是空的"由执行层判断（[SyncTarget.prune]）——这里只给候选。
 * 参考实现把这段与 [mayDelete] 一样放在共用位置，本地后端与将来的远端后端**同一套策略**。
 */
internal fun pruneCandidates(deletedRels: List<String>, cfg: SyncConfig): List<String> {
    val level = LinkedHashSet<String>()
    for (rel in deletedRels) {
        val norm = normalizeRelPath(rel)
        var parent = norm.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            level += parent
            parent = parent.substringBeforeLast('/', "")
        }
    }
    return level
        // 深的先删：先清 a/b/c 再清 a/b，否则 a/b 那一刻还不空
        .sortedByDescending { it.length }
        .filter { rel ->
            // 范围根本身不删（那是用户在目标端的落脚点）。
            // 注意这里用的是 **syncableDir**：`trunk/build` 这种"自己名字就是垃圾"的
            // 目录，用 syncable 问会说不是垃圾（isJunk 只判祖先层）。
            normalizeRelPath(rel) !in cfg.syncRoots && syncableDir(rel, cfg)
        }
}
