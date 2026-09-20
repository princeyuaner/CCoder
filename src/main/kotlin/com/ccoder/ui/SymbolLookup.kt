package com.ccoder.ui

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ChooseByNameContributorEx2
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.ccoder.text.CcoderText

// 符号引用的 IDE 面。**不可单测**（要 Project / PSI / 索引），照 collectProjectFiles 的成例：
// 这一层只做"把平台的东西变成纯数据"，判据与排版全在 SymbolCandidates.kt 里测。
//
// ---- 线程与读操作（改这里之前先读这三条）----
//
//  1. 本文件的每一个函数**必须在读操作里调用**（`ReadAction.compute`），且**不在 EDT 上**
//     —— 索引查询会阻塞 EDT，那正是这条路要避开的卡顿。
//  2. **PSI 元素 / NavigationItem 一律不许带出读操作**：出口只有 [SymbolHit] 这份纯数据。
//     因此不需要 SmartPointer，也不需要事后再解析 —— 采纳时用的是快照。
//  3. 后台线程不碰面板字段（`completionItems` 那些）；结果经 invokeLater 回 EDT。

/** 一次符号搜索的结果。**失败要说出来**，不许静默变成"没找到"。 */
internal data class SymbolSearchResult(
    /** 解析好的符号（已带记号与源码），可直接成行。 */
    val hits: List<SymbolHit>,
    /**
     * 本次**枚举**到的全部符号名；`null` = 这次用的是外面的缓存。
     *
     * 枚举是这条路上最贵的一步，而它与**查询无关**（见下面的 `processNames` 说明），
     * 所以由调用方按"补全会话"缓存 —— 与 `projectFiles` 同一种做法，只是活得更久。
     */
    val names: List<String>?,
    /** 前缀命中的名字数（解析**之前**）—— 用来区分"根本没这个名字"与"有但解析不出来"。 */
    val matched: Int,
    /**
     * 只该出现在状态行里的说明（今天只有一种：索引还在建）。
     *
     * 与 [failure] 分开是因为它**不是失败**：不用弹气球，等一会儿自然就好了。
     * 但也不能不说 —— 那时一个候选都没有，"什么都没发生"与"这个符号不存在"在屏幕上一样。
     */
    val status: String? = null,
    /** 非空 = 这次没成（索引读不了 / 贡献者抛错），界面必须把它说出来。 */
    val failure: String? = null,
)

private val LOG = Logger.getInstance("com.ccoder.ui.SymbolLookup")

/** 一次枚举最多收多少条**命中**的名字。到顶就停：弹层只有 8 行，收再多也没人看得见。 */
private const val SCAN_CAP = 200

/** 枚举的软预算。再大的项目也不该让第一次敲 `#` 等上几秒。 */
private const val SCAN_BUDGET_MS = 2_000L

/**
 * 查符号：枚举名字 → 前缀过滤 → 解析成 [SymbolHit]。
 *
 * **必须在读操作里、且不在 EDT 上调用**（见文件头）。
 *
 * 贡献者的三种形态按平台自己的分派来（`ContributorsBasedGotoByModel` 就是这三条分支），
 * 不另立一套：
 *
 * | 形态 | 取名字 | 取元素 |
 * |---|---|---|
 * | `Ex2` | `processNames(processor, params)`（带 pattern，索引级收窄） | `processElementsWithName` |
 * | `Ex` | `processNames(processor, params.searchScope, params.idFilter)`（**全量**） | 同上 |
 * | 老的 `ChooseByNameContributor` | `getNames(project, false)`（**全量**） | `getItemsByName` |
 *
 * **PyCharm 里只有第二种**（`PyGotoSymbolContributor implements ChooseByNameContributorEx`，
 * 2026-09-15 在 2025.3.1.1 的 jar 上验过），所以主路径就是"枚举一次、自己按前缀滤"——
 * 这也正是 [SymbolSearchResult.names] 值得缓存的原因。`Ex2` 那条留着是因为别的 IDE /
 * 别的语言插件会走它，而它的实现形状与这里完全一样。
 *
 * [cachedNames] 非空时**跳过枚举**（名字与查询无关，第一次之后都该命中缓存）。
 */
