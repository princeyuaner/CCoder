# CCoder 0.2.29：目录同步（本地 → 目标）

日期：2026-09-24　分支：`v0.2.29-dev`　`pluginVersion=0.2.29`
状态：**设计已定，待实施**

---

## 1. 要解决的问题

用户的原话：

> 我想做一个文件同步功能，我可以配置本地目录和目标目录，只要本地目录有任何文件修改，增加，
> 删除，都要同步到目标目录，只要我当前打开了这个插件就执行同步，可以参考 `C:\Users\CY\Desktop\sync`

参考目录里是一个**已经做完并用了很久**的 Python 工具（M71SyncTool：`sync_remote.py` 同步逻辑本体、
`watch_remote.py` 监听调度、`sync_ui.py` 界面、PyInstaller 打成无控制台 exe）。它解决的问题很具体：
本地工作副本 `C:\M71\server` 的改动要实时到达运行机 `Z:\m71\server`（即 `192.168.3.221` 的
`/home/lihoo/m71/server`），改完不用手工拷。

本版要把它**搬进插件**，并且**泛化**：不再是 M71 专用，而是任意一对目录；配置放进设置对话框，
**不配置就不开启**；第一版只支持本地/映射盘目标端（不做 SSH）。

### 为什么这件事放在 AI 插件里说得通

不是挂件。CCoder 已经在"每次改文件都先给你看 diff"这条线上——**Claude 改完的文件自动到运行机**，
是这个插件能提供的、别人不提供的一条链路。Claude 一次改一批文件，正好落在静默期那个抖动窗口里。
第一版就把定位定在这里，而不是做成一个通用同步器。

### 决策：泛化 = 砍掉 svn 通道

参考工具有**两条互相独立的发现通道**：`svn status`（版本控制语义）与**内容对账**（逐文件比内容）。
后者是它存在的原因——`svn update` / `svn revert` 改了文件内容却不留任何 `svn status` 痕迹，
只看前者会**永久漏同步**（README「常见问题」里那条真实缺陷）。

泛化到任意目录时 `svn status` 这条通道失效，**但不要紧**：内容对账那条通道本来就不依赖 SVN，
而它恰好是两条里更强的那条。所以本版 = **只保留内容对账 + 基线门控的镜像删除**。
代价只是"本地改了"的发现从"VCS 直接告知"退化成"事件提示 + 巡检"——而参考工具的调度器已经是这么设计的。

---

## 2. 先钉事实

### 2.1 参考实现量到的数（它自己的记录，可复核）

| # | 事实 | 怎么量到的 |
|---|---|---|
| 1 | **扫描 16,743 个文件：本地 0.15s、网络映射盘 1.7s、`os.walk` 33s**（差 20 倍） | 参考 README「内容对账怎么做到不拖慢每一轮」；关键在 `os.scandir` 直接取目录项里的 size/mtime |
| 2 | **稳态一轮约 0.5s；完整轮（遍历目标端）多花 1.7s** | 同上 |
| 3 | **首次无基线时全量核对约 1 分钟**（目标端是独立 checkout，mtime 天然不同，只能真读一遍内容） | 同上 |
| 4 | **内容比对并发 8 个读者、阈值 32 个文件、写者只有一个** | `sync_remote.py` `SCAN_WORKERS` / `PARALLEL_THRESHOLD` / `run()` 的串行复制 |
| 5 | **静默期默认 2.0s（配置文件里 1.5s），巡检周期默认 60s** | `sync_config.json` |
| 6 | **退避序列 5/10/20/30/60 秒，静默期上限 20 秒** | `watch_remote.py` `BACKOFF` / `MAX_SETTLE` |
| 7 | **删除的安全闸 = 目标端那份的 (大小, mtime) 与基线记录的逐位相同** | `sync_remote.py` `run()` 那段 `if rec[2] == st[0] and rec[3] == st[1]` |
| 8 | **巡检的计时是"距上次完整轮的硬期限"，不是"已安静多久"** —— 按后者写，事件一频繁巡检就被饿死 | `watch_remote.py` `_sweep_due()` 的注释 |
| 9 | **`reconcile` 只管要不要遍历目标端，与本地内容对账无关**；本地改动在任何一轮都被发现 | `watch_remote.py` `sync_once()` 的注释 |

### 2.2 平台侧：扫描量级（**已实测**，2026-09-24）

探针 `SyncScanProbe`（`src/test/kotlin/com/ccoder/sync/`，可重跑，产物落在
`build/probe/sync-scan-*.txt`）。四种"取属性"的写法各跑冷热两遍，下面是**热**的那些：

| 写法 | `C:\M71\server`（38,905 文件） | `Z:\m71\server`（42,650 文件） |
|---|---|---|
| **A `walkFileTree`**（用 `visitFile` 顺手给的属性） | **155 ms** | **3209 ms** |
| B `newDirectoryStream` + 逐文件 `readAttributes` | 1309 ms | 25617 ms |
| C = B 的遍历、`java.io.File` 取属性 | 2784 ms | 未量（已无必要） |
| D `File.listFiles()` 递归 | 2798 ms | 未量 |

**结论：A 比 B 快 8.8 倍（本地）/ 8.0 倍（网络盘）。**

