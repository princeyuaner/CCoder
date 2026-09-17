# 环境页加「运行依赖」：检测 + 一键安装

日期：2026-09-17　分支：`v0.2.19-dev`　`pluginVersion=0.2.19`
状态：**设计定稿，待实现**（本稿的技术事实全部实测过，未实测的逐条标了「未实测」）

---

## 1. 要解决的问题

用户原话：

> 设置页想加个环境检测，如果依赖项还没安装则给个按钮点击后自动安装

今天缺一样就跑不起来的依赖有**两个**：**Node.js（≥18）** 与 **claude CLI**。而它们缺失/过那时，
插件给出的是一条死路：

| 现状 | 出处 |
|---|---|
| `node` 路径写死 `"node"`，**完全不可配** —— 无设置项、无环境变量、不探常见安装位置 | `ClaudePanel.kt:2053`、`SidecarProcess.kt:62`、`ClaudeSettings.State` 无该字段 |
| `claude` 的解析在 **Node 侧**：显式路径 → PATH 扫描 → 抛 `ClaudeNotFoundError`；Kotlin 侧没有对应物 | `sidecar/claude-path.js:100-125` |
| **完全没有** `claude --version` 之类的检查 | 全仓 grep 零命中 |
| 失败只有转写区一条 ErrorItem；`failureHint` 连 `CLAUDE_NOT_FOUND`/`NODE_NOT_FOUND` 都**没有映射** | `ClaudePanel.kt:2174`(`fail`)、`:2646`(`failureHint`) |
| spec §5.3「显示设置引导」、§7.2「启动前检查 claude 可解析」、§8.2 第三级「常见安装位置探测」**均未实现** | `2026-09-11-pycharm-claude-code-plugin-design.md:177,370,423` |

用户已拍板的边界（四个决定）：**放环境页顶部**、**插件代跑安装**（确认框列出确切命令 → 执行 → 显示输出 →
成功自动复检）、**装不了的环境退化为复制命令 + 打开官方安装页**、**node 与 claude 都试**
（Windows: winget/npm、macOS: brew/npm、Linux: 只给命令）。**不加第八个页签**（页签栏 `TABS_WIDTH=140` 已满）。

---

## 2. 已钉死的事实（实测，2026-09-17）

### 2.1 本机探测（Windows 11 / JDK 21.0.12）

| # | 事实 | 怎么测的 |
|---|---|---|
| 1 | **JDK 21 的 `ProcessBuilder` 能直接起 `.cmd`**：`npm.cmd --version` → `11.8.0`；`claude.cmd --version` → `2.1.268 (Claude Code)`；带特殊字符的参数原样过去。**不需要绕 `cmd /c`** | `build/CmdSpawnProbe.java`（单文件源码启动，五个用例全部 exit=0） |
| 2 | node 在 `C:\Program Files\nodejs\node.exe`；npm 同目录 `npm.cmd`；claude 垫片在 `%APPDATA%\npm\claude.cmd`，真身在 `%APPDATA%\npm\node_modules\@anthropic-ai\claude-code\bin\claude.exe` | `where node/npm/claude` + `claude doctor` |
| 3 | 版本输出格式：node `v24.13.1`、claude `2.1.268 (Claude Code)`、npm `11.8.0` | 探针输出 |
| 4 | `npm.cmd prefix -g` → `C:\Users\CY\AppData\Roaming\npm`（npm 全局前缀可被改，故 Task 9 用它做事后补刀） | 探针 |
| 5 | winget 里 Node.js 由 `OpenJS.NodeJS.LTS` 管：已装 24.13.1、**有新版 24.19.0**（即"升级"是真实存在的路径） | `winget list --id OpenJS.NodeJS.LTS --exact` |
| 6 | 五个 winget 参数都真实存在：`--id` / `-e,--exact` / `--accept-package-agreements` / `--accept-source-agreements` / `--disable-interactivity` | `winget install --help` |
| 7 | `claude install [target]`（native build）与 `claude update` 是 CLI **自带**命令 —— 但它们要求 claude **已经存在**，做不了引导安装 | `claude install --help` |

### 2.2 官方渠道（联网核对）