internal fun searchSymbols(
    project: Project,
    prefix: String,
    cachedNames: List<String>?,
): SymbolSearchResult {
    val started = System.currentTimeMillis()
    val dumb = DumbService.getInstance(project)

    val contributors = runCatching {
        ChooseByNameContributor.SYMBOL_EP_NAME.extensionList
            // 逐个问"现在能用吗"，而不是一句 isDumb 把整条路掐掉：
            // 声明了 DumbAware 的贡献者在索引期间照样能答（Python 那个就是）。
            .filter { dumb.isUsableInCurrentContext(it) }
    }.getOrElse {
        LOG.warn("符号：取贡献者列表失败", it)
        return SymbolSearchResult(
            hits = emptyList(),
            names = cachedNames,
            matched = 0,
            failure = CcoderText.text("composer.symbol.indexUnavailable", it.javaClass.simpleName),
        )
    }

    // 索引还在建：声明了 DumbAware 的贡献者仍然可用，一个都没剩下就说明整个索引
    // 还没就绪。这时候**不能静默** —— 一个候选都没有的界面，与"这个符号不存在"
    // 长得一模一样（与 `@` 文件那条的差别：文件那条 dumb 态下也是空的，但它不弹层，
    // 用户不会以为自己在等什么；这里我们已经开层了）
    if (contributors.isEmpty() && dumb.isDumb) {
        return SymbolSearchResult(
            hits = emptyList(),
            names = cachedNames,
            matched = 0,
            status = CcoderText.text("composer.symbol.indexing"),
        )
    }

    // 前缀进的是 Ex2 的 pattern 通道，而那边 `*` 是通配符 —— 净化掉再传。
    // 净化后为空就别问了（`#*` 本来也不是个查询）。
    val pattern = prefix.filterNot { it == '*' }
    val params = FindSymbolParameters.wrap(pattern, project, false)

    var scanned = 0
    var ex2 = 0
    var ex = 0
    var legacy = 0

    val names: List<String> = cachedNames ?: run {
        val collected = LinkedHashSet<String>()
        var matched = 0
        val deadline = started + SCAN_BUDGET_MS
        val collector = Processor<String> { name ->
            if (name.isBlank()) return@Processor true
            scanned++
            if (name.lowercase().startsWith(prefix.lowercase())) {
                collected += name
                matched++
                // 到顶就停：命中数已经够铺满弹层了
                if (matched >= SCAN_CAP) return@Processor false
            }
            // 未命中也要看一眼预算：大项目里"扫过去"本身就有成本
            System.currentTimeMillis() < deadline
        }

        for (contributor in contributors) {
            runCatching {
                when (contributor) {
                    is ChooseByNameContributorEx2 -> {
                        ex2++
                        contributor.processNames(collector, params)
                    }
                    is ChooseByNameContributorEx -> {
                        ex++
                        contributor.processNames(collector, params.searchScope, params.idFilter)
                    }
                    else -> {
                        legacy++
                        contributor.getNames(project, false).forEach { if (!collector.process(it)) return@forEach }
                    }
                }
            }.onFailure {
                // 一个语言的贡献者炸了，不该让整条路空掉
                LOG.warn("符号：贡献者 ${contributor.javaClass.name} 抛错，跳过", it)
            }
            if (collected.size >= SCAN_CAP) break
        }
        collected.toList()
    }

    val nameList = names ?: emptyList()
    // 两个数分开数：2026-09-16 起候选是**子序列**收的，前缀命中只是一部分 ——
    // 混成一个数的话，探针日志会让人以为"候选比命中的还多"
    val prefixMatched = nameList.count { it.lowercase().startsWith(prefix.lowercase()) }
    val toResolve = rankSymbolHits(nameList, prefix)
    val matched = toResolve.size
    val hits = toResolve.mapNotNull { resolve(project, params, contributors, it) }

    val elapsed = System.currentTimeMillis() - started
    // 探针（设计稿 §1.7 靠它回填实测表，可重跑）：敲 # 之后到 idea.log 里读这一行
    LOG.info(
        "符号探针：prefix='$prefix'（${prefix.length} 字符）" +
            " 枚举看到 $scanned 条名字（Ex2=$ex2 Ex=$ex legacy=$legacy，缓存=${cachedNames != null}），" +
            " 前缀命中 $prefixMatched、子序列收进 $matched 条（解析前）→ 解析出 ${hits.size} 个，耗时 ${elapsed}ms",
    )

    val failure =
        if (hits.isEmpty() && toResolve.isNotEmpty()) {
            // 名字找得到、位置定不到：这不是"没有这个符号"，要说清楚
            CcoderText.text("composer.symbol.noLocation", toResolve.size)
        } else {
            null // 真的没有：不算失败（与 `@` 一致，安静地不弹）
        }
    return SymbolSearchResult(
        hits = hits,
        names = if (cachedNames == null) nameList else null,
        matched = matched,
        failure = failure,
    )
}