原因是 `visitFile` 拿到的 `BasicFileAttributes` **是目录枚举时顺手带回来的**（Windows 的
`FindNextFile` 返回的 `WIN32_FIND_DATA` 里就有 size 与时间），而 `Files.readAttributes`
是对一个已知路径**再单独发一次** `GetFileAttributesEx`。也就是说 Python `os.scandir`
那个 20 倍优势，JVM 里**有** —— 它藏在 `walkFileTree` 里。第一版按"JVM 没有对应物"的
判断选了 B，恰好是最慢的那一类；**探针把它否掉了**。剪枝照样能做：
`preVisitDirectory` 返回 `SKIP_SUBTREE`（此时 `postVisitDirectory` 不会被调用，压栈出栈配平）。

**顺带钉实两件事**：

- **"秒级响应"成立**：本地全扫 155 ms（比参考实现还快 —— 它 16,743 个文件要 0.15 s）
- **`sweepMs` 的代价算得出来了**：每轮巡检多花一次目标端遍历 ≈ 3.2 秒（映射网络盘、
  42,650 个文件），占空比 5~6%。默认值据此定为 **60 秒**（与参考实现一致，不再是想当然）

### 2.3 看盘（**已实测**，2026-09-24）

探针 `SyncWatchProbe`，产物 `build/probe/sync-watch*.txt`。

| # | 事实 | 怎么量到的 |
|---|---|---|
| 10 | **纯 JDK 能收到事件，延迟 1~3 ms** | 最小复现（不带我们那层）：创建 / 修改 / 删除 / 建目录四条全收到 |
| 11 | **递归注册的代价：本地 765 个目录 144 ms；网络盘 1391 个目录 3398 ms** | 遍历真树并按过滤规则注册 |
| 12 | **注册时剪枝的收益：网络盘上 1391 → 773，省掉 618 个内核句柄（44%）** | 同上。这是"注册时就用过滤规则"这个设计的直接回报 |
| 13 | **`register()` 返回 ≠ 已经在看盘**：武装窗口里发生的改动永远收不到 | `SyncWatcherTest` 四条用例一上来就写文件、全红；加上 300 ms 之后全绿 |

`WatchService` 不递归、一个目录一个句柄（1391 个目录就是 1391 个句柄），所以 [MAX_WATCHED_DIRS]（1 万）只是个上限保险；按实测的目录数（几百到一千多）离它很远。

### 2.4 仍待实测

| # | 待实测 | 为什么要量 |
|---|---|---|
| 1 | 目标端**落在项目内**时，`refreshNioFiles` 刷一批路径的开销 | 目标在项目里会触发索引；量出来才能决定要不要在界面里拦这种配置 |

---

## 3. 数据模型

### 3.1 配置

存在**项目级** `@State` 里（新服务，不碰 `ClaudeSettings`——那个类的 `loadState` 是逐字段手写的，
漏一个字段会**静默**回落到默认值）。

```kotlin
// com.ccoder.sync.SyncConfig —— 一整套同步配置
data class SyncConfig(
    val enabled: Boolean = false,
    val src: String = "",                       // 本地源目录（绝对路径）
    val dst: String = "",                       // 目标目录（绝对路径）
    val exclude: List<String> = emptyList(),    // 匹配语义见 §4.3
    val settleMs: Long = 1_500,                 // 静默期
    val sweepMs: Long = 60_000,                 // 巡检周期；0 = 关闭兜底
    val deleteMissing: Boolean = true,          // 镜像删除
    val junk: JunkRules = JunkRules.DEFAULT,    // 内置垃圾规则（本版不进界面）
)

data class JunkRules(
    val dirNames: Set<String>, val fileNames: Set<String>,
    val suffixes: List<String>, val prefixes: List<String>,
)
```

`JunkRules.DEFAULT` **逐字沿用参考实现的四组**（`sync_config.json` 的 `junk` 块），并补上 JVM/IDE
生态的几项：目录 `.gradle`、`out`、`target`、`dist`。理由：参考那四组是踩出来的（`.svn` 同步过去会
破坏目标端工作副本、日志家族是有意排除的运行产物），照抄比拼一套新的安全。

**不变量**：`enabled && src.isNotEmpty() && dst.isNotEmpty()` 三者同时成立才启动引擎。
界面允许半填（不清空、不报错），但**引擎不启动**，并在状态里写明缺哪一项。

### 3.2 基线（内容对账的支点）

```kotlin
data class FileStamp(val size: Long, val mtimeNs: Long)
data class FilePair(val src: FileStamp, val dst: FileStamp)   // 上次"已核实"的双方状态
data class Baseline(val src: String, val dst: String, val files: Map<String, FilePair>)
```

- **身份键**：`src` / `dst` 两端各存一份字符串。任一端的路径变了 → 整份基线作废 → 自动重做一次
  全量核对。这是**安全且自愈**的行为（参考实现 `load_state()` 的 `dst_key` 就是干这个的）。
- **落盘位置**：`PathManager.getSystemPath()/ccoder/sync/<key>.json`，`key` = `"src|dst"` 的
  SHA-256 前 16 个十六进制字符。**不往用户项目目录里写任何东西**——这个仓库目前一个字节都不写，
  本版不开这个先例。哈希是新增的小代码（全仓 `MessageDigest` 零命中）。
- **读写**：走 `ProjectJson.read` / `ProjectJson.write` 的既有规矩——**读坏了返回空对象、绝不抛**；
  写入稳定、正好一个结尾换行。写失败不致命（下轮退化成全量核对），只吞异常不打断同步。
- 基线丢了 = 下一轮重做一次全量核对（1 分钟量级），**没有别的副作用**。

### 3.3 跨窗口认领

