/**
 * 探针：**后台子代理跑完时，SDK 实时链路上有没有一条能当"完成"信号的事件？**
 *
 * 起因（2026-09-18，用户截图）：转写区里那张 `Agent Explore` 卡在子代理**还在跑**时
 * 就显示了 ✔。查下来根因在 `web/src/toolStatus.ts` —— 它的规则是"结果到了 = 干完了"，
 * 而后台启动型的调用**立刻返回**（返回的是"已派出"，不是"已完成"）。于是卡提前结账。
 *
 * 三条修法里"认完成通知"最准，但当时**没验过**它走不走实时链路。这个探针就是去验。
 *
 * ## 要看的三件事
 *
 *   Q1 主线程 `result`（回合结束）**之后**，事件流里还会不会再冒东西 ——
 *      不会的话，"后台还在跑"这件事在实时链路上根本无从得知；
 *   Q2 那个 Task 调用的 `tool_result` 原文长什么样 —— 它是"已启动"还是"已完成"？
 *      （这决定"认结果文本"那条路有多脆）
 *   Q3 有没有一条事件带着**能把卡和这次调用对上**的 id（tool_use_id / agent id）——
 *      对不上的信号等于没有信号
 *
 * ## 跑法
 *
 *   cd <仓库根>
 *   node sidecar/tools/probe-background-agent-status.mjs
 *
 * 退出码：0 = 拿到了主线程 result（结论可用）；3 = 模型没照做（没丢后台），不算数；2 = 超时。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// 夹具：文件够多，让子代理"一个一个读"撑住一段时间。
// 刻意**不**用 sleep 拖时间 —— 那要 Bash 权限（同 probe-subagent-text.mjs 那条教训）。
const workDir = mkdtempSync(join(tmpdir(), 'ccoder-bgagent-'));
mkdirSync(join(workDir, 'fixture'), { recursive: true });
const FILES = 24;
for (let i = 0; i < FILES; i += 1) {
  writeFileSync(
    join(workDir, 'fixture', `note-${String(i).padStart(2, '0')}.txt`),
    `note ${i}: 第 ${i} 号样本。\n`,
  );
}

const t0 = Date.now();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/** 相对 t0 的时刻。函数声明会被提升，所以下面提前用也没问题。 */
function ts0(at) { return `+${String(at).padStart(6)}ms` }

/**
 * 必须点名 `run_in_background: true` —— 这条探针的全部意义就在"后台"这个形状上。
 * 前台那趟的结果语义是对的（结果到达 = 干完了），不需要验。
 */
const PROMPT = `请用 Task 工具派一个 **后台** Explore 子代理（run_in_background 设成 true，不要等它）。`
  + `让它把 fixture/ 目录下的每个文件一个接一个地读（不要并行、不要一次读多个），每读完一个报出文件名。`
  + `派出去之后你自己**立刻**回一句"DISPATCHED"，不要等子代理。`;

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
    send(text) {
      queue.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null });
      notifyInput?.('go');
    },
    stop() { stopped = true; notifyInput?.(null); },
  };
}

/** 一条事件里的 tool_use / tool_result 摘要，便于对齐时间线。 */
function blocksOf(m) {
  const c = m?.message?.content;
  if (!Array.isArray(c)) return [];
  return c.map((b) => {
    if (b.type === 'tool_use') return { kind: 'use', id: b.id, name: b.name, input: b.input };
    if (b.type === 'tool_result') {
      const txt = typeof b.content === 'string' ? b.content : JSON.stringify(b.content);
      return { kind: 'result', id: b.tool_use_id, isError: !!b.is_error, text: txt };
    }
    return null;
  }).filter(Boolean);
}

const input = makeInputStream();
const events = [];
const options = {
  cwd: workDir,
  permissionMode: 'default',
  env: buildChildEnv(process.env),
  includePartialMessages: true,
  // 只放只读工具 + Task。探针没有替用户放宽权限的权力（同 probe-subagent-text.mjs）
  canUseTool: (name) => Promise.resolve(
    ['Read', 'Glob', 'Grep', 'Task', 'TodoWrite'].includes(name)
      ? { behavior: 'allow' }
      : { behavior: 'deny', message: '探针：只读' },
  ),
};

const q = query({ prompt: input.stream, options });
let qErr = null;
const pump = (async () => {
  try { for await (const m of q) events.push({ at: Date.now() - t0, m }); }
  catch (e) { qErr = e; }
})();

input.send(PROMPT);

