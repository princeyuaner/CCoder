/**
 * 集成探针：一次真机回合 → 快照 → 回滚 → 逐字节核对（2026-10-13）。
 *
 * 与 `probe-turn-changes.mjs` 的分工：那个量的是**hook 事实**（时机、模式、字段）；
 * 这个把**产品链路**整个接起来跑一遍 —— `createSession`（真实的 session.js，
 * 含 PreToolUse 接线与轮次记账）+ `createTurnSnapshots`（真实的快照 store），
 * 让真模型改文件，然后回滚，逐字节核对。
 *
 * 要回答的问题：
 *   Q1 快照真的抓到了吗（每抓到一份都发一条 onSnapshot；三种 existed 各一次）
 *   Q2 同轮同文件第二次编辑**不再抓**（deduped —— 第一份才是"改动前"）
 *   Q3 回滚把文件写回**逐字节相同**、把本轮新建的**删掉**
 *   Q4 忙时（回合没跑完）`revertFiles` 逐条拒绝（error=busy）
 *   Q5 轮末记账生效：回合结束后又被改过的文件，回执里 `dirty=true`
 *
 * **SDK 的 cwd 是临时目录** —— 探针要真写文件，绝不能在仓库里写。跑完不删。
 *
 * 用法（cwd 必须是仓库根；要一次模型调用）：
 *
 *   node sidecar/tools/probe-turn-revert.mjs
 *
 * 退出码：0 = Q1-Q5 全部成立；1 = 有不成立；2 = 超时。
 */
import { mkdtempSync, readFileSync, writeFileSync, existsSync, utimesSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createSession } from '../session.js';
import { createTurnSnapshots } from '../snapshots.js';

const workDir = mkdtempSync(join(tmpdir(), 'ccoder-revert-'));
const A = join(workDir, 'a.txt');
const B = join(workDir, 'b.txt');
// 带填充的长内容：两次 Edit 的 old/new 串都在这份文件里
const SEED = 'AAA\nfill1\nfill2\nfill3\n';

const t0 = Date.now();
const ts = () => `+${String(Date.now() - t0).padStart(6)}ms`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const findings = [];
const note = (q, text) => findings.push(`  ${q}: ${text}`);
const answers = {};

const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  console.log(`临时目录：${workDir}`);
  process.exit(2);
}, 420000);

let session = null;