IDE 里天然会开多个项目窗口，而两个窗口同步同一对目录会**重复删除**。
在同一个系统目录放一份认领文件 `<key>.claim`：`{ pid, projectPath, since }`。
启动前先看有没有活着的认领（`ProcessHandle.of(pid).isAlive`），有就**不启动**并在状态里写明
"已被另一个窗口占用（`<projectPath>`）"——不静默。进程死了的陈旧认领自动接管。

---

## 4. 线路

### 4.1 一轮同步（`contentPlan` 的判定表）

对每个相对路径，按顺序判：

| 情况 | 处理 |
|---|---|
| 两侧的 (大小, mtime) 都与基线**逐位相同** | 跳过——稳态下这是绝大多数 |
| 目标缺失 / **大小**不同 | 直接复制，**不读内容** |
| 大小相同但状态变过 | 才真读内容比对（≥32 个才开线程池） |
| 内容一致、只是 mtime 不同 | 不动，只把"已核实一致"记回基线 |
| 基线里有、本地没了、**且仍在过滤范围内** | 候选镜像删除 |
| 基线里有、本地没了、但已落到过滤规则之外 | 静静遗忘——是用户改了配置，不是文件没了 |

删除前过安全闸（事实 7）：**目标端那份的 (大小, mtime) 必须与基线记录的逐位相同**才删；
被别的东西改过 → 只提醒、不删（`[提醒]` 段写明"目标端那份已被改动过"）。

删完 → 只清"因此变空"的目录（沿被删项的父链、要求目录**确实为空**、**范围根不删**）→
基线落盘（**失败项不记**，下轮自动重试；`dry` 模式不写）→ 报告分五段。

### 4.2 触发状态机

worker 线程是**唯一**会跑同步的线程——串行化靠"只有一个消费者"，不靠锁。Kotlin 侧就是一条
`Thread("ccoder-sync-worker")` 守护线程 + `synchronized(lock) { lock.wait(timeout) }`，
`poke()` 做 `notifyAll`（正好对上 Python 的 `Event.wait`）。

```
loop:
  baseline（启动时一次）→ reason=基线, full=true
  force_retry            → reason=重试, full=false
  否则：
    due = lastFullAt + sweepMs - now
    wait(due)                            ← 被事件叫醒时：
      settle_wait()                      ← 还有事件就继续等，安静 settleMs 为止
                                         ← 上限 MAX_SETTLE=20s（事件洪流不能无限延后）
    full = (没被事件叫醒) or (巡检已到期)   ← 到期就必须走完整轮，事件再频繁也不能饿死巡检
    reason = 巡检 | 变更
  snapshot+clear pending（只为日志）
  syncOnce(reconcileTarget = full)
  full 成功 → lastFullAt = now; streak = 0
  失败 → 退避 BACKOFF[min(streak,4)] = 5/10/20/30/60s → forceRetry = true  ← 立即重试，不等事件
```

**`reconcileTarget = full` 的含义**（事实 9）：本地内容变了在任何一轮都被发现并推送；
只有"目标端那份被人改过"需要遍历目标端（1.7 万目录项），所以它只放在基线轮和巡检轮，
普通事件轮跳过，以保住秒级响应。

### 4.3 过滤三层（逐字沿用参考实现的语义）

依次生效，任一命中即不参与复制/删除/对账：

| 层 | 语义 |
|---|---|
| **排除项** `exclude` | **含 `/`** → 按相对路径匹配：等于该路径或位于其下（`.claude`、`trunk/temp`）；**不含 `/`** → 匹配**任意一层**路径分量，支持 `*` 通配（`build` 排任何位置的 build、`*.log` 排所有 .log）。大小写不敏感 |
| **垃圾规则** `junk` | 四组：目录名 / 文件名 / 后缀 / 前缀，均小写比较。`isJunk()` 的目录语义只对子路径生效（`isJunk(".git")` 为假、`isJunk(".git/x")` 为真），监听那条路要补一层子路径再判，才能与扫描同语义 |
| **符号链接 / junction** | 一律不参与：既避免成环，也避免把链接当文件去比大小 |

### 4.4 失败与边界（不静默）

| 情况 | 表现 |
|---|---|
| 源或目标目录不可访问（盘没挂上） | 前置检查失败 → 记 `[错误]` + `[重试]`，退避后自动重试，**进程绝不退出**；状态卡变红 |
| 单个文件复制失败（被占用、权限） | 记 `[失败]` 并**留在基线外**，下一轮自动重新发现并重试；整轮不中断 |
| 单个目录读不了（网络盘偶发） | 跳过该目录，不让整轮失败 |
| 目标端那份被人改过、而本地已删 | **不删**，进 `[提醒]` 段并列前 10 条 |
| 目标端独有的文件（从未在本地出现过） | 永远不在基线里 → **永远不会被删**（这是镜像删除安全性的全部来源） |
| 基线文件读坏 / 被删 | 当作"没有基线"，下一轮全量核对一次；不报错 |
| 配置半填 | 引擎不启动，状态里写明缺哪一项；不抛异常 |
| 同一个 (src,dst) 已被别的 IDE 窗口同步 | 不启动，状态写明占用者；不静默 |
| 目标端在项目内 | 同步完对写入路径做一次 VFS 刷新（`refreshNioFiles`，走 `OpenFileTarget` 那条现成做法，**必须离 EDT**） |
| 监听器哑了（事件不再来） | 巡检兜底，最坏滞后一个 `sweepMs`；连续 N 轮无事件时在状态里写明"监听可能失效，仅巡检在跑" |

