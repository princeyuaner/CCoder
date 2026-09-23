# 符号引用（`#`）+ 输入框用法占位

日期：2026-09-15　分支：`v0.2.15-dev`　`pluginVersion=0.2.15`

两件一起做：`#` 符号引用（主）与空输入框的用法占位（小，但它替 `#` 做宣传，必须同批上线）。

---

# 第一章　符号引用

## 1.1 要解决的问题

输入框现在四种喂上下文的方式：`@` 整个文件（CLI 自己展开）、编辑器右键选区（插件内联成代码块）、
`/` 命令、预设 prompt。**"引用一个类/函数"没有入口** —— 要么开文件、全选、右键（把整个文件的内容
带进来），要么手打 `@路径` 再指望模型自己翻。用户 2026-09-15 点名要做这个。

目标：打 `#` + 前缀 → 弹项目符号候选 → 采纳后插入一行记号 `⟦…⟧`，**发送时才展开**成
"路径:行范围 + 代码块"，与选区那条路完全同构。

## 1.2 前提：已核实的事实

**代码侧**（读源码得出，非推测）：

| # | 事实 | 影响 |
|---|---|---|
| 1 | 记号系统认 `⟦[^⟧]*⟧`，`SnippetRefs` 按 **token 文本**作键 | 新记号**零改动**即可解析/高亮/展开；但 token 必须能区分同名符号 |
| 2 | `formatSnippet(path, lines, fileTypeName, code)`（`AddSelectionToChat.kt:41`）就是选区内联的形状 | 符号内联复用它，不另造形状 |
| 3 | `selectionLineRange` / `mentionPathOf` / `lineRangeText` / `refToken` 都在 | 行号、相对路径、记号格式全部复用 |
| 4 | `refreshCompletion` 的 `when (q.trigger)` 是穷尽的（`ClaudePanel.kt:2610`） | 加 `Trigger` 常量会在那里编译失败 —— 这是一道**故意留的闸** |
| 5 | 补全路径目前**全程同步在 EDT**，唯一异步先例是 `requestCommands()`（结果 `invokeLater` 回 EDT） | 符号这条路要么同步、要么照那条先例异步 |
| 6 | 弹层最多 8 行、**不可滚动**（`COMPLETION_MAX_ROWS`） | 候选排序必须把"最可能是你要的"排进前 8 |

**平台侧**（在 PyCharm 2025.3.1.1 的 jar 里逐一验过，不是查文档）：

| # | 事实 | 影响 |
|---|---|---|
| 7 | 项目符号的统一入口是 `ChooseByNameContributor.SYMBOL_EP_NAME`（EP 名 `com.intellij.gotoSymbolContributor`），它本身在 **API jar** `intellij.platform.lang.jar` 里 | 这是一个"平台自己就在用"的口子，不是野路子 |
| 8 | PyCharm 已注册的实现覆盖各语言：Python 是 `com.jetbrains.python.PyGotoSymbolContributor`（implements `ChooseByNameContributorEx`），另有 JS / CSS / SQL / HTML / ini / editorconfig 等共 17 个 | **语言无关**：Python 项目、JS 项目都能用，不用为每种语言写一套 |
| 9 | `ChooseByNameContributorEx` / `Ex2` / `FindSymbolParameters` / `IdFilter` 都**没有 `@ApiStatus` 标记**（不是 internal / experimental），但落在 `*.impl.jar` 模块里 | 可以用；这条记下来，因为 0.2.13 被 internal API 卡过一次 |
| 10 | **2025.3 的 `IdFilter` 已经没有前缀工厂**（只剩 `ACCEPT_ALL` / `getProjectIdFilter`） | "打前缀时是索引级收窄、还是枚举全部名字再内存过滤"**查不到答案，必须实测** —— 见 §1.7 |

## 1.3 语义（界面文案与实现唯一依据）

> **采纳一个符号 = 把它的源码按"路径:行范围 + 代码块"的样式写进这条消息。**

