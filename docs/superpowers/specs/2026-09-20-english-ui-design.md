# CCoder 英文界面

日期：2026-09-20　分支：`v0.2.22-dev`　`pluginVersion=0.2.22`

一个批次：**机制 + Kotlin + web + sidecar**。用户已定：设置里给「界面语言」下拉
（跟随 IDE / 中文 / English，**默认跟随 IDE**），存 APP 级；英文由我译、用户抽查。

---

## 一、要解决的问题

界面是纯中文，市场描述里写着 "The CCoder interface is currently in Chinese"，
因此只覆盖中文用户。而英文 IDE 的用户装上之后看到的第一屏就是中文 —— 这是市场面的天花板。

**量级**（三份探查实测，非估算）：

| 面 | 用户可见中文字面量 | 文件数 |
|---|---|---|
| Kotlin | **≈460** | 64 |
| web（React 转写区） | **≈27** | 9 |
| sidecar | **≈34** | 3 |
| sidecar 注入对话内容 | 1（`history-images.js:76`） | 1 |
| `plugin.xml` | 8 个字段（description / change-notes 已双语） | 1 |

注：`web/src` 里那 ~1450 条中文**是注释**，不是字符串 —— 一开始按"中文行数"排出来的
密度榜把这份报告带偏过一次，可靠的那份是逐个字面量数出来的。

---

## 二、平台侧的事实（实测，附证据）

| # | 事实 | 证据 |
|---|---|---|
| 1 | 插件自带翻译（Bundled Translations）自 **2024.1** 支持，本项目 target 253，可用 | JetBrains 文档；`pluginSinceBuild=253` |
| 2 | 资源布局：**基础（无后缀）= 英文，也是回退**；中文放同路径 `_zh.properties`。**不需要注册任何扩展点** | 同上；建 `_zh` 而不是 `_zh_CN`：`SIMPLIFIED_CHINESE` 的候选是 `zh_CN → zh → 基础`，一份覆盖两者 |
| 3 | **平台的自动查找只认 IDE 语言**，认不了插件里的下拉 | `AbstractBundle` 的字节码里没有 setLocale；`DynamicBundle.getLocale()` 是**静态无 setter**，唯一的改写入口 `loadLocale(LanguageBundleEP)` 由平台在启动时驱动 |
| 4 | `com.intellij.AbstractBundle` / `BundleBase` / `DynamicBundle` 都在 `lib/util-8.jar`（253）里 | 解包核过；`com.intellij.util.LocaleUtil` **不存在**（唯一的同名类是 bouncycastle 的） |
| 5 | `applicationService` 的 `preload="true"` 仍然有效 | 平台自己在 2025.3 里用了 **43 处** |
| 6 | **默认的 `ResourceBundle.Control` 会在候选落空后插一脚系统默认 Locale** | 2026-09-20 实测（Temurin 21）：`Locale.setDefault(zh_CN)` 之后 `getBundle(…, Locale.ENGLISH)` 返回的是 **`_zh` 那份**，哪怕基础那份就在 classpath 上 |
| 7 | `Properties.load(InputStream)` 是 ISO-8859-1 | 读原始 `.properties` 必须自己包 `InputStreamReader(UTF_8)`；写错的话中文全乱码，而 `containsKey` **照样通过** |

第 6 条是最险的一条：中文 Windows 上，英文 IDE 的用户选了 English，会被**系统默认 Locale
悄悄改回中文**，且不报错。修法是自备一个 `Control`：

```kotlin
private val control = object : ResourceBundle.Control() {
    override fun getFallbackLocale(baseName: String, locale: Locale): Locale? = null
}
```

（同一个实例复用：JDK 的缓存键算上了它。）

---

## 三、机制

```
src/main/resources/messages/CcoderBundle.properties      基础 = 英文
src/main/resources/messages/CcoderBundle_zh.properties   中文
```

新增包 `com.ccoder.text`（**既不属于 settings 也不属于 ui** —— 两边都依赖它，
而 `settings` 不许反向依赖 `ui`）：

