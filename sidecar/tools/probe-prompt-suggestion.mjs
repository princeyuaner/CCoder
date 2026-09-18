/**
 * 探针：`prompt_suggestion`（"猜你下一句问什么"）到底怎么来的。
 *
 * 用户 2026-09-18 看完 SDK 面清单后问这条是干嘛的，然后说"探"。
 *
 * `sdk.d.ts:1943-1958` 把交付语义写得比我原本打算问的还全 —— 所以这一跑不是
 * "摸黑"，是**逐条验它**：
 *
 *   C1 每条回合最多一条，且**在 `result` 之后**到达
 *   C2 **消费方必须在 `result` 之后继续迭代流**才能收到（这条对插件最要紧：
 *      它是"回合已结束"的判据，很容易在 result 那一步就收工）
 *   C3 **第一回合不发**（抑制）
 *   C4 `suggestion` 原文长什么样、跟上下文有没有关系
 *   C5 result 到 suggestion 之间隔多久（它要现算一句话，不是白给）
 *   C6 **"piggyback on the parent's prompt cache, making them nearly free"** ——
 *      这句最值得验：走自定义网关时缓存命中率本来就不确定。
 *      量法：开 `debugFile`，从 CLI 自己的调试日志里捞那次请求的 usage
 *   C7 被中断的回合发不发（文档只写了"API 错误后不发"，中断是另一回事）
 *
 * 用法（cwd 必须是仓库根）：
 *   node sidecar/tools/probe-prompt-suggestion.mjs              # 甲：三回合
 *   node sidecar/tools/probe-prompt-suggestion.mjs --interrupt  # 乙：中断那半
 *
 * 花钱与副作用：甲跑 **3 次模型调用**（每条 prompt 一次，外加它自己那些建议调用），
 * 乙跑 2 次。都不动你的仓库，只在一个临时目录里读写。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const INTERRUPT = process.argv.includes('--interrupt');
const WIND_DOWN = process.argv.includes('--wind-down');

const workDir = mkdtempSync(join(tmpdir(), 'ccoder-suggest-'));
writeFileSync(join(workDir, 'package.json'), JSON.stringify({ name: 'suggest-fixture' }, null, 2));
writeFileSync(join(workDir, 'README.md'), '# fixture\n\n这个目录只用来给探针读。\n');
const debugFile = join(workDir, 'debug.log');

const t0 = Date.now();
const at = (ms) => `+${String(ms).padStart(6)}ms`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const LONG_CMD = 'node -e "setTimeout(()=>console.log(\'SLOW\'),30000)"';

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
  permissionMode: 'default',
  env: buildChildEnv(process.env),
  includePartialMessages: true,
  // 这一次的主角
  promptSuggestions: true,
  // 给 C6 用的：CLI 自己的调试日志（`--debug-file` 的 SDK 版，开它就等于开 debug）
  debugFile,
  canUseTool: (name, inp) => {
    if (name === 'Bash') {
      bashRewrites += 1;
      return Promise.resolve(bashRewrites === 1
        ? { behavior: 'allow', updatedInput: { ...inp, command: LONG_CMD, run_in_background: false } }
        : { behavior: 'deny', message: '探针：只跑一条' });
    }
    const ok = ['Read', 'Glob', 'Grep', 'TodoWrite'].includes(name);
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

/** 等一条**在 mark 之后**出现的某类型事件，超时返回 null。 */
async function waitFor(type, mark, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const hit = events.slice(mark).find((e) => e.m.type === type);
    if (hit) return hit;
    await sleep(150);
  }
  return null;
}

/**
 * 一个回合：发出去 → 等 result → **继续等 suggestion**（C2 要的正是"result 之后
 * 还继续收"）。返回两者的时间差。
 */
async function turn(label, text, { interruptAfterMs = 0, suggestionWaitMs = 25000 } = {}) {
  const mark = events.length;
  console.log(`\n── ${label}：${text.slice(0, 40)}${text.length > 40 ? '…' : ''}`);
  input.send(text);

  const result = await waitFor('result', mark, 120000);
  if (!result) { console.log('   ✗ 没等到 result（这一跑不算数）'); return; }
  console.log(`   result        ${at(result.at)}  subtype=${result.m.subtype}`);

  if (interruptAfterMs > 0) {
    await sleep(interruptAfterMs);
    console.log(`   中断          ${at(Date.now() - t0)}（interrupt()）`);
    try { await q.interrupt(); } catch (e) { console.log(`   interrupt 抛错：${e?.message ?? e}`); }
  }

  const sug = await waitFor('prompt_suggestion', mark, suggestionWaitMs);
  if (!sug) {
    console.log(`   suggestion    —— 等了 ${Math.round(suggestionWaitMs / 1000)}s 没来`);
    return;
  }
  console.log(`   suggestion    ${at(sug.at)}  （result 之后 ${((sug.at - result.at) / 1000).toFixed(1)}s）`);
  console.log(`   「${sug.m.suggestion}」`);
}

