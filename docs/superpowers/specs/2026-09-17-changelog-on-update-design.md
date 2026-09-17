# 更新之后弹一次更新日志

日期：2026-09-17　分支：`v0.2.19-dev`　`pluginVersion=0.2.19`
状态：**已实现**（§2 的事实全部实测过）

---

## 1. 用户要什么

> 我想要用户更新插件后，自动弹出该版本的更新日志，只弹一次，实现很复杂吗？

用户拍板的两条：

- **时机 = b**：打开 CCoder 面板时弹（不是 IDE 启动时）
- **老用户也弹**：第一次跑这套逻辑时（没记过任何版本）也弹一次

---

## 2. 钉死的事实（实测，2026-09-17）

| # | 事实 | 怎么测的 |
|---|---|---|
| 1 | `PluginManagerCore.getPlugin(PluginId.getId("com.ccoder.claudecode"))` 在**测试 JVM 里返回 null**（沙箱里插件已装好也照样 null） | 临时用例打印（跑完删了） |
| 2 | `javaClass.getResourceAsStream("/META-INF/plugin.xml")` 拿到的**就是我们自己的**描述符：`jar:file:…/plugins-test/CCoder/lib/CCoder-0.2.19.jar!/META-INF/plugin.xml` | 同上 |
| 3 | 打包后的描述符里 `<id>com.ccoder.claudecode</id>`、`<version>0.2.19</version>`、`<change-notes><![CDATA[…]]>` 都在（版本由 `intellijPlatform.pluginConfiguration.version = providers.gradleProperty("pluginVersion")` 注入） | 直接解包 `CCoder-0.2.19.zip` 读 |
| 4 | **源文件** `src/main/resources/META-INF/plugin.xml` 里**没有** `<version>`（靠 patchPluginXml 注入）—— 于是 dev 模式（Run Plugin）读不到版本 | `grep '<version>'` 零命中 |
| 5 | 应用级 `PropertiesComponent` 在无头单测里拿不到（会 NPE） | 本仓既有注释（`ClaudePanel.kt:729`、`TranscriptSplit.kt:39`） |
| 6 | `change-notes` 当前是 **TODO 占位**（366 字符） | 见 §6 的待办 |

**结论：内容和版本都从 `2` 那条路读** —— 那是市场页、插件管理器、弹窗**同一个源**，
不用在代码里再抄一份文案，也不用再抄一份版本号（本仓为"同一份知识的两份副本"付过代价）。

---

## 3. 决策（每条都给了被否掉的那一半）

### 3.1 真源 = 自己的 `META-INF/plugin.xml`，不用平台 API

- **否掉 `PluginManagerCore.getPlugin(...)?.changeNotes`**：事实 1 说它在测试里是 null，
  而"读得到读不到"这件事本身不该成为弹不弹的前提 —— 我们自己的文件就在自己的 classpath 上。
- **`<id>` 必须对得上**再认：`/META-INF/plugin.xml` 这个路径不止我们有（平台自带的插件、
  其它第三方插件都可能有同名资源），万一 classloader 先撞上别人的，就会**弹别人的更新日志**。
  一行 id 校验把这条彻底关掉。
- 解析用 JDK 的 `DocumentBuilder`（关掉 DTD 与外部实体），**不用正则拼 XML** ——
  change-notes 里是 CDATA 包着的 HTML，正则在这个文件上今天能用、明天不一定。

### 3.2 只弹一次：应用级 `PropertiesComponent`，**先记再弹**

- key：`ccoder.changelog.shownVersion`，值 = 已经弹过的版本号。应用级 = 跨项目、跨重启，
  正是"这个用户看过没"的语义（事实 5：无头测试拿不到，所以读写抽成接口注入）。
- **先写标记再弹**。反过来（弹完再写）的代价是：弹框还开着的时候又一次上屏 -> 再弹一个。
  宁可"对话框万一构造失败就再也不弹了"，也不要每次开窗都弹 —— 用户要的是一次。
  （这条有测试盯着：`show` 抛异常时标记**已经**写下了。）

### 3.3 判断逻辑是纯函数

```kotlin
enum class ChangelogDecision { Show, SameVersion, Older, NoContent }

fun changelogDecision(stored: String?, changelog: PluginChangelog?): ChangelogDecision
```

| 输入 | 结果 | 理由 |
|---|---|---|
| 描述符读不到 / 没有版本 / 内容是占位 | `NoContent` | 没有可说的就不弹（见 §3.4） |
| `stored == null` | **`Show`** | 用户拍板：老用户也弹。代价是新装用户会多看一次，接受 |
| `current > stored` | `Show` | 更新了 |
| `current == stored` | `SameVersion` | 本版已经看过了 |
| `current < stored` | `Older` | 回滚到旧版不该再弹一次 |

**版本比较必须语义化**：字符串比较下 `0.2.10 < 0.2.9`，而本仓 0.2.9 / 0.2.10 都真实存在过。
按 `.` 切段、数字段按数值比、缺的段当 0、"dev/EAP" 这类后缀按字符串比。

### 3.4 内容闸：占位文案不弹

`notes` 为空 **或** 含 `TODO` / `待写` -> `NoContent`。理由很实际：0.2.19 的
change-notes 现在还是占位（事实 6），没有这道闸，这一版装上就会弹出一个写着
"TODO: this version's changes…" 的框。等文案写好，它自动就开始弹了 —— 不用改代码。