- `TextCatalog`：纯函数（取 bundle、`{0}` 朴素替换、`keysOf`）。不碰平台，可单测。
- `IdeLocale`：**唯一读平台**的一处。判据顺序 = `ccoder.lang` 系统属性（测试/探针开关）
  → `DynamicBundle.getLocale()` → 英文。**刻意不回退 `Locale.getDefault()`**（见事实 6）。
- `CcoderText`：唯一门面。`text(key, vararg args)` / `tag()` / `setOverride(locale)`。
  缓存**按 Locale 走**，保证 `tag()` 与 `text()` 永远同一门语言（否则会出现
  "sidecar 拿到 zh、界面显示 en"）。

### 为什么不用 `MessageFormat`

它要求字面撇号写成 `''`，而英文文案里满是 `Don't ask` / `Claude's` —— 漏一个就把
`don't` 渲染成 **`dont`**：静默的句子损坏，且偏偏出现在用户读得最多的句子里。
所以只做朴素替换，撇号原样穿过，并用例钉住。

### 缺键返回**键本身**

界面上显示 `settings.page.general` 是一眼能报的 bug；静默回退英文是在撒谎。
配合 `TextKeysTest` 的**双向源码扫描**（引用的键必须存在、词表里的键必须有人引用），
缺键正常到不了用户眼前。

### 语言怎么被选中、什么时候生效

- 设置项存在 **APP 级** `UiLanguageSettings`（`ccoderUiLanguage.xml`），照 `PromptPresets` 的骨架。
- 它在 `plugin.xml` 里以 **`preload="true"`** 注册（不是 `@Service` 注解：注解表达不了预加载）。
  为什么必须预加载：最早取文案的两处（编辑器/项目树右键菜单的 `update()`、状态栏的
  `getDisplayName()`）都**早于**工具窗口，而平台服务是懒的。
  **必须写在 `<extensions>` 里** —— 写到顶层会被平台当成 `Unknown element` 静默丢掉
  （2026-09-20 的事故：服务没注册上 → `getService()` 回 null → 工具窗口 NPE 起不来；
  见计划文档的事故记录与 `PluginXmlShapeTest`）。
- **生效时机 = 立刻**（2026-09-20 从"重启生效"改成热切换）。改设置 → setter 把选择
  推给 `CcoderText` → 通知订阅者 → `SessionTabs` 让每个开着的面板跑一遍
  `ClaudePanel.retranslate()`：带键的控件走树重取 + 刷新家族重算 + 转写区重推语言 +
  sidecar 收一条 `setUiLang`。
  实现口径见 `ui/LocalizedText.kt`：**键记在控件上**，所以新代码漏不了；
  护栏是 `LocalizedTextTest`（切成英文后控件树里不许剩汉字）与
  `LocalizedTextScanTest`（长命控件里不许把翻好的字直接塞进控件）。
- **为什么以前不做热切换**：那会儿没有"重译"这条路，中途换语言会得到"卡片英文、
  面板中文" —— 晚生效好过一半生效。现在每个取词点要么带键、要么在刷新家族里，
  一半生效不再可能（2026-09-20 用户问"改成英文为什么没切换"，就是被晚生效逼出来的）。
- **转写区里已经说过的条目不动**：那是"说过的话"（历史），跟日志一样留在当时的
  语言里，新说的才是新语言。刻意如此，不是漏译。

---

## 四、先杀掉「字符串当身份」，再动一个字的文案

这是本批次**唯一的结构性改动**，必须在批量迁移之前做。

`ui/StatusCards.kt` 原来是 `connectionTone(status: String)` 拿中文 `when`，
生产端是 `ClaudePanel` 里散着的 **9 处** `setConnection("…")`。翻译一动手，两边只要
有一边没跟上，`when` 就整体落到 `else -> Tone.Idle` —— **灰点、零报错**，
而且看起来跟"没事"一模一样。同一族的还有 `ACTIVITY_*` 那十个中文常量
（`if (text == ACTIVITY_WAITING) startWaitingTicker()`：失配的表现是秒表不走）。

改成 `ConnectionState` / `Activity` 两个枚举，色调挂在枚举上：

- 加一档忘了给色调 → **编译错误**。
- 翻译不再牵动任何逻辑。
- 用例从"八种文字 → 四种色调"变成"遍历枚举"，新增一档自动被覆盖。

