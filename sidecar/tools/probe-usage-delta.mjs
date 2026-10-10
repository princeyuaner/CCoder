#!/usr/bin/env node
/**
 * 探针：**result 的 modelUsage 在几种局面下怎么动** —— 给"落账差分"钉口径。
 *
 * sdk.d.ts 写的是：`usage` 只含主循环、按回合给；`modelUsage` 是**全管线、
 * 跨回合累计**（"read the latest result rather than summing across results"）；
 * `/clear` 会把累计**重置**；resume / fork 会**从 transcript 里存的总额续走**
 * （第一条 result 就带着旧账）；崩溃/启动失败的那条可能是零值。
 *
 * 但"文档说"与"真机上是不是这样"是两件事（本仓的老规矩）。这个探针跑五种局面：
 *
 *   ① 全新会话第一回合 —— 验证"第一条 = 本回合全量"（基线=0 的差分成立）；
 *   ② 同一会话第二回合 —— 验证"跨回合累计"（差分[2-1]≈第 2 回合本身）；
 *   ③ 关掉再 resume 接着聊 —— 验证"恢复后从存的总额续走"；
 *   ④ /clear —— 看累计是否被重置（差分出现负数 → 该当重置处理）；
 *   ⑤ /clear 之后再来一回合 —— 验证从零重新长。
 *
 * 跑法：node sidecar/tools/probe-usage-delta.mjs ['模型名']
 * 退出码：0 = 走完了（**结论在输出里**）；1 = 起不来。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { resolveClaudePath } from '../claude-path.js';

const MODEL = process.argv[2] ?? 'deepseek-v4-flash[1m]';
const env = buildChildEnv(process.env);
const cwd = process.cwd();
const claudePath = resolveClaudePath({});

console.log(`模型 : ${MODEL}`);
console.log(`cwd  : ${cwd}`);
console.log('---');

// ---- 一个可发多条、能收尾的输入流（照 session.js 的形制） ----
function makeInput() {
  const queue = [];
  let notify = null;
  async function* gen() {
    while (true) {
      if (queue.length > 0) { yield queue.shift(); continue; }
      const item = await new Promise((r) => { notify = r; });
      notify = null;
      if (item === null) return;
      yield item;
    }
  }
  return {
    stream: gen(),
    send(text) {
      queue.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null });
      notify?.('go');
    },
    close() { notify?.(null); },
  };
}

/** 把一条 result 压成可读的账。 */
function brief(msg) {
  const mu = msg.modelUsage ?? {};
  return {
    subtype: msg.subtype, isError: msg.is_error, turns: msg.num_turns,
    totalCost: msg.total_cost_usd, localCommand: msg.local_command,
    usage: {
      in: msg.usage?.input_tokens, out: msg.usage?.output_tokens,
      cr: msg.usage?.cache_read_input_tokens, cc: msg.usage?.cache_creation_input_tokens,
    },
    models: Object.entries(mu).map(([name, u]) => ({
      name,
      in: u.inputTokens, out: u.outputTokens,
      cr: u.cacheReadInputTokens, cc: u.cacheCreationInputTokens,
      cost: u.costUSD, basis: u.costBasis, canon: u.canonicalModel, provider: u.provider,
    })),
  };
}

function printBrief(tag, msg) {
  const b = brief(msg);
  console.log(`\n[${tag}] subtype=${b.subtype}${b.isError ? ' is_error' : ''} turns=${b.turns}` +
    ` total_cost=${b.totalCost}${b.localCommand ? ` local_command=${b.localCommand}` : ''}`);
  console.log(`  usage（主循环、本回合）: in=${b.usage.in} out=${b.usage.out} cr=${b.usage.cr} cc=${b.usage.cc}`);
  for (const m of b.models) {
    console.log(`  modelUsage[${m.name}]: in=${m.in} out=${m.out} cr=${m.cr} cc=${m.cc}` +
      ` cost=${m.cost} basis=${m.basis} canon=${m.canon} provider=${m.provider}`);
  }
  return b;
}