| # | 事实 | 出处 |
|---|---|---|
| 8 | npm 包名 `@anthropic-ai/claude-code`，官方命令带 `@latest`；本机可见版本 **2.1.274** | `npm view` + 官方 setup 页 HTML 里的原句 |
| 9 | **该包的 `engines` 是 `node >= 22.0.0`** —— 与 sidecar 的 `>= 18`（`NodeCheck.MIN_MAJOR`）**不是同一个门槛** | `npm view @anthropic-ai/claude-code engines` |
| 10 | `https://nodejs.org/en/download` 200；`https://docs.claude.com/en/docs/claude-code/setup` 200 | `curl -w %{http_code}` |
| 11 | ⚠️ **`https://claude.ai/install.ps1` / `install.sh` 返回的是 `text/html` 落地页，不是裸脚本** —— 不进命令表 | `curl -I` + 取正文为空 |
| 12 | macOS 的 `brew install node` / Linux 的发行版包管理器 **未实测**（本机是 Windows）。Linux 本来就定为"只给命令"，macOS 的 brew 一行在首次真机验证前按"文档承诺"对待 | — |

---

## 3. 决策（每条都给了被否掉的那一半）

### 3.1 检测放 Kotlin 侧，**不**跑 node 探针脚本

三级解析：**设置里的显式路径 → PATH 扫描 → 已知安装目录**；找到后跑 `<路径> --version` 取版本。

- **否掉「复用 `claude-path.js` 的 node 探针脚本」**：用户点开这一块的时刻，往往正是 sidecar 起不来的时刻
  （spec §5.3 存在的全部意义）。走探针就要先 `SidecarLocator.resolve`（可能触发 jar 提取 6000 个文件）、
  还要 node 本身 —— 把最基本的检查绑在三层依赖上，方向是反的。
- **复刻的知识很小，且不是难的那部分**：检测只要 `namesFor` + 分隔符；`claude-path.js` 里真正精巧的
  `.cmd` 垫片解析（`%dp0%`、CVE-2024-27980）**检测不需要** —— 报一个 `.cmd` 路径是合法答案
  （事实 1 证明它还能直接跑起来取版本）。
- 路径逻辑全是「注入 PATH 原文 + 变量表 + `isFile`」的纯函数，单测直接打，不起进程。

### 3.2 第三级（已知安装目录）**必须两边都做**，并用一条用例钉住

不做这一级的后果比没有功能更糟：**装完 node 之后 IDE 进程的 PATH 不会刷新**，检测会绿、而开会话仍然
`CLAUDE_NOT_FOUND`。

- `claude-path.js` 补上 spec §8.2 承诺过、从未实现的第三级，并**导出** `CANDIDATE_NAMES` / `KNOWN_DIRS`；
- Kotlin 侧镜像同一张表（纯数据），配一条**跨语言同步用例**：测试里 `node -e` 打印 JS 那两张表，
  与 Kotlin 逐字比对。红了 = 有人只改了一边。
- 单一真源仍是 JS，Kotlin 那份是**被验证过的副本** —— 比注释靠谱（`NodeCheck.MIN_MAJOR` 那条
  "同一份知识的两个副本" 的教训，这次用用例兜住而不是靠自觉）。

### 3.3 「PATH 不刷新」的三重缓解

① 两侧共用已知目录表（3.2）；② 装成功后**仅当设置有字段为空时**把发现的路径写回（Task 9，绝不覆盖手填）；
③ 取消/超时/非零退出**如实显示**，不假装成功。残留风险：npm 全局前缀被改到自定义目录时，靠 Task 9 的
`npm prefix -g` 补刀（只在「npm 装 claude 后自动复检仍失败」时触发，有界）。

### 3.4 执行器 `CommandRunner`（零平台依赖）

- `redirectErrorStream(true)` + 单条读取线程逐行 —— 与 `SidecarProcess` 相反（那里 stdout 是 NDJSON
  协议通道必须分开）；这里 stdout 只是日志，合并才保得住 stderr 与 stdout 的**时序**。
- 取消语义照抄 `SidecarProcess.shuttingDown`：先置位再杀，退出回调据此发 `Cancelled`
  而不是 `Exited(1)`。少了这步，界面会把"用户按的取消"报成"安装失败"——本仓为同一类谎话付过代价。
- **杀树顺序与 `SidecarProcess.shutdown` 相反**：`ProcessTreeKiller.killTree(pid)`（父还活着时）→
  `destroyForcibly()` → 等 1s。`npm.cmd` 的真实结构是 `cmd.exe → node.exe → …`，先 destroy 会让
  `taskkill /T` 因为父 PID 已消失而**枚举不到子进程** —— 界面说"已取消"，msiexec 还在后台装。
