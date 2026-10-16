# 上架 JetBrains Marketplace：规矩、实测、以及名字的决定

日期：2026-09-15 · 分支 `v0.2.13-dev` · 状态：**插件侧已就绪，等手传**

## 一、为什么 Gradle 发不出去（不是配置问题）

JetBrains 官方原文：

> The first plugin publication must always be uploaded manually.
> …Once Gradle support has been configured, **and the plugin has been uploaded manually
> to the plugin repository at least once**, it can be built and deployed … using dedicated
> Gradle tasks.

也就是说 `./gradlew publishPlugin` **发不了第一版**。首版只能走浏览器：
JetBrains 账号 → plugins.jetbrains.com → Profile → Add new plugin → 传 `build/distributions/CCoder-0.2.13.zip`。
第 2 版起才轮到 `publishPlugin`（需要 `intellijPlatformPublishingToken`；签名**可选**——
不签的后果只是用户安装时弹一个警告框，`signPlugin` 在没配证书时会被自动跳过）。

本仓库**没有任何**发布配置（无 token、无签名证书、`build.gradle.kts` 里没有 publishing / signing 块）。
这没关系：上面那条规矩没满足之前，配了也用不上。

**2026-09-18 更正**：这条已不成立 —— `build.gradle.kts` 后来补上了 publishing 块
（token 从 `GRADLE_USER_HOME` 的 gradle.properties 读，**仍不进仓库**；没有签名证书，
`signPlugin` 会自动跳过）。0.2.21 的上传走的就是 `./gradlew publishPlugin`。

## 二、描述符补了什么

| 补的 | 依据（审核指南原句） |
|---|---|
| `vendor` 加 `email` | "Vendor's email address is valid" |
| `description` 换成英文三段 | "the plugin description must start with Latin characters and have at least 40 characters"；且要求英文在前 |
| `change-notes`（中英各 6 条） | "Description and change notes are present and changed from the default values" |

`vendor` 的 `url` **空着**：没有主页也没有公开仓库，不编一个指到别人家的网址。
审核若因此打回，补一行 `url="..."` 即可。

## 三、搜索是怎么排的（官方原文 + 实测）

被匹配的文本字段是五个：

> These text fields include the **plugin ID, name, description, tags, and the plugin's
> vendor name**. Matches in different fields are scored differently.

算出初始分之后，要乘两个系数：`log10(1 + 5 × 下载数)` 与 `sqrt(评分)`。

**实测三条**（2026-09-15，走 `plugins.jetbrains.com/api/…`）：

| 量到什么 | 数 |
|---|---|
| 标签总数 | **170 个固定项**，里面**没有 "Claude"**，也没有能拼出 "claude code" 的 —— 标签扛不动这个词，它只管分类和筛选 |
| 搜 "claude code" | 命中 373 条，**前 20 名的名字里全部带 "Claude Code"** |
| 下载量的实际分量 | 第 20 名 1273 下载 → `log10(6366)=3.8`；第 1 名 107 万 → `log10(5394246)=6.7`。**只差 1.8 倍**，不是"新插件永远没戏" |

结论：这条路上起作用的是**名字**，不是描述。所以有了下面这一节。

## 四、名字：CCoder (Claude Code Assistant)

- 括号是市场指南 ✅ 栏里**点名允许**的符号（"period, hyphen, parentheses"）
- 4 个词，落在指南的 1–4 词范围内；30 字符，远低于 60 的硬上限
  （"建议 ≤20"是建议，不是硬性）
- 搜 "claude code" 的**第一名**就是同款结构："CC GUI (Claude, Codex and More)"
- **名字的真正来源是 `gradle.properties` 的 `pluginName`** —— `pluginConfiguration.name`
  会在 patchPluginXml 时覆盖 plugin.xml 里那一行。两处都写了同样的值，**别只改 plugin.xml**
- `<id>` 不动（`com.ccoder.claudecode`）：已发布的插件靠 id 认身份，改了等于换一个插件
- 代码里那几处 `"CCoder"`（工具窗口 id / 通知组 id / 凭据服务名）是**标识**不是显示名，不动

**改了名字之后实测过**：打包路径一个字没变 —— zip 仍叫 `CCoder-0.2.13.zip`，
里面仍是 `CCoder/lib/CCoder-0.2.13.jar`（这些取自 `rootProject.name`，不是 `pluginName`）。
所以 `.install-plugin.py` 里写死的 `CCoder` 目录名与 `CCoder-*.jar` **不受影响**。

**已知的代价**：市场里已有一个插件就叫 `Claude Code Assistant`
（`com.claude.code.plugin`，39418 下载，搜 "claude code" 排第 6）。全名不同、id 不同，
不算冲突，但两条会在搜索结果里挨着。要完全避开重名，可以用 `CCoder for Claude Code`（22 字符）。

## 五、上传表单里还得做的（仓库里改不了）

