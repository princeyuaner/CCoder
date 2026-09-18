/**
 * 探针：子代理那层能拿到什么（`forwardSubagentText`）＋ 那句"进行时"长什么样
 * （`agentProgressSummaries`）。
 *
 * 用户 2026-09-18 从选型台上挑了 **A1（嵌套）＋ B2（两行）**。这两件事都押在
 * 事件流的形状上，而形状**读 .d.ts 读不出来** —— 文档只写着
 * "the full subagent conversation is forwarded so consumers can render a nested
 * transcript"（sdk.d.ts，forwardSubagentText 那段），不写：
 *
 *   Q1 不开这个开关时，带 `parent_tool_use_id` 的到底是哪些事件；
 *   Q2 开了之后**多了**什么（正文？思考？工具结果？）；
 *   Q3 子代理的正文带不带 `stream_event` 的增量帧 —— 带不带决定 web 侧能不能
 *      像主线程那样"边流边写"，还是只能整段冒出来；
 *   Q4 主线程自己的消息，`parent_tool_use_id` 是 `null` 还是干脆没有 ——
 *      这决定 Kotlin 侧那个字段该用可空还是用哨兵值；
 *   Q5 `task_progress.summary` 的**原文**长什么样、"每 ~30s 一次"是不是真的；
 *   Q6 `canUseTool` 会不会也被子代理的工具调用打到（会的话，权限卡上得能分辨
 *      这是谁在问）。
 *
 * 两趟对照着跑：甲 = 今天的选项（对照臂），乙 = 加上两个开关。
 *
 * 用法（cwd 必须是仓库根）：
 *
 *   node sidecar/tools/probe-subagent-text.mjs
 *
 * 退出码：0 = 两趟都拿到了 result（结论可用）；3 = 模型没照做（派子代理），
 *         这一跑不算数；2 = 超时。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const workDir = mkdtempSync(join(tmpdir(), 'ccoder-subagent-'));
writeFileSync(join(workDir, 'package.json'), JSON.stringify({
  name: 'probe-fixture',
  scripts: { test: 'node --test' },
}, null, 2));
writeFileSync(join(workDir, 'README.md'), '# fixture\n\n这个目录只用来给子代理读。\n');
// 慢跑用的那批：一行一句话，读一个的代价很小 —— 靠"数量"而不是"等待"把子代理拖住
mkdirSync(join(workDir, 'fixture'), { recursive: true });
for (let i = 0; i < 60; i += 1) {
  writeFileSync(join(workDir, 'fixture', `note-${String(i).padStart(2, '0')}.txt`), `note ${i}: 第 ${i} 号样本。\n`);
}

const t0 = Date.now();
const ts = () => `+${String(Date.now() - t0).padStart(6)}ms`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PROMPT = '请用 Task 工具派一个 Explore 子代理，让它读一下 package.json 和 README.md，'
  + '然后告诉你 package.json 里的 name 与 scripts.test 两个值。'
  + '**你自己不要读这两个文件**，一定要用 Task 工具，拿到结果后只回一句"DONE: <两个值>"。';

/**
 * `--slow`：让子代理**一直忙到 75 秒**，因为 `summary` 只在跑着的时候每 ~30s 生成一次
 * （sdk.d.ts:1960-1968）。第一跑的子代理 2 秒就干完了，两趟的 summary 全是空 ——
 * 那不是"这个功能没用"，是"没给它机会说"。
 */
const SLOW_CMD = 'node -e "setTimeout(()=>console.log(\'SLOW-DONE\'),75000)"';
const PROMPT_SLOW = '请用 Task 工具派一个前台 Explore 子代理（别丢后台，你等它跑完），'
  + '让它把 fixture/ 目录下的每个文件**一个接一个地读**（不要并行、不要一次读多个），'
  + '每读完一个就把文件名报出来，全部读完后汇总一句。'
  + '主线程你自己不要读这些文件；拿到结果后只回一句"DONE"。';
/** 慢跑用的夹具：文件够多，一个一个读才撑得久（不能靠 sleep，那要 Bash 权限）。 */
const SLOW_FILES = 60;

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

