/**
 * 探针：MCP 与 hooks 的项目级配置，CLI 到底怎么读。
 *
 * 整套 MCP/hooks 面板押在两件**文档里没有权威**的事上：
 *
 *   1. `.mcp.json` 的 schema —— SDK 的 `sdk.d.ts` 里**只提到文件名**（在
 *      `strictMcpConfig` 与 `managedMcpServers` 的注释里），没有给类型定义。
 *      仓库里 `mcpServers` / `.mcp.json` 也是零命中。面板要写这个文件，
 *      格式只能实测。
 *   2. hooks 的 JSON 形态。`Options.hooks` 收的是 **JS 回调**（`HookCallback`
 *      是函数，sdk.d.ts:869-871），函数过不了 NDJSON 线，所以插件只能把 hooks
 *      写进文件或走 `Options.settings`（flag 层）。这两种载体的字段名与行为
 *      必须实测确认。
 *
 * 省钱的关键：**不真起 MCP server**。配一个指向不存在命令的 server，只要它
 * 出现在 `mcpServerStatus()` 里（status=failed + error），就证明"配置被读到了"
 * —— 这比连通性更能回答我们的问题，且不需要任何模型调用。
 *
 * 要回答的问题：
 *   Q1 `.mcp.json` 放在项目根、内容是 `{mcpServers:{...}}` 时，会被读到吗
 *   Q2 不批准的话会怎样（`enableAllProjectMcpServers` 在 `.claude/settings.json` 里）
 *   Q3 `.claude/settings.json` 里的 hooks 会被执行吗（一个能拦住 Write 的 PreToolUse）
 *   Q4 被 hook 拦住时，CLI 往事件流里推什么（`hook_started` / `hook_response` 的形状）
 *   Q5 同样的 hooks 改走 `Options.settings`（flag 层）能不能生效（不落盘的后备路）
 *
 * **SDK 的 cwd 是临时目录** —— 探针要真写配置文件，绝不能在仓库里写。
 * 跑完**不删**。
 *
 * 用法（cwd 必须是仓库根）：
 *
 *   node sidecar/tools/probe-mcp-hooks.mjs          # Q1-Q3（无模型调用，秒级）
 *   node sidecar/tools/probe-mcp-hooks.mjs --hooks  # 追加 Q4、Q5（要跑模型回合）
 *
 * 退出码：0 = Q1 成立（能读到）且（若跑了 --hooks）Q3 成立；1 = 不成立；2 = 超时。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { mkdtempSync, writeFileSync, mkdirSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const RUN_HOOKS = process.argv.includes('--hooks');

const workDir = mkdtempSync(join(tmpdir(), 'ccoder-mcp-'));
const MCP_JSON = join(workDir, '.mcp.json');
const CLAUDE_DIR = join(workDir, '.claude');
const SETTINGS_JSON = join(CLAUDE_DIR, 'settings.json');
const TARGET = join(workDir, 'blocked.txt');

/** 指向一个不存在的命令：连不上是**预期**的，我们要的是"它被读到了"。 */
const BOGUS_SERVER = 'ccoder-probe-bogus';
const BOGUS_COMMAND = 'no-such-command-ccoder-probe-xyz';

/** 能拦住 Write 的 hook。exit 2 = 阻断，stderr 会喂回模型（Claude Code 的成规）。 */
const BLOCK_HOOK = 'echo "probe: 这条写入被 hook 拦下了" >&2; exit 2';