- **Tags**：至少选一个。建议 `AI` + `Code Tools` + `Code Editing`（同赛道第一名就是这个组合）
- **License**：必填，市场不给推荐
- 截图 / 视频、Getting started、源码链接都可以后补；**description 与 tags 也能在后台改，
  不用重传包**

### 描述已改成"后台直编"（2026-09-17）

市场从 2025-09 起支持在插件页直接编辑描述（General Information → Description）：
**Markdown 编辑器，存进去是 HTML，不用重传包**。仓库里那份文字的两个外壳是：

| 位置 | 角色 |
|---|---|
| `src/main/resources/META-INF/plugin.xml` 的 `<description>` | 源码侧权威副本，随包发上去 |
| `docs/marketplace-description.md` | 同一份的 Markdown，给后台粘贴用 |

第一次在后台编辑时会问"以后都以后台为准，还是只到下次更新为止"。**选后者** ——
仓库就仍是唯一的权威，下次传包时 plugin.xml 里是同一份文字，不会回退。

口径：英文在前（指南要求**首 40 字符是英文**，预览卡取的就是这一段），中英各一份（§八）。
**不用 `<h1>`~`<h6>`**：后台存 HTML，标题标签在论坛上有出问题的报告，粗体小标题同样清楚
（同赛道第一名用了 h3 也没事，但我们没有非用不可的理由）。

当日实测备案：市场 API 上列出的已上架版本到 **0.2.17** 为止 —— 0.2.18 已上传但还没过审，
所以页面上的描述此刻仍是 0.2.17 那一份（也就是 plugin.xml 里那段两句话）。

**2026-09-17 更新**：0.2.18 **已过审上架**（用户确认）。所以 0.2.19 的 change-notes 按
"相对 0.2.18 的增量"写，**不**并 0.2.18 那四条（d342e45）—— 用户更新到 0.2.18 时已经看过了。

**2026-09-18 更新**：0.2.19 **也已过审上架**（这次不是"用户确认"，是查出来的 ——
`api/searchPlugins` 拿到 id 34281，`api/plugins/34281/updates` 上 `approve: true`、
`listed: true`、`version: 0.2.19` 就是线上那一版）。所以 0.2.20 同样只写增量，不并
0.2.19 那六条（3a9a6f3）。这条查法留着：以后开版时先打一次这个 API，别靠记忆。

**同日更晚**：0.2.20 **也已过审上架**（同一个 API 复核：`approve: true`、`listed: true`）。
开 0.2.21 时因此按"0.2.20 已上架"写增量，b490a9d 那五条不并进来。

**2026-09-21 更新**：**0.2.22 已过审上架**（开 0.2.23 时同一个 API 复核：`approve: true`、
`listed: true`），所以 0.2.23 的清单只写增量（五条，5485344），77d3b9e 那三条不并进来。

同日 `./gradlew publishPlugin` 上传 **0.2.23**。回读 `api/plugins/34281`：
`hasUnapprovedUpdate: true` —— 已落库、等审核（0.2.23 此时**不在** `/updates` 里，
过审前那里看不到，这条同 0.2.22）。过审后的复核还是那一句：`/updates` 里出现
`version: 0.2.23` 且 `approve: true`、`listed: true`。

这版的发布链与绿数（照 §五 的顺序）：

| 闸 | 结果 |
|---|---|
| Kotlin（zh） | **1573 条全绿** |
| Kotlin（`-PtestLang=en`） | 1573 条、161 失败 —— 全是"断言钉中文"的老形状（英文那遍只用来出图与量宽，见 english-ui-design §五），不是回归 |
| sidecar | **213 条全绿** |
| web | **279 条全绿**（19 个文件） |
| `buildPlugin` | zip 8,728,176 字节；打包校验 `[verify] OK: manifest 5001 entries`；jar 里 plugin.xml 已是 0.2.23 且**不含**占位字样 |
| `verifyPlugin` | PY-253 / 261 / 262 / 263 **四份都 Compatible**，只有 deprecated 用法（`JBUI.scale(float)`×5、`SimpleListCellRenderer.create`×3 等，全是旧版就在的），无 internal API 违规 |
| `publishPlugin` | BUILD SUCCESSFUL（`signPlugin SKIPPED`，照旧没证书） |

## 六、还没解决的前提

插件包里带着 `@anthropic-ai/claude-agent-sdk` 的 6035 个文件，而它的 `LICENSE.md` 只有一行：

> © Anthropic PBC. All rights reserved.

**专有许可，不是开源的。** 把它连着插件发到公开市场是法律问题，市场不会替你拦。
这一条没有结论，是留给作者的判断。

## 七、0.2.13 被卡：internal API（2026-09-15 当天）

上传后市场自动跑的 Plugin Verifier 报了两条红字：

| 红字 | 出处 |
|---|---|
| `Internal method usage: DataContext.getData(String)` | `ImagePaste.kt` 里那个 6 行的 `private object EmptyDataContext` 覆写了它（该方法是 `@ApiStatus.Internal`） |
| `Non-extendable interface usage violation: DataContext` | **同一个对象**实现了 `DataContext`（接口本身标着 `@ApiStatus.NonExtendable`） |

