# 会话列表：删除按钮的轮廓 + 右上角「清空全部」

日期：2026-09-17　分支：`v0.2.19-dev`　`pluginVersion=0.2.19`
状态：**已实现**（两件事是用户同一条反馈里的两个半句）

---

## 1. 用户原话

> 会话列表的删除按钮轮廓不对，太矮了，同时右上角需要增加一个一键清空所有历史对话的功能

附了一张截图：某一行行尾的「删除」被一个**很扁的框**框住，框的上下边正好横穿这两个字
（看着像被划掉）。

---

## 2. 第一件事：按钮框为什么横穿字形

### 2.1 量出来的数（探针打印，`SessionListRenderProbe`）

| 量 | 值 | 出处 |
|---|---|---|
| 标签字号 / 字高 / 「删除」两字宽 | 12 / **16** / **24** | `UIUtil.getLabelFont()` |
| 按钮**首选**尺寸 | **30 × 22** | 24×16 加平台边框上下各 3、左右各 3 |
| 按钮**实际**尺寸（改之前） | 30 × **12** | 被槽压扁 |
| 槽 `deleteSlot` 首选尺寸 | 30 × **12** | `Dimension(button.preferredSize.width, base.size)` |

`base.size` 是**字号**（12），不是按钮需要的高度。悬停到按钮上时 `paintDelete(danger=true)`
打开 `isContentAreaFilled` + `isBorderPainted`，平台那个框**画在按钮自己的边界里** ——
12px 高的盒子里画一个带内缩的圆角框，上下两条边自然落在字形的中间。

（另一半是宽度：边框会再往里收 1–2px，24px 的字放在 30px 的盒子里，收完就贴着笔画。）

### 2.2 修法：槽 = 按钮自己的首选尺寸，一个数都不改

```kotlin
preferredSize = deleteButton.preferredSize
```

### 2.3 代价与一并否掉的两条路

**代价：行高 22 → 28**（宽不变）。行高本来由这一列里最高的子件决定，按钮不再压扁
之后它就成了那个决定者。10 行的弹层因此从 228 高变成 288 —— 仍在
[`SESSION_LIST_MAX_ROWS`] 的封顶之内。

| 否掉的 | 为什么不 |
|---|---|
| 行内边距压到 0，把行继续钉在 22 | 按钮框会贴着相邻行的边界，看着像两条线叠在一起 |
| 自己画一个圆角框（`paintComponent`） | 丢掉平台按钮的观感（悬停/按下/主题色都是平台给的），还多一份要跟着主题维护的画法 |

---

## 3. 第二件事：「清空全部」

### 3.1 清空的是**哪个范围**

弹层里列的是 `listSessions({dir})` —— **这个项目**的历史会话（不是"这台机器上所有项目"）。
确认语里写明条数，杜绝"我以为是全部项目"这种歧义：

> 清空这个项目的 37 条历史会话？

### 3.2 协议：一次动作一次请求

新增请求 `clearSessions { dir, keep[] }`，回执 `sessionsCleared { id, deleted[], failed[] }`。

| 否掉的 | 为什么不 |
|---|---|
| 在 Kotlin 侧循环发 N 次 `deleteSession` | ①列表只取 50 条（`SESSION_LIST_LIMIT`），"清空所有"必须把 50 条以外的也删掉 —— 那就要在 Kotlin 侧再写一遍分页；②部分失败要有"删掉几条、哪几条没删掉"，N 个回调里自己记账等于把 sidecar 的活搬到界面线程上 |

**分页在 sidecar 侧**：按 200 一页把该项目所有会话列全，再逐条删。单条失败**不中断**，
收进 `failed`（一条坏数据不该让另外 36 条都留着）。

### 3.3 `keep`：正在使用中的会话不删

`keep` = 正在被**任何**标签跑着的会话（`OpenSessions.takenIds()`，该面板自己那条也在里面
—— 它在 `init` 时就登记了，见 `ClaudePanel.confirmOwnership`）。

理由：那条 jsonl 正被一个活着的 CLI 进程写着，删文件是拿正在写的会话冒险。
这条规矩**行级删除早就有**：被占的行连删除入口都不给（`deleteButton.isVisible = clickable`，
而 `clickable` 要求 `sessionId !in takenIds`）。

保留了几条要**说出来**，不假装清干净了：

> 已清空 35 条历史会话（2 条正在使用，保留）

### 3.4 UI 形态

```
历史会话                                    清空全部
──────────────（hairline）──────────────────────────
✓ PyCharm插件调用Claude Code         + 1 小时前  删除
```

- 入口用与行内「删除」同一套文字按钮（平时次要色、指针上去变红），**不用** JButton 实心按钮：
  这一列里没有实心按钮，冒一个出来会显得比"删除一行"还重
- 确认**就地**换：问句 + `[取消] [清空]`，照行内确认那一套（销毁不可逆的动作不给"点一下就走完"）
- **复用同一个 `ConfirmSlot`**：整列同一时刻只允许一个确认态 —— 点了某行的删除再点清空全部，
  行要先收回原样，反之亦然
