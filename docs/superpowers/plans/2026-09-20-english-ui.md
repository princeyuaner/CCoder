# CCoder 英文界面 —— 实施记录

日期：2026-09-20　分支：`v0.2.22-dev`　`pluginVersion=0.2.22`

设计稿在 [`specs/2026-09-20-english-ui-design.md`](../specs/2026-09-20-english-ui-design.md)。
本文只记**做了什么、量到什么、与计划的偏差**。

---

## 一、任务与结果

| # | 任务 | 结果 |
|---|---|---|
| 1 | 取词机制（`com.ccoder.text`：TextCatalog 纯函数 / IdeLocale 平台读 / CcoderText 门面） | 完成。两份 `messages/CcoderBundle*.properties`；`build.gradle.kts` 钉 `ccoder.lang`（默认 zh，`-PtestLang=en` 看英文） |
| 2 | 杀掉「字符串当身份」：`connectionTone(String)` → `ConnectionState`、`ACTIVITY_*` → `Activity` | 完成。`connectionTone` **删除**（不是弃用）；「已结束」的色调保持灰色并用命名用例钉住 |
| 3 | Kotlin 文案迁移（≈460 条，64 文件） | 完成。`ui/` 全部 + `settings/` 全部 + `update/` + `sidecar/` 包的报错 |
| 4 | 设置项 + 四个 action + 状态栏 | 完成。`UiLanguageSettings`（APP 级、`preload="true"`）+ 通用页下拉；`plugin.xml` 注册 `resource-bundle` 并把 4 个 action 写成 `%key`，同时 `update()` 里 `setText` 兑现设置 |
| 5 | sidecar：两个杠杆 + 文案 | 完成。`CCODER_UI_LANG`（进程环境）+ `start.uiLang`（每会话）；`env.js` 黑名单；拒绝语保持中文并用 `shared/deny-message.json` 钉住 |
| 6 | web 转写区 | 完成。`i18n.ts`（懒读、`useSyncExternalStore`）+ `strings.ts`（26 键 ×2）+ 27 条文案；注入 `window.ccoder.locale` / `ccoderSetLocale`；`<html lang="en">` |
| 7 | 三侧测试 | Kotlin 1510 条（zh）；sidecar 211 条；web 279 条 |
| 8 | 英文量宽那一遍 | 完成。见 §三 |
| 9 | 文档与变更日志 | 设计稿 + 本文 + `plugin.xml` 的 description/change-notes + README + 市场描述 |

---

## 二、与计划的四处偏差（都是实现时才浮出来的）

1. **平台的自动挑语言只认 IDE 语言** → 自备 `Control`（这一条计划里预判到了，但**真正的坑更狠**：
   默认 `Control` 还会在候选落空后插一脚**系统默认 Locale**，中文 Windows 上问英文会拿到中文那份）。
   实测证据与修法见设计稿事实 6。
2. **枚举的显示文本做成"带 getter 的属性"而不是函数**（`val label get() = CcoderText.text(key)`）：
   `ui/` 里所有调用点读的是属性，改成 `label()` 会逼着改一大片 `ui/` 代码。
   getter 每次访问都重取词表，新鲜度与函数调用等价。
3. **`plugin.xml` 的 action 走两条腿**：`%key` 给 Find Action 的搜索索引（它只认 IDE 语言），
   `update()` 里的 `setText` 才兑现设置里那个下拉。两处的键**同一批**，抄两份迟早漂移。
4. **设置渲染探针改成按"词表键"找页签**：原来按中文找控件，英文那一遍直接出不了图
   （只动"找控件"，没动断言）。

---

## 三、量到的数（不是估的）

| 场合 | 中文 | 英文 | 结论 |
|---|---|---|---|
| 状态卡值行/标签行（404px 一行） | ≤4 个字 | 可用 **≈79px** | `Not connected` 91px ✗、`Not resumed` 80px ✗、`Compacting…` 85px ✗ → 换短词 |
| 清空确认语（弹层宽度定死） | 「清空这 4 条？ 1 条正在使用，保留。」 | 35 字符被截成 `… will be …` | 改成 `Clear this project's sessions?` + `{0} in use, kept.` |
| 模型页「使用中」 | 46px 占位 | `In use` 34px | 放得下 |
| 依赖行「找到了但跑不起来」 | 108px | `Found but won't run` 107px | 没长 |
| MCP 工具数 | 92px | `Connected · 2 tools` 107px | 仍放得下（左栏 240px） |