### 3.5 弹在哪儿：**面板首次上屏**，与"恢复最近会话"同一个点

`ClaudePanel` 里"面板真的显示出来了"已经有两个触发点（`showingWatcher` 的
`SHOWING_CHANGED`、`addNotify` 里让出一拍后的 `isShowing` 判断），两者共用
`consumeFirstShow()` 那道闸。更新日志挂在**同一处**、`startSession` 之后：

- 一次上屏只判一次；标记是应用级的，**多标签 / 多项目天然只弹一次**（判断与写标记都在 EDT 上，
  两个面板同一拍上屏也是先后执行，第二个看到标记就跳过）。
- 为什么不挂 `ClaudeToolWindowFactory`：那是"工具窗口被创建"，用户完全可能开着 IDE 一整天
  也不点它一下；而这一步的语义是"用户真的在看这个面板了"。
- 为什么不挂 IDE 启动（`AppLifecycleListener.appStarted`）：用户拍板选了 b（见 §1）。

### 3.6 弹什么：`DialogWrapper` + `JEditorPane`

- 标题：`CCoder 已更新到 0.2.19`；正文：一句引入 + change-notes 的 HTML（`<h3>/<ul>/<code>` 这些
  JEditorPane 都认）；只留一颗「知道了」（照 `SettingsDialog` 的 `createActions()` 写法，
  不用平台默认的 OK/Cancel 对）。
- **不用 `Messages.showInfoMessage`**：它渲染 HTML 的能力有限（列表会散架），也放不下"以后再说"这类扩展。
- 可缩放、Esc 与回车都关掉（这一屏没有任何可选动作，关掉就是唯一出路）。

---

## 4. 文件

**新增**
- `src/main/kotlin/com/ccoder/update/ChangelogOnUpdate.kt` — 描述符解析 + 版本比较 + 判断 + store 接口
  + `maybeShowChangelog(store, load, show)` 这一条入口
- `src/main/kotlin/com/ccoder/update/ChangelogDialog.kt` — 那个框
- `src/test/kotlin/com/ccoder/update/ChangelogOnUpdateTest.kt`、`ChangelogDialogProbe.kt`（出图）

**修改**
- `ui/ClaudePanel.kt` — 两个上屏触发点各加一句 `maybeShowChangelog()`；store 走字段（可注入）

---

## 5. 验证

```bash
PATH="/c/Program Files/nodejs:$PATH" ./gradlew test -PskipWeb
PATH="/c/Program Files/nodejs:$PATH" ./gradlew test --tests 'com.ccoder.update.ChangelogDialogProbe'
# → build/probe/changelog-dialog.png
```

**看图只回答三件事**：HTML 有没有渲染成"能读的一篇"（不是一堆标签）、框别太大也别太小、
「知道了」在不在它该在的地方。

**真机冒烟**（探针替代不了）：装上新包 -> 打开 CCoder 面板 -> 应该弹一次；关掉再开 -> **不该再弹**；
清掉标记（`PropertiesComponent` 的 `ccoder.changelog.shownVersion`）再来一遍。

---

## 5.1 实现记录（2026-09-17）

| 文件 | 干了什么 |
|---|---|
| `update/ChangelogOnUpdate.kt` | 描述符解析（id 校验 + 只看直接子节点）、语义化版本比较、四种判断、`ChangelogStore` + `PropertiesComponent` 实现、`maybeShowChangelog` 这一条入口（**先记再弹**） |
| `update/ChangelogDialog.kt` | 那个框：`JEditorPane` 渲染 change-notes + 一句引入 + 一颗「知道了」 |
| `ui/ClaudePanel.kt` | 两个上屏触发点各加一句 `checkChangelogOnUpdate()`；`changelogStore` 是 `internal var` 注入缝 |

用例 **1430** 全绿（新增 24：`ChangelogOnUpdateTest` 22 + `ChangelogDialogProbe` 2），包重打 `[verify] OK`。

出图与编译期揪出来的四件事（都留了痕）：

| 症状 | 根因 |
|---|---|
| 探针出的图**一片空白** | 探针把内容面板与弹簧都塞进 `BorderLayout.CENTER` —— 同一个区域放两个组件时只有后加的那个会被排，内容被摆成 0 尺寸（探针自己的注释里记了） |
| `<code>` 里的 commit 号显示成正文字体 | JEditorPane 只认 HTML 3.2 那套 CSS，`font-family` 基本被忽略；写一条 `code { font-family: … }` 反而盖掉了它自带的等宽样式 —— 那条规则删了 |
| 探针拿不到内容面板 | 平台的 `createCenterPanel()` 是 `protected`；照 `SettingsDialog.contentPanel` 的老写法暴露一个 `internal val contentPanel` |
| 构造器塞不进 store | `ClaudePanel` 是 public 类，参数类型 `ChangelogStore` 是 internal —— 编译器不让（"public function exposes its internal parameter type"）。改成 `internal var` 属性当注入缝 |

---

## 6. 没做的 / 待办

- **0.2.19 的 change-notes 还是占位** —— 这个功能要真正生效，得先把它写出来（本来也是上架前的待办）。
  在写好之前，闸门（§3.4）让这一版**什么都不弹**，这是刻意的。
- 不做「查看完整更新记录」的跳转按钮（市场页链接），也不做「以后不再提示」——
  前者这版没有额外的信息量，后者的语义已经被"每个版本只弹一次"覆盖了。
- 不改插件管理器 / 市场的行为（它们本来就显示 change-notes）。