- stdin 保持管道且**永不写入**（不 `inheritIO`）：继承控制台 stdin 会把 IDE 的输入抢走。
- **不在 IDE 退出时杀安装**（刻意）：腰斩 msiexec 会留下半个 node，比让它跑完更糟。

### 3.5 状态宿主是项目级服务 `RuntimeDepsService`

照 `McpStatus.kt` 的形状（服务发布 / 页订阅 / EDT 通知），理由是**生命周期**不是风格：

- 安装要活得比设置对话框长（npm/winget 几十秒到几分钟），关框不能杀进程；
- 关框重开必须还能看到「装完了 / 装失败了 + 那几十行输出」；
- 检测结果要缓存（`claude --version` 是几百毫秒的真进程），否则"关掉再开"就重跑。
- `post`（回 EDT）与 `runAsync`（起线程）**都从构造器注入**，测试给同步实现 —— 测试 JVM 里
  `ApplicationManager.getApplication()` 是 null（`EnvironmentSettingsPage.kt:108-126` 的教训）。
  规矩：服务内部**只**通过注入的 `post`/`runAsync` 碰外部世界。
- `AtomicInteger epoch` 失效闸：`startInstall` 自增（作废在飞的检测），每行输出也带 epoch ——
  取消/重装之后，上一轮迟到的行不会再往输出区里插。

### 3.6 命令表（三平台 × 两依赖）

| 平台 | node | claude |
|---|---|---|
| win32 | 装：`winget install --id OpenJS.NodeJS.LTS --exact --accept-package-agreements --accept-source-agreements --disable-interactivity`；升级：同参数换 `winget upgrade` | `<node目录>\npm.cmd install -g @anthropic-ai/claude-code@latest` |
| darwin | `/opt/homebrew/bin/brew install node` / `upgrade node` | `<node目录>/npm install -g @anthropic-ai/claude-code@latest` |
| linux | **不代跑**：复制命令 + 打开官方页 | 同左 |

- **三个 winget flag 不是装饰**（事实 6 已确认它们存在）：少一个就可能原地等一个没人能回答的交互，界面卡在"安装中…"直到超时。
- **npm 用绝对路径**（node 同目录），比 PATH 查找可靠，顺手绕开"PATH 不刷新"。
- **npm 装 claude 的前置是 node ≥ 22**（事实 9）：node 只有 18–21 时，claude 那一行的动作改成
  指向"先升级 Node.js"，而不是装出一个跑不起来的 claude。
- **`winget install` 会弹 UAC 提权**，确认框正文必须写明，否则用户以为插件卡死。

### 3.7 UI 形态（环境页顶部一块，独立文件）

```
──────── 分隔线（新补 hairlineBottom）────────
运行依赖                          重新检测
Node.js    可用 · v24.13.1
claude     未找到            安装 claude
[输出区：安装中/装完才有，固定高 96px，等宽，贴底，只留最后 500 行]
```

- 两行状态用 `BorderLayout`，名字列宽**钉死**（否则 "Node.js" 与 "claude" 长度不同会让状态起点不齐），
  配一条"两行状态起点相同"的用例。
- 动作一律 `actionLabel`（设置框里从不用 `JButton`）；灰掉时 `getInactiveTextColor()` + `DEFAULT_CURSOR`
  **且处理函数里再判一次**（灰字照样收得到 `mouseClicked`）。
- 输出区**不用 `wrappedHint`**（它是"量一次高度"的静态件，每次追加都要重量高）：建一次、只改 `Document`；
  `maximumSize` **等于**定死的高度（给了 `Int.MAX_VALUE` 就会变成页里的弹簧，`tableBox`/`modelListBox`
  各踩过一次）。
- 文案走纯函数 `statusText(DepStatus)`；常量 `internal const val`（测试按文字找控件，`labelsTagged` 先例）。

### 3.8 确认框

`Messages.showYesNoDialog`（先例 `ClaudePanel.kt:1437`），**可注入**（测试与探针给假实现 —— `Messages`
在纯 JVM 里必然 NPE）。正文单独一行放 `command.display()`，并写明「由 CCoder 代跑、可能弹 UAC、装完自动复检」。

**确认必须真的参与计算**：两条用例钉死 —— ①确认回调拿到的 plan 与命令表**逐字相同**；
②`confirm` 返回 false 时 **runner 一次都没被构造**。这两条一红，就说明确认沦为装饰
（`ClaudeSettings.kt:64-70` 那个"安慰剂复选框"的教训）。

### 3.9 失败引导（顺带补的账）

