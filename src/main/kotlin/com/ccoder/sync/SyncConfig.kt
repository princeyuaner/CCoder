package com.ccoder.sync

/**
 * 一整套同步配置。**不可变**，且不含任何平台依赖 —— 于是它和规整函数都能起纯 JVM 单测。
 *
 * 落盘走项目级 `@State`（见 `SyncSettings`），那里会有个可变的镜像类；两者之间靠
 * 一对 `toState` / `fromState` 一对一搬运，并由一条**往返测试**钉住"漏字段"——仓库里
 * `ClaudeSettings` 的注释记着那个坑（漏一个字段 = 重启后静默回落默认值，
 * 而且是"我设的东西自己变回去了"这种最难查的形状）。往返测试把它变成会红的。
 *
 * ## 关于 `junk`：为什么内置而不是用户填
 *
 * 参考实现把它放在"高级，一般不用动"，因为它不是用户的业务规则，而是**噪音清单**
 * ——`.svn` 同步到目标端会破坏那边的 SVN 工作副本、日志家族是有意排除的运行产物。
 * 所以本版照抄它的四组（顺手把 M71 专有的那几项也留着：它们的实际效果就是"不复制
 * 这些噪音"，而用户真去同步 M71 时正合用），另加 JVM/IDE 生态的几项。
 */
internal data class SyncConfig(
    /** 总开关。**不配置就不开启**：这个、[src]、[dst] 三者缺一，引擎都不启动。 */
    val enabled: Boolean = false,
    /** 本地源目录（绝对路径）。 */
    val src: String = "",
    /** 目标目录（绝对路径）。第一版只支持本地盘/映射网络盘。 */
    val dst: String = "",
    /**
     * 只同步这几个顶层目录（相对 [src]，空表 = 整个源目录）。
     *
     * 与 [exclude] **不是一回事**：排除项是否定表，表达不了"我只要 trunk/"。
     * 参考实现还给了这层存在的第二个理由 —— 但它那条理由是 svn 专有的（垃圾规则只在
     * "未版本控制"那个分支生效），本版砍掉了 svn 通道，所以留下的理由就是上面这条。
     */
    val syncRoots: List<String> = emptyList(),
    /** 用户排除项。两种写法，见 [isExcluded]。 */
    val exclude: List<String> = emptyList(),
    /** 静默期（毫秒）：最后一次事件之后安静这么久才开始一轮。 */
    val settleMs: Long = DEFAULT_SETTLE_MS,
    /** 巡检周期（毫秒）：距上次**完整**轮的硬期限。`0` = 关闭兜底。 */
    val sweepMs: Long = DEFAULT_SWEEP_MS,
    /** 本地删了、目标端也删（镜像删除，带安全闸）。关掉就只在报告里提示。 */
    val deleteMissing: Boolean = true,
    /** 内置垃圾规则。本版不进界面（见类头注）。 */
    val junk: JunkRules = JunkRules.DEFAULT,
) {

    /** 三个条件同时成立才算配好。界面允许半填，但引擎不启动 —— 见 [configProblem]。 */
    val isConfigured: Boolean
        get() = enabled && src.isNotBlank() && dst.isNotBlank()

    companion object {
        /** 1.5 秒：参考实现配置文件里的取值（它 README 的默认是 2.0）。 */
        const val DEFAULT_SETTLE_MS = 1_500L

        /**
         * 60 秒。**这个数是量出来的**（设计稿 §2.2）：目标端遍历在映射网络盘上
         * 实测 42,650 个文件 3.2 秒，所以每轮巡检的代价约占 5~6% 的占空比 —— 可以接受。
         * 与参考实现一致，不是想当然取的整分钟。
         */
        const val DEFAULT_SWEEP_MS = 60_000L

        /** 静默期的上限：事件洪流（一次 `revert -R .` 之类）不能把一轮无限延后。 */
        const val MAX_SETTLE_MS = 20_000L

        /** 静默期的下限，防止配成 0 之后每来一个事件就跑一轮。 */
        const val MIN_SETTLE_MS = 100L
    }
}

/**
 * 内置垃圾规则：四组，**一律小写比较**。逐字沿用参考实现 `sync_config.json` 的 `junk` 块。
 *
 * 四组的语义各不相同（见 [isJunk]）：
 * - [dirNames] 判的是**祖先目录名**（所以只有"在某目录之下的路径"才算 junk，目录自身不算）
 * - [fileNames] / [suffixes] / [prefixes] 判的是**叶子名**
 *
 * M71 专有的那几项（`logbak`、`battlevideo`、`tcpserver.txt`、四个日志前缀）**刻意留着**：
 * 它们的净效果是"不复制这些噪音"，而用户真把 M71 指进来时正合用。将来真要清理，
 * 删掉即可 —— 它们不会让任何该同步的东西不同步（被跳过的一律会出现在报告的 `[跳过]` 里）。
 */
