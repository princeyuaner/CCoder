# CCoder 0.2.30：后台任务面板

日期：2026-09-28　分支：`v0.2.30-dev`　`pluginVersion=0.2.30`

选型稿：`docs/design/background-tasks.html`（用户挑了**乙** —— 三段式面板）。
SDK：`@anthropic-ai/claude-agent-sdk@0.3.283`（捆 CLI 2.1.283）。

---

## 1. 要解决的问题

那张卡点开只回答一个问题："眼下有谁在跑"。而且：

- `task_notification` 一到，那一行就从表里**删掉**（`RunStatusTracker.kt` 的
  `byId::remove`）—— "成了没有、败在哪、输出去哪了"在界面上是零；
- `task_updated.patch.status == "paused"` 从前明确忽略（那时只有"在跑"一档，暂停没有落点）；
- ambient（看盘、live-update 看门狗）被**整个过滤掉**，于是"还有几个后台在看家"说不出来。

而 SDK 一直在送这些字段，只是没人接。

## 2. 前提：事件里到底有什么（`sdk.d.ts`）

| 事件 | 字段 | 我们从前怎么用 |
|---|---|---|
| `task_started` | `task_id` / `tool_use_id` / `description` / `subagent_type` / `task_type` / `is_backgrounded` / `spawn_depth` / `workflow_name` / `prompt` / `skip_transcript` / **`ambient`** | 建行；ambient 直接丢 |
| `task_progress` | `usage{total_tokens, tool_uses, duration_ms}` / `last_tool_name` / `summary` | 只留 token 与时长 |
| `task_updated` | `patch{status ∈ pending/running/completed/failed/killed/paused, description, end_time, total_paused_ms, error, is_backgrounded}` | 只拿 `status` 判终态 |
| `task_notification` | `status ∈ completed/failed/stopped` / `reason` / `summary` / `usage` / **`output_file`** / `resource_links` | **只用来删行** |
| `background_tasks_changed` | 整集替换的 `tasks[]{task_id, task_type, description, ambient}` | 替换成员，保留读到的数字 |

两处容易踩的词表差异（都写进了用例）：

- **`stopped`（notification）与 `killed`（updated）是同一件事**，少认一个就会把"被停掉"
  画成一个勾；
- `task_updated` 的 `paused` 是**真状态**，不是"还不知道"。

## 3. 决定

1. **三段**：在跑 / 暂停 / 刚结束（`buildTasksDetail`）。
2. **刚结束只留最近两条**（`RunStatusTracker.MAX_FINISHED = 2`）。它是"刚才那一下怎么了"的账，
   不是历史 —— 回看整段历史是会话文件的事。
3. **行是两排**：上排 记号 + 类型与名字 + 读数（时长 · 工具数 · token）+ 右端控件；
   下排 进行时（`summary` → `description`）+ 最后用到的工具 / 结局摘要 + 两颗小按钮。
   读数里每一项**有才写**（刚起来的任务只有一个时长，不写"0 工具"）。
4. **卡面**：第 4 张 `Tasks`→`List`（中文「任务列表」→「清单」），第 5 张 `Agents`→`Tasks`
   （中文「子代理」→「任务」）。理由：第 5 张现在装的是**所有**后台任务（子代理之外还有
   后台命令、MCP 任务），叫「子代理」是错的；而两张都叫「任务」更糟。
   **值行仍是那个数**（在跑数）—— "2 跑 · 1 完"在 58px 的格子里放不下（英文更放不下），
   见 `StatusCardsRowTest` 的量法。
5. **ambient 只贡献一个数**：不进"在跑"的计数（SDK 明说它们不是活动），面板里折成一句
   "还有 N 个后台维护任务（不计入上面的数）"。
6. **`task_id` 与 `tool_use_id` 分存**：与子代理记录对号优先用后者（`SubagentInfo.toolUseId`）。
   从前只存 `task_id`，等于押"两者相等"这个**没人验过**的假设。
7. **看输出**：读 `output_file` 的**末尾**（200 行 / 256KB 封顶），从尾部反向按块读 ——
   `Files.readString` 在几十 MB 的输出上是 OOM 的路。头被切断的那一残行**丢掉**；
   文件不在了就直说"输出文件不在了（临时目录会被清掉）"，不装作空输出。
   读盘在 EDT 之外（骨架同 `openInEditor`），框先弹、内容后填。
8. **不闪那件事继续管**：`rendersSameShapeAs` 的判据补上"段归属（`paused`）"与
   "有没有第二排"，"刚结束"另有一条自己的判据（那一段的行恒为两排）。

## 4. 与既有决定的关系（两条是反转，都不是推翻）

