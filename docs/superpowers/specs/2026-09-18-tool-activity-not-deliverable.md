# 三条"看得见、收不到"的 SDK 面：tool_progress / tool_use_summary / prompt_suggestion

日期：2026-09-18　分支：`v0.2.21-dev`
状态：**结案 —— 不是"暂不做"，是"这一侧收不到"**（prompt_suggestion 见附录，
成因不同：那两条是**生产者丢**，它是**生成了没放**）

用户 2026-09-18 点名要接这两条。它们能进候选，是因为 `sdk.d.ts` 里**有这个类型**
（那份 SDK 面清单按"类型在不在"排的档）。真去量之后结论相反：**CLI 在把消息交给宿主
之前就把它们丢了**，插件这一侧写多少代码都是白写。这份文件把"为什么"钉死，
免得下一个人再翻一遍 233MB 的 claude.exe。

## 1. 探针（两跑，都真起会话）

`sidecar/tools/probe-tool-activity.mjs`（可重跑，见文件头）：

| 跑 | 场景 | 事件总数 | `tool_progress` | `tool_use_summary` |
|---|---|---|---|---|
| 甲 | 前台 20 秒 Bash ＋ 一次 Read ＋ 一个子代理 | 1304 | **0** | **0** |
| 乙 | 同一条命令丢后台（`run_in_background: true`） | 290 | **0** | **0** |

甲跑里 `system/task_started` 有 **2** 条、`task_progress` 1 条 —— 长任务**确实被 CLI
认成了任务**，只是进度帧不走这条线。所以那两个 0 不是"没跑起来"，是"压根不发"。

## 2. 权威依据：`claude.exe` 2.1.274 的内嵌 JS

`%APPDATA%/npm/node_modules/@anthropic-ai/claude-code/bin/claude.exe`（233 MB，
bun 打包，JS 明文在内）。`tool_progress` 全篇 13 处、`tool_use_summary` 14 处，三处关键：

### 2.1 `tool_use_summary` 被**无条件**丢弃

```js
case "tool_use_summary":
  return t("[sdkMessageAdapter] Ignoring tool_use_summary message"), {type:"ignored"};
```

没有条件、没有开关。这条消息是给**交互式 TUI** 用的（模型干完一段活那行小结），
SDK 这条路上适配器直接扔。`Options` 的 67 个字段里也没有任何一个提到它
（`sdk.d.ts` 里搜 `tool_use_summary` 只搜得到类型声明本身 —— 对比
`forwardSubagentText`、`includeHookEvents` 那种写明"开这个才有"的写法）。

### 2.2 `tool_progress` 里最有信息量的两种帧也丢

```js
case "tool_progress":
  if (e.heartbeat === true || e.subagent_retry !== undefined || e.tool_name === gt)
    return t("[sdkMessageAdapter] Ignoring heartbeat/subagent-retry tool_progress frame"), {type:"ignored"};
```

`heartbeat`（心跳）与 `subagent_retry`（子代理重试：`agent_id/attempt/max_retries/
retry_delay_ms/error_status`）**都写在丢弃条件里** —— 这条消息里唯一"别处拿不到"的两个
字段，一个也到不了宿主。

### 2.3 剩下的"普通帧"有一道硬闸门

```js
if (!env.CLAUDE_CODE_REMOTE && !env.CLAUDE_CODE_CONTAINER_ID) break;
if (s.shouldEmit(e.parentToolUseID, Date.now(), ma)) yield {
  type: "tool_progress",
  tool_name: e.data.type === "bash_progress" ? "Bash" : "PowerShell", ...
}
```

`bash_progress` → `tool_progress` 这条桥**只在 Remote Control / 容器模式下才走**。
本机普通会话（插件跑的就是这种）在这一行直接 break —— 这正是探针那两个 0 的来源。

## 3. 结论

| 想要的 | 到得了吗 | 为什么 |
|---|---|---|
| 「这次工具跑了 12s」 | ❌ | 被 2.3 的闸门挡住。而**卡片上那个秒表今天已经有了**：`web/src/elapsed.ts` 的 `useElapsed`，纯客户端，进行中的卡片本来就在跳 |
| 子代理重试上屏（网关抖动时看得见） | ❌ | 2.2：`subagent_retry` 帧在适配器就被丢 |
| 工具卡上加一句模型写的小结 | ❌ | 2.1：无条件丢 |

**不做"绕过"**：`CLAUDE_CODE_REMOTE` 是 CLI 的一种**运行模式**（remote control 的整套
传输契约），替用户把它设上不是"接一个接口"，是改他 CLI 的模式 —— 与当初不肯伪造
`bypassPermissions` 是同一条规矩（`docs/superpowers/specs/2026-09-18-subagent-nesting-design.md`
§2 那条）。