宽度**做成了用例**：`StatusCardsRowTest` 遍历枚举量值行与标签行（英文那一遍就是
`-PtestLang=en` 跑它）；设置那边用 `FontMetrics` 量过的三条写在 `RuntimeDepsSectionTest` 里。

---

## 四、拒绝语与"不翻"的清单（都留了注释与用例）

- **不翻**：工具名、命令、diff、路径、Claude 的话、模型名、`12.4k`/`33.8s`、`·`、
  `⟦路径 24-27 · 4 行⟧` 引用记号（Kotlin 造的用户数据，还会送到模型）、`LOG.*` /
  `error()/require()`、`shared/transcript-ops.json`。
- **拒绝语保持中文**（`用户拒绝`/`已中断`/`会话已终止`）：协议内容不是界面文案，
  两侧各写一份、用 `shared/deny-message.json` 钉住；`DenyMessageFixtureTest` 还断言
  这三句**不在任何一份词表里**（哪天有人"顺手翻掉"，那条会红）。
- **一条例外**：`history-images.js` 那句"更早的 N 张图已省略"—— 它进对话内容、
  也在模型看得见的那一侧，所以翻（理由写在那一行旁边）。

---

## 五、事故记录：0.2.22 装上去工具窗口打不开（2026-09-20 当天修）

**症状**：用户装 0.2.22 → 重启 → CCoder 窗口打不开 → 退回 0.2.21（市场那份）后恢复。

**证据**（用户机器 `idea.log`，11:49 那次启动加载的是 0.2.22）：

```
SEVERE - #c.i.p.p.p.i.PluginParser - Unknown element: applicationService
SEVERE - #c.i.o.w.i.ToolWindowManagerImpl - Cannot init toolwindow com.ccoder.ClaudeToolWindowFactory
Caused by: java.lang.NullPointerException: getService(...) must not be null
    at UiLanguageSettings$Companion.getInstance(UiLanguageSettings.kt:68)
    at SessionTabs.addTab(SessionTabs.kt:238)
    at ClaudeToolWindowFactory.createToolWindowContent(ClaudeToolWindowFactory.kt:30)
```

**根因**：界面语言那个服务的注册写在了 `plugin.xml` 的**顶层**。平台（261）把它当未知元素
**静默丢掉** —— 服务从未注册，`getService()` 回 null，建工具窗口内容时 NPE。
判据：IDE 自带 21 个能正常加载的描述符里，`applicationService` / `projectService`
**一次都没出现在顶层**（都写在 `<extensions>` 里），而 `resource-bundle` 21 个全在顶层。

**修法**（三处）：

1. `plugin.xml`：`<applicationService … preload="true"/>` 移进 `<extensions>`；
   `<resource-bundle>` 按惯例留在顶层。
2. **加固**：文案/服务出问题**不许**升级成界面故障 ——
   `CcoderText.bundle()` 取词表永不抛（退回空词表，`text()` 本来就"缺键返回键"）；
   新增 `UiLanguageSettings.applyLanguageToText()`：服务取不到就退回「跟随 IDE」+ 记一条 warn
   （`getInstance()` 保持严格 —— 设置对话框那边"取不到就该响亮地失败"）。
3. 新增 `PluginXmlShapeTest`：顶层元素白名单 + 服务 EP 必须在 `<extensions>` 里
   （名单从 21 个能加载的描述符统计而来，写在用例注释里）。

**流程教训**：1510 条单测 + 三侧测试 + 英文探针全绿，**没有一条能拦住描述符写错** ——
只有真机启动看得见。计划的验收清单里本来有 `./gradlew runIde`，那次没跑。
从这一版起：**动启动路径（plugin.xml / 服务注册 / 工具窗口构造）必须跑一次 `runIde`**。

---

## 六、仍未做的

- **「已结束」那一档的色调**：本次只把现状（灰）钉住，改不改另开一条（设计稿口径 5）。
- **`failure.claudeNotFound.body` 后半句指错了地方**（中英一样）：它说"也可以在那里手填
  claude 可执行文件的完整路径"，而那个路径栏在**通用**页、不在运行依赖那块
  （`RuntimeDepsService` 自己的处置段落写的就是「设置 → 通用 → claude 可执行文件」）。
  这是翻译之前就有的错，本次刻意不在 i18n 的 diff 里顺手改中文 —— 另开一条修，
  修的时候 `EnglishCopyTest` 里那条注释要跟着加一句断言。
