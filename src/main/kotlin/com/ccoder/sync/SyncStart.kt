package com.ccoder.sync

/**
 * "这次该不该把同步跑起来" —— **这一层唯一会被单测钉的逻辑**，其余都是接线。
 *
 * 抽出来的理由与 [contentPlan] 一样：把判定从平台壳子里拿出来，它就能被喂数据。
 * 而它要回答的三种"不启动"里，有两种是**必须讲清理由**的 ——
 *
 * - **没配好**：用户只填了一半。界面允许半填（不清空不报错），但引擎不许启动，
 *   而且要说出缺的是哪一项（[ConfigProblem]）
 * - **被别的窗口占着**：同一对目录同时只许一个窗口同步，否则两边互相删对方刚写的文件。
 *   要说清是**哪个项目**占着（[SyncStart.Occupied.by]），否则用户只会看到"不工作"
 * - **配好了就启动**
 */
internal sealed interface SyncStart {

    /** 可以跑。 */
    data object Start : SyncStart

    /** 配置不齐。 */
    data class Cannot(val reason: ConfigProblem) : SyncStart

    /** 被另一个活着的 IDE 窗口占着。`by` 是那个窗口的项目路径（可能为空）。 */
    data class Occupied(val by: String) : SyncStart
}

/**
 * 判定。
 *
 * @param claim [SyncClaim.tryAcquire] 的结果 —— 注意它**已经顺手认领过了**：
 *   返回 [SyncClaim.Result.Acquired] 表示认领文件现在写的是我们。
 */
internal fun syncStartDecision(cfg: SyncConfig, claim: SyncClaim.Result): SyncStart {
    // 先看配置：没配好的话连认领都不该去争
    configProblem(cfg)?.let { return SyncStart.Cannot(it) }
    return if (claim is SyncClaim.Result.Occupied) {
        SyncStart.Occupied(claim.owner.project)
    } else {
        SyncStart.Start
    }
}

/**
 * 改配置之后要不要把**整套**重启（含重新注册看盘）。
 *
 * 这一条是为了让"时长"和"镜像删除"能**当场生效而不用重启**：设置页是改动即写的，
 * 没有保存按钮 —— 若改什么都重启，用户在"静默期"里敲三个字符就是三次重新注册看盘，
 * 而在映射网络盘上那一次是 3.4 秒（探针实测 1391 个目录）。
 *
 * 反过来，下面这几项**必须**重启，因为它们决定的是"哪些路径被看着"：
 *
 * - [SyncConfig.src] / [SyncConfig.dst]：换了一对目录，连基线与认领文件都换了一份
 * - [SyncConfig.syncRoots] / [SyncConfig.exclude] / [SyncConfig.junk]：看盘的注册
 *   只在启动时做一次（`node_modules` 那种目录是**不下探**的），所以改了得重新注册
 * - [SyncConfig.enabled]：开关本身
 *
 * 不需要重启的：[SyncConfig.settleMs] / [SyncConfig.sweepMs]（调度器每轮现读）
 * 与 [SyncConfig.deleteMissing]（引擎每轮现读）。
 */
internal fun needsRestart(old: SyncConfig?, new: SyncConfig): Boolean {
    if (old == null) return true
    return old.enabled != new.enabled ||
        old.src != new.src ||
        old.dst != new.dst ||
        old.syncRoots != new.syncRoots ||
        old.exclude != new.exclude ||
        old.junk != new.junk
}
