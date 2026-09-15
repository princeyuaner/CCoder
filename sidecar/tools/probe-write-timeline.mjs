/**
 * 探针：一次 Write 调用在事件流上的**时间线** —— 参数生成那一段到底有多长、
 * 这期间到底有没有可用的信号。
 *
 * 起因：用户反馈「写文件的时候看起来像卡住了」。
 *
 * 当时从代码上看到的是：转写区只认 `text_delta` / `thinking_delta`
 * （`MessageRenderer.renderStreamEvent`），工具参数的 `input_json_delta` 明确不显示；
 * 而工具卡要等**完整的 assistant 消息**才出现。于是参数生成那段屏幕上完全静止，
 * 而能证明"它在动"的转圈与耗时都长在工具卡上 —— 指示器恰好缺席在最需要它的那段。
 *
 * 据此做了一处修改：`content_block_start`（带 tool_use）时产出一个只喂状态卡的项，
 * 让状态卡提前说「编辑文件」。**这个改动押在一条假设上**：
 *
 *   SDK 真的会发 `content_block_start`、而且里面带着 tool_use 的名字。
 *
 * 本探针就是来验这条假设的，顺带量出那个窗口有多长 —— 若它只有一两秒，
 * 那个改动其实无关紧要；若是十几秒，它才值得存在。
 *
 * 走生产路径：选项照抄 session.js，环境过 env.js 的清洗。
 * **SDK 的 cwd 是临时目录** —— 探针要真写文件，不能在仓库里写。
 *
 * 用法（cwd 必须是仓库根）：
 *
 *   node sidecar/tools/probe-write-timeline.mjs          # 默认写 80 行
 *   node sidecar/tools/probe-write-timeline.mjs --lines 400
 *
 * 退出码：0 = 假设成立（发且带名字）；1 = 假设不成立（那处改动是空操作）；
 *         2 = 全局超时；3 = 模型没照做，这一跑不算数。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { mkdtempSync, existsSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const LINES = Number(process.argv[process.argv.indexOf('--lines') + 1]) || 80;

const workDir = mkdtempSync(join(tmpdir(), 'ccoder-write-'));
const TARGET = join(workDir, 'generated.txt');

const t0 = Date.now();
const ts = () => `+${String(Date.now() - t0).padStart(6)}ms`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} 超时（${ms}ms）`)), ms)),
  ]);
}

// ---- 输入流：与 session.js 同款 ----
let stopped = false;
let notifyInput = null;
const queue = [];
async function* inputStream() {
  while (!stopped) {
    if (queue.length > 0) { yield queue.shift(); continue; }
    const item = await new Promise((r) => { notifyInput = r; });
    notifyInput = null;
    if (item === null) return;
    yield item;
  }
}

const events = [];
const marks = { sent: null, blockStart: null, blockStop: null, assistant: null, result: null };

/** 只记与"参数生成那一段"有关的帧，别的只计数。 */
function onEvent(msg) {
  events.push(msg);
  if (msg.type === 'stream_event') {
    const inner = msg.event ?? {};
    const kind = inner.type;
    if (kind === 'content_block_start') {
      const b = inner.content_block ?? {};
      // **这条就是全部要害**：有没有这一帧、带不带 name
      console.log(`  ${ts()} content_block_start type=${b.type} name=${b.name ?? '（没有）'} id=${b.id ?? '-'}`);
      if (b.type === 'tool_use' && marks.blockStart === null) marks.blockStart = Date.now();
      return;
    }
    if (kind === 'content_block_stop') {
      console.log(`  ${ts()} content_block_stop index=${inner.index}`);
      if (marks.blockStart !== null && marks.blockStop === null) marks.blockStop = Date.now();
      return;
    }
    if (kind === 'content_block_delta') {
      const d = inner.delta ?? {};
      // 只报工具参数那种：它有多少帧、每帧多大，直接决定那段窗口有多长
      if (d.type === 'input_json_delta') {
        if (marks.deltaCount === undefined) {
          marks.deltaCount = 0;
          console.log(`  ${ts()} input_json_delta 开始（第一帧 ${(d.partial_json ?? '').length} 字符）`);
        }
        marks.deltaCount++;
        marks.deltaChars = (marks.deltaChars ?? 0) + (d.partial_json ?? '').length;
      }
      return;
    }
    console.log(`  ${ts()} stream_event/${kind}`);
    return;
  }

  if (msg.type === 'assistant') {
    const tools = (msg.message?.content ?? []).filter((b) => b.type === 'tool_use').map((b) => b.name);
    if (tools.length > 0 && marks.assistant === null) {
      marks.assistant = Date.now();
      console.log(`  ${ts()} assistant（完整消息，含 tool_use: ${tools.join(',')}）`);
    }
    return;
  }

  if (msg.type === 'user') {
    const blocks = Array.isArray(msg.message?.content) ? msg.message.content : [];
    if (blocks.some((b) => b?.type === 'tool_result')) {
      console.log(`  ${ts()} tool_result 到了 —— 卡片这时才会变成完成态`);
    }
    return;
  }

  if (msg.type === 'result') {
    marks.result = Date.now();
    console.log(`  ${ts()} result`);
  }
}

