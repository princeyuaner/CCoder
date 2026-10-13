# CCoder 0.2.37：本轮改动卡（清单 / 对比 / 回滚）

日期：2026-10-13　分支：`v0.2.37-dev`　`pluginVersion=0.2.37`

设计稿：`docs/design/turn-changes.html`（用户已选：机制甲·侧车快照 + 落盘；卡片乙·默认收起一行；
选择甲·复选框批量；确认甲·卡内确认态。未动的两项按推荐：Review 全开、点行=打开对比）。

---

## 1. 探针事实（2026-10-13 实测，可重跑：`node sidecar/tools/probe-turn-changes.mjs`）

| # | 事实 | 怎么量到的 |
|---|---|---|
| 1 | `Options.hooks.PreToolUse`（进程内 JS 回调）在 **default / acceptEdits / bypassPermissions 三种模式都先于工具执行触发**，`tool_input.file_path` 在 | 三模式各跑一个完整回合，各 3 次编辑 |
| 2 | **「同轮同文件只留第一份 = 本轮开始前」成立**：两次 Edit 同一文件，hook 第一次读到 `AAA`、第二次读到 `BBB`（第一次改后的内容） | 同上（时序证据链） |
| 3 | **Write 新建时 hook 那一刻文件不存在**（`exists=false`）——「回滚 = 删除」的依据 | 同上 |
| 4 | 回调返回 `{}` **零副作用**：事件流 0 条 `hook_*` 事件、工具照常执行 | 同上（三个回合都是 0 条） |
| 5 | **resume 恢复的会话**照常触发 | 恢复后发一条 Read |
| 6 | **子代理里的编辑照常触发**，且 `agent_id` 在（子代理改的文件也是「本轮改的」） | 让子代理改一个文件 |

留一条给集成探针补钉：hook 的 `tool_input.file_path` 与 assistant 消息里 `tool_use.input.file_path`
**不要求逐字节一致** —— 标记的配对走 `toolUseId`（§4），不信路径字符串。

## 2. 语义（文案的唯一依据）

- 「本轮」= **最后一条用户消息之后**；卡片**只活在最新一轮**，下一轮开始上一张卡就被取代。
- 「改动前」= 该文件在本轮**第一次被编辑工具改动之前**（同轮同文件只存第一份）。
- **回滚 = 把那一份写回；存档时文件不存在 → 回滚就是把它删掉**。
- **对话一个字不动**：工具卡原样保留，回滚只追加一条系统提示。
- 忙时不能回滚（查看对比随时）；Bash 改的文件看不见（共同盲区，卡片文案照实说「编辑工具」）。

## 3. 线路

```
侧车（PreToolUse hook，先于工具落地）
  Edit/Write/MultiEdit/NotebookEdit + tool_input.file_path + tool_use_id
  → 读原样存档（键：会话 × 轮 × normalize(cwd, path)；同轮同文件只留第一份）
  → out {type:'turnSnapshot', toolUseId, path, existed}   ← 网页据此标「新 / 无存档」

网页（卡片，纯前端聚合 toolFile/toolDelta；只认成功落地的编辑）
  ├ openTurnDiff{path} ─▶ 插件 ─ request fileSnapshot ─▶ 侧车取内容 ─▶ DiffManager 打开对比
  └ revertFiles{paths} ─▶ 插件 ─ request revertFiles ─▶ 侧车逐文件写回/删除 ─▶ 逐文件回执
                                                    └▶ 系统提示 + 推 revertReport 给网页
```

- **存储**：`~/.ccoder/turn-snapshots/<projectKey>/<sid>/index.json` + blob 文件
  （tmp+rename 原子写，同 `usage.js`）；**单文件 ≤ 2MB**（超过标「未存档」）；
  **每会话保留最近 20 轮**，超限淘汰。设置里一颗开关（默认开），关掉 = 行为与今天一字不差。
- **轮次号**：侧车每 `send` 递增；重启恢复后从磁盘索引的 `max+1` 续（不撞号）。
- **匹配**：网页→侧车的路径按 `normalize(cwd, raw)` 归一键（斜杠、`/c/` 盘符、相对转绝对、
  Windows 小写折叠）——归一化只在这个边界做一次；**标记配对不信路径，信 `toolUseId`**（见 §4）。
- **防呆**：忙时侧车也拒绝回滚（网页禁用是第一道，这是第二道）。
- **恢复会话**：`snapshotIndex` 把最近一轮的 entries（含 `toolUseId`）在 ready 时推给网页，
  恢复进来的最新一轮照样标「新 / 无存档」、照样能对比能回滚。

## 4. 协议增量

| 层 | 增量 |
|---|---|
| web → Kotlin 桥 | `openTurnDiff{path}`；`revertFiles{paths:[...]}` |
| Kotlin → web 转写 ops（进 `shared/transcript-ops.json`） | `snapshots{entries:[{toolUseId, path, existed}]}`（ready 时全量 + 实时逐条）；`revertReport{results:[{path, ok, action:'restored'\|'deleted'\|'failed', error?, dirty?}]}` |
| Kotlin ↔ sidecar | 请求 `fileSnapshot{id, path}` → 回 `{kind:'file'\|'absent'\|'missing', content?}`；请求 `revertFiles{id, paths}` → 回逐文件回执；请求 `snapshotIndex{id}` → 最近一轮 entries；事件 `turnSnapshot{toolUseId, path, existed}` |

