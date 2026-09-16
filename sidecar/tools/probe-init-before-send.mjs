/**
 * 探针：**不发消息**的时候，会话能问到什么？
 *
 * 起因（2026-09-15，用户报）：刚打开插件时打 `/` 不出一条候选，得先聊一句才有。
 * 已知的链路是：`ready` 一建好会话就发 → 插件立刻问命令列表（拿到了，那一份是
 * `supportedCommands()`）；但"可发送名"只来自 `system/init.slash_commands`，
 * 而流式输入下 init 要等第一条消息才来（composer-completion 设计稿 §10 事实）。
 * 名字配不上 → `commandCandidates` 把那一条整条丢掉 → 列表空。
 *
 * 要量的是"能不能提前拿到"：
 *   1. 此刻 `supportedCommands()` 返回什么？（条数 / 名字写法 / 多久）
 *   2. 此刻 `initializationResult()` 返回什么？（它有 commands/models/account，
 *      我们至今没接）—— 如果它的 commands 带得动，就不用等 init
 *   3. 什么都不发，等 15 秒，看有没有自发的 `system/init` 事件
 *
 * **不发消息 = 没有模型调用**（这条探针不花钱）。选项照抄 session.js，
 * 环境走 env.js 的清洗，保证测的是生产路径。
 *
 * 用法：
 *   cd <repo root>
 *   node sidecar/tools/probe-init-before-send.mjs
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';

const WAIT_MS = 15_000;
const CALL_TIMEOUT_MS = 10_000;

const events = [];

function onEvent(msg) {
  events.push(msg);
  const tag = `${msg.type}/${msg.subtype ?? ''}`;
  if (msg.type === 'stream_event') return; // 增量分片，不打印
  console.log(`  EVENT ${tag}`);
  if (msg.subtype === 'init') {
    const cmds = msg.slash_commands ?? [];
    console.log(`    payload: model=${msg.model} session=${String(msg.session_id).slice(0, 8)} slash_commands=${cmds.length} 条`);
    console.log(`    样例：${JSON.stringify(cmds.slice(0, 8))}`);
  }
}

/** 给一个 thenable 套超时：超时返回 { timedOut: true } 而不是抛。 */
async function withTimeout(label, promise) {
  const started = Date.now();
  let timer;
  const timeout = new Promise((r) => {
    timer = setTimeout(() => r({ timedOut: true }), CALL_TIMEOUT_MS);
  });
  try {
    const value = await Promise.race([promise, timeout]);
    const ms = Date.now() - started;
    if (value?.timedOut) return { timedOut: true, ms };
    return { value, ms };
  } catch (err) {
    return { error: String(err?.message ?? err), ms: Date.now() - started };
  } finally {
    clearTimeout(timer);
  }
}

function describeCommands(list) {
  if (!Array.isArray(list)) return `不是数组：${typeof list}`;
  const names = list.map((c) => (typeof c === 'string' ? c : c?.name));
  return `${list.length} 条 | 样例 ${JSON.stringify(names.slice(0, 8))} | 第 0 条的字段 ${JSON.stringify(
    typeof list[0] === 'object' && list[0] !== null ? Object.keys(list[0]) : typeof list[0],
  )}`;
}

let stopped = false;
let notifyInput = null;
const queue = [];

async function* inputStream() {
  while (!stopped) {
    if (queue.length > 0) {
      yield queue.shift();
      continue;
    }
    const item = await new Promise((r) => {
      notifyInput = r;
    });
    notifyInput = null;
    if (item === null) return;
    yield item;
  }
}

const cwd = process.cwd();
console.log(`工作目录：${cwd}`);
console.log('=== 建会话（一条消息都不发）===');

const q = query({
  prompt: inputStream(),
  options: {
    cwd,
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: true,
  },
});

const consumer = (async () => {
  try {
    for await (const msg of q) onEvent(msg);
  } catch (err) {
    console.log(`  事件流中断：${err?.message ?? err}`);
  }
})();

// 让事件循环先转起来（SDK 在建子进程）
await new Promise((r) => setTimeout(r, 300));

