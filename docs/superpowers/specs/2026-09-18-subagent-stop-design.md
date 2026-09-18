# 子代理终止按钮（stopTask）

日期：2026-09-18　分支：`v0.2.21-dev`　`pluginVersion=0.2.21`
状态：**已实现**（三侧全绿），真机冒烟与探针验证**待网关通道恢复**

---

## 1. 需求与决定

用户原话："子代理能不能加终止按钮，有时候想让他终止" —— 随后拍板"先做"。

界面上的落点：**「运行中」浮层**（点状态卡里"子代理"那张弹出来的）每行右端一颗
方块。两个已定的取舍：

- **常驻可见（很淡），悬停提亮**，而不是悬停才出现 —— 它的用途是"赶紧把在跑的
  东西停下来"，藏起来等于让人先找一遍；且悬停版在这个行结构里要跨子组件追踪
  鼠标（行、两行正文、统计文字、按钮各自进出），容易闪。
- **不做二次确认** —— 与"关标签会丢上下文"不同，子代理停了再派一个就是。

**终止钮对"运行中"里的每一条都画**，不区分是不是子代理：`local_bash` 后台命令
也在同一份清单里，同一颗按钮天然覆盖（探针里那条 local_bash 行就是守卫）。

## 2. 依据（`sdk.d.ts`，这版 SDK 的契约）

- `stopTask(taskId)`：**"Stop a running task. A task_notification with status
  'stopped' will be emitted."**（sdk.d.ts:2988）——完成信号是契约里写好的。
- `task_started / task_progress / task_notification` 三者都带 `tool_use_id`；
  而插件"运行中"清单的 `RunningTask.id` 就是 `task_started.task_id`
  （对子代理来说等于它的 tool_use id，见 `buildRunningDetail` 的对号注释）——
  **"停谁"这个信息不需要另外取，今天就在手里**。

**但有一处从未真跑过**：`stopTask` 至今一次都没有被真实调用过（stop-bash 探针
2026-09-16 只测了 bash，且它后台那半没拿到 task id）。这版先接线，真机行为
（停得掉吗 / 回执形状 / 模型侧随后发生什么）留给探针补 —— 见 §5。

## 3. 接线（三侧各一小笔）

```
RunDetail 的那颗方块 → ClaudePanel.stopRunningTask → Protocol.encodeStopTask
→ sidecar/index.js 的 case 'stopTask' → session.stopTask → SDK query.stopTask(taskId)
```

- **sidecar/session.js**：`stopTask(taskId)` —— 缺方法就抛，**不照抄
  `setPermissionMode` 的可选链**（那种写法在 `query` 为 null 时静默成功，
  用户点了个没用的按钮却以为停掉了）。
- **sidecar/index.js**：**成功不回执**（界面的反馈是那一行自己消失），
  **失败报 `STOP_TASK_FAILED`** —— 静默吞掉的话，用户点的是"终止"、看到的是
  一个继续在跑的条目，只能猜。
- **Kotlin**：`Protocol.encodeStopTask(id, taskId)`；`ClaudePanel.stopRunningTask`
  用 `sendLine` 发（发完就走）；`FailureHint` 补 `STOP_TASK_FAILED` 一条 ——
  说清"它多半还在跑"，并给出第二条停法（回合级那颗「停止」）。
- **回流不用新写**：CLI 的 `task_notification`（status 'stopped'）到了之后，
  `RunStatusTracker` 现有的 `task_notification -> 移除` 会把那一行收掉。

## 4. 浮层重画（不然"点了没反应"）

「运行中」浮层从前是**打开那一刻的快照**：不重画的话，点了终止要收起再打开
才看得到那一行消失。补的机制：

- `refreshStatusCards()` 末尾调 `refreshRunningPopup()`：清单变了（`RunningTask`
  是 data class，判等含 detail / 时长）就重画 —— 顺带让 B2 那两行**活起来**；
- 单独一面旗 `runningListShown`：转写页复用的是"子代理"这张卡的同一个浮层，
  靠 `openDetail` 分不开 —— 清单再变也**不许把人正看着的转写页顶掉**；
- 顺手补了一个既有洞：`showDetailPopup` 里 cancel 旧浮层会触发 `onClosed ->
  closeDetail()` 把 `openDetail` 清掉，此后"再点一次收起"会失灵（点它变成重新
  打开）—— 现在按新内容把 `openDetail` 补回来（`detailOf`）。

## 5. 验证

- **侧车**：`node --test` **189 全绿**（+5）——stopTask 原样转发 / 缺方法抛错 /
  分发转 taskId / 失败上报 / 缺 taskId 挡下。
- **Kotlin**：`./gradlew test -PskipWeb` **1482 全绿**（+5）——三个新用例（点钮报
  task id、local_bash 也有钮、点钮不会顺手翻转写页）+ `FailureHintTest` 一条
  + 渲染探针两条。
- **出图**：`build/probe/running-detail.png` / `-hover.png`
  （`RunningDetailRenderProbe`，真机 LAF）—— 看过的结论：方块与统计之间有
  8px 的透气空、两行那条的方块垂直居中、悬停的提亮看得出来。
- **还没做（等通道恢复，与"后台子代理进度"共用同一根探针）**：
  ① 派真子代理 → `stopTask` → 它真的死了吗；② 回执事件原样（status='stopped'
  带不带 summary/output_file）；③ 模型那边随后发生什么（前台子代理被停之后
  那个 tool_result 变什么）；④ `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS` 时会不会抛。

## 6. 明确不做

- 二次确认（理由见 §1）。
- "挪到后台"（`backgroundTasks(toolUseId)`，Ctrl+B 语义）—— 那是另一个动作，
  等有人真要。
- 行内联的"停止中…"中间态：CLI 的 `task_notification` 正常在秒级到达，
  先看真机，不行再补。
