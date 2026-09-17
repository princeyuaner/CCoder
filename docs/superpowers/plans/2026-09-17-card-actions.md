# 状态卡上的两颗按钮（清空 / 压缩）Implementation Plan

> Spec：`docs/superpowers/specs/2026-09-17-card-actions-design.md`
> 设计稿：`docs/design/card-actions.html`（**方案乙：常驻角标**）+ `docs/design/card-action-icons.html`（图形：清空 = A 扫把，压缩 = E 压进托盘）
> 改版记录见 spec §3 顶上那条：方案甲（悬停换值）装到真机上被否，原因与根因都记在那儿
> 探针：`sidecar/tools/probe-compact.mjs`（Task 0 新增 —— 量 spec §8/§9 那几条）

**要解决的问题**：`/clear` 与 `/compact` 两条命令都通，但都藏在"你得知道能敲"后面。
在连接卡与上下文卡右上角各加一颗**常驻**图标（清空 / 压缩），点击走与输入框完全相同的发送路径。

---

## Global Constraints

1. **三侧零改动**：协议、sidecar 产品代码、`web/` 一个字节都不动 —— 消息还是今天那条
   `Protocol.encodeSend`。所以**不用跑** `web/` 的 `npm test`、也不用跑布局探针。
   sidecar 只新增一个**探针文件**（`tools/` 下，不是产品代码，不进 `npm test` 的集合）。
2. **`./gradlew test -PskipWeb` 必须绿。** 加 `-PskipWeb`：`test` 会顺着
   `resources.srcDir(buildWebUi)` 拉起一次前端构建，而这次前端零改动。
3. **注释写"为什么"**，不写"是什么" —— 沿用本仓库的注释风格。凡"为什么砍掉某个提示语"
   "为什么这个顺序"都落到代码上。
4. **提交粒度**（前四个各自可独立提交，**Task 4 必须一次提交**）：
   - Task 0 只加探针文件；Task 1 是纯新增逻辑（无人调用）；Task 2 是视图能力（无人调用 = 行为不变）；
   - Task 3 是纯重构（行为一字不变），单独提交好回滚；
   - **Task 4 是接线** —— 参数加了没人传、动作建了没人接，都会编译不过或半死不活。
     （2026-09-13 status-cards 那次就栽在"任务边界打断构建"上。）
5. **纯逻辑与 Swing 分开**：动作三态、点击路由、压缩判据、回执文案全是纯 Kotlin，
   可单测；组件只负责画与转发（`MainButtonState` / `SessionSwitchState` 的先例）。

## 文件结构

新增：

| 文件 | 内容 |
|---|---|
| `src/main/kotlin/com/ccoder/ui/CardAction.kt` | 动作模型 + 三态 + 点击路由 + 压缩判据 + 回执文案（全纯函数） |
| `src/test/kotlin/com/ccoder/ui/CardActionTest.kt` | 三态、路由、压缩判据 |
| `src/test/kotlin/com/ccoder/ui/CompactReceiptTest.kt` | 四种回执文案（齐全 / 缺 post / 缺 duration / auto） |
| `sidecar/tools/probe-compact.mjs` | 探针：压缩的现场（Task 0） |

改动：

| 文件 | 改什么 |
|---|---|
| `src/main/kotlin/com/ccoder/ui/StatusCardView.kt` | Task 2：动作槽位、右上角自绘图标、命中区几何、点击路由、tooltip |
| `src/main/kotlin/com/ccoder/ui/StatusCards.kt` | Task 1：`contextCardOf` 加压缩中；`Indicator.Meter` 加 warn |
| `src/main/kotlin/com/ccoder/ui/StatusCardsRow.kt` | Task 4：两个新回调（**加在尾随 lambda 之前**，见类注释） |
| `src/main/kotlin/com/ccoder/ui/ClaudePanel.kt` | Task 3 抽 `sendText`；Task 4 接线（动作态、点击、压缩中、回执） |
| `src/test/kotlin/com/ccoder/ui/StatusCardsRenderProbe.kt` | Task 2：加三张图（可用 / 忙灰 / 压缩中） |
| `src/test/kotlin/com/ccoder/ui/StatusCardsRowTest.kt` | Task 4：构造签名变了，改调用点（那条宽度钉子不许动） |

