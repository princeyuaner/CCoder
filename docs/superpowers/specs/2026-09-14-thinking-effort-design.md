# 思考深度（effort）

日期：2026-09-14

---

## 1. 要解决的问题

输入框左下角能切「模型」和「权限模式」，但**没有地方调 Claude 的思考深度**。

这不是个可有可无的旋钮：它是延迟与花销的主要开关。同一个问题，
`low` 与 `max` 的差别可能是几秒和几分钟、几分钱和几块钱。现在用户只有一个
办法调它 —— 去改 CLI 自己那份配置，而那跟这个会话没什么关系。

SDK 里这个旋钮叫 `effort`，官方文档原话是 "Works with adaptive thinking to
guide thinking depth"，取值 `low / medium / high / xhigh / max`（sdk.d.ts:594）。

## 2. 目标

- 输入框左下角加第三个标签，点开选档位
- **改了立刻生效，不重开会话** —— 它改的是下一轮，当前这轮既不被腰斩，
  上下文也不丢
- **默认档 = 不干预**：没碰过这个设置的用户，行为与从前一字不差
- 分模型的档位把话讲在明面上，不假装它总能生效

## 3. 档位

| 枚举 | wireValue | 标签 | 弹层说明 |
|---|---|---|---|
| `DEFAULT` | `null` | 默认 | 不干预，按模型自己的默认档执行 |
| `LOW` | `"low"` | 低 | 最少思考，最快回复 |
| `MEDIUM` | `"medium"` | 中 | 适度思考 |
| `HIGH` | `"high"` | 高 | 深度推理（CLI 的默认档） |
| `XHIGH` | `"xhigh"` | 极高 | 比「高」更深；仅部分模型认，其余降级为「高」 |
| `MAX` | `"max"` | 最大 | 最高档；仅少数模型认，其余会降级 |

后两档**分模型**（SDK 原话：xhigh 在不支持的模型上静默降级成 high，
sdk.d.ts:597；max 只有少数模型认）。这件事在界面上没有别的办法看出来，
所以它写在弹层的第二行说明里 —— 这是唯一的告知渠道。

**为什么不做"读回实际生效档位"**：SDK 确实有 `getSettings()` 能返回
`applied.effort`（经模型降级、org 上限之后的值），做了就能在用户选「最大」
而实际按「高」跑时如实标出来。但那个字段在非 Remote-Control 宿主上**可能
压根不下发**，写了就是一条永远不亮的路径。降级行为改为在说明里讲清楚。

## 4. 只有一条路：applyFlagSettings

**这是本次设计里最重要的一条。**

SDK 给了两条路，但它们**不是同一件事**：

- `Options.effort` 会被 SDK 翻成 CLI 的 `--effort` 启动开关
  （sdk.mjs：`if (this.options.effort) W.push("--effort", ...)`）
- `applyFlagSettings({ effortLevel })` 改的是 flag 层，**会话中途生效**
  （sdk.d.ts:2705），`null` 表示把这一项从 flag 层清除、回落到低优先级来源

两条是**两个优先级来源**。把它们一起用的话：

> 用户选了「极高」起会话（`--effort xhigh`），再切回「默认」——
> 我们清掉的是 flag 层，清不掉启动时那个 `--effort xhigh`。
> **标签显示「默认」，会话照旧按极高跑。**

控件撒谎比它不好用严重，所以思考深度**只有一条路**：`applyFlagSettings`。
`StartParams` 上刻意没有这个字段，`ClaudeSettingsTest` 里有一条测试钉着它，
免得后来者"顺手补全"。

代价是：新会话起手是 CLI 自己的档位，得由界面在 `ready` 之后拨一下。这一趟
在 `ClaudePanel.applyEffortToSession()` 里，收到 `ready` 就发。

## 5. 时序：标签等回执

与权限模式**逐字同一条规矩**：用户点的那一下**只发请求、不动标签**，
等 sidecar 的 `effortChanged` 回执到了才改。先改标签后等结果的话，
切换失败时标签会显示一个没生效的档位。

「默认」档的回执里 `level` 是 **JSON null**，不是缺字段。插件侧解析时必须
判**键在不在**（`obj.has("level")`）：键不在 = 畸形丢弃，键在且为 null =
合法的默认档。用 `str()` 一把梭的话，选「默认」时标签会一动不动 ——
看着像点坏了。

起会话时那一趟也会回执。它与用户点选走的是同一个分支，所以靠
`picked != currentEffort` 分辨"是不是真的变了"：只有真变了才往转写区插一句
「思考深度已切换为「X」」，否则每开一个新会话都会冒出一行。

## 6. 分层

| 层 | 位置 |
|---|---|
| 枚举与持久化 | `settings/ClaudeSettings.kt`（项目级，与 `permissionMode` 同源） |
| 协议 | `Protocol.encodeSetEffort` / `SidecarMessage.EffortChanged` |
| sidecar 转发 | `session.js` 的 `setEffort`，`index.js` 的 `setEffort` case |
| 控件 | `ui/ComposerEffort.kt`（照 `ComposerMode.kt` 写） |
| 接线 | `ClaudePanel`（弹层、选中、回执、刷新出口、`ready` 时应用） |

**不照抄 `setPermissionMode` 的一个地方**：那边是
`await query?.setPermissionMode?.(mode)`，可选链在 `query` 为 null
（`queryFn` 抛错那条路径）时会**静默成功**，上层据此发出一条假回执 ——
界面标签切过去了，实际什么都没生效。新的 `setEffort` 在方法缺失或 `query`
为 null 时**抛明确的中文异常**，让失败顺着回执暴露出来。

（权限模式那条既有缺口本次**没动**，它是独立的一处修改。）

`index.js` 另有一道**值白名单**：CLI 对认不出的字符串未必报错、可能直接
忽略，那时我们会发出一条"切换成功"的回执。宁可现在说失败，也不要界面上
出现一个没生效的档位。

## 7. 验收

- 单测：`ClaudeSettingsTest`（wireValue 逐字对齐、只有默认档的 wireValue 是
  null、坏值回退、启动参数里没有 effort）、`ComposerEffortTest`、
  `ProtocolTest`（含"键在但为 null"与"键不在"那一对）、`index.test.js`
  与 `session.test.js`。
- 离屏渲染：`ComposerModelRenderProbe` 在 420px 下画三标签（含最挤的一版：
  最长模型名 + 绕过权限 + 极高），`ComposerEffortRenderProbe` 画弹层宽度
  （弹层宽度是内容撑开的，说明文字是本次最长的文案）。
- 人工：起会话 → 切「极高」→ 标签变化 → 再切「默认」→ 标签回默认，
  **且后续回合确实回落到 CLI 默认档**。最后半句是唯一真正的验收点：
  如果切完「默认」仍按高档跑，说明有东西绕过了 flag 层，要当 bug 查。