JetBrains 的审核指南里有一条明写的 approval criterion：「The Plugin does not violate
JetBrains' internal API usage」；论坛上员工的回答是 "most likely we will never approve
such new usages in a plugin and the version will not be published"。**这不是提示，是拦路。**

**为什么会写出来**：`PasteProvider.isPastePossible(DataContext)` 的签名要一个上下文，
而我们的 provider 根本不用它 —— 当初随手造了个空的。**平台自带
`DataContext.EMPTY_CONTEXT`**，没有任何理由自己造。

**修法**：`provider.isPastePossible(DataContext.EMPTY_CONTEXT)`，删掉那个 object，
零行为变化。0.2.14 发。

**一处容易误判的**：`ComposerTextArea` 实现的 `UiCompatibleDataProvider` **不含**
`DataContext` —— 从 PyCharm 2025.3.1.1 的字节码里读出来的（它继承的是 `DataProvider`
+ `UiDataProvider`）。所以那两条红字只出自 `EmptyDataContext` 一个地方，这也解释了
为什么报告里两条的计数都是 1 而不是 2。

### 教训：上传前跑 verifyPlugin

我们从头到尾**没跑过** `./gradlew verifyPlugin`。从 IntelliJ Platform Gradle Plugin
2.15.0 起它会直接在 internal / override-only API 用法上判失败 —— 也就是说这道闸门
本来就在手边，白挨了一轮审核。

发版流程补一步：

```
三侧测试 → buildPlugin → ./gradlew verifyPlugin → publishPlugin
```

**2026-09-18 补（跑这条链之前先做的事）**：把 nodejs 目录塞到 PATH **最前** ——
Git Bash 里 `export PATH="/c/Program Files/nodejs:$PATH"`，守护进程要
`./gradlew --stop` 重建才吃到新 PATH。原因：系统 PATH 里有一条**带引号的坏项**
（`…WBEM"`，旁边还有几条丢了反斜杠的），cmd 按名字找命令时遇到引号会把**它后面**
的目录全废掉 —— nodejs 恰好在后面，于是 `npm.cmd` 的 shim 报
"'node' 不是内部或外部命令"，而 `where node` 又找得到（它自己分词，不受影响）。
0.2.21 这次就是被这个卡过一次 buildWebUi。

两个坑：它**不能和别的 Gradle 构建并发跑**（会撞 "Timeout waiting to lock Artifact
transforms cache"）；第一次跑要下 IDE 分发，慢。

**2026-09-24 补（这条闸门被跳过了一次，所以更要紧了）**：0.2.26 发的时候**又没跑**这条
—— 而且这次是"知道规矩但没照做"。代价一样：市场报 `Internal method usage (1)
JBCefApp.getNativeBundleVersionString()`（`JcefBrokenAdvice` 里取 JCEF 版本那处，
字节码里是 `public static`，拦截的是注解），0.2.26 没上架；改走 `org.cef` 的公开 API
之后，0.2.27 一次过（`approve`/`listed` 都真）。

所以"靠人记"到此为止：**该把它接成 `publishPlugin` 的前置依赖**。接之前先解决第三个
（EAP）验证目标本地挂住的问题 —— 跑到 "Finished 2 of 3" 之后就再也不写日志了
（261/262 都正常报 Compatible），现在接上会把发版本身卡死。见 0.2.28 开版挂账 2。

**2026-10-08 更正（0.2.31 发版时量到的）**：那个"第三个卡死"今天没有复现 ——
11:42:09 报完 `Finished 2 of 3` 之后同样长时间不出日志、进程 CPU 采样也纹丝不动
（41.79s → 41.79s），差点按挂账把它毙了；结果 11:43:51 它自己完成：
`Finished 3 of 3: PY-263.6259.38: Compatible`。静止的那段是**在下载插件依赖**
（日志尾部记着 "Total time spent downloading plugins and their dependencies:
1 m 19 s"；期间 CPU 空转、没有大文件落盘，看起来与挂住一模一样）。整条
`verifyPlugin` 全长 6 分 07 秒。所以挂账 2 的前提要重新量：若"本地挂住"就只是
这一段下载，"接成 publishPlugin 前置依赖"也许已经可行 —— 但再遇到"挂住"先等足
十分钟再下结论。

0.2.31 这版的发布链与绿数（照 §五 的顺序）：