推论：

- 引用的是**符号的源码**，不是"文件路径 + 让模型自己去读"。理由：`@路径` 展开的是**整个文件**，
  大文件下比内联那一段更费上下文；而选区的既有语义就是内联。
- 记号和选区一样**只是一行**（`⟦名字 · 路径 12-18 · 7 行⟧`），完整代码在发送时才展开 ——
  输入框不被 300 行代码顶开。
- 名字放在记号**最前**：用户在输入框里一眼看得见引用了谁（选区的记号以路径打头，那是因为
  选区没有名字）。

## 1.4 记号形状

```
⟦Foo.bar · src/main/kotlin/com/ccoder/ui/Foo.kt 12-18 · 7 行⟧
```

- 格式沿用 `refToken` 的 `· ${count} 行` 与 `lineRangeText`（`12-18` / 单行时 `12`）
- **路径必须在 token 里**：`SnippetRefs` 按 token 文本作键，`src/a/Foo.kt` 与 `test/b/Foo.kt`
  里的同名符号若记成同一个 token，后记的那份会覆盖前一份（`ComposerReferences.kt` 的头注释
  里就写着这条教训）
- 行范围取**元素自身的 textRange**（类 = 整个类体，函数 = 整个函数体），不做截断 ——
  与选区口径一致：用户选多少就发多少

## 1.5 UI

### 弹层

- 每行：`名字  ·  相对路径`（走现成的 `completionRowText`，不新造行型）
- **不分组**（单一来源不需要组头）
- 最多 8 行（现成的上限）；排序：**先按与查询的贴合度**（前缀完全匹配优先），再**短名优先**，
  最后按名字字母序 —— 同名的一串里，短的那个通常是用户要的
- 采纳**永不自动发送**（补全层既有规则）

### 时序（不能看起来像卡住，也不能闪）

**一次后台跳完成"查名字 + 解析"，结果齐了才开/更新弹层**：

```
打 #Fo ─▶ 后台：ReadAction 里查名字（上限 8）─▶ 解析成 SymbolHit ─▶ 回 EDT
                                                                      │
                        比对 symbolSeq（陈旧就丢）◀────────────────────┘
                                    │
                        一次性换掉 completionItems ─▶ 弹层出现（带路径）
```

这么排的原因：`processNames` 是索引级的，可能慢（事实 10）；而解析要碰 PSI。若先显示名字、
再补路径，弹层会闪 8 次；一次齐了再开只晚几十到几百毫秒，观感与平台的"转到符号"一致。

**退路（等实测数字，不预先实现）**：若探针显示"查名字很快、解析才是瓶颈"，改成两段式
—— 名字先开层（`description` 留空），解析结果到了再更新一次。

### 失败与边界（不静默）

| 情况 | 表现 |
|---|---|
| 索引中（dumb 态） | 不弹（与 `@` 文件补全同一条守卫） |
| 查不到候选 | 不弹（与 `@` 一致：宁缺勿错） |
| 解析不出来（索引刚变、元素已删） | **按纯文本名字插入**（用户敲的意图保留）+ WARN 日志 + 状态栏气球说一句（照 `OpenFileTarget.kt:182` 的先例） |
| 同名多个（两个 `Foo.bar`） | v1 取**第一个项目内的**；弹层已显示路径，用户看得见选的是哪一个 |
| 一次 `#` 查到的名字超过 8 个 | 按 §1.5 的排序取前 8 |

## 1.6 分层与落点

| 文件 | 责任 | 可测 |
|---|---|---|
| `ui/SymbolCandidates.kt`（新） | 纯逻辑：`SymbolHit`、`symbolToken`、`rankSymbolHits`、`symbolCandidates` | ✅ 单测 |
| `ui/SymbolLookup.kt`（新） | IDE 薄层：`symbolNames(project, query, limit)`、`resolveSymbol(project, name)`；只在 `ReadAction` 里碰 PSI，出口只有纯数据 | ❌（照 `collectProjectFiles` 成例：没有平台测试夹具） |
| `ui/Completion.kt`（改） | `Trigger.Symbol('#')`、`completionQuery` 的 `#` 分支、`CompletionItem` 增一个承载解析结果的可空字段 | ✅ 单测 |
| `ui/ClaudePanel.kt`（改） | `refreshCompletion` 的 `when` 分支（异步 + `symbolSeq`）、`acceptCompletion` 的 `remember`、失败路径 | ❌ |

