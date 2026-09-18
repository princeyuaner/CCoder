/**
 * 探针：工具卡那两条 —— `tool_progress`（跑了多久）与 `tool_use_summary`（模型写的一句话）。
 *
 * 用户 2026-09-18 点名要接这两条。它们的**开关条件文档一个字都没写**：
 * `sdk.d.ts` 里 `tool_progress` / `tool_use_summary` 各只出现一次，就是那两个类型
 * 声明本身 —— 没有哪个 Option 的说明提到它们（对比 `forwardSubagentText`、
 * `includeHookEvents` 都写明了"开这个才有"）。所以只能实测。
 *
 * 要钉的：
 *   Q1 默认就发吗？还是要开某个开关（这一跑的选项 = 插件线上的那套，不改）
 *   Q2 `tool_progress` 的**节奏**：几条、间隔多少、`elapsed_time_seconds` 是否在涨
 *   Q3 `tool_use_summary` **什么时候**来：一次调用一条？一组几条？还是回合尾巴上
 *      一条？`preceding_tool_use_ids` 里装几个 id？原文长什么样
 *   Q4 `parent_tool_use_id`：主线程的工具是 `null` 还是有值 —— A1 那套嵌套按这个
 *       字段分组，值不对就会挂错块
 *   Q5 子代理内部的工具会不会也推进度（会的话，嵌套块里也得按秒跳）
 *
 * 用法（cwd 必须是仓库根）：
 *   node sidecar/tools/probe-tool-activity.mjs                # 甲：全部前台
 *   node sidecar/tools/probe-tool-activity.mjs --background   # 乙：那条命令丢后台
 *
 * 两趟为什么分开：甲跑（2026-09-18）里**两条都是 0**。翻 claude.exe 2.1.274 的
 * 内嵌 JS 才看清 —— `tool_use_summary` 被适配器**无条件丢弃**
 * （`case "tool_use_summary": … {type:"ignored"}`），而 `tool_progress` 只丢
 * `heartbeat === true`、带 `subagent_retry`、以及内部工具那三种帧。既然"普通帧"
 * 理论上会过，那甲跑的 0 条就只剩一个解释：**前台工具根本不发**。
 * 乙跑就是去验这一条的（丢后台）。
 *
 * 花钱与副作用：**一次模型调用**（一句短提示），外加在本机跑一条 20 秒的 no-op
 * node 命令、读几个临时文件。没别的。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const workDir = mkdtempSync(join(tmpdir(), 'ccoder-toolact-'));
writeFileSync(join(workDir, 'package.json'), JSON.stringify({ name: 'toolact-fixture' }, null, 2));
writeFileSync(join(workDir, 'README.md'), '# fixture\n\nTHE-FIRST-LINE\n');
mkdirSync(join(workDir, 'fixture'), { recursive: true });
for (let i = 0; i < 6; i += 1) {
  writeFileSync(join(workDir, 'fixture', `n-${i}.txt`), `note ${i}\n`);
}

const t0 = Date.now();
const ts = () => `+${String(Date.now() - t0).padStart(6)}ms`;
const secs = (ms) => `${((Date.now() - ms) / 1000).toFixed(1)}s`;

/** 主线程那条长命令。**探针自己造**，不靠模型写出正确命令（见下面 canUseTool 的改写）。 */
const LONG_CMD = 'node -e "setTimeout(()=>console.log(\'LONG-DONE\'),20000)"';

/** 乙跑：把它丢后台。前台不发的话，看后台这条唯一的岔路发不发。 */
const BG = process.argv.includes('--background');

const PROMPT = '请按顺序做四件事：'
  + '① 用 Bash 跑一条命令（它会跑 20 秒左右才结束，别加 & 别丢后台）；'
  + '② 读一下 package.json；'
  + '③ 用 Task 工具派一个**前台** Explore 子代理，让它读 README.md 并把第一行内容回报给你；'
  + '④ 全部做完后只回一句 "DONE"。';

const PROMPT_BG = '请按顺序做两件事：'
  + '① 用 Bash 在**后台**跑一条命令（run_in_background: true，它会跑 20 秒左右）；'
  + '② 等它跑完（你会收到通知）之后，只回一句 "DONE"。';

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

const input = makeInputStream();
const events = [];
let bashRewrites = 0;

const options = {
  cwd: workDir,
  // 与插件线上那套一致（session.js 的选项）。
  permissionMode: 'default',
  env: buildChildEnv(process.env),
  includePartialMessages: true,
  forwardSubagentText: true,
  agentProgressSummaries: true,
  canUseTool: (name, inp) => {
    if (name === 'Bash') {
      // 只放行**探针自己那条**：模型写的命令一并改写成它。这一步是为了让
      // "20 秒的工具"确定发生 —— 否则模型完全可能写个 `echo hi` 就交差，
      // 那样量到的节奏是假的。第二次起拒绝，免得它反复跑。
      bashRewrites += 1;
      return Promise.resolve(bashRewrites === 1
        ? { behavior: 'allow', updatedInput: { ...inp, command: LONG_CMD, run_in_background: BG } }
        : { behavior: 'deny', message: '探针：只跑一条' });
    }
    const ok = ['Read', 'Glob', 'Grep', 'Task', 'TodoWrite'].includes(name);
    return Promise.resolve(ok
      ? { behavior: 'allow', updatedInput: inp }
      : { behavior: 'deny', message: '探针：只读' });
  },
};