| 闸 | 结果 |
|---|---|
| Kotlin | **2053 条全绿** |
| sidecar | **216 条全绿**（含新用例 skills-once.test.js） |
| web | **316 条全绿**（21 个文件） |
| `buildPlugin` | zip 9,075,408 字节；打包校验 `[verify] OK: manifest 5005 entries`；jar 里 plugin.xml 已是 0.2.31 且**不含**占位字样 |
| `verifyPlugin` | PY-261 / PY-262 / PY-263 **三份都 Compatible**，只有 deprecated / scheduled-for-removal / experimental 用法，无 internal API 违规 |
| `publishPlugin` | BUILD SUCCESSFUL（`signPlugin SKIPPED`，照旧没证书） |
| GitHub release | `gh release create v0.2.31 --verify-tag --title "CCoder 0.2.31" --notes-file docs/releases/0.2.31.md build/distributions/CCoder-0.2.31.zip`，一次成功、自动成为 Latest；附件 9,075,408 字节（与 marketplace 那份同 sha） |

2026-10-08 补记：**gh 早就装着且登录着**（2.101.0，princeyuaner，repo scope；
0.2.25 起就是用它建的）—— 早先"建 release 要认证、别去翻 token"那条是更早的挂账，
已作废。正文口径核对：release 里存的与 `docs/releases/<版本>.md` **只差一个尾行的
空行**（0.2.30 对照出来的），所以 `--notes-file docs/releases/<版本>.md` 就是标准做法。

0.2.32 这版的发布链与绿数（照 §五 的顺序，2026-10-09）：

| 闸 | 结果 |
|---|---|
| Kotlin | **2089 条全绿**（`test --rerun` 强制真跑；上一次真实执行是 3dae420 那棵树） |
| sidecar | **225 条全绿**（含 MCP 两条控制请求带来的 9 条新用例） |
| web | **316 条全绿**（21 个文件） |
| `buildPlugin` | zip 9,201,350 字节；打包校验 `[verify] OK: manifest 5005 entries`；jar 里 plugin.xml 已是 0.2.32 且**不含**占位字样 |
| `verifyPlugin` | PY-261 / PY-262 / PY-263 **三份全 Compatible**；internal API **0 条**（只有 1 条 deprecated + 19 条 experimental，与 0.2.31 同型） |
| `publishPlugin` | BUILD SUCCESSFUL（`signPlugin SKIPPED`）；回读 `updates?version=0.2.32` → id 1190346，当天审核中（approve/listed=false，属正常 —— 写 0.2.33 的 change-notes 之前复核它） |
| GitHub release | `gh release create v0.2.32 --verify-tag …`，一次成功、自动 Latest；附件 9,201,350 字节；正文与 `docs/releases/0.2.32.md` 尾空白归一后一致 |

tag 照例打在含 change-notes 的那棵树（`v0.2.32` → c011f7f），release 正文那笔
（8b646d0）在 tag 之外。zip 的 sha256（marketplace 与 GitHub 同一份）：
`caa8babb730f22cdbf293ec270f70377c5af76db52b20a7456b074240842447e`。

0.2.33 这版的发布链与绿数（照 §五 的顺序，2026-10-10）：

| 闸 | 结果 |
|---|---|
| Kotlin | **2113 条全绿**（`test --rerun` 强制真跑） |
| sidecar | **250 条全绿**（含使用量账本/回填带来的 25 条新用例） |
| web | **324 条全绿**（21 个文件） |
| `buildPlugin` | zip 9,291,698 字节；打包校验 `[verify] OK: manifest 5007 entries`；jar 里 plugin.xml 已是 0.2.33 且**不含**占位字样 |
| `verifyPlugin` | PY-261 / PY-262 / PY-263 **三份全 Compatible**；internal API **0 条**（deprecated 16 + scheduled-for-removal 3 + experimental 6 —— 全是旧版就在的那批：ToolWindowFactory 覆写、JBUI.scale(float) 等） |
| `publishPlugin` | BUILD SUCCESSFUL（`signPlugin SKIPPED`）；回读 `api/plugins/34281` → `hasUnapprovedUpdate: true`；`updates?version=0.2.33` → id **1190681**，当天审核中（approve/listed=false，属正常 —— 写 0.2.34 的 change-notes 之前复核它） |
| GitHub release | `gh release create v0.2.33 --verify-tag …`，一次成功、自动 Latest；附件 9,291,698 字节（与 marketplace 同一份） |

tag 照例打在含 change-notes 的那棵树（`v0.2.33` → c1b3395），release 正文那笔
（c2c0135）在 tag 之外。zip 的 sha256（marketplace 与 GitHub 同一份）：
`65557b03da076709ba7bd5fd4f3977897de03c8da0a21132c64d4edeeabbc6d5`。
增量口径三条：使用量统计（常驻条 + 仪表盘）、流式上滚修复、/clear 清清单 ——
2026-10-10 复核过 0.2.32 已上架（id 1190346），不并条。

0.2.34 这版的发布链与绿数（照 §五 的顺序，2026-10-11）：