## 1.7 待实测（探针，跑完回填）

| # | 要量什么 | 怎么量 |
|---|---|---|
| 1 | 前缀查询耗时：1 / 2 / 3 字符各一次，各返回多少条 | `SymbolLookup` 里带 `LOG.info` 计时，`./gradlew runIde` 起沙箱真敲 `#`，读 `idea.log` |
| 2 | 解析耗时：8 个符号 → `SymbolHit`（含冷文件的 PSI 加载） | 同上 |
| 3 | 贡献者是谁、是否 `Ex` / `Ex2` | 同上（启动时打一次） |
| 4 | 前缀是不是索引级收窄（事实 10 的答案） | 由 1 的数字反推：1 字符若与 3 字符耗时同量级且结果被截断，就是全量枚举 |

**这条决定 §1.5 用"一次跳"还是"两段式"。** 数字出来之前不写死。

## 1.8 明确不做

- 弹层里显示图标 / 按符号类型过滤（要多解析一层，收益不明）
- 搜索库（只看 `GlobalSearchScope.projectScope`，不搜 SDK / site-packages）
- 参数级、局部变量级符号（平台贡献者给什么就是什么）
- "同名多义"的选择 UI（v1 取第一个）
- 符号源码截断（与选区口径一致）
- 反向能力：从转写区的代码块跳回编辑器（那是另一件事）

---

# 第二章　空输入框的用法占位

## 2.1 要解决的问题

`@` 与 `/` 从上线起在界面上**没有任何说明**，`#` 更要靠猜。用户要求把用法写进输入框。

## 2.2 形态（用户已定）

- 只在**空输入框**里画一行灰字，一打字就消失；不常驻、不做 `?` 浮层
- 文案：`@ 文件 · # 符号 · / 命令`
- 位置：光标的落点（`insets.left`, `insets.top + fontMetrics.ascent`），颜色取
  `UIUtil.getInactiveTextColor()`

## 2.3 两条规则

- **禁用态不画**：CLI 找不到、会话断开时输入框是禁用的，这时挂一行"你可以打 `#`"是撒谎
- **文案与能力同步**：占位里写着 `# 符号`，就必须先有 `#` —— 两件同批上线；哪件被砍，
  文案同步改回两段

## 2.4 落点

`ui/Composer.kt` 的 `ComposerTextArea`（已有 `paintComponent` 同源的绘制需求）：

- `internal const val COMPOSER_PLACEHOLDER`
- `internal fun placeholderTextOf(area: JTextComponent): String?`（纯函数：空且可用 → 文案，否则 null）
- `paintComponent` 覆写里画那行字

## 2.5 明确不做

- 不做"教几次就不再显示"（多一个持久化状态，且想再看时没入口）
- 不做悬停提示 / `?` 帮助浮层（用户已排除）
- 不把快捷键（`Ctrl+V` 贴图、拖拽、发送键）写进占位（一行塞不下）

---

# 第三章　测试

| 层 | 测什么 |
|---|---|
| 纯逻辑 | `completionQuery` 的 `#` 边界（行首 / 空白后 / `a#b` 不触发 / 空格终止 / 与 `@`、`/` 互不干扰）；`symbolToken` 形状（含同名不同文件不撞 token）；`rankSymbolHits`（去重、前缀优先、短名优先、上限）；`symbolCandidates` 的 display/description/verbatim |
| 占位 | 按像素（照 `ComposerInputTest` 成例）：空 → 有非底色像素；有字 → 没有；禁用 → 没有 |
| 渲染探针 | `CompletionRenderProbe` 加一张 `#` 候选图：行型是"名字 · 路径"、无组头、路径不撑宽弹层、8 行上限 |
| 手工冒烟 | `./gradlew runIde`：真敲 `#` → 候选出现 → 采纳 → 记号进输入框 → 发送 → 转写区里是展开后的代码块；再各看一眼失败路径与占位的三种状态 |