---

## 5. 落点

新包 `com.ccoder.sync`。**纯函数边界**是本设计的核心：引擎只依赖一个后端接口，于是整轮编排
可以对着**内存假目标端**跑单测，一行真磁盘都不用碰。

```kotlin
internal interface SyncTarget {
    val stateKey: String                                  // 基线身份
    fun check()                                           // 不可访问就抛 SyncError
    fun scan(): Map<String, FileStamp>                    // 全树（已过三层过滤）
    fun stat(rel: String): FileStamp?
    fun compareMany(rels: List<String>, onProgress: (Int, Int) -> Unit): Map<String, Boolean>
    fun copyFrom(srcFile: Path, rel: String)
    fun remove(rel: String): Boolean                      // false = 目标端原本就不存在
    fun prune(rels: List<String>): List<String>           // 只清空目录
}
```

`LocalTarget(root)` 是本版唯一实现；SSH 目标端将来落在这里，**引擎一个字不用改**。

文件清单（`src/main/kotlin/com/ccoder/sync/`）：

| 文件 | 内容 | 纯函数？ |
|---|---|---|
| `SyncConfig.kt` | 配置数据类 + `normalizeConfig()` | 是 |
| `SyncFilters.kt` | `inScope` / `isExcluded` / `isJunk` | 是 |
| `SyncScan.kt` | `scanTree(root, listdir = 真实现)` —— 遍历骨架与过滤规则**只有一份** | 注入 `listdir` 后可测 |
| `SyncBaseline.kt` | `loadBaseline` / `saveBaseline`（+ 哈希键） | 否（薄） |
| `SyncPlan.kt` | `contentPlan(srcNow, dstNow, baseline, filters) -> RoundPlan` | **是** |
| `SyncEngine.kt` | 一轮的编排：复制 / 删除 / 清空目录 / 基线落盘 / 报告 | 用假 `SyncTarget` 可测 |
| `SyncScheduler.kt` | §4.2 的状态机（注入时钟与"跑一轮"的 lambda） | **是** |
| `SyncWatcher.kt` | `WatchService` 注册、新目录动态补注册、`OVERFLOW` 处理 | 否（薄） |
| `SyncTarget.kt` | 上面那个接口 + `LocalTarget` | 否（薄） |
| `SyncService.kt` | 项目服务：生命周期、认领、配置、停止 | 否（薄） |
| `SyncStatus.kt` | 状态发布（照 `PendingPermissionCount`：`CopyOnWriteArrayList` + 回调前快照 + EDT 契约） | 否（薄） |

**哪几块是纯函数、各自钉什么**（这是"能像现在这样先写单测"的答案）：

