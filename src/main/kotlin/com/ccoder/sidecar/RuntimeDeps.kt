package com.ccoder.sidecar

import java.io.File
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 运行期依赖的**发现与判定**：三级解析（显式 → PATH → 已知安装目录）+ 版本探针。
 *
 * 设计稿 `docs/superpowers/specs/2026-09-17-runtime-deps-design.md` §3.1–§3.3。
 *
 * ## 为什么第三级「已知安装目录」必须在这儿（而不是只在 JS 侧）
 *
 * 装完 node 之后，**IDE 进程的 PATH 不会刷新**（那是启动时继承的）。只认 PATH 的话，
 * 设置页检测会绿、而开会话仍然 `CLAUDE_NOT_FOUND` —— 比没有这个功能更糟。
 * 所以 `sidecar/claude-path.js` 也补上了同一级，两边共用一张表；
 * Kotlin 这份是**被 [CANDIDATE_NAMES] / [KNOWN_DIRS] 同步用例钉住的副本**，
 * 单一真源在 JS 那边（见 `ClaudePathTablesSyncTest`）。
 *
 * ## 为什么不跑 node 探针脚本复用 `claude-path.js`
 *
 * 用户点开「运行依赖」的那一刻，往往正是 sidecar 起不来的那一刻（spec §5.3 存在的意义）。
 * 走探针就要先 `SidecarLocator.resolve`（可能触发 jar 提取几千个文件）、还要 node 本身 ——
 * 把最基本的检查绑在三层依赖上，方向是反的。复刻的只是候选名与目录两张**纯数据**表，
 * 不是 `accept()` 里那段 `.cmd` 垫片解析（那是 spawn 时才需要的知识，检测不需要）。
 *
 * ## 分层
 *
 * - 纯函数：候选表、模板展开、版本解析、[decideStatus]
 * - 一层最薄的进程调用：[readVersionOutput]（5 秒超时，形状同 `NodeCheck.verify`）
 */

/** 两个运行期依赖。[label] 就是界面上写的名字（用例按它找控件）。 */
enum class RuntimeDep(val label: String) {
    NODE("Node.js"),
    CLAUDE("claude"),
}

/**
 * 平台。**注入而不是读宿主**：两张表按平台不同，测试要能构造另外两种场景
 * （同 `claude-path.js` 里 `separatorFor` 那条理由）。
 */
enum class Os(val jsKey: String) {
    WINDOWS("win32"),
    MAC("darwin"),
    LINUX("linux"),
    ;

    /** PATH 的分隔符。Windows 是 `;`，POSIX 是 `:`。 */
    val separator: String get() = if (this == WINDOWS) ";" else ":"

    companion object {
        /** 从 `os.name` 那种自由文本判平台。辨识不出来的一律当 Linux（最保守的假设）。 */
        fun fromOsName(name: String): Os {
            val n = name.lowercase(Locale.ROOT)
            return when {
                n.contains("win") -> WINDOWS
                n.contains("mac") || n.contains("darwin") -> MAC
                else -> LINUX
            }
        }

        /** 从 `process.platform` 那种键判平台（与 JS 侧同一套键）。 */
        fun fromJsKey(raw: String): Os = when (raw.lowercase(Locale.ROOT)) {
            "win32" -> WINDOWS
            "darwin" -> MAC
            else -> LINUX
        }
    }
}

/** 宿主平台。 */
fun hostOs(): Os = Os.fromOsName(System.getProperty("os.name") ?: "")

/**
 * 一个依赖的检测结果。
 *
 * [Unknown]（还没查过）与 [NotFound]（查了，没有）**必须分开** ——
 * 前者界面上什么都不说，后者才是"要装"。混在一起用户会以为插件坏了一半。
 */
sealed interface DepStatus {
    /** 还没查过。 */
    data object Unknown : DepStatus

    /** 正在查。 */
    data object Checking : DepStatus

    /** 找到并且跑得起来。[version] 拿不到时为 null（比如输出格式变了）—— 那不构成失败。 */
    data class Ok(val path: String, val version: String?) : DepStatus

    /** 找到、跑得起来，但版本低于下限（今天只有 node 有下限）。 */
    data class TooOld(val path: String, val version: String, val minMajor: Int) : DepStatus

    /** 三级都找过了，没有。 */
    data object NotFound : DepStatus

