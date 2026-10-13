/**
 * 探针：本轮改动卡的机制地基 —— `Options.hooks.PreToolUse` 能不能当快照抓手。
 *
 * 「本轮改动」（docs/design/turn-changes.html 机制甲）把整件事押在一条上：
 * **在编辑落地之前，侧车有机会把文件原样读一份**。SDK 的 `Options.hooks`
 * 收的是进程内 JS 回调（`HookCallback`，sdk.d.ts:891 / 1725-1731），
 * `PreToolUseHookInput` 带 `tool_name` / `tool_input` / `tool_use_id`
 * （sdk.d.ts:2741）—— 但「回调时机到底在不在工具执行之后」「哪些权限模式
 * 会跳过它」**文档一个字没保证**，只能实测。
 *
 * 要回答的问题：
 *   Q1 默认模式：hook 先于工具执行触发吗（两次 Edit 的内容时序会作证）、
 *      `tool_input.file_path` 在吗、返回 `{}` 不拦也不产生副作用吗
 *   Q2 acceptEdits 模式：同样触发吗（编辑不再走询问，hook 是不是唯一的抓手）
 *   Q3 bypassPermissions 模式：同样触发吗
 *   Q4 同轮同一文件两次 Edit：第二次 hook 读到的是**第一次之后**的内容吗
 *      （「同轮同文件只留第一份 = 本轮开始前」的策略依据）
 *   Q5 Write 新建文件：hook 那一刻文件**不存在**吗（「回滚 = 删除」的依据）
 *   Q6 resume 恢复的会话里照常触发吗
 *   Q7 子代理里的编辑也触发吗（`agent_id` 字段在不在）
 *
 * **SDK 的 cwd 是临时目录** —— 探针要真写文件，绝不能在仓库里写。跑完不删。
 *
 * 用法（cwd 必须是仓库根；Q1-Q5 一个回合，Q6/Q7 再各一个回合，要模型调用）：
 *
 *   node sidecar/tools/probe-turn-changes.mjs
 *
 * 退出码：0 = Q1-Q5 全部成立；1 = 有不成立；2 = 超时。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { mkdtempSync, writeFileSync, rmSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, basename } from 'node:path';

const workDir = mkdtempSync(join(tmpdir(), 'ccoder-turnchg-'));
const A = join(workDir, 'a.txt');
const B = join(workDir, 'b.txt');
const SEED = 'AAA\nfill1\nfill2\n';

const t0 = Date.now();
const ts = () => `+${String(Date.now() - t0).padStart(6)}ms`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 编辑类工具 —— 产品侧快照要认的那一族（与 web/src/tools.ts 的 FILE_TOOLS 同族）。 */
const EDIT_TOOLS = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit']);

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

/**
 * 开一个会话。`log` 是 hook 记录表（本探针的核心证据）：
 * 每次 PreToolUse 记下 {tool, file, exists, content, agent, mode}。
 *
 * hook 回调里用 `readFileSync` **同步**读 —— 这是探针，要的就是那一刻的磁盘真相；
 * 返回 `{}`（空的 SyncHookJSONOutput，sdk.d.ts:993 起）= 不拦、不表态。
 */
function openSession({ mode = 'default', resume = null, log }) {
  const input = makeInputStream();
  const events = [];
  const options = {
    cwd: workDir,
    permissionMode: mode,
    env: buildChildEnv(process.env),
    includePartialMessages: true,
    hooks: {
      PreToolUse: [{
        hooks: [async (hookInput) => {
          const tool = hookInput?.tool_name;
          const ti = hookInput?.tool_input ?? {};
          const file = ti.file_path ?? ti.notebook_path ?? null;
          let exists = null;
          let content = null;
          if (file && EDIT_TOOLS.has(tool)) {
            exists = existsSync(file);
            if (exists) {
              try { content = readFileSync(file, 'utf8'); } catch { content = '(读失败)'; }
            }
          }
          log.push({
            tool, file: file ? basename(file) : null, exists, content,
            agent: hookInput?.agent_id ?? null,
            permMode: hookInput?.permission_mode ?? null,
            at: ts(),
          });
          return {};
        }],
      }],
    },
  };
  // default 模式下，编辑要经过我们的批准 —— 探针只放行临时目录里的写
  if (mode === 'default') {
    options.canUseTool = (name, inp) => Promise.resolve(
      (name === 'Write' || name === 'Edit' || name === 'MultiEdit')
        && String(inp?.file_path ?? '').startsWith(workDir)
        ? { behavior: 'allow', updatedInput: inp }
        : { behavior: 'deny', message: '探针：只放行临时目录里的编辑' },
    );
  }
  if (mode === 'bypassPermissions') options.allowDangerouslySkipPermissions = true;
  if (resume) options.resume = resume;
  const q = query({ prompt: input.stream, options });
  (async () => {
    try { for await (const m of q) events.push(m); } catch { /* 收尾时断开属预期 */ }
  })();
  return { q, input, events };
}