| 纯函数 | 钉什么 |
|---|---|
| `inScope` / `isExcluded` / `isJunk` | 三种匹配语义、大小写、`\` 与 `/` 归一、目录语义要补子路径 |
| `contentPlan` | 上表**每一个分支**：基线命中、大小异、大小同但状态变、目标缺失、目标被改、本地已删、历史条目出范围 |
| `mayDelete(baselineRec, dstStat)` | 安全闸：目标被改过就是 false |
| `pruneCandidates(deletedRels, filters)` | 深的先删、范围根不删、过滤命中的不删 |
| 触发判定 | settle / 巡检不被饿死（这条正是参考实现纠正过一次的地方）/ 退避序列 |
| `normalizeConfig` | 缺项与类型不对一律回落默认 |

**线程与停止**：worker 线程守护；`stop()` 置退出标志 + `notifyAll`；服务实现 `Disposable`，
`dispose()` 里按序停监听 → 停 worker → **等在飞的那一轮跑完**（不留半个文件）→ 释放认领。
每个入口都先查 `project.isDisposed`。所有碰 Swing 的回调走
`invokeLater(task, ModalityState.any())`——**必须显式给 `ModalityState.any()`**，否则模态对话框
开着时这些任务根本不执行（`RuntimeDepsService` 里记着这个坑）。

服务工作线程的构造器注入要照 `RuntimeDepsService` 的写法：**注入参数不能写成主构造器的默认参数**，
平台反射找不到那种合成构造器，服务会静默注册失败。

---

## 6. 界面

### 6.1 设置对话框第 9 页

`SettingsPage` 接口只有 `title` / `component()`（**必须 memoize**）/ `reload()` / `dispose()`，
**没有保存/校验钩子——改动即写**。要动的地方：

1. 新文件 `settings/FileSyncSettingsPage.kt` + 纯逻辑 `settings/SyncConfigJson.kt` 不必——
   配置走 `@State` 而不是 JSON 文件，所以纯逻辑只有 `SyncConfig.kt` 里的 `normalizeConfig`。
2. `SettingsDialog.kt` 的 `pageSpecs` 加一行（页 + `NavIcon.Sync`）；新服务从
   `showSettingsDialog` 与构造函数传进来。**这会动 4 个调用点**：
   `SettingsDialogTest.kt:141`、`:280`、`SettingsDialogProbe.kt:597`、`:699`。
3. `SettingsNav.kt` 的 `NavIcon` 加一项 + `glyph()` 加一个分支（16×16 框、1.3f 描边）。
4. **两份词表**加键（`sync.*` 区），中英键集与顺序必须逐字平行，且每个键都要被代码字面量引用。

版面照 MCP 页的骨架（左列固定宽 240 + 右列表单）：

| 位置 | 内容 |
|---|---|
| 左列卡一（只读） | **状态**：运行中 / 上次一轮的时刻与用时 / 本轮复制·删除·跳过·失败数 / 最近几条 `[失败]`·`[提醒]`（列表窄，详情进 tooltip——同 MCP 页的做法） |
| 左列卡二 | **排除项**列表（可增删；行内编辑；空行不落盘——同 MCP/hooks 的"不持久化半填行"） |
| 右列表单卡 | 启用开关、本地目录、目标目录、静默期、巡检周期、镜像删除、内置垃圾规则只读摘要 |

**目录选择器**：仓库里**没有**现成的目录选择器，而且 `TextFieldWithBrowseButton` 在这里是**禁用**的
（它的 "…" 是内部扩展组件，离屏布局时会掉到字段外面）。照 `GeneralSettingsPage.pathRow()` 的形状写：
`actionLabel(标签) { FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null) { ... } }`，
BorderLayout 的 EAST，CENTER 放字段。

**`reload()` 的重入闸**：`reload()` 里 `text =` / 选中项会触发监听器，没有 `private var loading = false`
守卫的话，**一打开对话框就会把配置重写一遍**（挂在 `save()` 开头检查）。

### 6.2 状态栏组件

照 `PendingPermissionCount` + `PendingPermissionStatusBar` 那一对：组件**自己不存状态**，
`install()` 里订阅项目服务、`dispose()` 里退订，纯文本构造抽成顶层 `internal fun` 好单测。
注册进 `plugin.xml` 的 `<extensions>`（`statusBarWidgetFactory`）。

显示口径：没配置 → 空；运行中 → 上次同步时刻；出错 → 红色 + 待处理。**没配置就什么都不显示**，
不占状态栏。

---

## 7. 测试

| 层 | 测什么 |
|---|---|
| **纯函数** | 过滤三层、`contentPlan` 每个分支、删除安全闸、prune 候选、触发判定（含"巡检不被饿死"）、配置规整 |
| **引擎** | 对着内存假 `SyncTarget` 跑整轮：复制/删除/清空目录/基线落盘、**失败项不进基线**、dry 模式不写基线 |
| **调度** | 注入时钟：静默期抖动、事件洪流下的 20 秒上限、退避序列、"停止后不再有新一轮" |
| **设置页** | 用 `FieldLookup.inputOf/rowOf`：控件在、改动即写、`reload` 不覆盖正在编辑的值、目录为空时是只读而不是崩 |
| **探针**（手动跑一次） | §2.2 那三条：扫描量级、`WatchService` 行为、项目内目标的刷新开销 |
| **真机冒烟** | 配一对真目录，改/增/删各来一次；把目标端那份手工改一下，确认"只提醒不删"；拔掉目标盘确认退避重试 |

测试写法照仓库既有：JUnit 5、`` fun `中文长句说明规则` ``、断言带中文说明、
`@TempDir lateinit var tmp: Path` 用真文件系统、带线程的用例挂 `@Timeout(30)`。
**没有平台测试基类**，所以碰 `Project` 的一律抽成注入 lambda 的顶层函数。

---

## 8. 明确不做

- **SSH / SFTP 目标端**。后端接口（`SyncTarget`）留好，本版只实现 `LocalTarget`——上 SSH 在插件里
  要么用平台那套（可用面待确认）要么引 JSch，是一场独立的仗。参考实现里那条线的价值已经验证过，
  不急着搬。
- **反向同步（目标 → 本地）**。本版只有一个写者方向，因此不存在冲突与三向合并——这是刻意的简化，
  也是镜像删除能安全成立的前提。
- **定时全量重建基线**。基线自愈（读坏→空→下轮全量），没有需要定期重建的场景。
- **`junk` 规则的界面编辑**。内置默认 + 写进配置（将来暴露不用改代码）。`exclude` 才是用户会改的那层。
- **同步触发构建 / 热更**。"同步 ≠ 生效"——文件到了目标机还需要它自己重载，这是参考实现也明确
  划在范围外的。做这件事等于把插件的责任扩到运行机。
- **大文件复制的进度条**。逐文件失败会退避重试；进度属于第二版。
- **目标端文件锁检测**。锁着就当这次失败，下轮重试，不自造一套锁探测。

---

## 9. 代价与已知限制

- **JVM 上的扫描比 Python 慢**（无 `os.scandir` 对应物，每文件一次 stat）。量级待实测（§2.2 第 1 条），
  直接决定 `sweepMs` 能开多小。**已知未知**：量之前不承诺"秒级"这个说法在 1.7 万文件的目标端上成立。
- **`WatchService` 可能哑掉**（尤其网络路径）。设计上"事件只当提示"能容忍，但**最坏滞后一个巡检周期**
  （默认 60 秒）。连续无事件时状态里会写明，不会静默装作一切正常。
- **同一对目录只能被一个 IDE 窗口同步**。第二个窗口会看到"被占用"并且不启动——这是刻意的，
  好过两边同时删除。
- **基线不是跨机器共享的**（存在本机系统目录、按路径哈希）。换机器 = 一次全量核对。
- **同步本身会让 IDE 索引**（目标在项目内时）。有 VFS 刷新这一步，但大批量写入的索引开销没有量过，
  列为已知未知。

---

## 10. 实施顺序

1. **探针**：量 §2.2 三条。产出一个 `SyncScanProbe`（扫真目录，打印量级）与一个 `WatchService`
   行为探针。**数出来之前不定 `sweepMs` 的默认值。**
2. **纯函数层**：`SyncFilters` / `SyncPlan` / `SyncConfig`，连同它们的单测。这一层定下了整个功能的
   正确性，且完全不依赖平台。
3. **引擎与调度**：`SyncEngine` + `SyncScheduler`，对着假目标端与注入时钟跑。
4. **后端与监听**：`LocalTarget` + `SyncWatcher`。
5. **服务与认领**：`SyncService` + `SyncStatus`，接上项目生命周期与跨窗口认领。
6. **界面**：设置页第 9 页 + 状态栏组件 + 两份词表。
7. **真机冒烟**：§7 那张表最后一行。

---

## 11. 实现记录（2026-09-24）

**实现时才浮现、值得记下的两个坑**（都不是读代码能看出来的，都有回归用例守着）：

### 11.1 `WatchEvent.context()` 的 Path 可能是另一个类加载器造的

`sun.nio.fs.WindowsPath` 在测试 JVM（以及 IDE 的类加载器布局）里会被**加载两次**，
于是 `WatchEvent.context()` 返回的 `Path` 与我们自己 `Path.of(…)` 造出来的**不是同一个类**：
`dir.resolve(context)` 抛 `ProviderMismatchException`，`WindowsPath.equals` 里那句
`instanceof` 也为假 —— **连拿 Path 当 map 键都查不到**。

后果特别难查，因为它是**在看盘线程里抛的**：线程直接死掉、监听永久哑掉，日志里一个字都没有。
第一版的表现是"注册了 1 个目录、事件也收到了、却一条提示都没有"（按对象查表永远 miss）。

修法：**只对事件路径取 `toString()`**，相对路径靠"注册时记下的目录 → 相对路径"这张
**字符串键**的表拼出来（`SyncWatcher.relByDir`）。顺带也省掉了每个事件一次 `relativize`。

### 11.2 `poke` 的唤醒是**粘性**的，不能只靠 `await` 的返回值

参考实现的 `threading.Event` 是粘性的（`wait()` 会立刻返回），所以"事件到了但 worker 正在
跑上一轮"不会丢提示。第一版拿 `cond.await()` 的返回值当"被事件叫醒"，于是有一条真实的
**丢唤醒**：poke 若发生在 worker 还没进入等待时，那个 signal 就没了 —— 时间戳记下了却没人
据此进静默期，**那一轮事件永远不跑**（要等下一次巡检，默认 60 秒）。

修法：把"有没有未处理的事件"放进**状态**（`firstEventAtMs != null`）而不是放在
"被叫醒了吗"的返回值里。于是 `step` 连那个参数都不需要了，竞态从根上消失。
`SyncWatcherTest` 旁边的 `worker 还在跑上一轮时来的事件不会丢` 是它的回归用例。

### 11.3 界面那几处（都是"看着能写、其实会被约定挡住"的）

- **复选框的文字不能写在复选框上。** `FieldLookup` 按 `JLabel` 的文本找字段，而
  `JCheckBox` 的文字不在 `JLabel` 上 —— 写在里面就**找不到也点不着**。所以那两个开关
  走 `settingsRow(标签, 控件)` 那种"标签在左"的行语言，其余字段走 `labeledField`
  （标签在上）—— 目录要整行宽度，路径很长。
- **`reload()` 必须有一道 `loading` 闸。** 它是"把服务里的值灌进控件"，而灌值会触发
  控件的变更监听器 → 触发保存。排除项那一刻本地副本还是空的，于是**打开一次对话框就
  把用户的排除项清一遍**。用例 `打开对话框不会把排除项清空` 钉着它。
- **半填的时长不落库**：静默期框里把数字全选删掉的那一瞬间 `toLongOrNull()` 是 null，
  不判它就会写进一个 0，而 0 在 `normalizeConfig` 里会被夹成下限 —— 表现是
  "我还没改完它自己变了"。同 MCP/hooks 的"不落库半填的行"。
- **左栏刷新绝不能碰右栏**：服务每跑完一轮就推一次状态，而那条路直接回到 EDT ——
  重建表单会毁掉用户正在敲的东西（文本、光标、输入法状态）。所以 `refreshStatus()`
  只碰左栏那几个标签，右栏的控件建一次、只在 `reload()` 里填值。
- **用例里"在某张卡的子树里找控件"这个做法不行**：按卡片标题往上走一层拿到的是
  **标题栏**，而输入框在兄弟的正文里。改成"整页的输入框减去表单那五个"。

**四处只有渲染出图才看得出来的**（都改了，两张图留在 `build/probe/settings-file-sync-*.png`）：

- **`settingsCardColumn` 不管溢出**：它是纯 BoxLayout + 底部胶水，内容超过 560px 的对话框
  就是**被切掉**，没有任何滚动条。第一版表单最后两行整行不见了。
- **`cardRows` 的发丝线画在每行的上沿**，行自己没有上内边距时那条线会**压在标题字上**
  （出图里"上一轮"的顶被削掉半截）。`settingsRow` 自带 `empty(6, …)` 所以没这毛病 ——
  自己拼的行要自己留。
- **日志框不折行 = 横向被裁**，而且没有任何提示告诉用户后面还有字（栏只有 200px 宽，
  而一条"[完成] 复制 12/12，删除 3 项（…）"远超它）。改成折行，用滚动条换。
- **建了 Label 却忘了加进布局**：`detailLabel`（"打开配置缺哪一项"那句）建了、`refreshStatus`
  也一直在给它填值，但**从没进过布局树** —— 所以它一次都没显示过。这一类错**单测发现不了**，
  因为用例断言的是"值对不对"，而值一直是对的；出图才看出来那行字不在。
  现在有一条"那句话必须在树上"的用例钉着。
- **`problem` 为 null 时的映射陷阱**：在跑的时候 `problem` 是 null，而键映射把 null 当成
  DISABLED —— 会在"运行中"底下显示"打开开关并选好两端目录"。在跑时那一行整个藏起来，
  也不能留空话（词表不许有空值，`TextCatalogTest` 会红）。

### 11.4 两处对参考实现的小改进

- **含 `/` 又带通配符的排除项**：参考实现那条路永远匹配不上（静默失效），本版按字面意图生效
- **退避期内来的事件不重复跑一轮**：参考实现的 `_wake` 会残留，于是重试轮之后还会再跑一轮；
  本版由重试轮自身完成那次扫描，不再多跑（结果一样，少一轮 I/O）

### 11.5 同步卡进那一排（2026-09-24，用户要的）

用户原话：**"上下文左侧增加一个卡片，用来开启和关闭同步功能，点击卡片可以查看同步的日志"**。

位置就是字面意思：`StatusCardsRow` 里 `add` 的顺序成了 连接 · **同步** · 上下文 · 任务列表 · 子代理，
一条"同步卡在上下文左边"的用例把它钉住（不许哪天按"数据来源"重排把它挪走）。

**两件事分给两处**（与上下文卡同一套分流，不是新规矩）：

| 点哪儿 | 做什么 | 走的路 |
|---|---|---|
| 卡片主体 | 弹日志浮层（`SyncLogDetail`） | 与任务列表/子代理同一套 `showDetailPopup` |
| 右上角那颗 | 拨总开关（`SyncConfig.enabled`） | `SyncSettings.update` → 服务自己重建 |

那颗开关是**两态两图形**（▶ 开启 / ⏸ 关闭），不是"一颗电源符号"：图标是这颗按钮里唯一说
"点下去往哪个方向走"的地方，而卡上的词说的是"现在是什么状态"。

**开关方向按"开关在哪一侧"分，不按"跑没跑起来"分** —— 这条是这一版最容易写错的地方
（`syncActionOf` 里那张表）：`RUNNING / FAILED / OCCUPIED / STOPPED` 四档都给 ⏸。
其中 **`STOPPED` 必须给 ⏸**：那时 `enabled` 已经是真，再"开启"一次 `SyncSettings.update`
会判"没变"直接返回 —— 那是个**点了什么都不发生的按钮**（仓库里管这叫"画出可点的东西却点不动，
是在骗人"）。给 ⏸ 则两个方向都真：关掉 → 再打开就是一次真正的重试。
灰的只有一种情况：`DISABLED` 且两端目录没填（没配置就开 = 白跑一次重建），
提示语里说清缺什么（同清空那颗"灰着不解释，用户只会以为坏了"）。

卡面：值行取 `sync.state.*`（与设置页那行「状态」同一个来源，不再抄一份），
tooltip 直接用状态栏那句（`syncStatusBarTooltip`）——同一件事两处各写一份迟早口径不一。
**不画比例条**：一轮里复制多少是跑完才知道的，拿计划数当分母就是画一条一路涨到满的假进度。

日志那一屏做成了**类**（`SyncLogDetail`）而不是 `buildXxx` 函数：同步每跑完一轮就多几行，
而用户点开它多半正是为了**盯着**看下一轮跑到哪儿 —— 所以它得能就地更新（判据同
`refreshRunningPopup`：先核 `isShowing` 再改字）。换文本前先看滚动条在不在底，
在底就跟着走，**用户往上翻着看旧记录时不把他拽回来**。

面板这边：`ClaudePanel` 订阅 `SyncStatus`（`dispose` 里退订，否则每关一条标签漏一个监听器），
顺手 `SyncService.getInstance` 把它拉起来（卡出现了，开关就该能用），
刷新走单独的 `refreshSyncCard()` —— 一轮同步跑完就来一次，凭什么让另外四张跟着重算。

#### 代价：那一排从 79px 掉到 58px，英文撑破了八个词

**加卡是拿宽度换的**（`StatusCardsRow` 的头注里记着 2026-09-15 那次试加又撤）。这次**量了**：

| | 四张卡 | **五张卡** |
|---|---|---|
| 一格（404px 一行） | 97px | **76px** |
| 值行可用 | 79px | **58px** |

中文那套词量下来刚好，收短两个：`status.activity.agent` 子代理在跑→**代理在跑**（5 字 65px
放不下）、`status.card.compacting` 压缩中…→**压缩中**。
英文那份有**八个**撑破（Connected 68、No resume 68、No session 67、Won't start 67、
Searching 65、Compress 62、Loading… 61、Starting… 59），照 `CardTextMeasureProbe`
量出来的这一档重挑：Offline 42 / Loading 49 / Starting 49 / Linked 40 / Lost 26 / Failed 35，
加上 Search 42 与 Shrink 39。挑法不是"挑最短的"：**四档失败/断开状态仍旧分得开**，
`Connected→Linked` 与卡片自己的 `Link` 是同一个词根（中文那边也是 连接 / 已连接 这一对）。
用户当天从三个选项里挑的就是这一条（另两个是"五张不等宽"与"先出图再定"）。

量词这件事现在是**两个工具**：`StatusCardsRowTest` 那两条用例守着"当前这套词有没有被裁"
（`-PtestLang=en` 跑一遍就是英文那遍，一次列出所有放不下的），
`CardTextMeasureProbe` 是挑词时的尺子（把一批候选塞进真实的卡里，打印各自多少 px）。

### 11.6 同步气泡：跑完一轮在卡上冒一句（2026-09-24，用户选定甲）

用户原话："有文件同步的时候，在卡片上弹出一个气泡告诉我同步了哪些文件，停留 3 秒后自动关闭"。
四个方案摆在 `docs/design/sync-bubble.html`（都是按真机尺寸画的），**用户选了甲**：

- **形**：贴同步卡上方，标题一行（`已同步 3 个文件 · 480 毫秒`）+ 最多三条相对路径
  + 超出写 `还有 9 个 ›`；点它 = 打开日志浮层（**不是第三种点击去处**）
- **计时**：固定 3 秒 —— 平台气泡自带 `BalloonBuilder.setFadeoutTime(3000)`，
  不必自己做计时器（那是没选的丁才要付的代价）

**为它补的一口数据**：快照里原本只有计数，没有文件名。给 `RoundReport` 加了
`copiedRel` / `deletedRel` —— **做成的**那些，而不是 `copies` / `deletes`（**计划**，
含后来失败的、含目标端本来就没有的）。拿计划去报就是把没过去的说成过去了，
而"我改的东西怎么没同步"这个问题上界面绝不能替引擎撒谎（`SyncEngineTest` 里有两条钉着它）。
快照侧只带**各三条**（`SyncSnapshot.NAME_LIMIT`）—— 首轮全量可能动一万多个文件。

**三道闸**（每一道都对应一个真会撞上的情况，`SyncBubble.kt` 里逐条写着）：

1. 一轮只报一次 —— 判据是 `lastRoundAtMs`。**拿整份快照判等不行**：跑完一轮会连着推几次
   快照（先 RUNNING 再按结果分档），它们可以有完全相同的字段
2. 面板得真在屏幕上（`isShowing`）—— 同步是**项目级**的、面板是每标签一个，
   不在前台的标签不该冒气泡；**并且"报过了"要记在可见性判断之前**，否则切回这个标签会补报
3. 连轮不叠 —— 先收旧的再弹新的

不抢焦点（`setRequestFocus(false)`）、打字不打没它（`hideOnKeyOutside = false`）、
跟着面板销毁（`setDisposable(this)`）。

文件名放不下时走 `middleTruncate`：**文件名整段留住**、目录中段省略
（按比例对半分会把 `LongFileName.kt` 截成 `ngFileName.kt`，看着像另一个文件 —— 第一版就是这么错的，
用例把它钉住了）。观感另有 `SyncBubbleRenderProbe` 出三张图（稳态 / 有删除有失败 / 首轮全量），
真机上弹出与位置要靠冒烟清单看（浮层窗口无头跑不起来）。

### 11.7 弹层里的字色：一次"太暗"的返工（2026-09-28）

用户原话："弹框里的字体颜色太暗了"。两个新弹层中枪：**气泡里那三行路径**与
**日志浮层的小标题** —— 都取的 `UIUtil.getInactiveTextColor()`。那是主题的**次要文字色**，
语义是"可以看不清"（禁用、水印、句尾补充）。我拿它写了整个气泡的**内容**
（用户要逐条认"过去的是不是我改的文件"），于是屏幕上就是"发闷"。
这与 2026-09-16 计划框那次是同一族错误（`Contrast.kt` 开头记的那笔老账）：
**主题给的颜色得先问一句它是干什么用的**。

**量了一遍才改对**（`SyncBubbleRenderProbe` 里那行打点，Darcula 的实际值）：

| 色 | 对气球底 `#4b4d4d` | 对浮层底 `#3c3f41` |
|---|---|---|
| 主题的正文色 `#bbbbbb` | 4.43:1 | 5.53:1 |
| 正文色往下压 15%（第一版就是这么改的） | 3.66:1 | — |
| 主题的次要色 `#787878`（改之前用的） | **1.93:1** | — |
| 兜底那个亮蓝 `#7fb0ff`（强调色选中它） | 3.87:1 | 4.83:1 |