const t0 = Date.now();
const ts = () => `+${String(Date.now() - t0).padStart(6)}ms`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} 超时（${ms}ms）`)), ms)),
  ]);
}

function makeInputStream() {
  let stopped = false;
  let notifyInput = null;
  const queue = [];
  async function* stream() {
    while (!stopped) {
      if (queue.length > 0) { yield queue.shift(); continue; }
      const item = await new Promise((r) => { notifyInput = r; });
      notifyInput = null;
      if (item === null) return;
      yield item;
    }
  }
  return {
    stream: stream(),
    send(text) { queue.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null }); notifyInput?.('go'); },
    stop() { stopped = true; notifyInput?.(null); },
  };
}

function openSession({ settings = null } = {}) {
  const input = makeInputStream();
  const events = [];
  const options = {
    cwd: workDir,
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: true,
    canUseTool: (name, inp) => Promise.resolve(
      (name === 'Write' || name === 'Edit') && String(inp?.file_path ?? '').startsWith(workDir)
        ? { behavior: 'allow', updatedInput: inp }
        : { behavior: 'deny', message: '探针：只放行临时目录里的写' },
    ),
  };
  if (settings) options.settings = settings;   // Q5：flag 层
  const q = query({ prompt: input.stream, options });
  (async () => {
    try { for await (const m of q) events.push(m); } catch { /* 收尾时断开属预期 */ }
  })();
  return { q, input, events };
}

/** 纯控制请求，不跑模型。没会话也能读配置的话，这里就是最快的信号。 */
async function mcpStatus(q, label) {
  try {
    const list = await withTimeout(q.mcpServerStatus(), 20000, 'mcpServerStatus');
    console.log(`  ${ts()} [${label}] 共 ${list?.length ?? 0} 个 server`);
    for (const s of list ?? []) {
      console.log(`      · ${s.name} status=${s.status} scope=${s.scope ?? '-'} `
        + `tools=${s.tools?.length ?? 0} error=${JSON.stringify(s.error ?? null)}`);
    }
    return list ?? [];
  } catch (err) {
    console.log(`  ${ts()} [${label}] 抛错：${err?.message ?? err}`);
    return null;
  }
}

const answers = {};
const findings = [];
const note = (q, text) => { answers[q] = text; findings.push(`  ${q}: ${text}`); };

const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  console.log(`临时目录：${workDir}`);
  process.exit(2);
}, 600000);

let verdict = 1;

try {
  console.log(`工作目录：${workDir}`);

  // ---- 基线：什么都没配 ----
  console.log('\n=== 基线：临时目录里没有任何配置 ===');
  {
    const a = openSession();
    const list = await mcpStatus(a.q, '无配置');
    note('Q0', `无配置时 mcpServerStatus 返回 ${list === null ? '抛错' : `${list.length} 个`}`);
    a.input.stop();
    try { await a.q.interrupt?.(); } catch { /* 预期 */ }
  }

  // ---- Q1：写 .mcp.json，看是否被读到 ----
  console.log('\n=== Q1：写 .mcp.json（指向不存在的命令）===');
  writeFileSync(MCP_JSON, JSON.stringify({
    mcpServers: { [BOGUS_SERVER]: { command: BOGUS_COMMAND, args: [] } },
  }, null, 2) + '\n');
  console.log(`  ${ts()} 写入 ${MCP_JSON}`);
  console.log(`  ${ts()} 内容：${readFileSync(MCP_JSON, 'utf8').trim().replace(/\n/g, ' ')}`);
  {
    const b = openSession();
    await sleep(2000);
    const list = await mcpStatus(b.q, '有 .mcp.json');
    const hit = (list ?? []).find((s) => s.name === BOGUS_SERVER);
    note('Q1', hit
      ? `被读到了：status=${hit.status} error=${JSON.stringify(hit.error ?? null)}`
      : '**没被读到**（列表里没有它）—— 格式或位置不对，或需要批准（见 Q2）');
    b.input.stop();
    try { await b.q.interrupt?.(); } catch { /* 预期 */ }
  }

  // ---- Q2：批准开关 ----
  console.log('\n=== Q2：加 enableAllProjectMcpServers 再试 ===');
  mkdirSync(CLAUDE_DIR, { recursive: true });
  writeFileSync(SETTINGS_JSON, JSON.stringify({
    enableAllProjectMcpServers: true,
  }, null, 2) + '\n');
  console.log(`  ${ts()} 写入 ${SETTINGS_JSON}`);
  {
    const c = openSession();
    await sleep(2000);
    const list = await mcpStatus(c.q, '已批准');
    const hit = (list ?? []).find((s) => s.name === BOGUS_SERVER);
    note('Q2', hit
      ? `批准后仍是 status=${hit.status}（说明 Q1 那次也不是被批准挡住的）`
      : '批准后依然不在列表里 —— 批准的写法不对，或这个字段不在这里生效');
    c.input.stop();
    try { await c.q.interrupt?.(); } catch { /* 预期 */ }
  }

  // ---- Q3/Q4：hooks 写在 .claude/settings.json 里 ----
  if (RUN_HOOKS) {
    console.log('\n=== Q3：.claude/settings.json 里的 PreToolUse hook 能拦住 Write 吗 ===');
    writeFileSync(SETTINGS_JSON, JSON.stringify({
      enableAllProjectMcpServers: true,
      hooks: {
        PreToolUse: [
          { matcher: 'Write', hooks: [{ type: 'command', command: BLOCK_HOOK }] },
        ],
      },
    }, null, 2) + '\n');
    console.log(`  ${ts()} 写入 hooks：matcher=Write command=${JSON.stringify(BLOCK_HOOK)}`);
    {
      const d = openSession();
      const from = d.events.length;
      d.send(`请用 Write 工具把文件 ${TARGET} 的内容写成 BLOCKED，完成后回复 OK`);
      const deadline = Date.now() + 90000;
      while (Date.now() < deadline && !d.events.slice(from).some((m) => m.type === 'result')) {
        await sleep(300);
      }
      const wrote = existsSync(TARGET);
      console.log(`  ${ts()} 被拦住了吗：文件${wrote ? '**被写出来了**（hook 没生效）' : '没被写出来 ✓'}`);
      // Q4：hook 相关的事件长什么样
      const hookEvents = d.events.slice(from).filter(
        (m) => m.type === 'system' && String(m.subtype ?? '').startsWith('hook'),
      );
      console.log(`  ${ts()} hook 相关事件 ${hookEvents.length} 条：`);
      for (const h of hookEvents.slice(0, 6)) {
        console.log(`      · ${h.subtype} ${JSON.stringify(h.hook_name ?? h.hook_event ?? null)} `
          + `exit=${h.exit_code ?? '-'} out=${JSON.stringify(String(h.output ?? h.stderr ?? '').slice(0, 60))}`);
      }
      note('Q3', wrote
        ? '**hook 没生效** —— 项目级 settings.json 的 hooks 不被读，或字段名不对'
        : 'hook 生效：Write 被拦下了');
      note('Q4', hookEvents.length
        ? `事件流里有 ${hookEvents.length} 条 hook_* 事件（形状见上）—— 界面要能解释"为什么没发生"`
        : '**事件流里没有 hook_* 事件** —— 被拦住时用户将没有任何线索，界面只能自己造');
      d.input.stop();
      try { await d.q.interrupt?.(); } catch { /* 预期 */ }
    }

    // ---- Q5：同样的 hooks 走 flag 层 ----
    console.log('\n=== Q5：同样的 hooks 改走 Options.settings（不落盘）===');
    writeFileSync(SETTINGS_JSON, JSON.stringify({ enableAllProjectMcpServers: true }, null, 2) + '\n');
    {
      const e = openSession({
        settings: {
          hooks: { PreToolUse: [{ matcher: 'Write', hooks: [{ type: 'command', command: BLOCK_HOOK }] }] },
        },
      });
      const from = e.events.length;
      e.send(`请用 Write 工具把文件 ${TARGET} 的内容写成 BLOCKED2，完成后回复 OK`);
      const deadline = Date.now() + 90000;
      while (Date.now() < deadline && !e.events.slice(from).some((m) => m.type === 'result')) {
        await sleep(300);
      }
      const body = existsSync(TARGET) ? readFileSync(TARGET, 'utf8').trim() : '（不存在）';
      console.log(`  ${ts()} flag 层结果：文件=${JSON.stringify(body)}`);
      note('Q5', body === 'BLOCKED2'
        ? 'flag 层**没拦住** —— 后备路不成立'
        : 'flag 层也能生效 —— 不想落盘时可以用它');
      e.input.stop();
      try { await e.q.interrupt?.(); } catch { /* 预期 */ }
    }
  } else {
    console.log('\n（跳过 Q3-Q5 —— 加 --hooks 才跑，那几步要跑模型回合）');
  }

  console.log('\n--- 结论 ---');
  for (const f of findings) console.log(f);
  verdict = answers.Q1?.startsWith('被读到了') ? 0 : 1;
} catch (err) {
  console.log(`\n!! 探针失败：${err?.message ?? err}`);
  verdict = 3;
}

clearTimeout(watchdog);
console.log(`\n临时目录（不删，失败时它就是证据）：${workDir}`);
console.log(verdict === 0
  ? '  .mcp.json 的形状成立：{mcpServers:{...}} 写在项目根就会被读到 ✓'
  : '  .mcp.json 没被读到 —— 按实测改格式，或退回 Options.mcpServers（不落盘）✗');
setTimeout(() => process.exit(verdict), 1500);