try {
  console.log(`工作目录：${workDir}`);
  writeFileSync(A, SEED);

  const snapshots = createTurnSnapshots({
    root: join(workDir, 'snapshots'),
    projectKey: 'probe',
    cwd: workDir,
  });
  const shots = [];
  const events = [];

  session = createSession({
    cwd: workDir,
    permissionMode: 'acceptEdits',
    snapshots,
    onSnapshot: (e) => shots.push(e),
    onEvent: (e) => events.push(e),
  });

  const PROMPT = [
    '按顺序做两件事，只用工具，不要解释，不要用 Bash：',
    `1) 用 Edit 工具把 ${A} 里的 AAA 替换成 BBB；`,
    `2) 再用 Edit 工具把同一个文件里的 BBB 替换成 CCC。`,
    `3) 用 Write 工具新建 ${B}，内容两行：h1 和 h2。`,
    '做完回复 DONE。',
  ].join('\n');

  session.send(PROMPT);

  // ---- Q4：忙时拒绝（回合正在跑，这条立刻问）----
  {
    const busy = session.revertFiles([A]);
    note('Q4', busy.length === 1 && busy[0].ok === false && busy[0].error === 'busy'
      ? '忙时逐条拒绝（error=busy）✓'
      : `**没拒绝**：${JSON.stringify(busy)}`);
    answers.Q4 = busy[0]?.error === 'busy';
  }

  // ---- 等回合结束 ----
  const deadline = Date.now() + 240000;
  while (Date.now() < deadline && !events.some((m) => m?.type === 'result')) {
    await sleep(300);
  }
  const ended = events.some((m) => m?.type === 'result');
  console.log(`  ${ts()} 回合${ended ? '结束' : '**没等到 result**'}；`
    + `a.txt=${JSON.stringify(readFileSync(A, 'utf8').trim())} b.txt=${existsSync(B)}`);
  console.log(`  ${ts()} onSnapshot ${shots.length} 条：`);
  for (const s of shots) {
    console.log(`      · existed=${s.existed} ${s.path} toolUseId=${s.toolUseId ?? '-'}`);
  }

  // ---- Q1/Q2：每文件一条、第一次那份 ----
  {
    const aShots = shots.filter((s) => String(s.path).includes('a.txt'));
    const bShots = shots.filter((s) => String(s.path).includes('b.txt'));
    note('Q1', shots.some((s) => s.existed === true) && shots.some((s) => s.existed === false)
      ? `两种 existed 都发过（存下 ${shots.filter((s) => s.existed === true).length} 条、`
        + `不存在 ${shots.filter((s) => s.existed === false).length} 条）`
      : `**少了**（shots=${JSON.stringify(shots)}）`);
    note('Q2', aShots.length === 1
      ? '同轮同文件只发一条（第二次编辑被 dedupe）✓'
      : `a.txt 发了 ${aShots.length} 条 —— dedupe 没生效`);
    answers.Q1 = shots.some((s) => s.existed === true) && shots.some((s) => s.existed === false);
    answers.Q2 = aShots.length === 1 && bShots.length === 1;

    const before = snapshots.snapshot({ path: A });
    note('Q1b', before.kind === 'file' && before.content === SEED
      ? '取回的"改动前"与种子**逐字节相同** ✓'
      : `**对不上**：kind=${before.kind} content=${JSON.stringify((before.content ?? '').slice(0, 40))}`);
    answers.Q1b = before.kind === 'file' && before.content === SEED;
  }

  // ---- Q5：回合结束后又改过 → dirty ----
  writeFileSync(A, 'CCC\nfill1\nfill2\nfill3\nuser-touched\n');
  const future = new Date(Date.now() + 10_000);
  utimesSync(A, future, future);

  // ---- Q3：回滚 ----
  const results = session.revertFiles([A, B]);
  console.log(`  ${ts()} 回滚回执：${JSON.stringify(results)}`);
  const aRes = results.find((r) => String(r.path).includes('a.txt'));
  const bRes = results.find((r) => String(r.path).includes('b.txt'));
  const aAfter = readFileSync(A, 'utf8');
  console.log(`  ${ts()} 回滚后：a.txt=${JSON.stringify(aAfter.trim())} b.txt=${existsSync(B)}`);

  note('Q3', aRes?.ok === true && aAfter === SEED && bRes?.ok === true && !existsSync(B)
    ? 'a.txt 逐字节写回、b.txt 被删除 ✓'
    : `**没回干净**：a.ok=${aRes?.ok} 内容一致=${aAfter === SEED} b.action=${bRes?.action} b在=${existsSync(B)}`);
  note('Q5', aRes?.dirty === true
    ? '轮末记账生效：dirty=true（覆盖了回合后的新改动）✓'
    : `dirty=${aRes?.dirty} —— 轮末 mtime 记账没生效`);
  answers.Q3 = aRes?.ok === true && aAfter === SEED && bRes?.ok === true && !existsSync(B);
  answers.Q5 = aRes?.dirty === true;

  console.log('\n--- 结论 ---');
  for (const f of findings) console.log(f);
  const all = Object.values(answers).every(Boolean);
  console.log(`\n${all
    ? '  全链路成立：抓取（含 dedupe）→ 改动前逐字节一致 → 回滚写回/删除 → dirty 记账 ✓'
    : '  有项不成立 —— 看上面哪条 ✗'}`);
  clearTimeout(watchdog);
  session?.stop();
  console.log(`\n临时目录（不删，失败时它就是证据）：${workDir}`);
  setTimeout(() => process.exit(all ? 0 : 1), 1200);
} catch (err) {
  console.log(`\n!! 探针失败：${err?.message ?? err}`);
  clearTimeout(watchdog);
  session?.stop();
  console.log(`临时目录（不删，失败时它就是证据）：${workDir}`);
  setTimeout(() => process.exit(3), 1200);
}