**「已结束」的色调是刻意保留的**：它今天落到灰（原来压根没写进映射表）。两种读法都
说得通 —— 进程退出可能是用户自己停的（灰对），也可能是崩溃（值得显出来，
`SidecarExitReport` 存在的全部理由就是"崩了看不出"）。这是产品判断，
**不该藏在一次翻译的 diff 里**，所以本次只把现状用一条命名用例钉住，改不改另开一条。

---

## 五、宽度：英文真正会坏的地方

仓库里的宽度全是**按中文量的**，而 `JLabel` 是**静默裁掉**的。

| 量到的 | 数值 |
|---|---|
| 状态卡一行 404px 时，值行 / 标签行可用宽度 | **≈79px** |
| `Not connected`（13 字符） | 91px ✗ |
| `Not resumed`（11 字符） | 80px ✗ |
| `Compacting…` | 85px ✗ |

所以英文那份必须**量**而不是估，且宽度不是按字符数算的（`Won't start` 11 个字符
放得下，`Not resumed` 11 个字符放不下）：

| 档 | 中文 | 英文（量过） |
|---|---|---|
| Idle | 未连接 | No session |
| Loading / Starting / Connected / Ended | 载入中… 等 | Loading… / Starting… / Connected / Ended |
| RestoreFailed | 恢复失败 | **No resume** |
| StartFailed | 启动失败 | Won't start |
| Disconnected | 已断开 | Dropped |
| 压缩中 | 压缩中… | **Compacting**（英文那份没有省略号 —— "Compacting…" 撑破格子；"正在做"由水位动画与琥珀色调表达） |

量宽这件事**做成了用例**：`StatusCardsRowTest` 遍历枚举，在 404px 那一行里量每一档的
值行与标签行，撑破就红。用 `-PtestLang=en` 跑一遍就是英文那一遍；它一次列出**所有**
放不下的词（只报第一个的话，改一个词要重跑一次才能看见下一个）。

### 英文离屏那一遍看到了什么（2026-09-20，`-PtestLang=en` 跑全部探针）

**一个真缺陷**：会话列表「清空全部」的确认语被**截断**成
`Clear these 4? 1 are in use and will be …` —— 那个弹层的尺寸是打开那一刻定死的
（`clearAllConfirmPrompt` 的 KDoc：必须在一行里放下），而英文 35 个字符撑破了它；
顺带 `1 are in use` 还是句病句。改成 `Clear this project's sessions?` +
`{0} in use, kept.`（后半句不带动词，单复数同一句就成立），复看通过。

**其余都不是缺陷**，是夹具数据：MCP 左栏那两句中文、提问卡上的题目、权限卡上的
「允许」按钮、命令补全的「参数 / 别名」、会话标题 —— 都是**探针自己写的**夹具
（真实用户数据/CLI 给的文本），本来就不该翻。这条值得记下来：看图时先分
"这是产品文案还是夹具"，否则会把夹具当漏翻去改。

**一个测试侧的改动**：设置渲染探针原来按**中文**找页签与字段（`findLabel(root, "通用")`），
英文那一遍直接中止、出不了图。改成传**词表键**（`page = "settings.page.general"`，
点的时候再翻），MCP / hooks 两个专有名词页签仍写字面量。**只动"找控件"，
没动断言** —— 断言照旧钉中文（那是 `-PtestLang=zh` 那一遍的事）。

---

## 六、参考面：谁翻、谁不翻

**总原则：翻自己的词，CLI 的词原样穿过。**（既有先例：`2026-09-15-result-line-design.md:38`
「认不出的 subtype 原样留着 —— 编一个中文名比留着英文更糟」。）

不翻：工具名、命令、diff、文件路径、Claude 的话、模型名、`12.4k` / `33.8s` 这类单位、
`·` 分隔符、`⟦path 24-27 · 4 行⟧` 引用记号（那是 Kotlin 造的用户数据，还会送到模型那里）、
`LOG.*` 与 `error()/require()/throw`，以及 `shared/transcript-ops.json`（Kotlin 与 web
的测试共读同一份契约 fixture）。