---

## 0. 先钉死的事实（都已核过，不重新论证）

| # | 事实 | 出处 |
|---|---|---|
| 1 | 回放路径**也走** `MessageRenderer.render` —— 回执放 renderer 里会**同时进历史** | `ClaudePanel.kt:1746-1757` |
| 2 | `MessageRenderer` 里系统事件按 `subtype` 分发，`hook_started`/`hook_response` 就在那儿 | `MessageRenderer.kt:327` |
| 3 | 卡片监听器**只在 `onOpen != null` 时挂**；`hovered` 目前只用于描边 | `StatusCardView.kt:137-153` |
| 4 | tooltip 只挂了**四个**：卡片 + 图标 + 标签 + 值 —— 而**挂 tooltip 就是给那个组件装监听器**，子件因此会偷走卡片的事件（2026-09-17 用户实测报回，见执行记录） | `StatusCardView.kt:200-203` + JDK `ToolTipManager` |
| 5 | `StatusCardsRow` 的参数顺序规则：**新参数一律加在尾随 lambda 之前** | `StatusCardsRow.kt` 类注释 |
| 6 | `lastSendWasCommand = text.startsWith("/")`，命令回合的空输出/`(no content)` 已被抑制 | `ClaudePanel.kt:3313`、`MessageRenderer.kt:86` |
| 7 | 回放跑在池线程上且有锁 —— 新增的回执函数必须是纯的 | `ClaudePanel.kt:3432` |

---

### Task 0: 探针 —— 压缩的现场 ✅（2026-09-17 已跑，两次结论一致）

写 `sidecar/tools/probe-compact.mjs`：选项照抄 `session.js`、环境走 `env.js` 的清洗、
**必须消费事件流**（`probe-slash.mjs` 踩过的三个坑都记在 composer 设计稿 §10.6，照它写）。
流程：先发两条短消息把会话垫起来 → 发 `/compact` → 打印全部事件。

要回答的问题（spec §8/§9 里挂着的那几条）：

| # | 问题 | 打印什么 |
|---|---|---|
| Q1 | 压缩期间 assistant 的文本**到底是不是空串**（记录互相矛盾） | 每个 assistant 事件的 content block 类型 + 文本前 120 字 |
| Q2 | 压缩中的判据取哪个值 | 所有 `system/status` 的原文（`requesting` / `compacting` / `null`+`compact_result`） |
| Q3 | `compact_boundary` 的**原始形状**（是不是 `compact_metadata` 嵌套、字段齐不齐） | 该事件的原始 JSON 一行 |
| Q4 | boundary 与 init 的先后（决定"压缩中"什么时候结束） | 事件序列（type/subtype 一行一条） |

用法（`MSYS_NO_PATHCONV=1` 是必需的，否则 Git Bash 把 `/compact` 转成路径）：

```
cd <repo root>
MSYS_NO_PATHCONV=1 node sidecar/tools/probe-compact.mjs
```

**已跑（2026-09-17，两次运行结论一致）**，答案已回填 spec §2 事实 8-11：

| # | 答案 |
|---|---|
| Q1 | **压缩回合里没有任何 assistant 事件**（连 `thinking` 都没有）—— 摘要不会冒成气泡，`isEmptyCommandOutput` 那条抑制规则不用动 |
| Q2 | 判据是 `status:"compacting"`；结束是 `status:null` + `compact_result:"success"`。⚠️ `requesting` **每回合都有**，不能用 |
| Q3 | 嵌套在 `compact_metadata` 里，三个字段齐全（`{trigger:"manual", pre_tokens:30409, post_tokens:1666, duration_ms:17219}`） |
| Q4 | `status(compacting) → status(null,success) → init → compact_boundary → user ×2 → result` —— **boundary 比 status 晚**，"压缩中"结束认 status（卡上不该多挂一秒） |

