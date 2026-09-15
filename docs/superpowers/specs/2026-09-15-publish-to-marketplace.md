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

## 六、还没解决的前提

插件包里带着 `@anthropic-ai/claude-agent-sdk` 的 6035 个文件，而它的 `LICENSE.md` 只有一行：

> © Anthropic PBC. All rights reserved.

**专有许可，不是开源的。** 把它连着插件发到公开市场是法律问题，市场不会替你拦。
这一条没有结论，是留给作者的判断。

## 出处

- [Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
- [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
- [Plugin search results and rankings](https://plugins.jetbrains.com/docs/marketplace/plugins-ranking.html)
- [Best practices for listing your plugin](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)
- [Marketplace Approval Guidelines](https://www.jetbrains.com.cn/en-us/legal/docs/plugins_site/approval-guidelines/1.0/)
- [Plugin Configuration File](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html)
