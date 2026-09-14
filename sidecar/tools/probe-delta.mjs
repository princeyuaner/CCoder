/**
 * 探针：增量到底是"一个字一个字"来的，还是"一段一段"来的。
 *
 * 起因：用户提问「思考为什么不是一行一行输出的，而是一段一段输出」。
 * 管线这一侧是逐片转发的 —— `content_block_delta` 每来一条就产一个
 * `AppendDelta` op（MessageRenderer.kt:232），节流器 16ms 一批 —— 所以
 * 若屏幕上真是一段一段地冒，粒度就只能来自**事件本身**。
 * 这个探针把那条假设量出来：每个分片多长、两片之间隔多久。
 *
 * 选项照抄 session.js（cwd / permissionMode / env 清洗 / includePartialMessages），
 * 测的是生产路径。
 *
 * 用法：
 *   cd <repo root>
 *   node sidecar/tools/probe-delta.mjs ["自定义提问"]
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';

const events = [];
const waiters = [];

function waitFor(pred, label, ms) {
  const hit = events.find(pred);
  if (hit) return Promise.resolve(hit);
  return new Promise((resolve, reject) => {
    const w = { pred, resolve };
    waiters.push(w);
    setTimeout(() => {
      const i = waiters.indexOf(w);
      if (i >= 0) waiters.splice(i, 1);
      reject(new Error(`超时（${ms}ms）：${label}`));
    }, ms).unref?.();
  });
}

// ---- 分片记录 ----
/**
 * 按**内容块**编号记录，而不是按类型记。
 *
 * 用户的原话是"说一段后卡几秒"，而"卡几秒"到底发生在
 *   (a) 块与块之间（模型在思考和调工具之间本来就该停），还是
 *   (b) **块内**（那就是到达真的断了，屏幕上就是卡住）
 * 只看类型分不出这两者 —— 上一版探针就是这么把 2.2 秒的块间停顿
 * 记成了"最大片间隔"，然后被当成正常现象放过去的。
 */
const blocks = new Map(); // index -> {type, chars, chunks, last, pauses: [{gap, burst}]}
const blockOrder = [];
let pendingPause = null; // 上一次大停顿，等下一片到达时补上它有多大

function onEvent(msg) {
  events.push(msg);
  for (const w of waiters.slice()) {
    if (w.pred(msg)) {
      waiters.splice(waiters.indexOf(w), 1);
      w.resolve(msg);
    }
  }

  if (msg.type !== 'stream_event') return;
  const ev = msg.event ?? {};

  if (ev.type === 'content_block_start') {
    const idx = ev.index ?? blockOrder.length;
    blocks.set(idx, {
      type: ev.content_block?.type ?? '?',
      chars: 0,
      chunks: 0,
      first: null,
      last: null,
      pauses: [],
    });
    blockOrder.push(idx);
    return;
  }
  if (ev.type !== 'content_block_delta') return;

  const d = ev.delta ?? {};
  const text = d.type === 'text_delta' ? d.text : d.type === 'thinking_delta' ? d.thinking : null;
  if (typeof text !== 'string') return;

  const idx = ev.index ?? 0;
  const b = blocks.get(idx) ??
    { type: d.type, chars: 0, chunks: 0, first: null, last: null, pauses: [] };
  blocks.set(idx, b);

  const t = Date.now();
  if (b.first === null) b.first = t;
  const gap = b.last === null ? null : t - b.last;

  if (pendingPause !== null) {
    pendingPause.burst = text.length; // 停顿之后的第一片就是"一次蹦出来多少"的下限
    pendingPause = null;
  }
  if (gap !== null && gap >= PAUSE_MS) {
    const rec = { gap, at: b.type, burst: null };
    b.pauses.push(rec);
    pendingPause = rec;
  }

  b.chars += text.length;
  b.chunks += 1;
  b.last = t;
}

/** 超过这个间隔没有新字 → 屏幕上是肉眼可见的"卡住"。 */
const PAUSE_MS = 300;

/** 与 TranscriptPump.DEFAULT_THROTTLE_MS 对齐（那一侧是 60fps 的节流窗口）。 */
const THROTTLE_MS = 16;

// ---- 输入流：与 session.js 同款 ----
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

function send(text) {
  queue.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null });
  notifyInput?.('go');
}