const cwd = workDir;
console.log(`工作目录：${workDir}`);
console.log(`目标行数：${LINES}\n`);

const q = query({
  prompt: inputStream(),
  options: {
    cwd,
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: true,
    canUseTool: (name, input) => Promise.resolve(
      (name === 'Write' || name === 'Edit') && String(input?.file_path ?? '').startsWith(workDir)
        ? { behavior: 'allow', updatedInput: input }
        : { behavior: 'deny', message: '探针：只放行临时目录里的写' },
    ),
  },
});

(async () => {
  try { for await (const msg of q) onEvent(msg); } catch { /* 收尾时断开属预期 */ }
})();

const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  process.exit(2);
}, 300000);

let verdict = 1;

try {
  console.log(`=== 发一条 Write 请求（正文约 ${LINES} 行）===`);
  marks.sent = Date.now();
  queue.push({
    type: 'user',
    message: {
      role: 'user',
      content: `请用 Write 工具写文件 ${TARGET}，内容恰好是 ${LINES} 行，` +
        `每行形如「第 N 行：这是一些占位文字，用来把内容撑长一点」。写完只回复 OK`,
    },
    parent_tool_use_id: null,
  });
  notifyInput?.('go');

  const deadline = Date.now() + 180000;
  while (Date.now() < deadline && marks.result === null) await sleep(200);
  await sleep(1000);

  console.log('\n--- 时间线 ---');
  const span = (a, b) => (a === null || b === null ? '（缺）' : `${b - a}ms`);
  console.log(`  发出请求 → content_block_start      ${span(marks.sent, marks.blockStart)}`);
  console.log(`  content_block_start → 完整消息       ${span(marks.blockStart, marks.assistant)}   ← **静默窗口**`);
  console.log(`  完整消息 → tool_result               ${span(marks.assistant, marks.result)}`);
  if (marks.deltaCount !== undefined) {
    console.log(`  参数增量帧数 ${marks.deltaCount}，共 ${marks.deltaChars} 字符`);
  }
  const wrote = existsSync(TARGET);
  if (wrote) {
    const lines = readFileSync(TARGET, 'utf8').split('\n').filter((l) => l !== '');
    console.log(`  文件实际写出来了：${lines.length} 行`);
  }

  console.log('\n--- 结论 ---');
  if (marks.blockStart === null) {
    console.log('  ✗ 没有收到带 tool_use 的 content_block_start ——');
    console.log('    那个"提前改状态卡"的改动**是空操作**，得另找信号（或改回不做）');
    verdict = 1;
  } else {
    console.log(`  ✓ 收到了，且带着工具名 —— 那个改动有效`);
    console.log(`  静默窗口 ${span(marks.blockStart, marks.assistant)}：`);
    console.log(`    ${marks.assistant - marks.blockStart > 3000 ? '值得为它做点什么' : '短，那个改动属于锦上添花'}`);
    verdict = 0;
  }
} catch (err) {
  console.log(`\n!! 探针失败：${err?.message ?? err}`);
  verdict = 3;
}

clearTimeout(watchdog);
stopped = true;
notifyInput?.(null);
console.log(`\n临时目录（不删）：${workDir}`);
setTimeout(() => process.exit(verdict), 1500);