/**
 * 一个名字 → 一个符号（取**第一个**能用的元素）。
 *
 * 挨个贡献者问：平台自己记着"哪个名字是哪个贡献者给的"，我们没这份账，
 * 而多问几家只是一次索引查询的事（设计稿 §1.8 把"同名多义的选择 UI"划在了 v1 之外）。
 */
private fun resolve(
    project: Project,
    params: FindSymbolParameters,
    contributors: List<ChooseByNameContributor>,
    name: String,
): SymbolHit? {
    var hit: SymbolHit? = null
    val base = project.basePath ?: return null

    val processor = Processor<NavigationItem> { item ->
        val element = item as? PsiElement ?: return@Processor true
        val file = element.containingFile ?: return@Processor true
        val virtualFile = file.virtualFile ?: return@Processor true
        // 只要**项目内、能按相对路径说出来**的文件：记号最后要写进消息里，
        // 库里的符号（site-packages）说不出口，也就没法内联
        if (virtualFile.isDirectory || !params.searchScope.contains(virtualFile)) return@Processor true
        val relative = FileUtil.getRelativePath(base, virtualFile.path, '/') ?: return@Processor true

        val text = file.text
        val lines = selectionLineRange(text, element.textRange.startOffset, element.textRange.endOffset)
        hit = SymbolHit(
            name = name,
            path = relative,
            lines = lines,
            // **扩到整行**：PSI 的范围从 `def` 开始，直接把这段发出去会把 Python 首行的
            // 缩进切掉（发过去的就不是能跑的代码了）。行号与代码出自同一份文本，不会错位。
            code = linesText(text, lines),
            fileTypeName = file.fileType.name,
        )
        false // 找到第一个就停
    }

    for (contributor in contributors) {
        runCatching {
            when (contributor) {
                is ChooseByNameContributorEx2 -> contributor.processElementsWithName(name, processor, params)
                is ChooseByNameContributorEx -> contributor.processElementsWithName(name, processor, params)
                // 老接口的 getItemsByName 在 Ex 上标了 @Deprecated（默认实现转调上面那条），
                // 所以真正的老实现这条路只留给既不是 Ex 也不是 Ex2 的贡献者
                else -> contributor.getItemsByName(name, params.localPatternName, project, false)
                    .forEach { if (!processor.process(it)) return@forEach }
            }
        }.onFailure { LOG.warn("符号：${contributor.javaClass.name} 解析 '$name' 抛错", it) }
        if (hit != null) return hit
    }
    return null
}