- **`known` 的英文文案抽查**：五段 `FailureHint` 正文、六种权限模式的英文名、十个动作词
  等要用户过目（见对话里的清单）。
- **真机验收**：`./gradlew runIde` 用两种 IDE 语言各跑一遍（首装即英文 / 切中文后重开
  工具窗口生效 / 右键菜单与 Find Action 跟着变 / sidecar 报错的语言 / 八个设置页无裁切）。
- **web 侧的真机观感**：`npm run dev` 或 runIde 里看一遍转写区（英文那份的排版）。

---

## 六、自查

- 中文文案**逐字未改**：用一个脚本把两份词表里除模板值外的 **212 条**中文值
  拿去 `git show HEAD:` 的源码里找，找不到的 14 条逐句核对（都是原来就拼接过的长句 +
  本次新增的设置项 + 我手写的 `failure.*` 五段），全部对上。
- 三侧测试：Kotlin `./gradlew test -PskipWeb` **1510 全绿**；`cd sidecar && node --test`
  **211 全绿**；`cd web && npm test` **279 全绿**（含那条对负载敏感的流式开销哨兵）。
- 英文那一遍：`-PtestLang=en` 跑探针出图，人眼看过 10 张（设置 4 页 / 状态卡 / 会话列表 /
  胶囊 / composer / 提问卡 / 权限卡 / 附件条 / 命令补全），只揪出上面那一处截断。

---

## 七、立刻生效：从「重启生效」改成热切换（2026-09-20，当天第二次改动）

**症状**：用户把界面语言改成 English，界面没变，来问"为什么没切换"。

**机制本身没错**：语言只在**面板出生**那一刻读一次（`SessionTabs.addTab` →
`applyLanguageToText()`；启动时由 `loadState` 推）。用户机器上的时间线也印证：
`ccoderUiLanguage.xml` 12:06:19 落盘 `language=en`，而两个面板建在 12:05:30 /
12:05:34，之后既没重启也没新开面板。

**错的是我们许的愿**：`settings.language.hint` 与 `<change-notes>` 都写着
「下次打开工具窗口时生效」。而平台的工具窗口内容**只在该实例第一次显示时建一次**，
把窗口关掉再打开**不会**重建面板 —— 那句话在承诺一件做不到的事。

**第一版改法（当天作废）**：文案改成「重启 IDE 后生效」+ 改完弹一条带「重启 IDE」
按钮的通知。用户当场否掉：**"我不想要重启生效，我想要马上生效"**。

**三个方案摆出来量过**：

1. **原地热切换**（选中）：会话一个字不动 —— 进程不重启、跑着的那轮继续、
   草稿/队列/挂着的权限框都留着。
2. **立刻重开会话**：改动最小（复用 `switchToSession` 的停 → 占 → resume → 重放历史），
   但正在跑的那轮会断、挂着的权限框消失、草稿要专门搬 —— 为了换门语言重启会话，
   代价与收益不成比例。
3. **只热切换"看得见那层"**：状态卡/胶囊/转写区/sidecar 立刻变，深藏的角落留旧语言
   —— 与仓库一直拒绝的"一半生效"同源，不取。

**实现（新机制 4 处 + 一批小改）**：

- `ui/LocalizedText.kt`：**键记在控件上**（client property），语言一变
  `retranslateTree` 走一遍树照着键重取。为什么不逐个组件写 `retranslate()`：
  那种写法要靠人记得回来补一行，漏一个就是"角落里留着旧语言"。自绘控件实现
  `Relocalizable`，走树的同一遍叫到它。
- `UiLanguageSettings`：setter 从"只写不推"改成**写值 = 推给词表层 + 通知订阅者**；
  订阅者必须退订（APP 级服务跨项目活着），`SessionTabs.attach` 里把订阅挂在**项目**上。
- `ClaudePanel.retranslate()`：走树 + 刷新家族 + 转写区 `setLocale()` +
  sidecar `setUiLang`（新增协议消息；`session.js` 的取词器就地可换，会话不重建）。
- 两条跨语言的桥都复用现成的：web 侧 `ccoderSetLocale` 本来就有"换语言不重载页面"
  的用例；`setUiLang` 与 `start.uiLang` 用**同一个字段名**（`ProtocolTest` 钉着）。