| 闸 | 结果 |
|---|---|
| Kotlin | **2121 条全绿**（`test --rerun` 强制真跑） |
| sidecar | **250 条全绿** |
| web | **335 条全绿**（22 个文件） |
| `buildPlugin` | zip 9,297,597 字节；打包校验 `[verify] OK: manifest 5007 entries`；jar 里 plugin.xml 已是 0.2.34 且**不含**占位字样；包内 web UI 带新引导卡（中英两条都在、旧 `sessionReady` 键已消失） |
| `verifyPlugin` | PY-261 / PY-262 / PY-263 **三份全 Compatible**；internal API **0 条**（deprecated 16 + scheduled-for-removal 3 + experimental 6 —— 与 0.2.33 同批：ToolWindowFactory 覆写、JBUI.scale(float) 等） |
| `publishPlugin` | BUILD SUCCESSFUL（`signPlugin SKIPPED`）；回读 `updates?version=0.2.34` → id **1191350**，当天审核中（approve/listed=false，属正常 —— 写 0.2.35 的 change-notes 之前复核它）；`api/plugins/34281` → `hasUnapprovedUpdate: true` |
| GitHub release | `gh release create v0.2.34 --verify-tag …`，一次成功、自动 Latest；附件 9,297,597 字节（与 marketplace 同一份）；正文与 `docs/releases/0.2.34.md` **逐字一致**（这次连尾行空行都不差） |

tag 照例打在含 change-notes 的那棵树（`v0.2.34` → e396116），release 正文那笔
（3b43fba）在 tag 之外。zip 的 sha256（marketplace 与 GitHub 同一份）：
`7e41e8021c3b8b37e03fb59c2997e1a6f0064db76ac83ca032fb5d1959637b48`。
增量口径五条：空会话引导卡（含「会话已就绪」提示退场）、插件图标回原稿、
MCP 设置页自刷新、会话列表行尾对齐、使用量常驻条跟走 —— 2026-10-11 复核过
0.2.33 已上架（id 1190681），不并条。

0.2.35 这版的发布链与绿数（照 §五 的顺序，2026-10-12）：

| 闸 | 结果 |
|---|---|
| Kotlin | **2134 条全绿**（`test --rerun` 强制真跑，2m23s） |
| sidecar | **250 条全绿** |
| web | **335 条全绿**（22 个文件） |
| `buildPlugin` | zip 9,313,391 字节；打包校验 `[verify] OK: manifest 5007 entries`；jar 里 plugin.xml 已是 0.2.35 且**不含**占位字样 |
| `verifyPlugin` | PY-261 / PY-262 / PY-263 **三份全 Compatible**（2m12s，今天没遇到"静止下载"那段）；internal API **0 条**（deprecated 16 + scheduled-for-removal 3 + experimental 6 —— 与 0.2.33/0.2.34 同批：ToolWindowFactory 覆写、JBUI.scale(float) 等） |
| `publishPlugin` | BUILD SUCCESSFUL（`signPlugin SKIPPED`）；回读 `updates?version=0.2.35` → id **1192203**，当天审核中（approve/listed=false，属正常 —— 写 0.2.36 的 change-notes 之前复核它）；`api/plugins/34281` → `hasUnapprovedUpdate: true` |
| GitHub release | `gh release create v0.2.35 --verify-tag …`，一次成功、自动 Latest；附件 9,313,391 字节（与 marketplace 同一份） |

tag 照例打在含 change-notes 的那棵树（`v0.2.35` → b7e26e3），release 正文那笔
（7da9814）在 tag 之外。zip 的 sha256（marketplace 与 GitHub 同一份）：
`6dd45dbbf4b0024abc8c5c85588cc0dbd9010079ff7519fca4a9d630179c6d8f`。
增量口径一条：使用量显示真实扣费（DMXAPI 对账条 + 常驻条「实扣¥…」段）——
2026-10-12 复核过 0.2.34 已上架（id 1191350），不并条。

0.2.36 这版的发布链与绿数（照 §五 的顺序，2026-10-13）：

| 闸 | 结果 |
|---|---|
| Kotlin | **2135 条全绿**（`test --rerun` 强制真跑，2m37s） |
| sidecar | **250 条全绿** |
| web | **335 条全绿**（22 个文件） |
| `buildPlugin` | zip 9,315,912 字节；打包校验 `[verify] OK: manifest 5007 entries`；jar 里 plugin.xml 已是 0.2.36 且**不含**占位字样 |
| `verifyPlugin` | PY-261 / PY-262 / PY-263 **三份全 Compatible**（2m08s）；internal API **0 条**（deprecated 16 + scheduled-for-removal 3 + experimental 6 —— 与 0.2.33/0.2.34/0.2.35 同批：ToolWindowFactory 覆写、JBUI.scale(float) 等） |
| `publishPlugin` | BUILD SUCCESSFUL（`signPlugin SKIPPED`）；回读 `updates?version=0.2.36` → id **1192692**，当天审核中（approve/listed=false，属正常 —— 写 0.2.37 的 change-notes 之前复核它）；`api/plugins/34281` → `hasUnapprovedUpdate: true` |
| GitHub release | `gh release create v0.2.36 --verify-tag …`，一次成功、自动 Latest；附件 9,315,912 字节（与 marketplace 同一份） |