/** 相邻两条的 modelUsage 差分（把探针想验的账当面算出来）。 */
function printDelta(tag, prev, cur) {
  for (const m of cur.models) {
    const p = prev?.models.find((x) => x.name === m.name) ?? { in: 0, out: 0, cr: 0, cc: 0, cost: 0 };
    console.log(`  差分[${tag}][${m.name}]: in=${m.in - p.in} out=${m.out - p.out}` +
      ` cr=${m.cr - p.cr} cc=${m.cc - p.cc} cost≈${((m.cost ?? 0) - (p.cost ?? 0)).toFixed(4)}`);
  }
}

const PROMPT = (n) => `不要使用任何工具。只回两个字母：OK（第 ${n} 次）。`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(get, timeoutMs = 180000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const v = get();
    if (v) return v;
    await sleep(200);
  }
  throw new Error('等待超时');
}

// ---- ① ② 全新会话，两个回合 ----
let sessionId = null;
const r = [];
{
  const inp = makeInput();
  const q = query({ prompt: inp.stream, options: {
    cwd, model: MODEL, permissionMode: 'default', env,
    pathToClaudeCodeExecutable: claudePath,
    includePartialMessages: false,
  } });
  const consume = (async () => {
    try {
      for await (const msg of q) {
        if (msg.type === 'system' && msg.subtype === 'init') sessionId = msg.session_id ?? sessionId;
        if (msg.type === 'result') r.push(printBrief(`第 ${r.length + 1} 条 result`, msg));
      }
    } catch (err) { console.log(`  （前半段流断了：${err?.message ?? err}）`); }
  })();

  inp.send(PROMPT(1));
  await waitFor(() => r[0]);
  inp.send(PROMPT(2));
  await waitFor(() => r[1]);
  printDelta('2-1', r[0], r[1]);
  inp.close();
  await consume;
}

// ---- ③ resume；④ /clear；⑤ clear 后一回合 ----
{
  const inp = makeInput();
  const got = [];
  const q = query({ prompt: inp.stream, options: {
    cwd, model: MODEL, permissionMode: 'default', env,
    pathToClaudeCodeExecutable: claudePath,
    resume: sessionId,
    includePartialMessages: false,
  } });
  const consume = (async () => {
    try {
      for await (const msg of q) {
        if (msg.type === 'result') got.push(printBrief(`resume 段第 ${got.length + 1} 条 result`, msg));
      }
    } catch (err) { console.log(`  （后半段流断了：${err?.message ?? err}）`); }
  })();

  inp.send(PROMPT(3));
  await waitFor(() => got[0]);
  printDelta('3-2', r[1], got[0]);

  // ④ /clear：本地命令，可能自己产一条 result，也可能静默 —— 等一拍，把到的打出来
  const beforeClear = got.length;
  inp.send('/clear');
  await sleep(2500);
  if (got.length > beforeClear) {
    console.log('  ↑ /clear 自己产了 result（看 local_command 那一栏）');
  } else {
    console.log('\n[/clear] 等 2.5s 没有产生 result —— 本地命令静默完成');
  }

  // ⑤ /clear 之后再聊一回合
  const before5 = got.length;
  inp.send(PROMPT(4));
  await waitFor(() => (got.length > before5 ? got[got.length - 1] : null));
  const last = got[got.length - 1];
  printDelta('5-前一条', got[got.length - 2] ?? r[1], last);
  inp.close();
  await consume;
}

console.log('\n=== 结论 ===');
console.log('对照四件事（数字都在上面，这里只是"该看什么"）：');
console.log('  ① 第 1 条 modelUsage 是否≈本回合量 —— 基线=0 的差分对全新会话成立');
console.log('  ② 差分[2-1] 是否≈第 2 回合本身 —— "同一会话里差分"成立');
console.log('  ③ resume 第一条是否≈ 旧总额+第 3 回合（差分[3-2]≈第 3 回合）—— 恢复续账');
console.log('  ④ /clear 之后差分是否出现负数/跳变 —— 需要"计数器变小=重置"的判据');