console.log('\n--- 1. 立刻问 supportedCommands() ---');
const supported = await withTimeout('supportedCommands', q.supportedCommands());
if (supported.timedOut) console.log(`  超时（${CALL_TIMEOUT_MS}ms 内没答）`);
else if (supported.error) console.log(`  抛错：${supported.error}`);
else console.log(`  ${supported.ms}ms 拿到 ${describeCommands(supported.value)}`);

console.log('\n--- 1b. 立刻问 reloadSkills()（技能的"可发送名"是不是能单独拿到）---');
const reload = await withTimeout('reloadSkills', q.reloadSkills());
if (reload.timedOut) console.log(`  超时（${CALL_TIMEOUT_MS}ms 内没答）`);
else if (reload.error) console.log(`  抛错：${reload.error}`);
else console.log(`  ${reload.ms}ms 拿到：${JSON.stringify(reload.value).slice(0, 400)}`);

console.log('\n--- 2. 立刻问 initializationResult() ---');
const init = await withTimeout('initializationResult', q.initializationResult());
if (init.timedOut) console.log(`  超时（${CALL_TIMEOUT_MS}ms 内没答）`);
else if (init.error) console.log(`  抛错：${init.error}`);
else {
  const v = init.value ?? {};
  console.log(`  ${init.ms}ms 拿到。顶层字段：${JSON.stringify(Object.keys(v))}`);
  console.log(`  commands：${describeCommands(v.commands)}`);
  console.log(`  models：${Array.isArray(v.models) ? v.models.length + ' 条' : typeof v.models}`);
  console.log(`  account：${JSON.stringify(v.account ?? null).slice(0, 200)}`);
  console.log(`  output_style：${JSON.stringify(v.output_style ?? null)}`);
}

console.log(`\n--- 3. 什么都不发，等 ${WAIT_MS / 1000} 秒看有没有自发事件 ---`);
const before = events.length;
await new Promise((r) => setTimeout(r, WAIT_MS));
if (events.length === before) {
  console.log('  一个事件都没有（init 确实要等第一条消息）');
} else {
  console.log(`  来了 ${events.length - before} 个事件（上面已逐个打出）`);
}

// ---- 4. 发一条**本地命令**（不走模型）把 init 逼出来，拿到 B 那份对照 ----
//
// `/usage` 是本地命令：CLI 自己执行、自己出输出，不产生模型调用（设计稿事实 2/3）。
// 它一样会触发 system/init —— 于是 B 那份（可发送名）就到手了。
console.log('\n--- 4. 发一条 /usage（本地命令，不走模型）把 init 逼出来 ---');
const mark = events.length;
queue.push({
  type: 'user',
  message: { role: 'user', content: '/usage' },
  parent_tool_use_id: null,
});
notifyInput?.('go');

const deadline = Date.now() + 30_000;
while (Date.now() < deadline) {
  await new Promise((r) => setTimeout(r, 300));
  if (events.slice(mark).some((m) => m.type === 'result')) break;
}

const initEvent = events.slice(mark).find((m) => m.subtype === 'init');
const b = initEvent?.slash_commands ?? [];
console.log(`  B（init.slash_commands）：${b.length} 条`);

const a = (supported.value ?? []).map((c) => c.name);
const norm = (s) => s.trim().toLowerCase().replace(/\s+/g, '-');
const paired = new Map(); // A → B
for (const name of a) {
  const hit =
    b.find((x) => x === name) ??
    b.find((x) => norm(x) === norm(name)) ??
    b.find((x) => x.endsWith(':' + norm(name)) || norm(name).endsWith(':' + x));
  paired.set(name, hit ?? null);
}
const miss = a.filter((n) => !paired.get(n));
const bare = a.filter((n) => paired.get(n) === n);
const namespaced = a.filter((n) => paired.get(n) && paired.get(n) !== n);
console.log(`  A 与 B 的配对：${a.length - miss.length}/${a.length} 配上`);
console.log(`    · A 名**就是**B 名（裸名可直接发）：${JSON.stringify(bare)}`);
console.log(`    · A 名与 B 名不同（需要改写才能发）：`);
for (const n of namespaced) console.log(`        ${JSON.stringify(n)} → ${JSON.stringify(paired.get(n))}`);
console.log(`    · 配不上的（B 里没有）：${JSON.stringify(miss)}`);

console.log('\n--- 收尾 ---');
stopped = true;
notifyInput?.(null);
setTimeout(() => process.exit(0), 1500);
