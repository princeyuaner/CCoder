# 工具卡：卡面摘要与可点文件名

日期：2026-09-14

---

## 1. 要解决的问题

用户原话：

> bash 能不要直接显示代码吗？编辑文件，读文件我希望可以点击这个文件后直接打开阅读

拆成两件事：

1. **Bash 卡的标题就是命令原文**（`web/src/tools.ts` 的 `toolTitle` 只取 `command` 首行）。
   多行脚本会把代码直接露在卡面上 —— 要的是"卡面只留一行摘要"。
2. **文件类工具卡上的文件名点不动**。用户希望在编辑器里点开读，而不是在对话里
   读代码。桥这一层此前只认 `ready / pageState / openLink`
   （`ClaudeTranscriptView.handleFromJs`），没有"打开文件"这条路。

顺带一个实现 bug：展开体里的"命令"复用了标题（**首行**），而设计稿画的是
**整条**命令（`docs/design/transcript-tools.html:371/447`）。

## 2. 目标

- 卡面：Bash 只留一句摘要，命令原文、diff、输出都留在展开体里
- 文件名可点 → 编辑器打开；Read 带 `offset` 时定位到那一行
- 打不开时（路径认不出 / 文件不存在 / 是目录）**说一句话**，不静默
- 用户点名的既有行为一个不动：失败自动展开、输出 14 行封顶、折叠只有一层

## 3. 卡面：摘要从哪来

`toolTitle` 的 Bash 分支改成 `description` 优先（Claude 自己写的那句人话），
没有或者空串才退回首行命令 —— 卡面宁可露一行命令，也不要空着。

`description` 是 **Bash 专属字段**，只写在 Bash 分支里：写进通用兜底的话，
Read 的标题会变成一句与文件无关的话（有一条用例钉着这条不外溢）。

摘要上挂 `title` = 完整命令（HTML tooltip）：卡面省下来的信息不该真的消失。

**与设计稿的差异**：`transcript-tools.html` 方案乙的标题行画的是命令原文，
本次改成摘要。设计稿是历史记录，不回改；以这份 spec 为准，免得下次有人
照着 mockup 回滚。

新增 `toolCommand(name, input)` 给展开体用：Bash 给**整条**命令，其余给空串。
空串是刻意的 —— 展开体靠它决定要不要回落到"参数原文"那条兜底。
`.tool__cmd` 补 `max-height: 220px`：heredoc 那种命令没有上限时，一展开就把
上面几十条对话顶出屏幕。

## 4. 可点的文件名

工具集合沿用 `tools.ts` 的 `FILE_TOOLS`。`toolFile()` 只给**真的有单一文件**的
工具：Bash / Grep / Glob 给 null —— 硬凑一个路径出来只会把人带到错的地方。

- **行号只来自 Read 的 `offset`**。编辑类工具的参数里没有行号信息，
  猜一个等于指错地方。
- **路径原样透传**：归一化只存在于 Kotlin 一侧。两处各归一化一次就是两份真相，
  而"差一行、差一个段"正是这类改动最经典的 bug。

### 卡片头从 `<button>` 变成 `div[role=button]`

卡面上要放一个真的按钮（文件名），而按钮里套按钮是坏结构。代价与补偿：

- Enter / Space 自己补。Space 必须 `preventDefault`（否则页面滚动）；焦点落在
  文件名上时**让路**，否则按一下空格既打开文件又开合卡片
- UA 的焦点环随 `<button>` 一起没了 → CSS 补 `:focus-visible`
- div 默认是 content-box，配上既有的 `width: 100%` 会让卡片头比卡片宽出两个
  内边距，右侧的状态位被卡片的 `overflow: hidden` 裁掉 → 补
  `box-sizing: border-box`，并让布局探针量这一条（`headOverflow`），
  同一类"DOM 全对、屏幕上不对"的问题不能再靠肉眼发现

**一条不许动的约束**：文件名按钮**不带** `aria-expanded`。卡片头是既有 8 条
用例定位"开合手柄"的锚（`getByRole('button', {expanded: false})`），多一个匹配
会让它们一起报"找到多个元素"，而错误信息完全指不到根因。有一条用例把这个
隐式约束变成了显式断言。

文件名**静止时就画一条下划线**（2026-09-14 当天按用户要求改过一次：原来只有悬停
才出现，用户的原话是「希望界面上显示下划线表示可以点击」—— 能不能点不该靠悬停
才发现）。线用文字本色而不是 `--border`：后者在深色主题下几乎看不见。悬停时
连色带线一起换成强调色。这条由布局探针钉着（`fileDecoration`）。

## 5. 桥与线程

线上格式：`{"op":"openFile","path":"...","line":120}`。`line` 可以没有 ——
没有行号时**不发这个字段**，收端不必去分辨 `line: 0` 是"第一行"还是"没有行号"。