---

# 附：执行记录

（实现完回填：实际做了哪些、与计划的偏离、出图才发现的问题、仍未做的。）

## 落地的东西

| 文件 | 内容 |
|---|---|
| `ui/SymbolCandidates.kt`（新） | `SymbolHit`、`symbolToken`、`symbolSnippet`、`rankSymbolHits`、`symbolCandidates` —— 纯逻辑 |
| `ui/SymbolLookup.kt`（新） | `searchSymbols`：贡献者分派（Ex2 / Ex / 老接口）、名字枚举与缓存、前缀过滤、解析成 `SymbolHit`、探针计时日志 |
| `ui/Completion.kt` | `Trigger.Symbol('#')`、`completionQuery` 的 `#` 分支、`CompletionItem.symbol` |
| `ui/ClaudePanel.kt` | `#` 分支（异步 + `symbolSearchId` 陈旧丢弃 + 名字缓存 + 状态行）、采纳时 `remember`、失败路径（状态行 + 气球） |
| `ui/CompletionPopup.kt` | 状态行（没候选时才挂）、`rowTextFor`（按像素塞行）、`ROW_SEPARATOR` |
| `ui/AddSelectionToChat.kt` | `linesText`（整行取文，PSI 范围要扩到行首） |
| `ui/Composer.kt` | `COMPOSER_PLACEHOLDER` + `placeholderTextOf` + `paintComponent` 画灰字 |

## 与计划的偏离（四处，都有理由）

1. **多了一行状态**（计划写的是"结果齐了才开层"）。实现时加了「正在搜索符号…」与失败说明那一行：
   全量枚举可能要几百毫秒到几秒，那段时间一个字都不显示就是"看起来卡住" —— 这个仓库
   在贴图与"写文件"两处都栽过同一个形态。**计划里那条退路（两段式）没实现**：现在是一次跳、
   一次开层，状态行承担了"中间那段"的反馈。
2. **弹层行加了 `rowTextFor`**（计划写的是"走现成的 `completionRowText`"）。探针图上发现：
   名字长的那行被容器从**尾部**裁成 `tests/ap…` —— 文件名没了，而"是哪个文件"是这一列存在的
   全部理由。改成按**真实字体**量宽度、先让路径（保文件名）、再让名字。
   中间试过两版按字符数预截，探针图上两版都被裁 —— **字符数在比例字体下不是宽度**，删掉了。
3. **`linesText` 取整行**（计划里没有）。PSI 元素的范围从 `def` 那个词开始，行首缩进不在里面 ——
   照它发出去，模型的到的是"顶层 def"，而原文里它缩在类里（设计 agent 指出的坑）。
4. **dumb 态的处置**（计划写的是"不弹"）。实现改成逐个贡献者问 `isUsableInCurrentContext`
   （声明了 `DumbAware` 的在索引期间照样能答），一个都不剩且正在索引时在层里说一句
   「正在建索引」—— 静默地不弹，与"这个符号不存在"在屏幕上一样。

5. **光打一个触发字符时给一行提示**（用户实测反馈之后加的）。用户装包后报"输入 `@` 和 `#`
   都没反应" —— 其实两条都在按设计工作（**光打触发字符不列候选**，这是 `@` 从上线起就有的
   规矩），但屏幕上一点反馈都没有，看起来与"功能坏了"一模一样。现在弹层里会出一行
   「再打一个字找文件 / 找符号」：**一行候选都不列**，所以不违反那条规矩，但把
   "还差一个字"说出来了。

