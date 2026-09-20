# 恢复历史里的「CLI 信封」—— 实施记录

日期：2026-09-20　分支：`v0.2.23-dev`（本条的起点是用户来问的那一刻，见下）

---

## 一、症状

用户重启 IDE 后，恢复出来的转写区里躺着两条**像是自己说过的话**的气泡：

```
<command-name>/compact</command-name>
            <command-message>compact</command-message>
            <command-args></command-args>

<local-command-stdout>Compacted </local-command-stdout>
```

两条的时间戳还都是**重启那一刻**（14:06），不是命令真正跑的时间。

## 二、根因

**同一个事件在两条路上形状不同，而回放那条路的判据太松。**

会话文件（`84d335d7…`）里它们是两条普通条目 —— `type:"user"`、内容是**纯字符串**、
`isMeta` 没标：

| 文件行 | 内容 |
|---|---|
| 2411 | `<command-name>/compact</command-name>\n<command-message>compact</command-message>\n<command-args></command-args>` |
| 2412 | `<local-command-stdout>Compacted </local-command-stdout>` |

- **实时看不见**：`renderToolResults`（MessageRenderer.kt）只认**块数组**，纯字符串的
  user 事件一律丢；用户气泡是 `sendCurrentInput()` 本地回显的（就是你打的那串 `/compact`）。
- **回放看得见**：`renderPrompt` 的判据只有「`type=user` 且内容是**非空字符串**就当提问」
  —— 信封全部满足。
- **时间戳**：回放项的时间戳是**推出去那一刻**（`toOp` 里 `System.currentTimeMillis()`，
  ClaudePanel.kt:2894），所以两条同分钟。

信封是 CLI 自己造的（`claude.exe` 内嵌 JS 的 `Ke()`）：

```js
Ke(name, args) = [`<command-message>${name}</command-message>`,
                  `<command-name>/${name}</command-name>`,
                  args ? `<command-args>${args}</command-args>` : null].join('\n')
```

## 三、量到的事实（都是实测，不是推的）

1. **SDK 已经滤掉两类，而且把条目归一化了**：`getSessionMessages`（SDK 0.3.274）只回
   user/assistant —— 这条会话 107 条回放项里 **0 条 `system`**，`isMeta` 的
   `<local-command-caveat>` 那条也不在里面。即 `compact_boundary` 回执**恢复时根本
   到不了**（见 §五）。更要紧的是**回来的字段一律只有**
   `message,parent_tool_use_id,session_id,timestamp,type,uuid`（实测 196 条完全一致）
   —— **`isCompactSummary` / `isVisibleInTranscriptOnly` / `isMeta` 全都不传过来**。
   所以摘要**不能靠标记认**，只能靠内容（见 §四）。
2. **照抄 `renderPrompt` 的判据、拿回放项跑**：修前会画成用户气泡的只有 **4 条** ——
   信封两条 + 压缩摘要（**15153 字**）+ 用户真正打的那一条。修后只剩最后那一条。
3. **CLI 自己的转写过滤器**（`claude.exe` 内嵌 JS，权威判据）：

   ```js
   function Dhn(e){ return e.type==="user" && !e.isCompactSummary && !Z2r(e) }
   // Z2r = 内容以信封标签开头：Q1e 的 bash 四件套 + v1 的 record/output/caveat + YN 那串
   ```

   `hJn` 还把信封分了三档：`record`（命令回显）/ `output`（命令输出）/ `caveat`（给模型
   的说明）。**CLI 自己的转写视图对它们一条都不画**，压缩摘要同样不画。

## 四、修法（一处判据 + 用例）

`MessageRenderer.renderPrompt` 加三道挡：

1. 内容是**信封标签开头** → 不画。名单 = CLI 三张表的并集（`command-name` /
   `command-message` / `local-command-stdout` / `local-command-stderr` /
   `local-command-caveat` / `bash-input` / `bash-stdout` / `bash-stderr` /
   `bash-exit-code` / `task-notification`）。`<command-args>` **不在**名单里 —— 它只
   出现在 `<command-name>` 之后，做不了开头。
2. 内容是**压缩摘要的模板开头** → 不画：`This session is being continued from a
   previous conversation`（CLI 的 `Nae()` 拼死的句子）。这是拦摘要的**正主** ——
   标记传不过来（事实 1）。CLI 哪天改措辞，最坏是那条 15KB 气泡又冒出来，看得见。
3. `isCompactSummary` / `isVisibleInTranscriptOnly`（**兜底**，CLI 的 `Dhn` 认前者、
   另一处认后者）：哪天 SDK 把字段透传回来，这两条接得住。

三道都**只认开头**（CLI 自己也是 `startsWith`）：用户真在消息里引用这些标签、或提到
"previous conversation"，不该被吞掉 —— 有用例钉着。

用例（`MessageRendererTest`，新增 6 条）：信封五标签、只在开头才算、摘要模板、
两个标记各自都够、提到"上次那段对话"的提问照画、块形式的信封、判据名单。

## 五、代价与边界（刻意，不是漏）

- **恢复后看不到"这里压过一次"**：唯一的痕迹是那条摘要与两条信封，现在都不画了；而
  `compact_boundary` 回执在 SDK 层就被滤掉（事实 1）。`compactReceiptOf` 的 camelCase
  分支今天没有实战路径 —— 那条注释已按实测改掉，别再假设"恢复历史能看到压缩过"。
- **live 那条路一个字没动**：命令输出仍走 assistant 文本块（composer 设计稿事实 3），
  空输出仍由 `isEmptyCommandOutput` 丢。
- **与 0.2.22 无关**：只要会话里跑过 `/compact`、`/clear` 这类本地命令，恢复时就会这样。
- 本条要进 0.2.23 的 change-notes（发布时按 git log 收，口径见发布设计稿 §八）。

## 六、自查

- **修完再跑一遍同一批回放项**（把这套判据照抄成脚本，喂 `getSessionMessages` 的真实
  条目）：会画成用户气泡的从 **4 条 → 1 条**，剩下那条正是用户真打的那句话。
- `./gradlew test -PskipWeb` 全绿（含新增 6 条）。sidecar / web 两侧未动，无需重跑。
- 全量第一次跑时红过一条与本次无关的时序用例（`SidecarProcessTest` 的
  `shutdown 在宽限期内自行退出时不强杀`，判据是"别等满 5s 宽限期"，负载下 node 子进程
  真的用了 5015ms）；单跑与重跑都绿 —— 那是一条**卡在墙钟上**的用例，另开一条再说。