    /** 找到了但起不来（权限、坏文件、依赖缺失……）。[reason] 照贴给用户看。 */
    data class Broken(val path: String, val reason: String) : DepStatus
}

// ---------------------------------------------------------------------------
// 两张纯数据表 —— 与 sidecar/claude-path.js 的 CANDIDATE_NAMES / KNOWN_DIRS
// 逐字镜像，被 ClaudePathTablesSyncTest 钉住。键与 JS 的对象键**完全一样**
// （win32 / darwin / linux、win32 / posix），这样比对是结构对结构，不用翻译。
// ---------------------------------------------------------------------------

/**
 * claude 的候选文件名。`.exe` 优先：原生安装的是真正的可执行文件。
 *
 * 键按 JS 的写法分 `win32` 与 `posix` 两组（`namesFor` 就是这么判的）。
 */
internal val CANDIDATE_NAMES: Map<String, List<String>> = mapOf(
    "win32" to listOf("claude.exe", "claude.cmd", "claude"),
    "posix" to listOf("claude"),
)

/**
 * 已知安装目录（三级解析的第三级），模板形式：`%VAR%`（Windows）、`~`、`*`（nvm 的版本位）。
 *
 * 两张依赖**共用这一张表**：反正每项只是一个 `isFile`，省掉"谁住哪"的记账。
 * 表里既有 node/npm 的落点，也有 claude 的（npm 垫片在 `%APPDATA%\npm`、
 * 原生安装的在自己的 `.local\bin`）。
 */
internal val KNOWN_DIRS: Map<String, List<String>> = mapOf(
    "win32" to listOf(
        "%ProgramFiles%\\nodejs",
        "%ProgramFiles(x86)%\\nodejs",
        "%APPDATA%\\npm",
        "%USERPROFILE%\\.local\\bin",
        "%LOCALAPPDATA%\\Microsoft\\WindowsApps",
        "%LOCALAPPDATA%\\Programs\\nodejs",
        "%USERPROFILE%\\scoop\\shims",
        "%ProgramData%\\chocolatey\\bin",
        "%LOCALAPPDATA%\\Volta\\bin",
    ),
    "darwin" to listOf(
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "~/.local/bin",
        "~/.volta/bin",
        "~/.bun/bin",
        "~/.nvm/versions/node/*/bin",
    ),
    "linux" to listOf(
        "/usr/local/bin",
        "/usr/bin",
        "~/.local/bin",
        "/snap/bin",
        "~/.nvm/versions/node/*/bin",
    ),
)

/** nvm 那一项的版本位：`v9` 与 `v20` 按字典序会挑错，必须按数字比。 */
private val NVM_VERSION_DIR = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

/**
 * 从 nvm 的版本目录名里挑最高的那个。
 *
 * 纯函数（喂一串目录名）是为了能单测 —— 这里最容易踩的是字典序：
 * `listOf("v9.11.2","v20.1.0").max()` 挑出的是 `v9.11.2`。
 */
internal fun highestNodeVersionDir(names: List<String>): String? =
    names.mapNotNull { name ->
        val m = NVM_VERSION_DIR.matchEntire(name.trim()) ?: return@mapNotNull null
        val (a, b, c) = m.destructured
        Triple(name, listOf(a.toInt(), b.toInt(), c.toInt()), 0)
    }
        .maxByOrNull { it.second[0] * 1_000_000 + it.second[1] * 1_000 + it.second[2] }
        ?.first

/**
 * 展开一条目录模板。`%VAR%` / `~` / `*` 三个位置。
 *
 * [listDir] 注入是为了测 nvm 那一支时不用真建目录树。
 */
