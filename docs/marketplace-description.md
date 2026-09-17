# CCoder 的市场描述 —— Markdown 副本（粘贴用）

市场后台（插件页 → **Edit** → General Information → Description）从 2025-09 起可以直接改描述，
**不用重传包**；那个编辑器只吃 Markdown，存进去的是 HTML。这份文件就是给它粘贴的。

- **源码侧那份在 `src/main/resources/META-INF/plugin.xml` 的 `<description>`**（随包发上去）。
  两处是同一份文字的两个外壳：改一处，另一处跟着改。
- 第一次在后台编辑时，市场会问「以后都以后台这份为准，还是只用它到下次更新为止」。
  **选"到下次更新为止"** —— 这样仓库仍然是唯一的权威（下次传包时 plugin.xml 里是同一份文字，
  不会回退）。若要改成"以后都以后台为准"，也行，但那时 plugin.xml 就只是备份了。
- 口径：英文在前（指南要求**首 40 字符是英文**，预览卡就取这一段），中英各一份（发布设计稿 §八）。
- 标题标签（`#`/`##`）**不用** —— 见 plugin.xml 里那条注释。

复制下面分隔线以下的内容：

---

Claude Code in a PyCharm tool window: chat against the project you are editing, see every file edit as a diff before it lands, and approve the commands that touch your machine.

CCoder runs the Claude Code CLI (`claude`) you already have installed — it is not another AI backend and ships no key of its own. It works on the project you have open: its files, its `CLAUDE.md`, its settings.

**Working in the project**

- `@` references files and the code you selected in the editor (both show as one-line tokens and expand when sent); `#` references project symbols such as classes and functions. Completion is fuzzy — `cmprk` finds `ComposerMode.kt`.
- `/` opens slash commands and the preset prompts you saved.
- Ctrl+V pastes a screenshot straight into the input box.
- Type while Claude is working and your message is queued, sent when the turn ends.

**Changes you can see — and stop**

- Every file edit is shown as a diff before it is applied.
- Commands that touch your machine (shell, writes outside the project) wait for your Allow or Deny.
- Six permission modes, from read-only Plan and Auto-accept-edits to a classifier-driven Auto that judges each prompt and still asks when unsure.
- Plan approvals are laid out as Markdown, in dialogs you can resize.

**Sessions**

- Up to five sessions at once in one window; each has its own chip, with a status dot for running / waiting on you / idle.
- Sessions are named after your first message; resume, rename or delete them from the list. Closing a tab that is still working asks first.

**Bring your own model** — choose the model and the thinking effort; model profiles point at your own gateway or endpoint.

**Project configuration**

- MCP panel: read and edit the project's `.mcp.json`, with the live connection status of every server.
- Hooks panel: read and edit hooks in the project's `.claude/settings.json` — nothing else in that file is touched.

**Requirements**

- PyCharm 2025.3 or newer.
- Claude Code CLI (`claude`) — **not bundled**; CCoder drives the copy you installed.
- Node.js.
- An Anthropic account, subscription or API key — whatever the CLI itself uses.

**Notes**

- The plugin talks only to the CLI it launches; your code and prompts go where that CLI is configured to send them.
- The CCoder interface is currently in Chinese; Claude's replies follow your prompt's language.
- Not affiliated with Anthropic. "Claude" and "Claude Code" are trademarks of Anthropic PBC.
- MIT licensed. Source and issue tracker: [github.com/princeyuaner/CCoder](https://github.com/princeyuaner/CCoder)

**中文**

在 PyCharm 的工具窗口里跑 Claude Code：就着你正在编辑的项目提问，每次改文件都先给你看 diff，会动你机器的命令由你放行或拒绝。

CCoder 驱动的是你自己已经装好的 Claude Code CLI（`claude`）—— 它不是另一个 AI 后端，也不自带任何密钥。它就在你打开的那个项目里干活：项目里的文件、它的 `CLAUDE.md`、它的设置。

**在这个项目里干活**

- `@` 引用文件，以及在编辑器里选中的代码（都显示成一行记号，发送时才展开）；`#` 引用项目里的类、函数等符号。补全是模糊的 —— 敲 `cmprk` 也能找到 `ComposerMode.kt`。
- `/` 打开斜杠命令，以及你在设置里存下的预置 prompt。
- Ctrl+V 把截图贴进输入框。
- Claude 干活时你照样能打字：消息排进队列，当前这轮一结束就发出去。

**看得到、也拦得住的改动**

- 每次改文件都先给你看 diff。
- 会动你机器的命令（shell、项目外的写入）由你放行或拒绝。
- 六种权限模式，从只读的「仅规划」、「自动接受编辑」，到由模型逐条判定的「自动判定」—— 拿不准的仍会询问。
- 计划审批按 Markdown 排版，框可以拉伸。

**会话**

- 一个窗口里最多同时开 5 条会话；每条一颗胶囊，一颗状态点在跑 / 等你 / 空闲。
- 会话名取你说的第一句；列表里可恢复、改名、删除。关掉还在干活的标签会先问一句。

**自带模型** —— 选模型与思考档位；模型配置（profile）还能指向你自己的网关或端点。

**项目配置**

- MCP 面板：读写项目级 `.mcp.json`，每个 server 的连接状态实时可见。
- hooks 面板：读写项目级 `.claude/settings.json` 里的 hooks —— 同文件别的内容一个字节不动。

**需要什么**

- PyCharm 2025.3 或更新版本。
- Claude Code CLI（`claude`）—— **不随插件分发**，驱动的是你已经装好的那份。
- Node.js。
- CLI 本身要的东西：Anthropic 账号 / 订阅 / API key。

**说明**

- 插件只和它启动的那个 CLI 说话；你的代码与 prompt 去向由那个 CLI 的配置决定。
- 与 Anthropic 无关；"Claude" 与 "Claude Code" 是 Anthropic PBC 的商标。
- MIT 许可。源码与问题反馈：[github.com/princeyuaner/CCoder](https://github.com/princeyuaner/CCoder)