/** 跑一趟。[flags] 决定加不加那两个开关。 */
async function run(label, flags, prompt = PROMPT) {
  const input = makeInputStream();
  const events = [];
  const permCalls = [];
  const options = {
    cwd: workDir,
    // 慢跑**不开 bypass**：丙跑里那条 Bash 是被 CLI 自己拒的（`system/permission_denied`，
    // 而且没经过 canUseTool），绕过它等于替用户改他机器上的规矩 —— 探针没这个权力。
    // 让子代理忙起来的正当办法是"活多"：见下面 FILES 那个夹具，一个接一个地读。
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: true,
    canUseTool: (name, inp, opts) => {
      permCalls.push({ name, parent: opts?.toolUseID ? 'has-toolUseID' : '-', agentId: opts?.agentId ?? null });
      const ok = ['Read', 'Glob', 'Grep', 'Task', 'TodoWrite'].includes(name)
        || (name === 'Bash' && String(inp?.command ?? '').includes('SLOW-DONE'));
      return Promise.resolve(ok
        ? { behavior: 'allow', updatedInput: inp }
        : { behavior: 'deny', message: '探针：只读（外加那条慢命令）' });
    },
    ...flags,
  };

  const q = query({ prompt: input.stream, options });
  let qErr = null;
  const pump = (async () => {
    try { for await (const m of q) events.push({ at: Date.now() - t0, m }); }
    catch (e) { qErr = e; }
  })();

  input.send(prompt);
  const deadline = Date.now() + 180000;
  let result = null;
  while (Date.now() < deadline) {
    result = events.find((e) => e.m.type === 'result');
    if (result) break;
    await sleep(200);
  }
  await sleep(800);        // 收尾几帧
  input.stop();
  await Promise.race([pump, sleep(3000)]);

  console.log(`\n================ ${label} ================`);
  console.log(`选项：${JSON.stringify(flags)}  事件 ${events.length} 条  用时 ${Math.round((Date.now() - t0) / 1000)}s`);
  if (qErr) console.log(`（事件流收尾报错，属预期：${String(qErr).slice(0, 80)}）`);
  if (!result) { console.log('**没等到 result**'); return { events, ok: false }; }

  analyze(events, permCalls);
  return { events, ok: true };
}

/** 事件的形状分析 —— 探针的全部价值都在这几个数上。 */
function analyze(events, permCalls) {
  const withParent = (m) => m.parent_tool_use_id != null;
  const rows = new Map();   // key: type/subtype → {all, parent, kinds}
  const bump = (key, isParent, kind) => {
    const r = rows.get(key) ?? { all: 0, parent: 0, kinds: new Map() };
    r.all += 1;
    if (isParent) r.parent += 1;
    if (kind) r.kinds.set(kind, (r.kinds.get(kind) ?? 0) + 1);
    rows.set(key, r);
  };
  const blockKinds = (m) => {
    const c = m.message?.content;
    if (!Array.isArray(c)) return typeof c === 'string' ? 'text(string)' : null;
    const ks = c.map((b) => b?.type).filter(Boolean);
    return ks.length ? ks.join('+') : null;
  };

  for (const { m } of events) {
    const key = m.type === 'stream_event'
      ? `stream_event/${m.event?.type ?? ''}`
      : m.type === 'system' ? `system/${m.subtype ?? ''}` : m.type;
    bump(key, withParent(m), m.type === 'assistant' || m.type === 'user' ? blockKinds(m) : null);
  }

  console.log('\n事件形状（parent = 带 parent_tool_use_id 的条数）：');
  for (const [k, r] of [...rows.entries()].sort((a, b) => b[1].all - a[1].all)) {
    const kinds = [...r.kinds.entries()].map(([kk, n]) => `${kk}×${n}`).join(' ');
    console.log(`  ${k.padEnd(34)} 共 ${String(r.all).padStart(3)}  带 parent ${String(r.parent).padStart(3)}  ${kinds}`);
  }

  // 子代理的消息：给三条原始样本（键名 + 截断的 JSON）
  const samples = events.filter((e) => withParent(e.m)
    && (e.m.type === 'assistant' || e.m.type === 'user' || e.m.type === 'stream_event')).slice(0, 3);
  console.log('\n带 parent 的样本：');
  for (const s of samples) {
    const keys = Object.keys(s.m).join(',');
    console.log(`  [${s.m.type}] keys={${keys}}`);
    console.log(`    parent=${s.m.parent_tool_use_id} ${JSON.stringify(s.m).slice(0, 260)}`);
  }

  // 子代理正文：看有没有真的"文字"
  const subText = events.filter((e) => withParent(e.m) && e.m.type === 'assistant'
    && Array.isArray(e.m.message?.content)
    && e.m.message.content.some((b) => b?.type === 'text' && String(b.text ?? '').trim()));
  const subThink = events.filter((e) => withParent(e.m) && e.m.type === 'assistant'
    && Array.isArray(e.m.message?.content) && e.m.message.content.some((b) => b?.type === 'thinking'));
  const subDelta = events.filter((e) => withParent(e.m) && e.m.type === 'stream_event'
    && e.m.event?.type === 'content_block_delta');
  console.log(`\n子代理正文：整段 ${subText.length} 条、思考块 ${subThink.length} 条、流式增量帧 ${subDelta.length} 条`);
  for (const s of subText.slice(0, 2)) {
    const t = s.m.message.content.filter((b) => b?.type === 'text').map((b) => b.text).join(' ');
    console.log(`  ${ts()} 正文样本：${JSON.stringify(t.slice(0, 140))}`);
  }

  // **泄漏检查**：子代理那句正文是不是混在"不带 parent 的增量流"里。
  // 单独一条流事件看不出这个 —— stream_event 的载荷里没有内容归属，
  // 界面是把它拼进"当前那个气泡"的，一旦子代理的增量漏进来而 parent 是 null，
  // 子代理说的话就会被写进主线程的气泡里（而且最终会被主线程的正文覆盖掉）。
  const anonDelta = events
    .filter((e) => !withParent(e.m) && e.m.type === 'stream_event'
      && e.m.event?.type === 'content_block_delta'
      && e.m.event?.delta?.type === 'text_delta')
    .map((e) => e.m.event.delta.text ?? '')
    .join('');
  for (const s of subText.slice(0, 2)) {
    const t = s.m.message.content.filter((b) => b?.type === 'text').map((b) => b.text).join(' ');
    const frag = t.trim().slice(0, 24);
    console.log(`  泄漏检查：子代理正文前 24 字 ${JSON.stringify(frag)} `
      + `→ ${anonDelta.includes(frag) ? '**混进了主线程增量流**' : '没混进去'}`);
  }
  console.log(`  匿名增量流共 ${anonDelta.length} 字`);

  // 任务消息：summary 的原文与节奏
  const taskMsgs = events.filter((e) => ['task_started', 'task_progress', 'task_notification', 'task_updated']
    .includes(e.m.subtype) || ['task_started', 'task_progress', 'task_notification'].includes(e.m.type));
  console.log(`\n任务消息 ${taskMsgs.length} 条：`);
  for (const { at, m } of taskMsgs) {
    const sub = m.subtype ?? m.type;
    const desc = m.description ?? '';
    const sum = m.summary ?? '';
    const id = m.task_id ?? m.taskId ?? '';
    const bg = m.is_backgrounded === undefined ? '' : ` bg=${m.is_backgrounded}`;
    const tok = m.usage?.total_tokens === undefined ? '' : ` tok=${m.usage.total_tokens}`;
    console.log(`  +${String(at).padStart(6)}ms ${String(sub).padEnd(18)} id=${String(id).slice(0, 12)}${bg}${tok} `
      + `desc=${JSON.stringify(String(desc).slice(0, 40))} summary=${JSON.stringify(String(sum).slice(0, 60))}`);
  }

  // 原始载荷：permission_denied 与 init 各一条（丙跑里那两个没头没脑的事件）
  for (const want of ['permission_denied', 'init']) {
    const e = events.find((x) => x.m.subtype === want);
    if (e) console.log(`\n原始 ${want}：${JSON.stringify(e.m).slice(0, 300)}`);
  }

  console.log(`\ncanUseTool 被调用 ${permCalls.length} 次：${JSON.stringify(permCalls.slice(0, 8))}`);

  // Q4：主线程自己的消息，那个字段是 null 还是干脆没有 —— 决定 Kotlin 侧用可空还是哨兵值
  const mainAssistant = events.find((e) => e.m.type === 'assistant' && !withParent(e.m));
  const mainUser = events.find((e) => e.m.type === 'user' && !withParent(e.m) && Array.isArray(e.m.message?.content));
  for (const [who, m] of [['主线程 assistant', mainAssistant?.m], ['主线程 user', mainUser?.m]]) {
    if (!m) continue;
    const has = Object.prototype.hasOwnProperty.call(m, 'parent_tool_use_id');
    console.log(`  ${who}：字段${has ? `在，值=${JSON.stringify(m.parent_tool_use_id)}` : '**不在**'}`);
  }
  // 子代理消息上的额外字段（subagent_type / task_description）—— A1 那块小标题的字就从这儿来
  const subOne = events.find((e) => withParent(e.m) && e.m.type === 'assistant')?.m;
  if (subOne) {
    console.log(`  子代理 assistant 多了：subagent_type=${JSON.stringify(subOne.subagent_type)} `
      + `task_description=${JSON.stringify(String(subOne.task_description ?? '').slice(0, 40))}`);
  }
}