tag 照例打在含 change-notes 的那棵树（`v0.2.36` → cc706b5），release 正文那笔
（6d0e7ff）在 tag 之外。zip 的 sha256（marketplace 与 GitHub 同一份）：
`458bc88f6ee21b5dabd8374793de596955f74ef6daa4301e66ef5fcdfda6aa4b`。
增量口径两条：底部三个弹层改贴各自标签弹出（修「底下这几个弹框全部靠左」）、
补全弹层上下键换选中就地翻面 —— 2026-10-13 复核过 0.2.35 已上架（id 1192203），不并条。

0.2.38 这版的发布链与绿数（2026-10-13；**按用户指示只发 GitHub release，不推插件
市场** —— 这是与 0.2.36 全链那次最大的差别）：

| 闸 | 结果 |
|---|---|
| Kotlin | **2151 条全绿**（三侧分别跑的那一轮里 `test` 真跑，2m39s） |
| sidecar | **274 条全绿** |
| web | **368 条全绿**（24 个文件） |
| `buildPlugin` | zip 9,378,190 字节；打包校验 `[verify] OK: manifest 5008 entries`；jar 里 plugin.xml 已是 0.2.38 且**不含**占位字样（`grep -c TODO` = 0） |
| `verifyPlugin` | PY-261 / PY-262 / PY-263 **三份全 Compatible**（2m03s）；internal API **0 条**（PY-261：deprecated 16 + experimental 6；PY-262/263 另加 scheduled-for-removal 3 —— 与 0.2.36 同批：ToolWindowFactory 覆写、JBUI.scale(float) 等） |
| `publishPlugin` | **未跑**（用户指示不推市场） |
| GitHub release | `gh release create v0.2.38 --verify-tag …`，一次成功、自动 Latest；附件 9,378,190 字节；正文与 `docs/releases/0.2.38.md` 复核**一字不差**（API 回读） |

tag 照例打在含 change-notes 的那棵树（`v0.2.38` → f887c3a），release 正文那笔
（1b54e76）在 tag 之外。zip 的 sha256：
`c1b849d68e8dcb3d226f662e317335a8e5b397d448e0d75726e9de098f22f1c3`。
增量口径**三条**（0.2.37 从未上传，这批一并端出，按相对 0.2.36 的增量写）：
「本轮改动」卡（清单 / 对比 / 回滚）、MCP 未连完提示带（发送前就地确认）、
使用量常驻条与状态卡样式统一 —— 发版前复核 0.2.36 已上架（id 1192692）、
`updates?version=0.2.37` 仍为空数组。

**2026-10-14 补推市场**（用户指示）：0.2.38 当时只发 GitHub、没推市场，今天补上 ——
`git stash -u` 暂存 0.2.41 的未提交改动 → checkout `v0.2.38` 那棵树 → `./gradlew --stop`
→ `buildPlugin`（1m03s，`[verify] OK: manifest 5008 entries`）→ `publishPlugin`（31s，
`signPlugin SKIPPED`）。**重建的 zip 与 GitHub 附件字节一致**（sha256 仍是 `c1b849d6…`，
构建可复现 —— 市场拿到的就是同一个包）。回读 `updates?version=0.2.38` → id **1193332**，
当天审核中（approve/listed=false，属正常）；change-notes 三条照旧（0.2.36 已上架、不并条）。
推完 `git checkout v0.2.41-dev` + `stash pop`，工作区 `git diff` 哈希与推前一致。

0.2.41 这版的发布链与绿数（2026-10-14；**按用户指示只发 GitHub release，不推插件
市场**）：

| 闸 | 结果 |
|---|---|
| Kotlin | **2199 条全绿**（`test --rerun` 强制真跑，3m01s） |
| sidecar | **274 条全绿** |
| web | **377 条全绿**（25 个文件；首跑 1 条时序哨兵抖动，重跑全绿） |
| `buildPlugin` | zip 9,439,361 字节；打包校验 `[verify] OK: manifest 5008 entries`；jar 里 plugin.xml 已是 0.2.41 且**不含**占位字样（`grep -c TODO` = 0） |
| `verifyPlugin` | **本轮未跑**（用户要求提速；0.2.38 那三份兼容核查刚全过、本批改动都是 UI 内部代码 —— 要补跑随时 `./gradlew verifyPlugin`） |
| `publishPlugin` | **未跑**（用户指示不推市场） |
| GitHub release | `gh release create v0.2.41 --verify-tag …`，一次成功、自动 Latest；附件 9,439,361 字节；正文与 `docs/releases/0.2.41.md` 复核一致（API 回读） |