// ---- 阶段一：等主线程的 result（回合结束）
const mainDeadline = Date.now() + 120000;
let mainResultAt = null;
while (Date.now() < mainDeadline) {
  const r = events.find((e) => e.m.type === 'result');
  if (r) { mainResultAt = r.at; break; }
  await sleep(200);
}

// ---- 阶段二：主线程结束后**继续听**。这一段才是本探针的核心问题：
// 后台子代理还在跑，实时链路上会不会再冒事件出来？
const WATCH_MS = 90000;
console.log(`\n主线程 result 到达：${mainResultAt === null ? '（超时没等到）' : ts0(mainResultAt)}`);
console.log(`继续监听 ${WATCH_MS / 1000} 秒，看后台子代理跑完时会不会有事件……\n`);
const before = events.length;
await sleep(WATCH_MS);
const afterEvents = events.slice(before);

// ---- 结果
console.log('================ 事件类型直方图（全部）================');
const hist = new Map();
for (const { m } of events) {
  const key = m.type === 'system' ? `system/${m.subtype ?? '?'}` : m.type;
  hist.set(key, (hist.get(key) ?? 0) + 1);
}
for (const [k, v] of [...hist.entries()].sort((a, b) => b[1] - a[1])) {
  console.log(`  ${String(v).padStart(5)}  ${k}`);
}

console.log(`\n================ 主线程 result 之后到达的 ${afterEvents.length} 条 ================`);
if (afterEvents.length === 0) {
  console.log('  （一条都没有）');
} else {
  for (const { at, m } of afterEvents.slice(0, 40)) {
    const bs = blocksOf(m);
    const detail = bs.length
      ? bs.map((b) => b.kind === 'use' ? `use<${b.name}>` : `result(${b.isError ? 'err' : 'ok'})`).join(' ')
      : (m.type === 'system' ? (m.subtype ?? '') : '');
    console.log(`  ${ts0(at)}  ${m.type}  ${detail}`);
  }
}

console.log('\n================ Task 调用与它的结果 ================');
let taskUseId = null;
for (const { at, m } of events) {
  for (const b of blocksOf(m)) {
    if (b.kind === 'use' && /^(Task|Agent)$/.test(b.name)) {
      taskUseId = b.id;
      console.log(`  ${ts0(at)}  tool_use ${b.name}  id=${b.id}`);
      console.log(`      input: ${JSON.stringify(b.input)?.slice(0, 200)}`);
    }
    if (b.kind === 'result' && b.id === taskUseId) {
      console.log(`  ${ts0(at)}  tool_result  isError=${b.isError}`);
      console.log(`      原文: ${String(b.text).slice(0, 400)}`);
    }
  }
}
if (taskUseId === null) {
  console.log('  **模型没派子代理** —— 这一跑不算数（退出码 3）');
}

// ---- task_* 帧逐条打出来（Q1/Q3 的原始证据：后台注册标记、对号 id、到达时刻）
console.log('\n================ task_* / background_tasks_changed 帧 ================');
const taskFrames = events.filter(({ m }) => m.type === 'system'
  && (String(m.subtype ?? '').startsWith('task_') || m.subtype === 'background_tasks_changed'));
if (taskFrames.length === 0) console.log('  （一条都没有）');
for (const { at, m } of taskFrames.slice(0, 80)) {
  const fields = m.subtype === 'background_tasks_changed'
    ? `tasks=${JSON.stringify(m.tasks)}`
    : `task_id=${m.task_id ?? '?'} tool_use_id=${m.tool_use_id ?? '-'}`
      + ` bg=${m.is_backgrounded ?? '-'} type=${m.task_type ?? '-'}`
      + ` sub=${m.subagent_type ?? '-'} status=${m.status ?? '-'}`
      + ` desc=${JSON.stringify((m.description ?? m.summary ?? '')).slice(0, 120)}`;
  console.log(`  ${ts0(at)}  ${m.subtype}  ${fields}`);
}

// ---- 有没有一条事件能把"完成"和这次调用对上？
const idHits = events.filter(({ m }) => {
  const s = JSON.stringify(m);
  return taskUseId && s.includes(taskUseId);
});
console.log(`\n提到那个 tool_use id 的事件：${idHits.length} 条`);
for (const { at, m } of idHits.slice(0, 20)) console.log(`  ${ts0(at)}  ${m.type}${m.subtype ? '/' + m.subtype : ''}`);

input.stop();
await Promise.race([pump, sleep(3000)]);
if (qErr) console.log(`\n（收尾报错，属预期：${String(qErr).slice(0, 80)}）`);

process.exit(taskUseId === null ? 3 : mainResultAt === null ? 2 : 0);