**量到的数（侦察，不是估的）**：长命控件里 124 处取词，真正"构造时冻住"的只有
7~10 处（齿轮的 tooltip、`AttachmentStrip` 两处、`QueueStrip` 的 `headRemove`、
`SessionChips` 的胶囊 tooltip、`AskRestoreBar` 的标签与按钮）；其余都在 `get()`
属性 / `refresh*` / 纯函数里，重算即新语言。每次打开都重建的弹层（`SessionList`
16 处、`RunDetail` 16 处、提问卡 10 处…）**一处都不用动**。

**一条顺手的修正**：`AttachmentStrip` 的提示从"存句子"改成"存算法"
（`reject { … }`）—— 存下来的句子换语言时不会变；`imageRejectReason` 因此拆成
`imageTooBig`（判据）+ `imageTooBigReason`（现算的句子）。

**护栏**（没有它，这类改动只能靠人眼）：

- `LocalizedTextTest`：每个长命组件在中文下建出来 → 切成英文 → 走树 →
  **控件树里一个汉字都不许剩**（夹具全 ASCII，剩下的汉字只可能来自产品文案）。
- `LocalizedTextScanTest`：源码扫描 —— 长命控件文件里 `CcoderText.text(` 不许出现在
  "填进控件"的位置上（例外只有 `ClaudeTranscriptView` 的降级页，理由写在用例里）。
- `ClaudePanel` 本体在纯 JVM 里建不出来（要平台服务与 JCEF），它那半靠真机
  `-PtestLang=en` 探针看着 —— 这条限制写在测试类的注释里，免得下次有人以为漏了。

**没动 / 边界**：转写区里**已经说过的条目**保持当时的语言（那是历史，跟日志一样）；
改语言那一刻正开着的浮层要等下次打开；JCEF 降级页不重译。

**测试**：Kotlin 1534 全绿（`./gradlew test -PskipWeb`）；sidecar 213 全绿
（`node --test`，含 `setUiLang` 两条新用例）。

**真机验收（2026-09-20 下午，用户自己装的）**：装 0.2.22 → 重启（`idea.log` 12:51:52
那次加载 0.2.22，工具窗口正常起、无新报错）→ 切语言**当场生效** ✓。
这是这条线第一次在真机验过，也把"`ClaudePanel` 在纯 JVM 里建不出来、只能靠真机看"
那一半补上了。

还剩一小块没在真机看过：**13:10 那份**（`preload` → `LanguageStartup`）的启动行 ——
用户装的是 12:26 那版（带 preload 的）。影响面很小（最坏情况是注册被静默丢掉 →
右键菜单退回 IDE 语言，不会崩），但按"动启动路径必须跑 runIde"这条规矩，
真机日志里该出现一次 `CCoder 界面语言：启动时推给词表层（…）`。

---

## 八、市场拒收：`preload="true"` 已弃用（同日，发版时）

**症状**：`./gradlew publishPlugin` 报

> Failed to upload plugin: Upload failed: Service preloading is deprecated in the
> `<com.intellij.applicationService>` element. Remove the 'preload' attribute and
> migrate to listeners, see …/plugin-listeners.html

**性质**：市场在**生成版本记录之前**就拒了 —— 没留下半成品版本，重传不必换号。

**根因**：界面语言服务当初用 `preload="true"` 注册。理由是真的（右键菜单的
`update()` 与状态栏的 `getDisplayName()` 都早于工具窗口，而平台服务是懒实例化的），
只是这个写法本身成了市场的拒收项。

**改法**：去掉 `preload`，新增 `settings/LanguageStartup` —— `AppLifecycleListener`
的 `appFrameCreated` 里调 `UiLanguageSettings.applyLanguageToText()`。注册语法照平台
自己的描述符：`<extensions>` 里的 `<applicationListeners>` + `<listener … topic="com.intellij.ide.AppLifecycleListener"/>`（`PlatformExtensions.xml` 里 33 处都这么写）。
语义没变：帧建起来那一刻推一次，而那时还没有任何菜单被打开过。

**护栏**：`PluginXmlShapeTest` 新增一条「服务 EP 不许带 preload」，外加反向那条
「`LanguageStartup` 必须注册着」（删掉注册 = 菜单退回 IDE 语言，静默）。

**教训**：`verifyPlugin` 只验 API 用法，**不验市场的上传规则**。描述符里那些
"平台自己也在用"的写法（preload 就是）随时可能变成拒收项 —— 上传前跑一次
`publishPlugin` 本身就是最后一道门，而它是幂等的（失败不留痕），所以先发再补的
代价很低。