const done = (r) => r.events.some((e) => e.m.type === 'result');

if (process.argv.includes('--one')) {
  // 只跑"开"的那一趟（快速场景）—— 泄漏检查问的只是这一趟
  const B = await run('乙 · forwardSubagentText + agentProgressSummaries', {
    forwardSubagentText: true,
    agentProgressSummaries: true,
  });
  if (!done(B)) { console.log('\n**没跑完，结论不可用**'); process.exit(3); }
  console.log('\n（单跑拿到了 result）');
} else if (process.argv.includes('--slow')) {
  // 只跑"开"的那一趟 —— 对照臂第一跑已经答完了，这里要的是"撑够 30s 之后 summary 有没有"
  const S = await run('丙 · 慢子代理（75s，两个开关都开）', {
    forwardSubagentText: true,
    agentProgressSummaries: true,
  }, PROMPT_SLOW);
  if (!done(S)) { console.log('\n**慢跑没跑完，结论不可用**'); process.exit(3); }
  console.log('\n（慢跑拿到了 result，结论写进设计稿）');
} else {
  const A = await run('甲 · 今天（无开关）', {});
  const B = await run('乙 · forwardSubagentText + agentProgressSummaries', {
    forwardSubagentText: true,
    agentProgressSummaries: true,
  });
  if (!done(A) || !done(B)) {
    console.log('\n**有一趟没跑完，结论不可用**');
    process.exit(3);
  }
  console.log('\n（两趟都跑完了，结论写进设计稿）');
  console.log('（慢子代理那一跑单独跑：node sidecar/tools/probe-subagent-text.mjs --slow）');
}