tag 照例打在含 change-notes 的那棵树（`v0.2.41` → b967f39），release 正文那笔
（4cf8d82）在 tag 之外。zip 的 sha256：
`ae88a8f134102fc461953884d0fc0e37a6093e1eb1167dea2d6fef5af6fda0e0`。
增量口径**六条**、按相对 **0.2.38** 的增量写（0.2.38 于 10-14 补推市场、当天在审
未上架，正替 0.2.37 起的批次走进市场；0.2.36 仍是最近已上架版）：分隔条握把、
模型弹层两栏、「本轮改动」卡限高、定时发送、opencode 额度 + DeepSeek 余额、
卡片描边四角。**挂账**：若 0.2.38 未过审，推 0.2.41 上市场前要把它那三条并回
（plugin.xml 注释与 change-notes 提交里都记了）。

0.2.42 这版的发布链与绿数（2026-10-14；**按用户指示只发 GitHub release，不推插件
市场**）：

| 闸 | 结果 |
|---|---|
| Kotlin | **2222 条全绿**（`test --rerun` 强制真跑，2m24s） |
| sidecar | **274 条全绿** |
| web | **379 条全绿**（25 个文件；长正文那条 jsdom 耗时哨兵在并发下贴阈值 —— Gradle 守护进程还在时整包跑到 13–15ms/帧（阈值 12）红了两轮，停掉守护进程后 11.87ms 过线；单文件跑 5.5ms，正文渲染路径本批未动） |
| `buildPlugin` | zip 9,457,558 字节；打包校验 `[verify] OK: manifest 5008 entries`；jar 里 plugin.xml 已是 0.2.42 且**不含**占位字样（`grep -c TODO` = 0）；包内 webui 带新前端（`previewImage` 在） |
| `verifyPlugin` | **三份全 Compatible**（2m37s）：PY-261 deprecated 17 + experimental 6；PY-262 / PY-263 另加 scheduled-for-removal 3（PY-263 deprecated 18）—— 全是旧族（StatusBarWidget / ToolWindowFactory 覆写、`JBUI.scale(float)` 等；比 0.2.38 多的那条是 0.2.41 定时键 `ScheduleButton` 的同族用法），internal API **0 条** |
| `publishPlugin` | **未跑**（用户指示不推市场） |
| GitHub release | `gh release create v0.2.42 --verify-tag …`，一次成功、自动 Latest；附件 9,457,558 字节（与本地 zip 同件）；正文与 `docs/releases/0.2.42.md` 复核一致（API 回读，尾空白归一） |

tag 照例打在含 change-notes 的那棵树（`v0.2.42` → ab68c5b），release 正文那笔
（1308002）在 tag 之外。zip 的 sha256：
`737f7f646d804ec6ad354ec69c8f9dc16a4c731d5a9e649dee62fa1dd0de7823`。
增量口径**九条**、按相对 **0.2.38** 的增量写（0.2.38 已于 10-14 过审上架（id
1193332），是最近一个已上架版；0.2.39 / 0.2.40 / 0.2.41 从未上传）：0.2.41 那批
六条（分隔条握把、模型弹层两栏、「本轮改动」卡限高、定时发送、opencode 额度 +
DeepSeek 余额、卡片描边四角）整块接着并，末尾续写 0.2.42 的三条（思考中标题一行、
「MCP 还没连完」改工具栏徽章 + 居中确认框、输出区点图改平台看图框）。**挂账结清**：
0.2.41 记的"若 0.2.38 未过审、推市场前把它那三条并回"——已过审，不并。

**2026-10-15 补推 0.2.39**（用户指示 —— 推的是**字面 0.2.39**：它从未发过 GitHub
release，内容后来并进了 0.2.41）：树 = `v0.2.39-dev` 顶端 3384fe4（0.2.38 + 分隔条
握把一条），而它的 change-notes 在树上还是 TODO 占位 —— 先按 §八 补写成"相对 0.2.38
的增量（一条）"（措辞整块取自 tag v0.2.42 的清单、只改版本号），提交 `8d948d7` 推
origin。打包前把盘上的 sidecar 依赖 `npm ci` 归位到树的 lock（0.3.299 → 0.3.293，
否则包内 package.json 与 node_modules 打架）；`buildPlugin`（1m13s，
`[verify] OK: manifest 5008 entries`）→ 包内抽查 plugin.xml=0.2.39、无 TODO、
SDK=0.3.293 → Kotlin **2155 条全绿**（0.2.38 的 2151 + 握把 4 条）→ `verifyPlugin`
PY-261 / 262 / 263 **三份全 Compatible**（2m10s；无 internal API，旧族 experimental/
deprecated 照旧）→ `publishPlugin`（29s，`signPlugin SKIPPED`）→ 回读
`updates?version=0.2.39` → id **1194272**，当天审核中（approve/listed=false，属正常）。
zip（9,381,491 字节）sha256：
`5ca0a4ded7b9d6bc1d0b0b39ef3c876a82a729459b243855373649c68d841425`。市场 API 的
`size` 字段（9,387,599）比本地上传包大 **+6,108 字节** —— 0.2.38 那笔同差，是它的
记账口径，不是传错包。推完 `git checkout v0.2.44-dev` + `stash pop` + sidecar
`npm ci` 升回 0.3.299；工作区 diff / status 哈希与推前一致（7cc72b91… / 1a8be698…）。
**基线连锁**：0.2.39 现在是最近的"已上传"版 —— 下一个上传版本的清单 = 相对 0.2.39
的增量（= 0.2.42 那份九条去掉握把第一条，再续写 0.2.43 / 0.2.44 的增量）；若 0.2.39
没过审，按 §八 把握把那一条补回来。

