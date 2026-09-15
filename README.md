# CCoder

Runs the Claude Code CLI inside a PyCharm tool window: ask against the project you are
already editing, see every file edit as a diff before it is applied, and approve or deny
the commands that touch your machine.

## What it does

- Chat with Claude Code in a tool window, against the project you have open
- Every file edit shown as a diff; commands that touch your machine wait for your approval
- Reference files and editor selections with `@`
- Session list — resume, rename, delete; sessions are named after your first message
- Preset prompts — save your own and pick them with `/`
- MCP servers and hooks — edit the project-level `.mcp.json` and `.claude/settings.json`
- Model profiles — point at your own gateway or endpoint

## Requirements

- PyCharm 2025.3 or newer
- The Claude Code CLI (`claude`) — **not bundled**; the plugin uses the copy you have installed
- Node.js

## Install

Build the plugin and install the ZIP (Settings → Plugins → ⚙ → Install Plugin from Disk):

```bash
./gradlew buildPlugin          # 产物在 build/distributions/
```

**Node.js must be on `PATH`** when you run this — the build shells out to `npm` for the
transcript UI. If `npm` is installed but not on the daemon's `PATH`, run `./gradlew --stop`
first so the daemon picks up the new environment.

## Development

```bash
./gradlew test -PskipWeb       # Kotlin (skips the web build)
cd sidecar && npm test         # sidecar — node --test
cd web && npm test             # transcript UI — vitest
./gradlew runIde               # launch a sandbox IDE with the plugin
```

The architecture is three layers: **Kotlin UI → Node sidecar (NDJSON over stdio) → the
`claude` CLI**, with the transcript rendered by a React app inside JCEF.

Design docs and the reasoning behind most decisions live in
[`docs/superpowers/`](docs/superpowers/).

## Third-party components

The plugin bundle includes [`@anthropic-ai/claude-agent-sdk`](https://www.npmjs.com/package/@anthropic-ai/claude-agent-sdk).
That package is Anthropic's own software and remains under **its** terms —
"© Anthropic PBC. All rights reserved." The MIT license in [LICENSE](LICENSE) covers this
project's own code only.

## License

MIT — see [LICENSE](LICENSE).

---

## 中文

在 PyCharm 的工具窗口里跑 Claude Code：就着你正在编辑的项目提问，每次改文件都先给你看
diff，会动你机器的命令由你放行或拒绝。

需要自备 **Claude Code CLI（`claude`）** 和 **Node.js** —— CLI 不随插件分发，用的是你
已经装好的那份。

界面是中文的。设计稿、探针与执行记录都在 [`docs/superpowers/`](docs/superpowers/) 里。