探针自身的一个坑（第一版结论段全空的原因）：**`onEvent` 里漏了 `events.push(msg)`**。
`waitFor` 靠 waiter 直接 resolve，所以流程照跑、事件照打，只有按区间切片的"结论"段是空的。
写下一个探针时照 `probe-slash.mjs` 的第一行抄，别凭记忆重写。

---

### Task 1: 纯逻辑（可独立提交）

新建 `src/main/kotlin/com/ccoder/ui/CardAction.kt`：

```kotlin
internal enum class CardActionKind { Clear, Compact }

/**
 * 卡上那颗按钮要显示的全部内容。
 *
 * [danger] 不并用 [Tone]：Tone 说的是"状态要不要紧"，而这里是"这个动作危不危险"——
 * 清空是 danger、压缩是强调色，而两张卡**此刻的状态**都可能是 Ok（已连接）。
 * 两件事混进一个枚举，将来加动作就得给 Tone 瞎添成员。
 */
internal data class CardAction(
    val kind: CardActionKind,
    val label: String,
    val tooltip: String,
    val danger: Boolean,
    val enabled: Boolean,
)
```

三个纯函数 + 一个路由 + 一个判据 + 回执：

```kotlin
/** 可用条件：会话就绪**且**空闲（spec §3.5）。未就绪时按钮没得发，忙时会话语义暧昧。 */
internal fun clearActionOf(ready: Boolean, busy: Boolean): CardAction?
internal fun compactActionOf(ready: Boolean, busy: Boolean): CardAction?   // 压缩中返回 null

/** 点击落在哪儿。**只有落在右上角那 16×16 命中区里才算动作**（spec §3.3）。 */
internal enum class CardClickTarget { Action, OpenDetail, None }
internal fun cardClickTargetOf(onActionIcon: Boolean, hasAction: Boolean, hasDetail: Boolean): CardClickTarget

/** 压缩中的判据取 CLI 的 status 事件（自动压缩也要能看见）——与权限模式读回同一条规矩。 */
internal fun compactingOfStatus(event: JsonObject): Boolean

/**
 * compact_boundary → 系统提示。字段缺就降级照写（三个字段里两个是可选的）。
 *
 * **两种形状都认**：实时的 `compact_metadata.pre_tokens`（snake）与落盘历史的
 * `compactMetadata.preTokens`（camel）—— 回放同样走 renderer（事实 1、12）。
 * 只认一种，回执就只在"现场"或只在"历史"里出现。
 */
internal fun compactReceiptOf(event: JsonObject): String?
```

回执文案（spec §3.7，`formatTokenCount` 复用现成的）：

| 情况 | 文案 |
|---|---|
| 齐全 | `已压缩上下文：31.5k → 1.9k（用时 10s）` |
| 缺 `post_tokens` | `已压缩上下文（压缩前 31.5k）` |
| 缺 `duration_ms` | 省略括号 |
| `trigger:'auto'` | 前缀改 `CLI 自动压缩了上下文：…` |

同时改 `StatusCards.kt`（也是纯的、可测）：

- `contextCardOf(usage, compacting: Boolean = false)` —— 压缩中值行写「压缩中…」、色调 Warn

**测试**：`CardActionTest`（三态 × 两张卡；"未就绪"与"忙"都得灰；路由三条；压缩判据两种值）、
`CompactReceiptTest`（四条文案）。`StatusCardsTest` 若有 `contextCardOf` 的用例，补压缩中一条。

---

### Task 2: 卡片的动作图标（可独立提交 —— 没人传动作时行为一字不变）