/** 跑一个回合（发一句、等 result），返回这一段的事件。 */
async function runTurn(s, prompt, timeoutMs = 150000) {
  const from = s.events.length;
  s.input.send(prompt);
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline && !s.events.slice(from).some((m) => m.type === 'result')) {
    await sleep(300);
  }
  return s.events.slice(from);
}

function sessionIdOf(events) {
  for (const m of events) {
    if (m.type === 'system' && m.subtype === 'init' && m.session_id) return m.session_id;
  }
  return null;
}

const findings = [];
const note = (q, text) => findings.push(`  ${q}: ${text}`);
const answers = {};

const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  console.log(`临时目录：${workDir}`);
  process.exit(2);
}, 600000);

let verdict = 1;

try {
  console.log(`工作目录：${workDir}`);

  // ---- 主场景：默认模式，一次回合做三件事（两次 Edit 同一文件 + Write 新建）----
  const PROMPT = [
    '按顺序做三件事，只用工具，不要解释，不要用 Bash：',
    `1) 用 Edit 工具把 ${A} 里的 AAA 替换成 BBB；`,
    `2) 再用 Edit 工具把同一个文件里的 BBB 替换成 CCC；`,
    `3) 用 Write 工具新建 ${B}，内容两行：h1 和 h2。`,
    '做完回复 DONE。',
  ].join('\n');

  const modes = ['default', 'acceptEdits', 'bypassPermissions'];
  const firstRun = {};

  for (const mode of modes) {
    console.log(`\n=== Q(1/2/3) ${mode}：hook 触发时序 / file_path / 副作用 ===`);
    writeFileSync(A, SEED);           // 每个模式重放同样的起始状态
    rmSync(B, { force: true });
    const log = [];
    const s = openSession({ mode, log });
    const events = await runTurn(s, PROMPT);
    s.input.stop();
    try { await s.q.interrupt?.(); } catch { /* 预期 */ }

    const edits = log.filter((r) => EDIT_TOOLS.has(r.tool));
    console.log(`  ${ts()} 本回合 PreToolUse 共 ${log.length} 次，其中编辑类 ${edits.length} 次：`);
    for (const r of edits) {
      console.log(`      · [${r.at}] ${r.tool} ${r.file} exists=${r.exists} `
        + `content=${JSON.stringify((r.content ?? '').slice(0, 40))}`);
    }
    const hookEvents = events.filter((m) => m.type === 'system' && String(m.subtype ?? '').startsWith('hook'));
    console.log(`  ${ts()} 事件流里 hook_* 事件 ${hookEvents.length} 条；`
      + `最终 a.txt=${JSON.stringify(readFileSync(A, 'utf8').trim())} b.txt=${existsSync(B)}`);
    if (mode === 'default') firstRun.events = events;

    const twoEdits = edits.filter((r) => r.tool === 'Edit' && r.file === 'a.txt');
    const write = edits.find((r) => r.tool === 'Write' && r.file === 'b.txt');
    const q = { default: 'Q1', acceptEdits: 'Q2', bypassPermissions: 'Q3' }[mode];
    const fired = edits.length > 0;
    const orderingOk = twoEdits.length >= 2
      && String(twoEdits[0].content ?? '').includes('AAA')
      && String(twoEdits[1].content ?? '').includes('BBB');
    const writeAbsent = write ? write.exists === false : null;
    note(q, fired
      ? `${fired ? '触发' : '没触发'}（编辑 ${edits.length} 次）`
        + `；时序=${twoEdits.length >= 2 ? (orderingOk ? '第一次读 AAA、第二次读 BBB ✓' : '**不对**') : '（模型没做两次 Edit，Q4 以 default 那次为准）'}`
        + `；Write 新建时文件${writeAbsent === null ? '（模型没调 Write）' : writeAbsent ? '不存在 ✓' : '**已存在**'}`
        + `；hook_* 事件 ${hookEvents.length} 条`
      : '**没触发** —— 这条模式下 hook 不是抓手');
    if (mode === 'default') {
      answers.Q1 = fired && orderingOk && writeAbsent === true;
      answers.Q4 = orderingOk;
      answers.Q5 = writeAbsent === true;
      answers.Q6_副作用 = hookEvents.length === 0;
      note('Q6', hookEvents.length === 0
        ? '无 hook_* 事件（返回 {} 的副作用为零）'
        : `事件流里多了 ${hookEvents.length} 条 hook_* 事件 —— 插件侧 MessageRenderer 已整条丢弃，但要记一笔`);
    } else if (fired) {
      answers[q] = true;
    }
  }

  // 主场景后的最终状态检查：Edit 链 + 新建都对（转写反推做对照也在这两行里）
  const finalA = readFileSync(A, 'utf8').trim();
  const finalB = existsSync(B) ? readFileSync(B, 'utf8').trim() : '(不存在)';
  console.log(`\n最终状态：a.txt=${JSON.stringify(finalA)} b.txt=${JSON.stringify(finalB)}`);

  // ---- Q7：resume 恢复的会话 ----
  console.log('\n=== Q7 resume：恢复的会话里 hook 照常触发吗 ===');
  {
    const sid = sessionIdOf(firstRun.events ?? []);
    console.log(`  ${ts()} 恢复会话：${sid ?? '(没拿到 session_id)'}`);
    if (sid) {
      const log = [];
      const s = openSession({ resume: sid, log });
      await runTurn(s, `用 Read 工具读一下 ${A}，然后回复 DONE。`, 120000);
      s.input.stop();
      try { await s.q.interrupt?.(); } catch { /* 预期 */ }
      note('Q7', log.length > 0
        ? `恢复后 PreToolUse 照常触发（${log.length} 次，工具：${log.map((r) => r.tool).join(',')}）`
        : '**恢复后没触发** —— resume 路径要另想办法');
      answers.Q7 = log.length > 0;
    } else {
      note('Q7', '没拿到 session_id，跳过（看上面 init 事件）');
    }
  }

  // ---- Q8：子代理里的编辑 ----
  console.log('\n=== Q8 子代理：里面改的文件也触发 hook 吗 ===');
  {
    writeFileSync(A, SEED);
    const log = [];
    const s = openSession({ mode: 'acceptEdits', log });
    await runTurn(s, [
      '用 Agent 工具起一个子代理（subagent_type: general-purpose），',
      `让它用 Edit 工具把 ${A} 里的 AAA 替换成 ZZZ，`,
      '完成后回复 DONE。',
    ].join('\n'), 180000);
    s.input.stop();
    try { await s.q.interrupt?.(); } catch { /* 预期 */ }
    const sub = log.find((r) => r.agent);
    const anyEdit = log.find((r) => EDIT_TOOLS.has(r.tool));
    note('Q8', anyEdit
      ? `子代理的编辑${sub ? `**触发**了（agent_id=${sub.agent}）` : '触发了，但 agent_id 没在（可能主线程自己改的）'}`
      : '没看到子代理的编辑（模型可能没起子代理 —— 看上面日志）');
    console.log(`  ${ts()} 本回合 PreToolUse ${log.length} 次：`);
    for (const r of log) {
      console.log(`      · ${r.tool} ${r.file ?? '-'} agent=${r.agent ?? '-'} content=${JSON.stringify((r.content ?? '').slice(0, 30))}`);
    }
  }

  console.log('\n--- 结论 ---');
  for (const f of findings) console.log(f);
  verdict = (answers.Q1 && answers.Q2 && answers.Q3 && answers.Q4 && answers.Q5) ? 0 : 1;
} catch (err) {
  console.log(`\n!! 探针失败：${err?.message ?? err}`);
  verdict = 3;
}

clearTimeout(watchdog);
console.log(`\n临时目录（不删，失败时它就是证据）：${workDir}`);
console.log(verdict === 0
  ? '  机制甲成立：PreToolUse 在三种模式下都先于执行、同轮第二次读到已改内容、Write 新建时文件不存在 ✓'
  : '  有不成立项 —— 看上面哪条没了，机制要么改抓取点、要么把边界写进文案 ✗');
setTimeout(() => process.exit(verdict), 1500);