function stats(nums) {
  if (nums.length === 0) return '（无）';
  const sorted = [...nums].sort((a, b) => a - b);
  const at = (q) => sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
  const mean = nums.reduce((a, b) => a + b, 0) / nums.length;
  return `n=${nums.length} 均=${mean.toFixed(1)} p50=${at(0.5)} p90=${at(0.9)} 最大=${sorted[sorted.length - 1]}`;
}

const prompt =
  process.argv[2] ??
  '用中文解释一下 TCP 三次握手为什么不能是两次，然后给一段 20 行左右的 Python 示例代码。';

const q = query({
  prompt: inputStream(),
  options: {
    cwd: process.cwd(),
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: true,
  },
});

(async () => {
  (async () => {
    for await (const msg of q) onEvent(msg);
  })().catch(() => {});

  const t0 = Date.now();
  send(prompt);
  await waitFor((m) => m.type === 'result', 'result', 180000);

  console.log(`\n提问：${JSON.stringify(prompt.slice(0, 60))}`);
  console.log(`耗时：${((Date.now() - t0) / 1000).toFixed(1)}s`);

  // ---- 逐块 ----
  let totalPauseMs = 0;
  let pauseCount = 0;
  const allPauses = [];

  console.log('\n内容块：');
  for (const idx of blockOrder) {
    const b = blocks.get(idx);
    const pauses = b.pauses.length
      ? `⏸ ${b.pauses.length} 次，最长 ${Math.max(...b.pauses.map((p) => p.gap))}ms`
      : '无';
    const spanMs = b.first !== null && b.last !== null ? b.last - b.first : 0;
    const cps = spanMs > 0 ? (b.chars / spanMs) * 1000 : 0;
    // 「每 16ms 多少字」才是屏幕上真正看到的东西：节流器一拍把这段时间里
    // 到达的全部增量一次性画出去，所以一拍拍出来多少字，屏幕就一次跳多少。
    // 到达再细也没用 —— 决定观感的是这个乘积
    const perFrame = cps * (THROTTLE_MS / 1000);
    console.log(
      `  #${idx} ${b.type.padEnd(12)} ${String(b.chars).padStart(6)} 字符 ` +
        `${String(b.chunks).padStart(5)} 片  ${(spanMs / 1000).toFixed(1)}s  ` +
        `${cps.toFixed(0).padStart(4)} 字/秒  → 每 16ms 约 ${perFrame.toFixed(1)} 字`,
    );
    console.log(`       停顿>${PAUSE_MS}ms: ${pauses}`);
    for (const p of b.pauses) {
      totalPauseMs += p.gap;
      pauseCount += 1;
      allPauses.push({ ...p, idx });
    }
  }

  // ---- 停顿汇总：用户说的"卡几秒"就是这个 ----
  const span = Date.now() - t0;
  console.log(
    `\n停顿（同一块内 >${PAUSE_MS}ms 没有新字）：${pauseCount} 次，合计 ${totalPauseMs}ms，` +
      `占整轮 ${((totalPauseMs / span) * 100).toFixed(0)}%`,
  );
  if (allPauses.length) {
    console.log('  最长的几次（停顿后的第一片 = 接下来要一口气画多少字）：');
    for (const p of allPauses.sort((a, b) => b.gap - a.gap).slice(0, 6)) {
      console.log(
        `   #${p.idx} ${p.at}  停了 ${String(p.gap).padStart(5)}ms → 紧接 ${p.burst ?? '?'} 字一片`,
      );
    }
  }

  // ---- 按类型的分片统计（与上一版一致，用于对比） ----
  console.log('');
  for (const kind of new Set([...blocks.values()].map((b) => b.type))) {
    const mine = [...blocks.values()].filter((b) => b.type === kind);
    const lens = mine.flatMap((b) => [b.chars / Math.max(1, b.chunks)]);
    console.log(`${kind}：${mine.reduce((a, b) => a + b.chars, 0)} 字符，平均每片 ${lens.map((x) => x.toFixed(1)).join(' / ')} 字`);
  }

  if (![...blocks.values()].some((b) => b.type === 'thinking')) {
    console.log('\n注意：这轮没有 thinking 块 —— 换个更需要推理的提问再跑一次。');
  }
  stopped = true;
  notifyInput?.(null);
  process.exit(0);
})().catch((err) => {
  console.error('探针失败：', err.message);
  process.exit(1);
});