改 `StatusCardView.kt`：

1. **`setAction(action: CardAction?)`** + 右上角一颗**常驻**自绘图标（spec §3.1）
2. **监听器挂载条件**从 `onOpen != null` 放宽成 `onOpen != null || action != null`；
   外加 `MouseMotionListener`（光标与悬停底色要跟着指针走）
3. **命中区**：`actionIconBounds()` —— 右上角 16×16，允许压进右内边距（卡只有 97px 宽，
   全从内容区里抠的话，四字标签「等待响应」就没地方站了）。**画的与点的是同一个矩形**
4. **图形自绘**（spec §3.2）：`paintActionIcon` + `paintBroom`（扫把）/ `paintCompress`（压进托盘），
   按 16×16 方格画再整体缩到 ~14px —— 与 `CardIconView` 同一套路数
5. **三态由 `enabled` 决定**：常态次要色 / 灰（不可点）/ 不画（压缩中，`action == null`）
6. **悬停只有底色**（`iconHot`）：底色 = 动作色 × `0x2E` 透明度 + 手型光标 + tooltip 换成动作说明；
   移开还原成 `model.sub`。**值行永远显示模型的值**
7. **tooltip 只挂卡片自己**（2026-09-17 改）：挂子件 = 给子件装监听器 = 子件偷走卡片的事件。
   `ToolTipManager` 问的是 `event.getSource()`，只挂卡片照样弹（JDK 源码 + 实测，见执行记录）
8. **点击路由**：`mouseClicked` → `cardClickTargetOf(onActionIcon, hasAction, hasDetail)` →
   `Action` 就 `onAction()`（`enabled` 为假则什么都不做）、`OpenDetail` 就 `onOpen()`、`None` 不动

**渲染探针**（`StatusCardsRenderProbe.kt`，产物进 `build/`）：常驻可用 / 悬停底色 / 忙时灰 /
压缩中 / **等待响应那一档**（标签四字 + 图标，量挤不挤）。悬停态是运行时状态，
探针里**直接驱动监听器**摆出来（走 `dispatchEvent` 会把事件送给平台的 `ToolTipManager`，
它起的定时器会被夹具判成 "Not disposed"）。

### Task 3: 抽 `sendText`（纯重构，行为一字不变，独立提交）

`sendCurrentInput()` 里现在把"读输入框 → 组装（含图片与引用记号）→ 发"揉在一起。
抽出 `private fun sendText(text: String)`：**按钮那条路什么都不带**（没有图片、没有引用记号、
不进历史），输入框那条路的行为一字不变。抽完先跑 `./gradlew test -PskipWeb` 确认全绿再提交。

---

### Task 4: 接线（**必须一次提交**）

1. `StatusCardsRow`：加 `onClear: () -> Unit` 与 `onCompact: () -> Unit` 两个参数
   —— **加在既有三个尾随 lambda 之前**（事实 5 的规则，加错会静默绑错）
2. `ClaudePanel.refreshStatusCards()`：按 `ready` / `busy` 刷两颗动作
   （`connection.setAction(clearActionOf(ready, busy))` 等）；压缩中给 `null` + 卡的「压缩中…」
3. 点击回调：`sendText("/clear")` / `sendText("/compact")`
4. **压缩中**：在解析 `permissionModeOfStatus` 的那一处（同一个事件循环）加
   `compactingOfStatus`，值变了就刷卡片
5. **回执**：`MessageRenderer` 的 `subtype` 分发里加 `compact_boundary` → `RenderItem.SystemNote`。
   ⚠️ 事实 1：**回放也走 renderer，所以回执会同时出现在恢复出来的历史里** —— 已确认
   `compact_boundary` **真的落进了会话文件**（Task 0 顺手核过，spec 事实 12），这条不是死代码。
   ⚠️ ⚠️ **但两种形状的字段名不同**（事实 12）：实时 snake_case `compact_metadata.pre_tokens`、
   落盘 camelCase `compactMetadata.preTokens` —— `compactReceiptOf` 必须**两种都认**，
   `CompactReceiptTest` 里各一条用例。只认一种就是"现场有、历史里没有"的半边功能