## 4. 同类需求里还活着的候选（**未验**）

`system/api_retry`（`SDKAPIRetryMessage`：`attempt / max_retries / retry_delay_ms /
error_status / error / no_response`）指向的是同一件事 —— "网关抖动时别静默"
（本仓踩过：profile 的端点被 settings.json 盖掉 → 401 静默重试三分钟）。
CLI 引擎里确实在产它（内嵌 JS 里有 `[engine] … api_retry` 与那个 REPL 重试横幅的说明），
但**它到不到 SDK 宿主这一侧没量过**：适配器那个 system 分支里有一串显式忽略，
`api_retry` 在不在里面这次没读完。

要用的话先量一次（同样的两跑探针即可，让它撞一次可重试错误），**别当已确认**。

## 5. 这次留下的东西

- `sidecar/tools/probe-tool-activity.mjs` —— 两跑探针，可重跑（`--background` 是乙跑）
- `sidecar/tools/sdk-surface.mjs` —— SDK 面 vs 已接入的那张表（这次就是它挑出的候选；
  但它只回答"**类型在不在、代码提没提**"，**能不能送达得另外量** —— 这次踩的正是这条缝）

---

# 附录　`prompt_suggestion`：**算出来了，但扣住没放**（同日追加）

用户看完那份 SDK 面清单后问"prompt_suggestion 是干嘛的"，然后说"探"。这条**不是**适配器
丢弃 —— 与上面两条不是一回事。

## A1. 两跑（`sidecar/tools/probe-prompt-suggestion.mjs`）

选项里 `promptSuggestions: true`（SDK 侧开关，CLI 侧是 `--prompt-suggestions`），
并且开 `debugFile` 让 CLI 把自己的调试日志写下来。

| 跑 | 场景 | 送到 SDK 流的建议 |
|---|---|---|
| 甲 | 三个回合，每回合 `result` 之后再等 25s | **0**（生成 2 次） |
| 丙 | 同上，最后**把输入流关掉**再等 25s | **0** |

## A2. 但它真的生成了 —— CLI 自己的调试日志（原文照抄）

```
[DEBUG] [API REQUEST] /v1/messages source=prompt_suggestion
[DEBUG] Forked agent [prompt_suggestion] finished: 2 messages, types=[assistant, assistant],
        totalUsage: input=447 output=534 cacheRead=30208 cacheCreate=0
```

一次量到三件事：

1. **每回合一次**（3 个回合 2 次 —— 第 1 回合被抑制，与 `sdk.d.ts:1950` 那条对得上）；
2. 它是 **fork 出来的一次独立调用**，耗时 2.4–3.3s；
3. **"nearly free" 在官方模型上是真的**：`cacheRead=30208 / cacheCreate=0`，
   真正新算的只有 447 输入 + 534 输出。（走自定义网关时的命中率没量 —— 那是另一件事。）

## A3. 为什么收不到（claude.exe 2.1.274 内嵌 JS）

```js
afterResult(e) {
  if (!this.promptSuggestions() || e.shouldQuery === false || this.shuttingDown()
      || env.CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION === false) return;
  ...
  let w = { type:"prompt_suggestion", suggestion: m.suggestion, uuid: $S(), session_id: V() };
  if (this.holdsResults()) this.pendingSuggestion = w, ...   // ← 扣住
  else this.lastEmitted = M, this.emit(w)                    // ← 当场发
}
```

`holdsResults()` 的实现是 `heldBackResults.length > 0 || carriedEvalResults.length > 0` ——
这条路是给"结果被扣住"的会话准备的：建议先存进 `pendingSuggestion`，等
`emitPendingSuggestion()` 放行。真机上那个时机没出现过。

**没钉死的是"什么条件才放"**：承载它的那个类的 `release()` / `emitHeldResults()` 与
`drain.inputClosed`、`runningTasks`、`pendingNotificationWaits` 纠缠在一起，压缩代码里读不出
确定的触发条件。所以结论只到"**今天这个版本 + 这条消费方式下收不到**"，**不写"永远收不到"**。

## A4. 结论与后续

- **不做**（与上面两条同档）。
- 但它值得**升版后重跑一次**：`node sidecar/tools/probe-prompt-suggestion.mjs`
  （探针留着就是为这个）。上面两条是物理上没有；这条只是今天没放行。
- **不是插件挡住的**：`MessageRenderer.renderEvent` 逐条渲染、`result` 之后不封口
  （`ClaudePanel.kt:2512` 的 result 分支只做收尾），所以真要接，缺的只是一个
  `when` 分支加一项渲染 —— 门槛在 CLI 那边。
- 顺带：插件全程**输入流是开的**（`sidecar/session.js` 的 `inputStream` 只在收到
  `stop` 命令时结束，那意味着面板关闭/会话终止），这条事实是丙跑的由来。