- **2026-09-24"查看已结束的不要了"**：那次砍掉的是**磁盘上那份"全部子代理"历史列表**
  （长、无时间线、还要为它单独发一次请求）。这次回来的是**最近一两次的结局**，
  数据在事件流里、随手就有。`SubagentInfo` 仍然只用于"对上号"。
- **2026-09-24"也不用显示当前运行的工具，只需要显示标题即可"**：下排这次加回来了
  （用户从选型稿里挑的乙就是带读数的版式）。**哪天又觉得吵，该删的是下排**，
  删掉之后 `RunningTask.detail/lastTool` 会再次无人画 —— 但字段照旧收着。
- **没做**：丁（`Query.backgroundTasks()` 把前台阻塞的任务挪到后台）、
  `Options.perTaskStopAffordance`（声明它之后「停止」只收这一回合，后台任务留着）。
  两条都是独立动作，另开。

## 5. 边界与代价

- **命令类任务（`local_bash`）是上报的 —— 2026-09-28 真机截图确认。**
  面板「刚结束」里出现过两行 `local_bash`，带 ✓ 与「看输出」：那是一个子代理跑的两条
  `awk` 计数命令。**这条面板的覆盖面比原先写的大**：命令类任务照样进面板、而且带
  `output_file`（「看输出」真正有料可看的就是它们）。
- **但主代理自己发的 `run_in_background` 命令没进面板**（同一天：12 秒 ticker、
  注定失败那条、带暗号的短命令，三条都没出现）。两种可能，**还没分辨**：
  ① 它们被 CLI 标成 `ambient` / `skip_transcript`，而我们把这类**整个丢掉**
  （`RunStatusTracker.isAmbient`：不进 `byId`、结束也不留记录）——
  可 SDK 的原话是这类 "may still appear in a tasks panel"，**那这条就是我们自己的取舍错**；
  ② 主代理侧的 background bash 在 harness 那层就没注册成 task。
  **分辨法（下次先做）**：派一个子代理去跑同一条 `run_in_background` 命令，
  看它进不进面板 —— 子代理跑的命令已经证明能进。
- **`probe-stop-bash.mjs` 自己是瞎的**：2026-09-28 按它文件头写的最短路径加了对照相位
  `background-plain`（`canUseTool` 照常 allow、**不动 `updatedInput`**）——结果命令照跑
  （心跳 9 条）而消费侧**一条消息都没收到**，连 `assistant` 都没有。
  所以**不是 `updatedInput` 的锅**；这条流为什么哑仍未知。
  **不要再用这个探针下"CLI 不上报"的结论**（当天我就这么误判过一次）。

- **面板只记"连上这个会话之后看到的"**：`reset()` 在换会话时清空，重开会话不带历史。
  面板脚注明写这句（不然"我的任务呢"会变成一个说不清的 bug）。
- **`resource_links` 没用**（MCP 任务产出的文件）：这一版只做输出，产出文件另说。
- **`output_file` 可能已被清掉**（临时目录），那时只剩"看转写"这条路。
- **`spawn_depth` / `workflow_name` / `is_backgrounded` / `reason` 都收下但没画** ——
  面板一屏说不完那么多，先记着。

## 6. 验证

- 单测：`RunStatusTrackerTest`（42 条，含刚结束、暂停、ambient 计数、两套终态词）、
  `RunDetailTest`（三段、两排、结局行、看输出那颗钮点了报什么）、
  `TaskOutputTest`（末尾读的七条边界，真文件系统）。
- 渲染探针：`RunningDetailRenderProbe` → `build/probe/tasks-three-stage.png`
  （三段齐全那一张，含暂停与失败两行）。**离屏渲染是这一层唯一的"观感"证据。**
- 三侧测试：Kotlin / web / sidecar，见发布流程。
- **真机（2026-09-28，装了 0.2.30 的 PyCharm 上）**：
  - 派一个最小子代理 → `jcmd <IDE pid> VM.class_hierarchy com.ccoder.ui.TaskOutcome`
    从未加载变已加载 ⇒ "刚结束"那条路真的跑过（比"看着像"硬）。
  - 截图确认：`local_bash` 行（✓ + 看输出）、■ 停止（连停两个子代理）、
    "只留最近两条"都在真机上成立。
  - **还没验的**：点一次「看输出」（`TaskOutputContent` 才会加载）。
    验法同上：点完再问那个进程一次。
- 真机（挂账 #15 那条一起看）：三段在真实窗口里的高度与滚动；`output_file` 在 Windows 上
  的真实落点与生命周期；ambient 在真实会话里的数量级。