internal data class JunkRules(
    val dirNames: List<String> = DEFAULT_DIR_NAMES,
    val fileNames: List<String> = DEFAULT_FILE_NAMES,
    val suffixes: List<String> = DEFAULT_SUFFIXES,
    val prefixes: List<String> = DEFAULT_PREFIXES,
) {
    companion object {
        // ---- 参考实现原样（`sync_config.json` 的 junk 块）----
        internal val DEFAULT_DIR_NAMES = listOf(
            "__pycache__", ".svn", ".git", ".hg", ".idea", ".vscode", ".vs",
            "node_modules", ".pytest_cache", ".mypy_cache", ".ruff_cache",
            "logs", "logbak", "battlevideo", ".venv", "venv",
            // ---- 本版新增：JVM / 构建产物 ----
            ".gradle", "build", "out", "target", "dist",
        )
        internal val DEFAULT_FILE_NAMES = listOf(
            "thumbs.db", "desktop.ini", "tcpserver.txt",
            "watch.log", "watch.pid", "watch.log.1",
        )
        internal val DEFAULT_SUFFIXES = listOf(
            ".pyc", ".pyo", ".pyd", ".log", ".tmp", ".temp", ".swp", ".swo",
            ".bak", ".orig", ".rej", ".ds_store",
        )
        internal val DEFAULT_PREFIXES = listOf(
            "dblog", "gatelog", "gslog", "proxylog", "autobattletestlog",
        )

        val DEFAULT = JunkRules()
    }
}

/**
 * 把一份（可能半填、可能类型不对的）配置规整成可以用的。
 *
 * 规矩与参考实现的 `apply_config()` 一致：**缺项 / 类型不对一律回落默认值**，
 * 所以"没有配置"和"配置只写了一半"的行为都是确定的。多做的两件事：
 *
 * 1. **路径与模式一律归一**（`\` → `/`、去掉首尾 `/`、转小写）——参考实现把配置项
 *    原样拿去比对，于是配置里写个 `Trunk` 就永远匹配不上（它自己的小坑，这里顺手堵上）。
 * 2. **时长夹在合理区间**，免得填了 0 或负数的静默期把 CPU 烧起来。
 */
internal fun normalizeConfig(raw: SyncConfig): SyncConfig = raw.copy(
    src = raw.src.trim(),
    dst = raw.dst.trim(),
    syncRoots = raw.syncRoots.map { normalizeRelPath(it) }.filter { it.isNotEmpty() }.distinct(),
    exclude = raw.exclude.map { normalizeRelPath(it) }.filter { it.isNotEmpty() }.distinct(),
    settleMs = raw.settleMs.coerceIn(SyncConfig.MIN_SETTLE_MS, SyncConfig.MAX_SETTLE_MS),
    // 0 是**有效取值**（关闭兜底巡检），所以不能一起夹下限
    sweepMs = if (raw.sweepMs <= 0) 0 else raw.sweepMs.coerceAtLeast(SyncConfig.MIN_SETTLE_MS),
    junk = JunkRules(
        dirNames = raw.junk.dirNames.clean(),
        fileNames = raw.junk.fileNames.clean(),
        suffixes = raw.junk.suffixes.clean(),
        prefixes = raw.junk.prefixes.clean(),
    ),
)

/** 四组 junk 规则都要：去空白、转小写、丢空项、去重。 */
private fun List<String>.clean(): List<String> =
    map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

/**
 * 配置哪里不齐，齐了返回 null。
 *
 * **返回枚举而不是文案键**：仓库有条铁律是"永不动态拼键"（`TextKeysTest` 靠字面量
 * 扫描钉"引用的键都在、词表里的键都有人引用"），而一个被当作键返回出去、再由调用方
 * 喂给 `CcoderText.text()` 的字符串，扫描扫不到 —— 结果是一边报"这个键没人引用"，
 * 一边又真的在用它。所以归属留在纯层（枚举），文案在界面层用字面量取。
 */
internal enum class ConfigProblem { DISABLED, NO_SRC, NO_DST }

internal fun configProblem(cfg: SyncConfig): ConfigProblem? = when {
    !cfg.enabled -> ConfigProblem.DISABLED
    cfg.src.isBlank() -> ConfigProblem.NO_SRC
    cfg.dst.isBlank() -> ConfigProblem.NO_DST
    else -> null
}