`failureHint` 抽成纯函数 `failureHintText(code, message)`，补 `CLAUDE_NOT_FOUND` / `NODE_NOT_FOUND` 两条，
指向「设置 → 环境 → 运行依赖」。

**不做**错误块上的可点「打开设置」：那要新增渲染 op、动 JCEF 前端与桥（`TranscriptOp` + web/ 构建），
与"本版不加页签、不膨胀"的边界不符。**已知缺口记在这儿**：退路是现有齿轮入口
（`ClaudePanel.openModelSettings`，不随忙闲置灰）已经存在，提示文案直接指路。

---

## 4. 被否掉的方案（连同否掉的理由）

| 方案 | 为什么否 |
|---|---|
| node 探针脚本复用 `claude-path.js` | 见 §3.1：检测被绑在三层依赖上，且要在 sidecar 根目录塞非产品脚本（只有根目录进包，`build.gradle.kts:184-200`） |
| 只做检测、按钮只复制命令（不代跑） | 用户明确要"一键"，且确认框 + 输出 + 复检这套已经能把风险摆到明面上 |
| 加第八个页签 | 页签栏 140px 已满；「环境」页本来就叫环境 |
| 错误块上的可点「打开设置」 | 要动渲染 op 与 JCEF 前端，超出本版边界（见 §3.9） |
| 读 `~/.npmrc` 解析 npm prefix | 解析配置文件的收益不抵复杂度；改为事后 `npm prefix -g` 补一刀（§3.3） |
| Linux 也代跑 | 发行版差异太大且普遍需要 sudo；只给命令与官方页 |

---

## 5. 验证

```bash
./gradlew test -PskipWeb                       # Kotlin 全量（含新增四组）
cd sidecar && npm test                         # JS（claude-path 第三级）
PATH="/c/Program Files/nodejs:$PATH" ./gradlew test --tests 'com.ccoder.settings.SettingsDialogProbe'
# → build/probe/settings-environment-deps-{ok,missing,installing,manual}.png
```

**看图只回答四件事**：两行状态有没有对齐、输出区高不高得离谱、等宽体与周围是不是一家人、分隔线够不够。

**真机冒烟（探针替代不了）**：①没有 winget 的 Windows 上走兜底路径；②node < 18 的机器上看"升级"；
③node 18–21 时 claude 那行是否指去升级 node；④**装到一半按取消，看文案与任务管理器里进程树是否真没了**。

---

## 7. 实现记录（2026-09-17 当天完成）

分支 `v0.2.19-dev`。**Kotlin 1374 条用例全绿**（新增 60 条），JS 178 条全绿。

### 7.1 落地的东西

| 文件 | 干什么 |
|---|---|
| `sidecar/RuntimeDeps.kt` | 三级解析 + 版本判定 + 两张镜像表（[已知安装目录] / [候选名]）+ 工具查找 |
| `sidecar/CommandRunner.kt` | `Command` / `RunEvent` / `CommandRun`(接口) / `CommandRunner` |
| `settings/InstallCommands.kt` | 三平台命令表、`InstallPlan`、兜底路线 |
| `settings/RuntimeDepsService.kt` | 项目级服务：检测缓存 + 安装状态 + 日志 + 订阅 + 两个失效闸 |
| `settings/RuntimeDepsSection.kt` | 环境页顶部那块 UI + `confirmInstall` |
| `sidecar/claude-path.js` | 补上 spec §8.2 一直没实现的**第三级**，并导出两张表 |
| `sidecar/NodeCheck.kt` | 加 `resolve()`；`ClaudePanel` 起 sidecar 时用它 |
| `ui/FailureHint.kt` | `failureHint` 抽出纯函数 + `CLAUDE_NOT_FOUND` 那条引导 |

### 7.2 与计划的偏差（都留了痕）

1. **路径逻辑放在 `com.ccoder.sidecar` 而不是 `settings`**（计划写的是后者）。`NodeCheck`
   在 sidecar 包，Task 5 要接上解析；把解析放 settings 会让两个包互相依赖。UI 文案与命令表
   仍留在 settings。
2. **`NodeStatus` 没加 `path` 字段**，改成加了 `NodeCheck.resolve()`。调用方本来就握着
   解析出来的路径，往状态类型里再塞一份是重复。
3. **没做"把发现的路径写回设置"**（原计划 Task 9）。理由：显式路径的语义是**找不到就报错、
   不回退**（§3.1），把自动发现的路径写死进去，以后 node/claude 一挪位置就会从"自动解析"
   退化成"显式路径不存在"—— 用 resiliency 换一点确定性，不划算。第三级解析已经覆盖了
   真实场景（装完就能找到）。取而代之的是「装成功但复检仍找不到」时补一句指路（§7.3）。
