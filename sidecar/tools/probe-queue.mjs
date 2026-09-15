/**
 * 探针：回合进行中再发一条消息，会发生什么。
 *
 * **不是产品代码**，与 probe-slash.mjs 同一个惯例留在仓库里 —— 排队输入的设计稿
 * 里那几条"事实"要用它重跑。
 *
 * 要回答的四个问题：
 *   1. 回合还跑着的时候往输入流里推一条，CLI 收不收？是排队、折进当前回合，
 *      还是直接丢/报错？
 *   2. 排队的消息跑起来时，事件流上认不认得出来是**哪一条**跑起来了
 *      （`user_message_uuid` 这个联结点在不在我们的路径上）？
 *   3. `interrupt()` 的返回值里有没有 `still_queued`？排着的那条会怎样 ——
 *      跟着死掉，还是照样跑？
 *   4. 排队时 CLI 会不会给一条"已收下"的回执（界面上"排队中"那个状态靠什么点亮）？
 *
 * 选项照抄 session.js，环境走 env.js 的清洗，测的是生产路径而不是更宽松的旁路。
 *
 * 用法（cwd 必须是仓库根）：
 *
 *   node sidecar/tools/probe-queue.mjs            # 只跑阶段 1、2
 *   node sidecar/tools/probe-queue.mjs --interrupt # 再跑阶段 3（打断 + 收据）
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';

const RUN_INTERRUPT = process.argv.includes('--interrupt');

const t0 = Date.now();
const events = [];
const waiters = [];

/** 一条能在 8 秒内跑完、不碰网络的慢命令 —— 期间才有"回合进行中"可言。 */
const SLOW = 'node -e "setTimeout(()=>console.log(\'SLOW-DONE\'),8000)"';

function ts() {
  return `+${String(Date.now() - t0).padStart(6)}ms`;
}