6. `StatusCardsRowTest` 的构造调用点跟着改；那条宽度钉子**不许动**

---

### 收尾：手工冒烟（不在自动化范围内）

- 真机点右上角图标的手感：命中区够不够大、悬停底色跟不跟手（spec §8 第一条）
- 忙时点灰按钮：什么都不发生（不是静默入队）
- 压缩期间转写区到底出不出摘要（Task 0 的 Q1 是读事件流，这里是**看界面**）
- 压缩完成：系统提示与上下文卡百分比的先后
- 动作态下 tooltip 弹得出来（压在图标上时说的是动作、压在别处时说的是副值）
- 「启动失败 / 已断开」下两颗按钮的灰态与 tooltip

---

## 风险

| 风险 | 应对 |
|---|---|
| 悬停闪烁（组件增删的经典陷阱） | 设计上已排除（只画不换，spec §3.2）；真机仍是第一验收点 |
| 回执在历史里重复出现 | 事实 1 已说明这是刻意行为；若看得别扭，改成只走实时路径（`replayItems` 跳过该 subtype） |
| ~~`compact_boundary` 不进会话文件~~ | **已核**：进（spec 事实 12）。随之而来的新风险是两种字段形状，见下一行 |
| 回执只认一种字段形状 | 事实 12：实时 snake_case、落盘 camelCase。测试里两种各一条用例钉死 |
| 压缩中判据取错值 | **已量**（事实 9：只认 `compacting`，`requesting` 每回合都有）；判据是纯函数，真要改是一个常量的事 |
| 探针"结论"段再次哑掉 | 第一版漏了 `events.push(msg)`（plan Task 0 记了坑）。工具已修并复跑通过 |
| 忙时灰按钮让人以为坏了 | tooltip 说清"会话空闲时可清空"；冒烟清单里有这一条 |

## 执行记录（2026-09-17）

### Task 0（探针）—— 完成，跑了两次

四个问题的答案见上表。**探针自身修过一处**：第一版 `onEvent` 漏了 `events.push(msg)`，
`waitFor` 靠 waiter 直接 resolve 所以流程照跑、事件照打，"结论"段却全空。

### Task 1（纯逻辑）—— 完成

- 新增 `CardAction.kt`、`CardActionTest`（14 条）、`CompactReceiptTest`（8 条）
- 计划里"给 `Indicator.Meter` 加 `warn` 字段"那条**没要**：`IndicatorView.paintMeter`
  本来就是"只有警示色调才覆盖指示器颜色"，而压缩中那张卡整体是 Warn ——
  条子自己会转琥珀，加参数是重复（写进去了又撤掉）

### Task 2（卡片动作槽位）—— 完成

- 第一版是"值行换画法不换组件"（方案甲），被真机否掉后改成**右上角常驻自绘图标**（方案乙），见上面那条改版记录
- 点击按几何路由（16×16 命中区）；tooltip 只挂卡片；光标只在压着图标时变手型
- 五张渲染探针图（常驻可用 / 悬停底色 / 忙灰 / 压缩中 / 等待响应），都在真机 LAF 下
- 图上修过一处：**压缩中那张的连接卡按钮也得是灰的**（压缩是一个回合，会话是忙的）——
  第一版忘了把 `busy` 传给它，画出了"压缩中而清空还亮着"这种现实里不存在的组合

### Task 3（抽 `sendText`）—— 完成

纯重构，行为一字不变。抽出来的是三段：`sendCurrentInput()`（读输入框 → 交给 `submit`）、
`sendText(text)`（按钮那条路，什么都不带）、`submit(...)`（两条路共同的去路：
忙时入队 / 未就绪先起会话再暂存 / 否则发出去）。

