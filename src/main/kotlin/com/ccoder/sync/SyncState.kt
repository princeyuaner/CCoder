package com.ccoder.sync

/**
 * 落盘用的**可变镜像**（`@State` 要的形状：全是 `var`、集合是 `Mutable*`、每个字段都有默认值）。
 *
 * ## 为什么它是 public 而 [SyncConfig] 不是
 *
 * 平台需要 `SyncSettings` 公开 `getState()` / `loadState()`（那是 `PersistentStateComponent`
 * 的接口方法，不能降可见性），而它们提到 [SyncState] —— 所以**只有这一个类必须公开**。
 * 领域类型（[SyncConfig] / [JunkRules]）靠把服务的那些成员声明成 `internal` 挡住，
 * 同 `PendingPermissionCount` 的做法（它的公开面只留原始量）。
 *
 * ## 为什么不让 [SyncConfig] 直接当持久化类型
 *
 * [SyncConfig] 是**不可变**的，那条性质让纯层的每一处都少想一件事（配置不会被谁顺手改掉）。
 * 而 `XmlSerializer` 认的是"无参构造 + 可变字段"。两边的差别很小，所以这里放一个镜像，
 * 由 [toState] / [toConfig] 一对一搬运 —— 而**那对函数是这一类改动最容易出错的地方**：
 * 加了字段却忘了搬，症状是"重启之后我设的东西自己变回去了"，静默、且极难查
 * （仓库里 `ClaudeSettings` 的注释记着这个坑）。所以 `SyncStateTest` 里有一条**往返用例**
 * 钉死它：把每个字段都填上不一样的值，转一圈再比。
 *
 * ## 一处刻意的约定：空表 = 用当前默认
 *
 * `junk*` 四组是内置的噪音清单（本版不进界面，见 [JunkRules]）。它们**存进配置**，
 * 于是将来要暴露给用户改时不用改代码。但"存了"会带来另一个问题：默认值以后改了，
 * 老配置里的旧清单会一直压着新默认。
 *
 * 所以约定：**某一组是空表时按当前默认处理**（[toConfig] 那三行）。于是没动过的人
 * 自动跟上新默认，动过的人保留自己的。代价是"我想把某一组清空"表达不了 ——
 * 那条路真要做，得给个别的开关，不该用空表当哨兵。
 */
data class SyncState(
    var enabled: Boolean = false,
    var src: String = "",
    var dst: String = "",
    var syncRoots: MutableList<String> = mutableListOf(),
    var exclude: MutableList<String> = mutableListOf(),
    var settleMs: Long = SyncConfig.DEFAULT_SETTLE_MS,
    var sweepMs: Long = SyncConfig.DEFAULT_SWEEP_MS,
    var deleteMissing: Boolean = true,
    var junkDirNames: MutableList<String> = JunkRules.DEFAULT_DIR_NAMES.toMutableList(),
    var junkFileNames: MutableList<String> = JunkRules.DEFAULT_FILE_NAMES.toMutableList(),
    var junkSuffixes: MutableList<String> = JunkRules.DEFAULT_SUFFIXES.toMutableList(),
    var junkPrefixes: MutableList<String> = JunkRules.DEFAULT_PREFIXES.toMutableList(),
)

/**
 * 镜像 → 领域对象，**顺手归一**（[normalizeConfig]）。
 *
 * 归一放在这里而不是放在调用点：服务、设置页、引擎读到的配置因此都是规整过的，
 * "某个入口忘了归一"这一类不一致就不会发生。
 */
internal fun SyncState.toConfig(): SyncConfig = normalizeConfig(
    SyncConfig(
        enabled = enabled,
        src = src,
        dst = dst,
        syncRoots = syncRoots.toList(),
        exclude = exclude.toList(),
        settleMs = settleMs,
        sweepMs = sweepMs,
        deleteMissing = deleteMissing,
        junk = JunkRules(
            dirNames = junkDirNames.ifEmpty { JunkRules.DEFAULT_DIR_NAMES },
            fileNames = junkFileNames.ifEmpty { JunkRules.DEFAULT_FILE_NAMES },
            suffixes = junkSuffixes.ifEmpty { JunkRules.DEFAULT_SUFFIXES },
            prefixes = junkPrefixes.ifEmpty { JunkRules.DEFAULT_PREFIXES },
        ),
    ),
)

/** 领域对象 → 镜像。**逐字段搬，一个都不许省** —— 见类头注。 */
internal fun SyncConfig.toState(): SyncState = SyncState(
    enabled = enabled,
    src = src,
    dst = dst,
    syncRoots = syncRoots.toMutableList(),
    exclude = exclude.toMutableList(),
    settleMs = settleMs,
    sweepMs = sweepMs,
    deleteMissing = deleteMissing,
    junkDirNames = junk.dirNames.toMutableList(),
    junkFileNames = junk.fileNames.toMutableList(),
    junkSuffixes = junk.suffixes.toMutableList(),
    junkPrefixes = junk.prefixes.toMutableList(),
)
