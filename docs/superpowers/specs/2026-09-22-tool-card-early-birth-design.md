# 工具卡：出生提前到「起头帧」

日期：2026-09-22　分支：`v0.2.25-dev`　`pluginVersion=0.2.25`
状态：**已实现**

---

## 1. 用户要什么

> 「输出区的工具调用显示感觉有问题，现在是不是只有调用完成才会显示出来，比如读取文件，
> 搜索等这些操作，我希望是先把操作展示出来，如果该操作是进行中，应该显示转圈的状态，
> 完成后显示勾」

**用户猜得基本对。** 转圈与勾那套画法**早就有了**（`web/src/toolStatus.ts` +
`ToolCallBlock.tsx`，设计稿 `docs/design/tool-progress.html` 方案丙，2026-09-15），
缺的不是画法，是**卡片的出生时刻**。

## 2. 事实（实测）

一张卡原先只在**完整 assistant 消息**到达时才出生（`MessageRenderer.renderAssistant` 的
`tool_use` 分支），而参数生成在它之前。用用户自己的会话 JSONL 量「完整消息 → tool_result」
这一段（= 卡片能转圈的窗口）：

| 工具 | n | 中位 | 最大 |
|---|---|---|---|
| Read | 3 | **21ms** | 38ms |
| Bash | 41 | 168ms | 150373ms |
| MCP（codegraph_explore） | 4 | 248ms | 280ms |

**读取、搜索那类快工具，卡片生下来就已经是完成态** —— 屏幕上永远看不到转圈。
另一头是参数生成期（`probe-write-timeline.mjs`：80 行 3.5s，按行数等比放大 400 行 15s+），
那一段最初只有状态卡在说「编辑文件」（`ToolStarting` 明确不进转写区，2026-09-15 的决定）。

## 3. 做法

起头帧 `content_block_start[tool_use]` 里已经带着 `content_block.id` 与 `name`
（仓库自己的夹具就是这么写的：`MessageRendererTest.kt`），用它把卡片**提前生出来**：

```
content_block_start ──► Append toolUse(input="")     卡片出生：名字 + 转圈 + 秒表
完整 assistant 消息 ──► Append toolUse(input="{…}")  同 id，界面**合并**：文件名/命令出现
tool_result         ──► Append toolResult            既有规则：打勾
```

- Kotlin：`startedToolCard()`（纯函数，`MessageRenderer.kt`）把 `ToolStarting` 变成一张
  **空参数**的 `ToolUse`；`toOp` 给它一条 `Append`。**id 空 → 不画卡**（空 id 配不上结果，
  只会永远转圈），退回今天的样子。
- Web：`codec.ts` 的 `applyOps` 在 append 一条 `toolUse` 时，若本地 `items` 里已有同
  `toolUseId` 且 **`input === ''`** 的那张，就替换它而不是新增 —— **保留旧 `id`/`ts`**。

## 4. 三个刻意的取舍

1. **不加新 op**（不改协议）。`parseOps` 对未知 op 是**静默丢弃**（「未知即忽略」是写明的
   契约），新增 op 一旦遇上版本错配，工具卡会**整类消失**；而"同 id 的 append 合并"最坏
   只是多一张卡（不崩、不丢数据）。代价：Kotlin 侧不需要记"哪些 id 生过卡"，也就没有
   「四处 Reset 都要记得清集合、漏一处就静默丢卡」的坑。
2. **只吸收空参数那张**（`input === ''`）。完整卡 → 完整卡不走合并 —— 宁可多画一张，
   也不要凭空少一张。
3. **保留旧 `id`/`ts`** 是要害不是美观：React 的 key 是 `item.id`，换掉等于重挂组件 ——
   秒表归零（`elapsed.ts` 的 `mountedAt`）、展开态与「显示全部」一起丢。

## 5. 顺带修的一处（探针抓到的）

`.tool__status` 原先靠"标题恰好是个弹性元素"（`.tool__title { flex: 1 1 auto }`）被顶到最右，
而标题是**有条件渲染**的。起头帧那张卡没有标题 → 状态位会紧挨着工具名，等参数到了再往右跳
（实测量到空档 349px）。改成 `margin-left: auto` 之后是结构性的，布局探针新增的 `started`
场景把这条钉住（去掉那一行探针立刻报错）。

## 6. 边界（写进代码注释）

- **中途被打断**（参数还在生成就停）：占位卡转圈 → 回合结束那条 `result` 一到，既有的
  `endedToolUseIds` / `toolStateOf` 把它收成 ⊘，不需要新规则。
- **assistant 消息带 error 时**会留下一张孤儿占位卡（同样的收尾兜住）—— 这是"完整消息必然
  跟在起头帧后面"唯一的例外。
- **子代理的工具今天不会提前出生**（流事件只走主线程，实测 0 条带 parent）；`ToolStarting`
  仍然认流信封上的 `parent_tool_use_id`，真发过来时卡片会直接生在 Task 卡里。
- **回放历史**走完整消息那条路（没有 `stream_event`），不受影响。

## 7. 验证

- 三侧测试：Kotlin 1646 条、web 307 条、sidecar 213 条，全绿。
- 契约：`shared/transcript-ops.json` 多一条起头帧 append（18 条 op）；两侧用例各钉一半 ——
  Kotlin 侧钉"起头帧 input 为空、完整那条同 id"（`TranscriptOpCodecTest`），web 侧钉
  "两条合成一张卡、id 是前一条的"（`codec.test.ts`）。
- 布局探针（真实 Chromium）新增 `started` 场景：没有标题的卡，状态位仍在最右。
- **真机那一步不能被替代**：起会话让它读文件/搜索，应当看到「卡片先出（名字 + 转圈 + 秒表）
  → 文件名出现 → 勾」。若仍然在完成时才出现，唯一解释是起头帧没带 id（`startedToolCard`
  会给 null），行为退回改动前，不会更糟。