**三条拒绝语不翻，且不进词表**：`用户拒绝` / `已中断` / `会话已终止`。它们经权限回执与
`denyAllPending` 送到 CLI 与模型那里，是协议内容不是界面文案 —— 跟着界面语言走会产生
"界面说 X、模型被告知 Y"。跨语言的那份重复用 `shared/deny-message.json` 钉住
（照 `transcript-ops.json` 的先例），并加一条"它不是词表里的键"的断言。

**一条例外**：`sidecar/history-images.js:76` 那句 `（这一条里更早的 N 张图已省略）`
**要翻** —— 它进的是 `user` 消息的 `content`，既渲染成气泡、也在模型看得见的那一侧，
而它周围是用户自己的话（英文对话里夹一句中文括注更糟）。这处不对称写在该文件的注释里。

**更新日志对话框**：`<change-notes>` 本来就是中英各一份（市场页也这么显示），
对话框**两份都显示** —— 按 `<h4>中文</h4>` 切分是对话框里的 HTML 启发式，
切错了的症状是"更新日志漏了一整段"，属于静默失真。

---

## 七、两条跨语言的桥

- **sidecar**：语言有**两个**杠杆，缺一个就会"设置只生效一半"。
  A = 进程环境 `CCODER_UI_LANG`（管会话建立**之前**的报错：找不到 claude、认证失败）；
  B = `start` 消息的 `uiLang` 字段（管每会话的，因为新会话复用同一个 node 进程）。
  `CCODER_UI_LANG` 要进 `env.js` 的黑名单 —— 它是我们自己的变量，不该漏给 `claude` 子进程。
- **web**：只推**标签**不推词表（React 要按自己的方式重渲染，而 `npm run dev` 与渲染探针里
  没有任何注入，那边本来就得自带一份）。注入照 `ccoderSetTheme` 那条现成的路
  （`injectBridge` 注入 + `ready` 分支里紧挨着主题推一次 —— 否则会先按基础语言画一帧）
  ，并进 `installReadyWatchdog` 的探针对象。

---

## 八、明确不做

- **热切换**：改完设置不通知任何已建好的组件（见 §三 的生效时机）。
- **不推词表给 web**（§七）。代价是两份词表，各自有 parity 用例；边界写在这里，
  免得以后有人"顺手统一"它。
- **不做运行时改平台 locale**：那是 IDE 全局的事，插件没有那个口子（事实 3）。
- **不翻 CLI 传来的任何东西**（§六）。
- **不在本批次改「已结束」的色调**（§四）。
- **不把既有中文断言改成"查词表"**：文案在这个仓库是产品，`-PtestLang=zh` 钉死之后
  那 ~731 条断言照旧比字面量。英文侧的覆盖靠**结构**（双向源码扫描、键 parity、
  英文无 CJK）、**定点金标**（色调表、`FailureHint` 的可操作记号、六种权限模式英文名两两不同）
  与**眼睛**（`-PtestLang=en` 跑探针看截图）。

---

## 九、术语表（两份词表要对齐用同一批词）

连接 Connection · 会话 Session · 回合 Turn · 思考深度 Thinking effort · 权限模式 Permission mode ·
运行依赖 Runtime dependencies · 预置 prompt Preset prompt · 子代理 Subagent · 任务列表 Task list ·
上下文 Context · 压缩 Compact · 拒绝 Deny · 放行 Allow · 终止 Stop · 历史会话 Past sessions ·
待确认 Pending confirmations

---

## 十、风险（按"坏得多安静"排序）

1. **状态串撞上字面量 `when`** → 灰点、零报错。§四 根治了这一类。
2. **系统默认 Locale 劫持**（事实 6）→ 中文机器上英文界面悄悄变中文。自备 `Control`。
3. **`MessageFormat` 吃撇号** → `Dont ask`。
4. **`Properties.load` 的 ISO-8859-1** → 中文乱码而检查照绿。
5. **缺键 / 死键** → 裸键上屏。双向源码扫描兜。
6. **英文被裁** → 每个宽度都是按中文量的（§五）。
7. **英文名把状态说错**：`Ended` 不能读成"断开"；`恢复失败` 与 `启动失败` 英文必须是两句；
   依赖那四种状态不能塌成一个词（处置段落会指错病因）；六种权限模式英文名不得暗示"全放行"。
8. **sidecar 落后一个进程** → 只做一个杠杆就会"面板英文、报错中文"。