internal fun expandDirTemplate(
    template: String,
    os: Os,
    env: Map<String, String>,
    home: Path,
    listDir: (Path) -> List<String> = { dir ->
        runCatching { File(dir.toFile(), "").listFiles()?.map { it.name } }
            .getOrNull().orEmpty()
    },
): List<Path> {
    var text = template

    // %VAR% —— 变量名可以带括号（ProgramFiles(x86)），所以按 %…% 整段捞。
    // **变量缺失时整条丢弃**，不能只把它替换成空串：`%ProgramFiles%\nodejs` 会变成
    // `\nodejs`，那是个指向当前盘根目录的路径 —— 会去测一个根本不存在的地方
    if (text.contains('%')) {
        var missing = false
        text = Regex("%([^%]+)%").replace(text) { m ->
            val value = lookupEnv(env, m.groupValues[1])
            if (value == null) {
                missing = true
                ""
            } else {
                value
            }
        }
        if (missing) return emptyList()
    }
    if (text.startsWith("~")) {
        text = home.toString() + text.removePrefix("~")
    }

    if (!text.contains('*')) return listOfNotNull(safePath(text))

    // nvm 那一支：`…/*/bin` —— 把 `*` 换成一个具体版本目录
    val star = text.indexOf('*')
    val parent = safePath(text.substring(0, star)) ?: return emptyList()
    val tail = text.substring(star + 1) // 形如 "/bin"
    val picked = highestNodeVersionDir(listDir(parent)) ?: return emptyList()
    return listOf(parent.resolve(picked + tail))
}

/** 环境变量查表。Windows 的变量名大小写不敏感，注入的 map 也得按这个来。 */
internal fun lookupEnv(env: Map<String, String>, key: String): String? =
    env[key] ?: env.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

/**
 * 拼一个路径，拼不出来就 null。
 *
 * **PATH 里什么都有**：实测这台机器的 PATH 里就躺着一条带引号的条目
 * （`"C:\WINDOWS\system32\WBEM"` 那种，某些安装器会这么写），而 `Path.of` 碰到
 * 引号直接抛 `InvalidPathException`。不兜住的话，"打开设置"这一下就会崩在
 * 一条跟本插件毫无关系的 PATH 条目上。
 */
internal fun safePath(first: String, vararg more: String): Path? =
    runCatching { Path.of(first, *more) }.getOrNull()

/** 一个依赖按平台的可执行文件名。claude 走镜像表，node 是本侧自有的一条。 */
internal fun executableNames(dep: RuntimeDep, os: Os): List<String> = when (dep) {
    RuntimeDep.CLAUDE -> CANDIDATE_NAMES[if (os == Os.WINDOWS) "win32" else "posix"].orEmpty()
    RuntimeDep.NODE -> if (os == Os.WINDOWS) listOf("node.exe", "node") else listOf("node")
}

/**
 * 三级候选路径，按优先级排好。**纯函数**：不碰文件系统。
 *
 * 规则（与 `claude-path.js` 一致）：显式路径非空时**只**返回它 ——
 * 找不到就报错，绝不静默回退（静默回退会让用户以为设置生效了，实际用的是 PATH 里另一个）。
 */
internal fun candidatePaths(
    dep: RuntimeDep,
    explicit: String?,
    pathVar: String,
    os: Os,
    env: Map<String, String>,
    home: Path,
): List<String> {
    val explicitTrimmed = explicit?.trim().orEmpty()
    if (explicitTrimmed.isNotEmpty()) return listOf(explicitTrimmed)

    val names = executableNames(dep, os)
    // 拼不出来的条目（路径里有引号之类）直接跳过，见 [safePath]
    val fromPath = pathVar.split(os.separator)
        .filter { it.isNotBlank() }
        .flatMap { dir -> names.mapNotNull { name -> safePath(dir.trim(), name)?.toString() } }

    val fromKnown = (KNOWN_DIRS[os.jsKey] ?: emptyList())
        .flatMap { tpl -> expandDirTemplate(tpl, os, env, home) }
        .flatMap { dir -> names.mapNotNull { name -> safePath(dir.toString(), name)?.toString() } }

    return fromPath + fromKnown
}

/**
 * 找一个普通工具（`winget` / `brew` / `npm` ……）的可执行文件。
 *
 * 与依赖解析走同一套「PATH + 已知安装目录」：`winget` 就住在
 * `%LOCALAPPDATA%\Microsoft\WindowsApps`，而那个目录**未必在 IDE 的 PATH 里**。
 *
 * [nodeDir] 非空时优先看它 —— npm 就在 node 旁边（`npm.cmd`），比 PATH 可靠。
 */