function waitFor(pred, label, ms, from = 0) {
  const hit = events.slice(from).find(pred);
  if (hit) return Promise.resolve(hit);
  return new Promise((resolve, reject) => {
    const w = { pred, resolve };
    waiters.push(w);
    setTimeout(() => {
      const i = waiters.indexOf(w);
      if (i >= 0) {
        waiters.splice(i, 1);
        reject(new Error(`超时（${ms}ms）：${label}`));
      }
    }, ms).unref?.();
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function onEvent(msg) {
  events.push(msg);
  const tag = `${msg.type}/${msg.subtype ?? ''}`;
  let extra = '';

  // 联结点：哪一条发送触发了这个回合（部分帧上有）
  const join = msg.user_message_uuid ? ` uuid=${msg.user_message_uuid}` : '';

  if (msg.type === 'assistant') {
    const text = (msg.message?.content ?? [])
      .filter((b) => b.type === 'text')
      .map((b) => b.text)
      .join('');
    const tools = (msg.message?.content ?? [])
      .filter((b) => b.type === 'tool_use')
      .map((b) => b.name)
      .join(',');
    extra = ` [${tools || 'text'}] ${JSON.stringify(text.slice(0, 80))}`;
  } else if (msg.type === 'user') {
    // 工具结果也走 user 事件。要在里面认出**我们自己发的那两条**
    const c = msg.message?.content;
    extra = ` ${JSON.stringify(String(Array.isArray(c) ? JSON.stringify(c).slice(0, 120) : c).slice(0, 120))}`;
  } else if (msg.type === 'result') {
    extra =
      ` subtype=${msg.subtype} turns=${msg.num_turns} uuid=${msg.user_message_uuid ?? '-'}` +
      ` cost=${msg.total_cost_usd ?? '-'}`;
  } else if (msg.type === 'system' && msg.subtype === 'init') {
    extra = ` caps=${JSON.stringify(msg.capabilities ?? null)}`;
  } else if (msg.type === 'stream_event') {
    // 几百条增量只计数；但带 uuid 的那种是联结点，必须打出来
    if (!msg.user_message_uuid) return;
  }
  console.log(`  ${ts()} EVENT ${tag}${join}${extra}`);

  for (const w of waiters.slice()) {
    if (w.pred(msg)) {
      waiters.splice(waiters.indexOf(w), 1);
      w.resolve(msg);
    }
  }
}

// ---- 输入流：与 session.js 同款，只多了 uuid 这条路 ----
let stopped = false;
let notifyInput = null;
const queue = [];

async function* inputStream() {
  while (!stopped) {
    if (queue.length > 0) {
      yield queue.shift();
      continue;
    }
    const item = await new Promise((r) => { notifyInput = r; });
    notifyInput = null;
    if (item === null) return;
    yield item;
  }
}

function send(text, uuid) {
  const item = {
    type: 'user',
    message: { role: 'user', content: text },
    parent_tool_use_id: null,
  };
  // 客户端的联结点。SDK 的 .d.ts 说 submitMessage 支持 options.uuid ——
  // 输入流这条路认不认，正是这个探针要问的
  if (uuid) item.uuid = uuid;
  queue.push(item);
  notifyInput?.('go');
}

const resultsFrom = (from) => events.slice(from).filter((m) => m.type === 'result');

// ---- 主流程 ----
const cwd = process.cwd();
console.log(`工作目录：${cwd}`);

const q = query({
  prompt: inputStream(),
  options: {
    cwd,
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: true,
    canUseTool: (toolName, input) =>
      Promise.resolve(
        toolName === 'Bash' && String(input.command ?? '').includes('SLOW-DONE')
          ? { behavior: 'allow', updatedInput: input }
          : { behavior: 'deny', message: '探针：只放行那条慢命令' },
      ),
  },
});

(async () => {
  try {
    for await (const msg of q) onEvent(msg);
  } catch (err) {
    console.log(`\n!! 流错误：${err?.message ?? err}`);
  }
})();

const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  process.exit(2);
}, 240000);

try {
  // ============ 阶段 1：回合进行中推两条 ============
  console.log('\n=== 阶段 1：A 跑着的时候，先后推 B、C ===');
  const mark1 = events.length;
  send(`请先用 Bash 跑这条命令：${SLOW}，跑完告诉我「A完成」。`, 'client-A');
  await sleep(1500);
  console.log(`  ${ts()} >>> 发送 B（带着 uuid client-B，此刻 A 还在跑）`);
  send('回复 exactly: B-OK', 'client-B');
  await sleep(1000);
  console.log(`  ${ts()} >>> 发送 C（uuid client-C）`);
  send('回复 exactly: C-OK', 'client-C');

  // 等 A 那条慢命令跑完 + B、C 各自的结果
  await sleep(30000);
  const rs = resultsFrom(mark1);
  console.log(`\n  阶段 1 结果：收到 ${rs.length} 个 result`);
  for (const r of rs) {
    console.log(`    subtype=${r.subtype} uuid=${r.user_message_uuid ?? '-'} turns=${r.num_turns}`);
  }
  const texts = events
    .slice(mark1)
    .filter((m) => m.type === 'assistant')
    .flatMap((m) => (m.message?.content ?? []).filter((b) => b.type === 'text').map((b) => b.text));
  console.log(`  阶段 1 助手文本：${JSON.stringify(texts.join(' | ').slice(0, 300))}`);
  console.log(`  阶段 1 里出现过 B-OK：${texts.some((t) => t.includes('B-OK'))}`);
  console.log(`  阶段 1 里出现过 C-OK：${texts.some((t) => t.includes('C-OK'))}`);

  // ============ 阶段 3：打断时排队的会怎样 ============
  if (RUN_INTERRUPT) {
    console.log('\n=== 阶段 3：D 跑着的时候推 E，然后打断 ===');
    const mark3 = events.length;
    send(`请再用 Bash 跑这条命令：${SLOW}，跑完告诉我「D完成」。`, 'client-D');
    await sleep(1500);
    console.log(`  ${ts()} >>> 发送 E（uuid client-E，D 还在跑）`);
    send('回复 exactly: E-OK', 'client-E');
    await sleep(1000);

    console.log(`  ${ts()} >>> interrupt()`);
    let receipt;
    try {
      receipt = await q.interrupt();
    } catch (err) {
      receipt = `抛错：${err?.message ?? err}`;
    }
    console.log(`  interrupt 回执：${JSON.stringify(receipt)}`);

    await sleep(25000);
    const texts3 = events
      .slice(mark3)
      .filter((m) => m.type === 'assistant')
      .flatMap((m) => (m.message?.content ?? []).filter((b) => b.type === 'text').map((b) => b.text));
    console.log(`  阶段 3 助手文本：${JSON.stringify(texts3.join(' | ').slice(0, 200))}`);
    console.log(`  打断之后 E-OK 还是跑了吗：${texts3.some((t) => t.includes('E-OK'))}`);
    const rs3 = resultsFrom(mark3);
    console.log(`  阶段 3 的 result：${rs3.map((r) => `${r.subtype}/${r.user_message_uuid ?? '-'}`).join(', ') || '（无）'}`);
  } else {
    console.log('\n（跳过了阶段 3 —— 加 --interrupt 才跑）');
  }

  console.log('\n--- 收尾 ---');
  stopped = true;
  notifyInput?.(null);
  clearTimeout(watchdog);
  setTimeout(() => process.exit(0), 1500);
} catch (err) {
  console.log(`\n!! 探针失败：${err.message}`);
  clearTimeout(watchdog);
  stopped = true;
  notifyInput?.(null);
  setTimeout(() => process.exit(1), 500);
}