const q = query({ prompt: input.stream, options });
let qErr = null;
const pump = (async () => {
  try { for await (const m of q) events.push({ at: Date.now() - t0, m }); }
  catch (e) { qErr = e; }
})();

const sentAt = Date.now();
input.send(BG ? PROMPT_BG : PROMPT);

const deadline = Date.now() + 180000;
let result = null;
while (Date.now() < deadline) {
  result = events.find((e) => e.m.type === 'result');
  if (result) break;
  await new Promise((r) => setTimeout(r, 200));
}
await new Promise((r) => setTimeout(r, 800));
input.stop();
try { q.close?.(); } catch { /* 收尾 */ }
await Promise.race([pump, new Promise((r) => setTimeout(r, 3000))]);

// ---- 打印 ----

const byType = new Map();
for (const e of events) {
  const k = e.m.type === 'system' ? `system/${e.m.subtype}` : e.m.type;
  byType.set(k, (byType.get(k) ?? 0) + 1);
}
console.log(`\n=== 事件类型计数（${events.length} 条）===`);
for (const [k, v] of [...byType].sort((a, b) => b[1] - a[1])) console.log(`  ${String(v).padStart(3)}  ${k}`);

// 每次工具调用的名字（tool_use_id → name），后面把 id 翻译成人看的名字
const toolNames = new Map();
for (const e of events) {
  const content = e.m.message?.content;
  if (!Array.isArray(content)) continue;
  for (const b of content) {
    if (b?.type === 'tool_use') toolNames.set(b.id, `${b.name}${b.name === 'Task' ? '(子代理)' : ''}`);
  }
}
const nameOf = (id) => toolNames.get(id) ?? `?${String(id).slice(-6)}`;

const prog = events.filter((e) => e.m.type === 'tool_progress');
console.log(`\n=== tool_progress：${prog.length} 条 ${prog.length ? '' : '（默认不发 —— 要么有开关，要么这台 CLI 不产）'}===`);
for (const e of prog) {
  const m = e.m;
  console.log(
    `  ${ts.call(null, 0) && ''}${String(e.at).padStart(6)}ms  ${String(m.elapsed_time_seconds).padStart(5)}s`
    + `  ${String(m.tool_name).padEnd(6)} ${nameOf(m.tool_use_id).padEnd(20)}`
    + `  parent=${m.parent_tool_use_id ? nameOf(m.parent_tool_use_id) : 'null'}`
    + (m.heartbeat ? '  heartbeat' : '')
    + (m.subagent_type ? `  subagent=${m.subagent_type}` : '')
    + (m.subagent_retry ? `  retry=${m.subagent_retry.attempt}/${m.subagent_retry.max_retries}` : ''),
  );
}
if (prog.length > 1) {
  const gaps = prog.slice(1).map((e, i) => e.at - prog[i].at);
  console.log(`  间隔（ms）：${gaps.join(', ')}`);
}

const sums = events.filter((e) => e.m.type === 'tool_use_summary');
console.log(`\n=== tool_use_summary：${sums.length} 条 ${sums.length ? '' : '（默认不发）'}===`);
for (const e of sums) {
  const m = e.m;
  console.log(`  +${String(e.at).padStart(6)}ms  「${m.summary}」`);
  console.log(`            preceding: ${(m.preceding_tool_use_ids ?? []).map(nameOf).join(', ') || '(空)'}`);
}

// Q4：主线程那几条工具消息的 parent 到底长什么样
console.log('\n=== 主线程工具消息的 parent 形状（Q4）===');
const sample = events.find((e) => e.m.type === 'assistant'
  && Array.isArray(e.m.message?.content) && e.m.message.content.some((b) => b?.type === 'tool_use'));
if (sample) {
  console.log(`  assistant 上 parent_tool_use_id = ${JSON.stringify(sample.m.parent_tool_use_id)}`
    + `（${sample.m.parent_tool_use_id === null ? '是 null' : '有值'}）`);
}
console.log(`  tool_progress 里 parent 非 null 的：${prog.filter((e) => e.m.parent_tool_use_id).length} 条`
  + `（子代理内部的工具会推的话，这里就 > 0）`);

console.log(`\n时长：${secs(sentAt)}　result：${result ? result.m.subtype : '（没等到）'}`);
if (qErr) console.log(`query 报错：${qErr?.message ?? qErr}`);
process.exit(result ? 0 : 2);