if (INTERRUPT) {
  await turn('乙·第1回合（让第一回合的抑制过去）', '只回答一个数字：1 + 1 等于几？');
  await turn('乙·第2回合（一条长命令，跑到一半按停止）',
    '用 Bash 跑一条会跑 30 秒的命令（别丢后台），跑完告诉我。',
    { interruptAfterMs: 5000, suggestionWaitMs: 20000 });
} else {
  await turn('甲·第1回合（文档说这一回合不发）', '只回答一个数字：1 + 1 等于几？');
  await turn('甲·第2回合', '读一下这个目录里的 README.md，然后用一句话说它讲了什么。');
  await turn('甲·第3回合', '用一句话说明：如果我要给这个目录加一个测试脚本，应该放哪儿？');
}

/**
 * 丙跑（`--wind-down`）：**把输入流关掉之后再等**。
 *
 * 为什么加这一跑：甲跑量到"生成照做、但一条都没送到"。翻内嵌 JS 看到 CLI 有个
 * `holdsResults()` 的**扣住**机制（`heldBackResults` / `carriedEvalResults`），
 * 而扣不扣跟 `drain.inputClosed` 有关 —— 建议在这条路上会先存进
 * `pendingSuggestion`，等某个时刻才由 `emitPendingSuggestion()` 放出来。
 * 最省事的猜法是"宿主不再发消息之后才放"。这一跑就验它。
 */
if (WIND_DOWN) {
  console.log('\n── 丙：关掉输入流，然后继续读 25s');
  input.stop();
  console.log(`   输入流已关闭   ${at(Date.now() - t0)}`);
  const late = await waitFor('prompt_suggestion', 0, 25000);
  console.log(late
    ? `   ★ 关流之后来了！${at(late.at)}　「${late.m.suggestion}」`
    : '   关流之后 25s 内仍然没有');
}

// 收尾：再多等一会儿，看看有没有迟到的
await sleep(3000);
input.stop();
try { q.close?.(); } catch { /* 收尾 */ }
await Promise.race([pump, sleep(3000)]);

// ---- 汇总 ----

console.log('\n\n=== 汇总 ===');
const results = events.filter((e) => e.m.type === 'result');
const sugs = events.filter((e) => e.m.type === 'prompt_suggestion');
console.log(`回合（result）：${results.length}　建议：${sugs.length}`);

console.log('\n=== 建议的线格式（原样一条）===');
if (sugs[0]) console.log(JSON.stringify(sugs[0].m, null, 2));
else console.log('（一条都没有）');

// C6：从 CLI 自己的调试日志里找那次"生成建议"的请求，看它蹭没蹭上缓存
console.log('\n=== C6：CLI 调试日志里的证据 ===');
if (!existsSync(debugFile)) {
  console.log('（没有调试日志文件 —— debugFile 这条路没生效）');
} else {
  const lines = readFileSync(debugFile, 'utf8').split(/\r?\n/);
  console.log(`日志 ${lines.length} 行`);
  const hits = lines.filter((l) => /prompt_suggestion|promptSuggestion/i.test(l));
  console.log(`提到 prompt_suggestion 的行：${hits.length}`);
  for (const l of hits.slice(0, 6)) console.log(`   ${l.slice(0, 220)}`);
  const usage = lines.filter((l) => /cache_read_input_tokens/.test(l));
  console.log(`带 cache_read_input_tokens 的行：${usage.length}`);
  for (const l of usage.slice(-3)) console.log(`   ${l.slice(0, 220)}`);
}

console.log(`\n总时长：${((Date.now() - t0) / 1000).toFixed(1)}s`);
if (qErr) console.log(`query 报错：${qErr?.message ?? qErr}`);
process.exit(results.length > 0 ? 0 : 2);