`action` 只有三种：`restored`（写回）/ `deleted`（本轮新建）/ `failed`（带 `error`）。
`dirty` = 回执时发现文件在**本轮结束后**又被改过（mtime 比对；记录在轮的收尾）。

## 5. 边界（不静默，每态一句话）

| 情况 | 表现 |
|---|---|
| 会话忙 | 回滚禁用（悬停一句话）；对比可用 |
| 本轮没有改动 | 卡片不出现 |
| 无存档（开关关 / 超 2MB / 老轮次） | 行标「无存档」；点击只打开文件；勾不上 |
| 本轮结束后文件又被改过 | 回执里 `dirty`，行上写「覆盖了本轮后的新改动」 |
| 写盘失败 / 文件被外部删 | 逐文件红字 + 系统提示汇总；失败行保持可勾选，重试幂等 |
| 回滚更早的轮 | 不做（卡片只活最新一轮），说清 |
| 部分失败 | 「已回滚 N 个文件，M 个失败」，逐条列 |

## 6. 测试层

| 层 | 测什么 |
|---|---|
| 探针 | §1 六条（唯一能证明 hook 时序的手段，单测测不出） |
| web 纯函数 | `turnFiles` 聚合：口径（成功才计/被拒不算/子代理算）、`新`/`无存档` 标记（按 toolUseId 配对）、MultiEdit/NotebookEdit、`+N −N` |
| web 组件 | 卡五态（常态 / 选择 / 确认 / 已回滚 / 无存档）· 渲染探针出图**要真的打开看** |
| sidecar | 快照 store：同轮同文件只留第一份、2MB 上限、20 轮淘汰、原子写、normalize 键、回滚回执三分类、忙时拒绝；假 session 注入的 dispatcher 测试（断言调用形状） |
| Kotlin 纯函数 | 桥消息解析（照 `OpenFileTarget` 的拆法）；系统提示文案计数 |
| 集成探针 | 真机全链路：两次 Edit + Write 新建 → 回滚 → 逐字节核对 + 新文件被删 + dirty 检测 |

## 7. 明确不做

- 历史轮的回滚（卡片只活最新一轮；工具卡还能看 diff，但回不了）
- Bash 改动的追踪（工具入参里没有，共同盲区）
- 行内 ↺ 单个快回（选择丙，留到以后）
- 确认态里的逐行 `dirty` 预告（v1 只有通用一句 + 回执里的逐行 dirty；要提前就得再加一条预览协议，等有人抱怨再说）

## 8. 执行记录（2026-10-13）

落地当天完成（尚未提交）：探针 2 支、store + 协议三层、卡片 + 聚合、设置开关。
三套测试：sidecar `node --test` **274 全绿** · web `vitest` **368 全绿** · Kotlin `gradlew test` 全绿。

### 被渲染探针抓住的（单测看不出来的那类）

1. **`.turn-changes` 用了不存在的 `--panel` 变量** —— `ThemeInjector` 只注入
   `--bg/--surface/--text/…`（见那个文件的变量表），`--panel` 会静默落到深色兜底：
   **浅色主题下卡片是一块黑**。渲染探针（`web/tools/turn-changes-view.mjs`
   的 `--shot=light`）一眼看见。改用 `--surface`（与工具卡同源）。
2. 目录截断的省略号会贴住文件名 —— `.turn-row__dir` 加 2px 右内边距。

### 被单测抓住的

1. **「回滚所选」漏了确认态**：操作条那颗按钮直接提交了，确认那一步没接上
   （`TurnChangesCard.test.tsx` 的"翻成确认态"用例变红）。改回 `setConfirming(true)`。
2. **聚合把 `Read` 当成编辑**：`toolFile` 认得 `Read`（那是给"点文件名打开"用的），
   拿它当编辑白名单会把只读调用算成"改过"。聚合自己限定编辑类工具
   （与侧车 `EDIT_TOOLS`、Kotlin `DIFF_TOOLS` 同族）。

### 与计划的偏离

- 协议从"两条"长成了三条请求 + 一条事件 + 两条网页 op：`fileSnapshot` /
  `revertFiles` / `snapshotIndex`，事件 `turnSnapshot`，ops `snapshots` /
  `revertReport`。多出来的是**恢复会话**那条路（没有实时事件可听，得补一次全量索引）。
- `shared/transcript-ops.json` 末尾补了两条（`snapshots` / `revertReport`），
  两侧的解析用例都跟着钉。

### 仍未做的（写在这里，不掩盖）

- **手工冒烟一次都没跑** —— 三套自动化全绿，但没起过沙箱 IDE：DiffManager 打开的
  对比视图长什么样、VFS 刷新后项目树/编辑器的实时反应、Swing 设置页那颗新开关的
  排版，都还是"代码上如此"。这是本版最大的未验证面（同 0.2.13 那份 spec 的记法）。
- 集成探针（`probe-turn-revert.mjs`）跑的是**侧车层**全链路（真模型 → 快照 → 回滚
  → 逐字节核对）；Kotlin ↔ 侧车 ↔ 网页那两跳靠单测与形状断言，没在真 IDE 里连过。