internal fun findTool(
    names: List<String>,
    os: Os,
    env: Map<String, String>,
    home: Path,
    isFile: (String) -> Boolean,
    nodeDir: String? = null,
): String? {
    if (nodeDir != null) {
        val local = names.mapNotNull { safePath(nodeDir, it)?.toString() }.firstOrNull { isFile(it) }
        if (local != null) return local
    }
    val fromPath = lookupEnv(env, "PATH").orEmpty().split(os.separator)
        .filter { it.isNotBlank() }
        .flatMap { dir -> names.mapNotNull { safePath(dir.trim(), it)?.toString() } }
    val fromKnown = (KNOWN_DIRS[os.jsKey] ?: emptyList())
        .flatMap { tpl -> expandDirTemplate(tpl, os, env, home) }
        .flatMap { dir -> names.mapNotNull { safePath(dir.toString(), it)?.toString() } }
    return (fromPath + fromKnown).firstOrNull { isFile(it) }
}

/** 从 `--version` 的输出里抠版本号。认不出来就 null —— 那不是失败（见 [DepStatus.Ok]）。 */
internal fun parseVersion(raw: String): String? {
    val m = Regex("""(\d+\.\d+(?:\.\d+)?(?:[-.][0-9A-Za-z.]+)?)""").find(raw.trim()) ?: return null
    return m.groupValues[1]
}

/** 版本号的主版本。拿不到就 null。 */
internal fun majorOf(version: String?): Int? = version?.substringBefore('.')?.toIntOrNull()

/**
 * 判定：解析到什么 + 版本输出是什么 → 状态。
 *
 * 三条分支分开是为了界面能说三句不同的话：「没装」要装、「太低」要升级、
 * 「装了但跑不起来」要换个装法 —— 三者的修复动作完全不同（spec §5.3 那条理由）。
 */
internal fun decideStatus(
    dep: RuntimeDep,
    path: String?,
    versionOutput: String?,
    minMajor: Int = NodeCheck.MIN_MAJOR,
): DepStatus {
    if (path == null) return DepStatus.NotFound
    val version = versionOutput?.let { parseVersion(it) }
        ?: return DepStatus.Broken(path, "找到了，但读不出版本号")
    if (dep == RuntimeDep.NODE) {
        val major = majorOf(version) ?: return DepStatus.Broken(path, "版本号看不懂：${version}")
        if (major < minMajor) return DepStatus.TooOld(path, version, minMajor)
    }
    return DepStatus.Ok(path, version)
}

// ---------------------------------------------------------------------------
// 最薄的一层进程调用
// ---------------------------------------------------------------------------

/**
 * 跑 `<exe> --version` 并返回合并后的输出；起不来 / 超时 / 非零退出一律 null。
 *
 * 形状同 [NodeCheck.verify]（5 秒超时 + `destroyForcibly`），只是把结果原样交出来。
 * 实测（设计稿 §2.1 事实 1）：JDK 21 的 `ProcessBuilder` 能直接起 Windows 的 `.cmd`
 * 垫片，所以 claude 走到 `%APPDATA%\npm\claude.cmd` 时不必再绕 `cmd /c`。
 */
internal fun readVersionOutput(exe: String): String? = runCatching {
    val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
    if (!p.waitFor(5, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        return null
    }
    if (p.exitValue() != 0) return null
    p.inputStream.bufferedReader().use { it.readText() }.trim()
}.getOrNull()

/**
 * 真机探测一个依赖。四个口子（[env] / [home] / [isFile] / [runVersion]）都可注入 ——
 * 用例因此不用真装 node，也不用真起进程。
 */
fun probeRuntimeDep(
    dep: RuntimeDep,
    explicit: String? = null,
    os: Os = hostOs(),
    env: Map<String, String> = System.getenv(),
    home: Path = Path.of(System.getProperty("user.home") ?: "."),
    isFile: (String) -> Boolean = { File(it).isFile },
    runVersion: (String) -> String? = ::readVersionOutput,
): DepStatus {
    val explicitTrimmed = explicit?.trim().orEmpty()
    // 显式路径**只看它**：不存在就是坏，不回退（同 claude-path.js 的规矩）
    if (explicitTrimmed.isNotEmpty() && !isFile(explicitTrimmed)) {
        return DepStatus.Broken(explicitTrimmed, "设置里指定的路径不存在")
    }

    val pathVar = lookupEnv(env, "PATH").orEmpty()
    val candidates = candidatePaths(dep, explicit, pathVar, os, env, home)
    val found = candidates.firstOrNull { isFile(it) } ?: return DepStatus.NotFound
    return decideStatus(dep, found, runVersion(found))
}