- **忙时不出入口**：`block != None` 时整列不可点，删除入口本来就藏起来（同一条规矩）
- **空列表不出入口**：没有会话时只有一行「没有找到历史会话」，没什么可清

### 3.4.1 确认语必须短到**一行放得下**（出图之后改的）

第一版的问句是"清空这个项目的 37 条历史会话？其中 2 条正在使用，会保留。"，
而且试过"问句占一整行、按钮另起一行靠右"。出图看出来的两件事：

| 症状 | 根因 |
|---|---|
| 问句被**切掉一截**（第一张图：末尾几个字没了） | JLabel 装不下是**裁**，不是折行。量过：组件只有 250px，这句话要 325px。HTML 的 `width` 帮不上 —— 它只影响排版，不影响报出来的首选宽（写 250 时首选仍是 325） |
| 改成两行之后**缺一块**（第二张图） | 弹层的尺寸是**打开那一刻定死的**（`showTogglePopup` → `createComponentPopupBuilder` + `setResizable(false)`）。确认态比常态高 26px，那 26px 没处长 —— 问句被挤成负高度 |

结论：**确认语是长度受限的**（约 250px ≈ 20 个汉字），范围那句挪去两个地方说：
入口那颗的 tooltip（"清空这个项目的历史会话"）与结果那句话（[clearAllResultText]）。

```kotlin
// 一行放得下的那一版
if (moreThanListed) "清空这个项目的历史会话？"   // 截断时报不出准确条数
else "清空这 $count 条？"                      // 条数比"历史会话"四个字更该出现
// keptCount > 0 时再接：" $keptCount 条正在使用，保留。"
```

两条用例钉着：一条按字数粗算宽度（纯函数，`SessionSwitchStateTest`），
一条真排一次版量 `prompt.width >= prompt.preferredSize.width`（`SessionListTest`）——
后者正是第一版会红、而当时全绿的那条。

### 3.5 回执之后（`ClaudePanel`）

1. 从缓存里摘掉 `deleted` 里的那些行，刷新弹层
2. 当前会话若在 `deleted` 里（理论上不会 —— 它一定在 `keep` 里；兜底）→ 走 `onDeleteOutcome`
   那条老路：停会话、清转写区、回新会话
3. 转写区写一条系统提示：成功条数 + 保留条数；有 `failed` 时补一条错误项，把没删掉的条数说出来

---

## 4. 验证

```bash
PATH="/c/Program Files/nodejs:$PATH" ./gradlew test -PskipWeb        # Kotlin 全量
cd sidecar && npm test                                              # JS（clearSessions）
PATH="/c/Program Files/nodejs:$PATH" ./gradlew test --tests 'com.ccoder.ui.SessionListRenderProbe'
# → build/session-list-probe-delete-hover.png（删除按钮危险态）、session-list-probe-clear.png（清空确认态）
```

**看图只回答三件事**：按钮框（危险态）包不包得住「删除」两个字、框与行高是不是一家人、
清空那一行的问句与两颗按钮有没有把列表挤变形。

**真机冒烟**（探针替代不了）：开着两条标签，其中一条在使用中的会话 → 清空全部 →
看**那条会话还在不在**、提示里说的"保留 N 条"对不对。

---

## 5. 实现记录（2026-09-17）

| 文件 | 干了什么 |
|---|---|
| `ui/SessionList.kt` | `deleteSlot` 改按按钮首选尺寸；新增顶部那一行（标题 + 「清空全部」+ 就地确认）；抽出 `textAction` / `paintDanger` 两个共用的画法；`SESSION_LIST_QUERY_LIMIT` 从 ClaudePanel 搬过来 |
| `ui/SessionSwitchState.kt` | `clearAllConfirmPrompt` / `clearAllResultText` / `clearAllFailedText` |
| `ui/ClaudePanel.kt` | `requestClearAllSessions` + `onClearOutcome`；接线 `onClearAll` |
| `sidecar/Protocol.kt` | `SessionsCleared` / `ClearFailure` / `encodeClearSessions` / 解析 / `responseIdOf` |
| `sidecar/index.js` | `clearSessions` 分发 + `listAllSessions`（分页）+ `clearProjectSessions`（keep 跳过、单条失败不中断） |

用例：Kotlin **1405** 条全绿（新增 26），JS **183** 条全绿（新增 5）。

### 5.1 顺带被这行"顶出来"的三处老用例

顶部加了一行之后，`list.components[0]` 不再是第一条会话行 —— 三条老用例当场红了
（点标题、悬停提亮、以及一条把行高钉死成 22 的高度上限断言）。
修法是给那一行一个**名字**（[SESSION_LIST_HEADER_NAME]）并在用例里用它把非会话行滤掉；
高度那条改成**量出来的行高**（它本来就在注释里写着"不是写死的常数"，实际却写死了 22）。

### 5.2 这版没做的

- 清空**所有项目**的历史（只清了弹层里这个项目 —— 确认语与结果都写明了范围）
- 撤销 / 回收站（删除就是删除，所以确认做成了两段式）
- 正在使用中的那些**也**清掉（它们是 keep；真需要时先在标签里关掉那个会话）