4. **`npm prefix -g` 的自动补刀没做**，只把这条写进了那句指路里（让用户自己跑一眼）。
   理由同上：它是"改过全局前缀"这个少数情况，代价是一条额外的进程与一套触发条件。
5. **环境页套了一层 `JBScrollPane`**：加完这一块之后，那一页的首选高度是 581–677px，
   而对话框是定高的（可用不到 500px）—— 不套滚动条，底部那行说明会被**直接切掉**。
6. **同一天晚些时候加了第八个页签「群交流」**（用户要求，放一张微信二维码）。
   本稿 §1 那句"不加第八个页签"是**指运行依赖那块不放新页签**，不是"这个对话框永远
   只有七页" —— 事实上加了之后页签栏 140px 宽、八行高都绰绰有余。那一页不读写配置、
   不联网，图片按**原始字节**打包（核过：显示尺寸下 JPEG 噪点远小于一个模块，
   重编码只会白添一次损失）。

### 7.3 看图与用例揪出来的三处真问题

| 症状 | 根因 | 修法 |
|---|---|---|
| 输出区被挤成半宽（328/656）**还跑到右半边**，压在下面的表上 | `BoxLayout` 那一列里混着两种 `alignmentX`：行是默认 0.5、输出区是 0.0 | 整列统一 `LEFT_ALIGNMENT`；输出区外面再套一层壳（`tableBox` 一直是这个写法） |
| 环境页底部那行说明消失 | 内容比对话框高（581–677 vs ~490） | 页面套 `JBScrollPane`，横向滚动条关掉（滚动条吃的是右侧内边距） |
| **打开设置就崩**：`InvalidPathException: Illegal char <"> at index 22: C:\WINDOWSsystem32WBEM"\winget.exe` | 这台机器的 PATH 里有一条带引号的条目（某些安装器会这么写），`Path.of` 直接抛 | 加 `safePath()`，拼不出来的条目跳过 |

第三条是**用例**（`SettingsPagesTest` 走真机工具解析）暴露的 —— 用户机器上同样会踩。

### 7.3.1 真机上「一直显示检测中」（当天下班前，用户报的第一个真机 bug）

装进 PyCharm 2025.3.1.1 之后，环境页那两行永远停在「检测中…」。

**根因（从 IDE 字节码里读出来的，不是猜的）**：`invokeLater(task)` 不带 modal 参数时用的是
`ModalityState.defaultModalityState()`；对**非 EDT 线程**它走
`ApplicationImpl.getDefaultModalityState()` → `ModalityKt.defaultModalityImpl()`，
一路 fallback 到底取 **`ModalityState.nonModal()`**。也就是说：**模态对话框开着的时候，
从后台线程投递的任务不会执行**。而设置框正是模态的、界面又恰好长在那个框里 ——
结果永远不会更新。

**修法**：投递时显式给 `ModalityState.any()`（要更新的组件就在那个模态框里）。

**为什么用例没拦住**：测试给的是同步 `post`（测试 JVM 里没有 EDT），这条只在真机上才现形。
和 §7.3 前三条一样 —— 这一版的价值有一半是**真机跑一遍**发现的。

### 7.4 这版没做的（记在这儿，不是忘了）

- 错误块上的可点「打开设置」（要动渲染 op 与 JCEF 前端，见 §3.9）
- 自动更新 / 自动升级（只提示 + 给命令）
- 读 `~/.npmrc` 解析 npm 前缀（改为指路，见 §7.2 第 4 条）
- Linux 代跑
- 市场描述里**没有**写明"插件会代跑这几条安装命令" —— 那条可选项，等下次动描述时一并加

---

## 6. 出处

- 本机探测：`build/CmdSpawnProbe.java`（探针，随 build/ 忽略）、`where`、`winget list --help`、
  `npm view`、`claude doctor` / `claude install --help`
- 官方：[Claude Code setup](https://docs.claude.com/en/docs/claude-code/setup)、
  [Node.js downloads](https://nodejs.org/en/download)
- 仓内：`docs/superpowers/specs/2026-09-11-pycharm-claude-code-plugin-design.md` §5.3/§7.2/§8.2、
  `sidecar/claude-path.js`、`McpStatus.kt`、`SidecarProcess.kt`、`SettingsPage.kt`