第一版照 `PermissionCard.dimHex` 的配方"从正文往下压一档"，量出来才发现
**深色底上根本没有余量可压**（正文自己才 4.43）。定下来的：

- 三行路径、日志正文、小标题：**直接用主题的正文色**；层级交给**等宽字体 + 小一号的字**
  （同一件事在 `StatusCardView` 那条"Ok 不该是灰的"上也是这么办的）
- `还有 N 个 ›` 与状态词：`accentTextOn` = `pickReadable([focusColor, CODE_BLUE_BRIGHT], 底)`，
  只保证"不比主题自己给的差"—— 那块底上两个候选都够不到 4.5:1，别把它当及格线
- 失败那行**不动**：色相本身就是那句话的意思，亮一档的红发粉、告警反而弱（取舍，写在用例注释里）

两条用例钉着（**要读的字不许比主题自己的字暗 / 一处都不许再退回次要色**），
外加一条前提测试：哪天主题的次要色自己就够清楚了，回那两处重看一眼。

顺带补了一个洞：**日志浮层从做出来到这天一张图都没出过**（气泡有图、它没有），
"哪几行发闷"只能靠猜 —— `SyncLogDetailRenderProbe` 跟着补上（跑完一轮 / 还没开跑两张）。
这就是 `[[render-swing-to-look-at-it]]` 那条：**观感问题先离屏渲染看一眼**，
单测盯得住"空日志给一句话"，盯不住"这一屏看着亮不亮"。