Kotlin 侧 `handleFromJs` 的回调来自 CEF 线程，**不保证是 EDT**
（同 `ready` 分支的处境）。所以新分支不猜自己在哪条线程上：VFS 那半进
`executeOnPooledThread`，碰编辑器与气球一律 `invokeLater`（带 `project.isDisposed`
守卫）。

**必须刷新 VFS**：Claude 刚用 Write 建出来的文件，VFS 可能还不知道它存在 ——
不刷新就会得到一句"文件不存在"，而文件就在磁盘上。刷新是同步的
（`async = false`），**不能上 EDT**（平台会直接断言失败）；用 `CountDownLatch`
兜 2 秒，最坏结果是"这一次没刷到，再点一下"，而不是永久占住一个池化线程。

路径判定（纯函数，可单测）：

| 输入形状 | 结果 |
|---|---|
| `C:\p\a.kt` / `C:/p/a.kt` | `C:/p/a.kt`（两种写法同形） |
| `/c/Users/CY/a.kt`（Git Bash） | `C:/Users/CY/a.kt` |
| `\\srv\share\a.kt` | `//srv/share/a.kt`（UNC 的前导 `//` 必须保住） |
| `web/src/App.tsx` | 拼到 `project.basePath` 上，再折一次 `.` / `..` |
| Windows 上其余前导 `/` | **null** |

最后一条是刻意的：把 `/etc/hosts` 当成项目内的相对路径，会把用户送到一个
毫不相干的文件里 —— 弹一句"打不开"（再点一次就好）比静默开错地方强得多。

折 `.` / `..` 自己写，不用 `Path.normalize()`：那是**宿主相关**的，在 Linux 上
跑 Windows 用例的测试会变成假绿。

`line` 的基准换算（1 基 → 0 基）只在 `lineIndex()` 里做一次，并单独有一条用例 ——
"差一行"是这类改动最经典的 bug。

## 6. 分层

| 层 | 位置 |
|---|---|
| 摘要、完整命令、文件目标 | `web/src/tools.ts`（纯函数） |
| 桥的发出端 | `web/src/bridge.ts` 的 `openFile` |
| 卡面与展开体 | `web/src/components/ToolCallBlock.tsx` + `styles.css` |
| 布局防线 | `web/tools/layout-probe.mjs`（真实 Chromium 量卡片头的高度与宽度） |
| 解析与路径判定 | `src/main/kotlin/com/ccoder/ui/OpenFileTarget.kt`（纯函数 + 薄 IDE 层） |
| 桥的落点 | `ClaudeTranscriptView.handleFromJs`（顺带补未知 op 的日志） |
| 提示 | `plugin.xml` 的 `CCoder` 组，**非粘性** |

提示用非粘性的新组而不是既有那组：`CCoder Permissions` 是
`STICKY_BALLOON`（为待决权限准备的），拿它发"这一次没打开"会留一张撕不掉的
气球 —— 语义与形态都不对。

## 7. 验收

- **web 单测**：`tools.test.ts`（摘要来源与不外溢、`toolCommand` 给全文、
  `toolFile` 的各种形状与坏参数）、`bridge.test.ts`（线上格式，含不发 `line`）、
  `ToolCallBlock.test.tsx`（卡面是摘要而展开体是整条命令、点文件名发出
  `openFile`、点完不折叠、键盘可达、`aria-expanded` 唯一），以及既有 22 条
  一条不红
- **Kotlin 单测**：`OpenFileTargetTest`（解析、行号基准、Windows / POSIX / UNC /
  Git Bash 路径、`.` 与 `..` 折叠、认不出给 null）
- **布局探针**：`npm run probe:layout` —— 三条都要成立：卡片头的高度、卡片头
  "正好占满卡片宽"（`headOverflow`）、可点文件名与命令原文的**计算样式完全一致**。
  最后一条是真的会红：`<button>` 不继承字体与颜色，写完探针后把颜色改回
  `inherit` 试过一次，两条场景都报 `rgb(140,140,140) ≠ rgb(220,220,220)`
- **人工**（唯一能验 VFS 与编辑器的那半段）：点带 `offset` 的 Read 卡
  （**确认行号没差一行**）、点 Write 刚建出来的文件（VFS 竞态）、点不存在的
  路径、点目录、展开 Bash 卡看整条命令

## 8. 不做

- 不给 MultiEdit 猜行号（它没有行号信息）
- 不做 diff / 历史版本视图；不在项目树里 reveal
- 不把整张卡片做成"打开文件"（只有文件名可点）
- 不动 `shared/transcript-ops.json`：那是 Kotlin→JS 推送方向的契约，
  JS→Kotlin 多一个 op 与它无关