### Task 4（接线）—— 完成

- `StatusCardsRow`：`onClear` / `onCompact` 加在尾随 lambda **之前**（它自己的规矩）
- `ClaudePanel`：`refreshCardActions()` 与卡面**分开刷**（忙闲切换不经过卡面数据）；
  `setBusy` 里补一次；`compacting` 由 status 事件驱动 —— 自动压缩也看得见
- `MessageRenderer`：`compact_boundary` → `SystemNote`，**实时与回放同一条路**
- 一处**有意**改掉的既有测试：`三张卡可点，连接卡不可点`。连接卡现在有监听器了
  （动作按钮要亮），但"点不开详情"这条没变 —— 测试改名为
  `四张卡都挂了监听器 —— 连接卡那一个只为动作按钮而挂`，并在注释里说清为什么

### 验证

`./gradlew test -PskipWeb` —— **1290 条全绿**（本版新增 30 条：CardAction 14 +
CompactReceipt 8 + StatusCardView 7 + Row 改 1 + Renderer 1）。

### 改版：方案甲 → 方案乙（2026-09-17 傍晚，用户当场定）

装到真机上后用户报回：**「鼠标放在边框上按钮才出现，挪到中间就消失，我无法点击」**。

根因（已量，见下面「踩到的」第 1 条）：`JComponent.setToolTipText` 会把 `ToolTipManager`
注册成**那个子件的鼠标监听器**，而 Swing 把鼠标事件派发给"最深的有监听器的组件" ——
给子件挂 tooltip 就等于让子件把卡片的悬停偷走。**方案甲整个建立在悬停状态上，所以它必死**；
不是调参能救的。

用户当场改了形态：**图标常驻右上角**（spec §3 顶上那条改版记录），并要求「清空 = 扫把、
压缩 = 压缩图标」。图形我试了三轮都读不出来（斜杠 / 纸飞机 / 带柄小球），
于是不再猜 —— 出了候选表 `docs/design/card-action-icons.html`（每颗 6 个候选，
真实 14px 与放大版各画一遍），用户挑了 **A 扫把 / E 压进托盘**。

顺带留下的两条通用教训：**子件挂 tooltip = 子件成为事件目标**（这条现在钉在
`StatusCardViewTest` 的「后代组件一个 tooltip 都不许挂」上）；**14px 上细描边围出来的
形状会塌**，要么用实心块、要么把线画粗（扫把第一版就是这么废掉的）。

### 踩到的两件事（都不是计划的漏，是没有先例的新坑）

1. **`ToolTipManager` 的定时器会让测试红**。`JComponent.setToolTipText` 会把
   ToolTipManager 注册成该组件的一个监听器；它一收到**移出**事件就起一个
   `javax.swing.Timer`（dismiss 延迟），平台的测试夹具在收尾时判成
   `Not disposed javax.swing.Timer`。症状很能说明成因：**只悬停进入的用例全绿，
   "移开还原"的两条全红** —— 注册发生在进入那一刻之后，移出那一下才被它收到。
   解法：测试里悬停/移出走"直接喂我们自己的监听器"（滤掉 `javax.swing.*`），
   平台那层 tooltip 定时器不拖进来（`hoverOurListeners`，注释在案）。
2. **`StatusCardsRenderProbe` 里既有的 `render(...)` 还在 Metal 下画**
   （2026-09-14 写的，没跟上 09-15 那条"探针不许在 Metal 下跑"）。新加的
   `renderActions` 用了 `IdeLaf.withRealLaf`，所以**同一文件里现在两套 LAF**：
   旧图浅色、新图深色，一眼能看出不是一套。既有的那几段要不要一起迁移，
   建议单独一次做（会改掉已提交的图，与本版无关）。

## 自查记录（占位符扫描）

（执行完回填：计划与执行的偏差、渲染出来才发现的错、仍未做的）
