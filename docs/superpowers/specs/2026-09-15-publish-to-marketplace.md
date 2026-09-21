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

## 八、What's New 写哪几版（2026-09-16 补）

**规则：清单只写当前版本相对"上一个已上架版本"的增量。**

这条此前是**隐式**的，靠成例摸着走 —— 而 0.2.13 被拒、0.2.14 与 0.2.15 又都没上架，
所以那几次开版时就把前一代的条目**带着走**（根本没有"上一个已上架版本"可言，
带着走是对的）。0.2.15 上传之后，开 0.2.16 时又照抄了一遍 —— 于是 0.2.16 的清单里
躺着**十条其实属于 0.2.15 的内容**，直到有人问"这版的 What's New 有什么"才发现。

2026-09-16 起按增量写，并接受一条已知风险：**万一 0.2.15 最终没过审，0.2.16 就是
首个公开版本**，那时要把它那十条再补回来。补回来比删掉容易 —— 十条都还在 git 历史里
（`git show 0073cd0:src/main/resources/META-INF/plugin.xml`）。

## 出处

- [Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
- [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
- [Plugin search results and rankings](https://plugins.jetbrains.com/docs/marketplace/plugins-ranking.html)
- [Best practices for listing your plugin](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)
- [Marketplace Approval Guidelines](https://www.jetbrains.com.cn/en-us/legal/docs/plugins_site/approval-guidelines/1.0/)
- [Plugin Configuration File](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html)