6. **诊断日志加了又撤**。查"没反应"时先给补全每一步都加了留话（状态变化才记），
   但用户要求这次改动保持最小，撤掉了 —— 那一版的内容在本次提交历史里能查到。
   下次再遇到同类"没反应"，先按同样的思路加回来（贴图那次的教训是：这条路每个分支
   都是"什么都不显示"，日志里没有一个字就只能靠猜）。

## 与计划的另一处：探针的次序

计划里说"先量再做"。实际是**先做、后量**：沙箱（`./gradlew runIde`）在本机起不来
（前端构建在 cmd 里找不到 node，见下），而实现形状已经没有悬念 —— 异步 + 一次开层是无论
数字如何都成立的那个。**探针日志留在 `SymbolLookup.searchSymbols` 里**（每次搜索打一行：
前缀、枚举条数、命中数、解析数、耗时、贡献者形态），在真机上敲几次 `#` 就能读到数字，
届时回填 §1.7 那张表。

## 出图才发现的（单测看不见的）

1. **长名字那行把文件名挤没了**（上面第 2 条）—— 三版才收敛：字符预算 → 名字也截 → 按像素量。
2. **状态行空得可疑**（`completion-probe-symbol-status.png`）：一行灰字在 320px 宽的层里
   看着像没加载完 —— 但它就是"正在搜"的全部线索，留着。

## 仍未做的

- **§1.7 的实测数字**：要真机（下面那份包装出来之后，敲几次 `#` 读 `idea.log` 的「符号探针」行）
- **手工冒烟**：包已打出（`build/distributions/`），等用户装进 PyCharm 2026.1 实测
- 同名多义的选择 UI（v1 明确不做，取第一个）
- 符号类型过滤（类/函数/字段分开）
- `#` 紧跟在记号后面（`⟧` 之后）不触发 —— 边界规则要求空白或行首（设计稿 §1.2 事实 5 那条的
  已知副作用，先不动，看用户是否报）

## 环境坑（与本次功能无关，但记一笔）

本机这套 shell 里 **cmd 按 PATH 找不到 `node`**（`where node` 找得到、执行报"不是内部或外部命令"），
于是 `npm run build` 里那些 `.cmd` shim 全挂 —— `./gradlew buildPlugin` 因此失败在 `:buildWebUi`。
绕法（不改任何配置文件）：

```bash
export npm_config_script_shell="C:/Program Files/Git/bin/bash.exe"
./gradlew --stop && ./gradlew buildPlugin      # 守护进程要重建，它继承启动时的环境
```

用 bash 当 npm 的脚本壳之后，shim 换成 bash 脚本、`node` 在 bash 的 PATH 上，前端照常构建。
你自己终端里如果没这个毛病，就不用管这一段。

---

# 后续（2026-09-22）：占位补上发送与换行

用户："缺发送和换行的说明"。§2.5 当初明确**不**把发送键写进占位，理由是"一行塞不下" ——
实测放得下：`Composer.kt` 的 `composerPlaceholder(shortcut)` 在三个符号后面接一句
`Enter 发送 · Shift+Enter 换行`。

- **跟着设置走**：Ctrl+Enter 约定下换成 `Ctrl+Enter 发送 · Enter 换行`（真值在 `isSendKey`，
  写死一句在另一种模式下就是撒谎）。约定每次绘制现读设置 —— 设置页改完没有任何通知发到面板。
- **不复用 `settings.sendShortcut.*.label`**：那句是为设置页一整栏写的，英文整句
  （`Enter sends, Shift+Enter adds a newline`）接上去实测 ~470px，面板默认宽度下正好顶出去；
  这里用同义的短句。改一处要连着另一处一起看。
- 语言热切换：自绘控件本来要走树那一遍才重取，`ComposerTextArea` 因此实现 `Relocalizable`（repaint）。
- 出图：`ComposerRenderProbe` 多了一版**空输入框**（此前两版都填着字，占位在任何一张图上都看不见）。