**2026-10-16 补推 0.2.40**（用户指示）：先复核 0.2.39 —— `updates?version=0.2.39`
→ id 1194272、`approve`/`listed` 都真，**已过审上架**（10-15 补推那次只是"当天在审"），
于是它成了最近的已上架版，本版清单按**相对 0.2.39 的增量**写（两条：模型弹层两栏、
「本轮改动」卡限高；措辞整块取自 tag `v0.2.42` 的清单、只改版本号，§八），提交
`cfae474` 推 origin（此前 origin 落后 3 笔开发提交，一并推上）。打包前把盘上的
sidecar 依赖 `npm ci` 归位到树的 lock（0.3.299 → 0.3.293）；`buildPlugin`（1m01s，
`[verify] OK: manifest 5008 entries`）→ 包内抽查 plugin.xml=0.2.40、无 TODO、
SDK=0.3.293 → Kotlin **2158 条全绿**（0.2.39 的 2155 + 弹层两栏那笔 3 条）→ web
**369 条全绿**（24 个文件；首跑 1 条时序哨兵抖动，连跑两遍全绿）→ `verifyPlugin`
PY-261 / 262 / 263 **三份全 Compatible**（2m34s；internal API 0 条，旧族 deprecated +
experimental 照旧，本次无 scheduled-for-removal 条目）→ `publishPlugin`（26s，
`signPlugin SKIPPED`）→ 回读 `updates?version=0.2.40` → id **1195360**，当天审核中
（approve/listed=false，属正常）。zip（9,387,483 字节）sha256：
`2e589d6a5c03be0bbde02c30ee6a9376a6c58febc3c0457f287e45fc725d4365`。市场 API 的
`size` 字段（9,393,591）比本地上传包大 **+6,108 字节** —— 与 0.2.38 / 0.2.39 同差，
是它的记账口径，不是传错包。推完 `git checkout v0.2.44-dev` + `stash pop` + sidecar
`npm ci` 升回 0.3.299；工作区 diff / status 哈希与推前一致（`db2350e1…` / `1066ba43…`）。
**基线连锁**：0.2.39 已上架、0.2.40 在审 —— 下一个上传版本的清单 = 相对最近那个
已上架版的增量（0.2.40 过审即接替 0.2.39 当基线：0.2.42 九条去掉前三条 —— 握把随
0.2.39、弹层两栏 + 本轮改动卡随 0.2.40 —— 再续写 0.2.43 / 0.2.44 的增量）。

## 八、What's New 写哪几版（2026-09-16 补）

**规则：清单只写当前版本相对"上一个已上架版本"的增量。**

这条此前是**隐式**的，靠成例摸着走 —— 而 0.2.13 被拒、0.2.14 与 0.2.15 又都没上架，
所以那几次开版时就把前一代的条目**带着走**（根本没有"上一个已上架版本"可言，
带着走是对的）。0.2.15 上传之后，开 0.2.16 时又照抄了一遍 —— 于是 0.2.16 的清单里
躺着**十条其实属于 0.2.15 的内容**，直到有人问"这版的 What's New 有什么"才发现。

2026-09-16 起按增量写，并接受一条已知风险：**万一 0.2.15 最终没过审，0.2.16 就是
首个公开版本**，那时要把它那十条再补回来。补回来比删掉容易 —— 十条都还在 git 历史里
（`git show 0073cd0:src/main/resources/META-INF/plugin.xml`）。

**2026-09-24 补（这条应急方案被真用过一次，管用）**：0.2.26 被 Plugin Verifier 拦下
没上架（§七），于是 0.2.27 就落在"首个公开版本"那一侧 —— 它把 0.2.26 那七条原样带了
出去（commit 1270187，tag `v0.2.27`）。**0.2.27 过审了**（`approve`/`listed` 都真），
用户拿到的就是那七条，这条风险到此结清，0.2.28 起回到正常增量。

并条那一步的做法记在这儿，下次直接照做：`git show v0.2.26:src/main/resources/META-INF/plugin.xml`
把上一版那段**整块取出来**，只改 `<h3>` 里的版本号，措辞一个字不动。整块取的好处是
不必重新措辞 —— 少半个词不会有人发现，而这段字正是用户唯一会读的那段。

## 出处

- [Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
- [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
- [Plugin search results and rankings](https://plugins.jetbrains.com/docs/marketplace/plugins-ranking.html)
- [Best practices for listing your plugin](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)
- [Marketplace Approval Guidelines](https://www.jetbrains.com.cn/en-us/legal/docs/plugins_site/approval-guidelines/1.0/)
- [Plugin Configuration File](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html)
