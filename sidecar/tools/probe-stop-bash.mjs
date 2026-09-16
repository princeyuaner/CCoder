/**
 * 探针：**正在跑的 Bash 命令，怎么让它停下来** —— 两半各量一次。
 *
 * 起因（2026-09-16）：`stopTask` / `backgroundTasks` 这两条能不能用来停 bash。
 * SDK 的原话很明确 —— `backgroundTasks` 是"推到后台"（Ctrl+B 语义，回合继续），
 * `stopTask` 停的是**后台任务**（id 来自 `task_notification`）。但 CLI 里还有一处
 * 只对这个探针有意义的东西：
 *
 *   `backgroundedByTurnAbort` —— "@internal True if a plugin's turn abort moved
 *    the running command to the background"
 *
 * 也就是说**宿主触发的中止**可能不是"杀掉"而是"挪到后台"。这决定我们那个「停止」
 * 按钮的真实语义，光读字符串不敢下结论。
 *
 * 两半（**一次运行只做一个**，跑两次）：
 *   `background` 后台 Bash（`run_in_background`）→ 等事件里的 task id → `stopTask(id)`
 *   `foreground` 前台 Bash → `interrupt()`（就是我们「停止」按钮走的那条）
 *
 * 为什么强制分开跑：合在一个进程里跑过一次，模型回应慢的时候 Phase B 迟到的
 * 命令写进了 Phase A 的心跳文件 —— 数字就不知道是谁的了。
 *
 * ## 怎么保证确定
 *
 * 不靠模型听话：`canUseTool` 回 `{behavior:'allow', updatedInput:{…}}` 把它请求的
 * 命令**换成探针自己那条**（SDK 允许改写入参，见 sdk.d.ts:2340/2351）。
 *
 * 等的是**心跳真的开始跳**（不是"事件里看见 tool_use"）：后者依赖模型的响应速度，
 * 实测十几秒到一分钟以上都有，窗口一开就可能错过（第一版就是这么误判的）。
 *
 * 心跳命令**自限时**（最多两分钟，自己会死）—— 万一被挪到后台又没停掉，也不会
 * 在你机器上留一个 `while true`。
 *
 * ## 判据
 *
 * 命令每写一行时间戳到文件；看它**还在不在长**。停住 = 死了，继续长 = 活着。
 *
 * 花钱与副作用：**每次运行一次模型调用**（一句短提示），外加在机器上跑一条写文件
 * 的 `date >> file` 循环。没有别的。
 *
 * 用法：
 *   cd <repo root>
 *   node sidecar/tools/probe-stop-bash.mjs foreground
 *   node sidecar/tools/probe-stop-bash.mjs background
 *
 * ---- 实测（2026-09-16，claude.exe v1.2.3）----
 *
 * **前台那半：量死了。** 三次运行一致 —— `interrupt()` 之后心跳立刻停住
 * （采样 3,3,3,3,… 动都不动），事件里是 `result/error_during_execution`。
 * 也就是说：**我们那个「停止」按钮真的会杀掉正在跑的 Bash**，不会把它留在
 * 后台偷偷跑完。CLI 里那个 `backgroundedByTurnAbort` 字段至少在这个场景下
 * 没起作用。
 *
 * **后台那半：没测出来 —— 但不是因为 CLI 不支持，是探针自己瞎了。**
 * 两次 `background` 运行里，命令**确实起来了**（心跳 0→3），可探针
 * **一条消息都没收到**：没有 `tool_use`、没有 `task_notification`、
 * 没有 `background_tasks_changed`，连落盘的 jsonl 都没生成。拿不到 task id，
 * `stopTask` 那一步自然没跑。
 *
 * 对照：同一天、同一套消费写法，`probe-init-before-send.mjs` 收得到事件
 * （`system/init` / `assistant` / `result` 一个不少）。两条探针的差别只在于
 * **这条用了 `canUseTool` 并回 `updatedInput`**（那条没有）—— 但插件本身也一直
 * 在用 `canUseTool`，而它的转写区是有输出的，所以"canUseTool 让流变哑"这个解释
 * 站不住。
 *
 * **下次怎么查（最短路径）**：加一个"不换命令"的对照组 —— `canUseTool` 照常
 * allow、但**不动** `updatedInput`，其余不变。一次调用就能分辨是那条路的问题，
 * 还是这个探针别处写歪了。这次的诊断手段（实时打印 + 原始消息落盘）都留在代码里，
 * 一跑就有铁证。
 *
 * **顺带一条与本探针无关的发现**：仓库里**没有**真实会话的 `task_notification` /
 * `background_tasks_changed` 样本 —— `RunStatusTracker` 里那套后台任务计数是照
 * `sdk.d.ts` 写的，**没被真实数据验证过**。状态卡上"后台任务"那一格，可能一直是空的。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { readFileSync, existsSync, unlinkSync, appendFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildChildEnv } from '../env.js';

const MAX_LOOPS = 120; // 自限时：最多两分钟，自己会死
const STARTED = Date.now();
const EVENT_LOG = [];
const events = [];

/** 建过的所有心跳文件 —— 收尾时逐个删，别在机器上留垃圾。 */
const createdHeartbeats = [];
/** 原始消息落盘的位置（诊断用）。**不删** —— 它是证据，路径会在开头打出来。 */
const dumpPath = join(tmpdir(), `ccoder-probe-stopevents-${Date.now()}.jsonl`);
/** 当前这一半实验在写哪个文件（两半各用一个，免得心跳混在一起数）。 */
let heartbeatPath = newHeartbeat();
/** 由 Phase 决定：这段 Bash 是前台还是后台。 */
let RUN_IN_BACKGROUND = false;

function newHeartbeat() {
  const p = join(tmpdir(), `ccoder-probe-heartbeat-${Date.now()}-${Math.random().toString(36).slice(2, 6)}.txt`)
    .replace(/\\/g, '/'); // bash 认 `C:/…` 这种写法
  createdHeartbeats.push(p);
  return p;
}

function beats(path = heartbeatPath) {
  return existsSync(path) ? readFileSync(path, 'utf8').trim().split('\n').filter(Boolean).length : 0;
}

function makeInputStream() {
  let stopped = false;
  let notifyInput = null;
  const queue = [];
  async function* stream() {
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
  return {
    stream: stream(),
    send: (text) => {
      queue.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null });
      notifyInput?.('go');
    },
    stop: () => {
      stopped = true;
      notifyInput?.(null);
    },
  };
}

const settle = (ms) => new Promise((r) => setTimeout(r, ms));

const input = makeInputStream();
const q = query({
  prompt: input.stream,
  options: {
    cwd: process.cwd(),
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: true,
    canUseTool: (toolName) => {
      if (toolName !== 'Bash') {
        return Promise.resolve({ behavior: 'deny', message: '探针：只放 Bash，且命令会被换掉' });
      }
      // **换掉命令**：不管模型想跑什么，跑的都是我们这条自限时心跳
      return Promise.resolve({
        behavior: 'allow',
        updatedInput: {
          command: `for i in $(seq 1 ${MAX_LOOPS}); do date +%s >> "${heartbeatPath}"; sleep 1; done`,
          description: '探针心跳（自限时两分钟）',
          run_in_background: RUN_IN_BACKGROUND,
        },
      });
    },
  },
});

const consumer = (async () => {
  try {
    for await (const m of q) {
      events.push(m);
      // 诊断：每个消息都留一条痕（合成事件那层也要看得见，别只记组装后的），
      // 并且**实时**打出来 —— 只在收尾时打印的话，"事件一个都没有"到底是
      // 模型慢、CLI 卡住、还是我们没消费，全看不出来（上一版就是这么瞎的）
      if (m.type !== 'stream_event') {
        const t = ((Date.now() - STARTED) / 1000).toFixed(1);
        EVENT_LOG.push(`+${t}s RAW ${m.type}/${m.subtype ?? ''}`);
        console.log(`      [事件 +${t}s] ${m.type}/${m.subtype ?? ''}`);
        // **落盘**。控制台里"一个事件都没有"这件事已经骗过我两次：到底是没发、
        // 还是被谁吞了，光看结尾打印分不清。原始消息按行写下来，回头能逐个查
        try {
          appendFileSync(dumpPath, `${JSON.stringify({ at: t, ...m })}\n`);
        } catch { /* 落盘失败不该影响实验 */ }
      }
      if (m.type === 'stream_event') continue;
      if (m.type === 'assistant') {
        for (const b of m.message?.content ?? []) {
          if (b.type === 'tool_use') EVENT_LOG.push(`tool_use:${b.name}`);
        }
        continue;
      }
      const extra = m.subtype === 'task_notification'
        ? ` status=${m.status} task_id=${String(m.task_id ?? '').slice(0, 14)}`
        : m.subtype === 'background_tasks_changed'
          ? ` tasks=${JSON.stringify(m.tasks ?? []).slice(0, 240)}`
          : '';
      EVENT_LOG.push(`${m.type}/${m.subtype ?? ''}${extra}`);
    }
  } catch (err) {
    // 收尾时断开属预期，但**别的错不能吞** —— 吞掉就变成"事件一个都没有"这种
    // 谁也看不懂的结果（第一版就是这么栽的）
    EVENT_LOG.push(`consumer 中断：${err?.message ?? err}`);
  }
});

async function waitForBash(ms = 30_000) {
  const t = Date.now();
  while (Date.now() - t < ms && !EVENT_LOG.slice(-40).some((l) => l.startsWith('tool_use:Bash'))) await settle(250);
  return EVENT_LOG.slice(-40).some((l) => l.startsWith('tool_use:Bash'));
}

/** 从事件里翻 task id —— `background_tasks_changed` 带全量，`task_notification` 带单个。 */
function findTaskId(since) {
  let id = null;
  for (const m of events.slice(since)) {
    if (m.task_id) id = m.task_id;
    if (Array.isArray(m.tasks)) for (const t of m.tasks) id = t.id ?? t.task_id ?? id;
  }
  return id;
}

await settle(400);

// **一次运行只做一个实验。** 两半合在一起跑过一次，结果是灾难：模型回应慢时，
// Phase B 迟到的命令写进了 Phase A 的文件，"中断前心跳 21 条"这种数字就不知道
// 是谁的。分开跑，每个进程只有一条命令、一个文件。
const PHASE = (process.argv[2] ?? 'foreground').toLowerCase();
if (!['foreground', 'background'].includes(PHASE)) {
  console.log(`用法：node sidecar/tools/probe-stop-bash.mjs [foreground|background]`);
  process.exit(2);
}
RUN_IN_BACKGROUND = PHASE === 'background';
console.log(`=== ${PHASE === 'background' ? 'Phase B：后台 Bash + stopTask' : 'Phase A：前台 Bash + interrupt'} ===\n`);
console.log(`心跳文件：${heartbeatPath}`);
console.log(`原始消息落盘：${dumpPath}`);

input.send(PHASE === 'background'
  ? '请用 Bash 工具跑一条命令（跑久一点，放后台也行）。'
  : '请用 Bash 工具跑一条命令（跑久一点，前台跑，别放后台）。');

// 判据是**心跳真的开始跳**，不是"事件里看见了 tool_use"：
// 后者依赖模型的响应速度（实测 16 秒到一分钟以上都有），窗口一开就可能错过。
const started = Date.now();
let before = 0;
while (Date.now() - started < 150_000) {
  await settle(500);
  before = beats();
  if (before >= 3) break; // 攒够三条：确认它是一条在跑的命令，不是启动瞬间
  if ((Date.now() - started) % 15_000 < 500) {
    console.log(`  …等了 ${Math.round((Date.now() - started) / 1000)}s（心跳 ${before} 条；模型还没把命令跑起来）`);
  }
}
console.log(`  命令跑起来了：心跳 ${before} 条（等了 ${Math.round((Date.now() - started) / 1000)}s）`);
if (before < 3) {
  console.log('  ⚠ 等到超时也没跳够三条 —— 下面的结论不作数（模型可能没照做）');
  console.log(`  事件：\n    ${EVENT_LOG.join('\n    ') || '（无）'}`);
}

if (PHASE === 'background') {
  await settle(2000); // 让任务注册、事件到达
  console.log(`  事件：\n    ${EVENT_LOG.join('\n    ') || '（无）'}`);

  let taskId = findTaskId(0);
  if (!taskId) {
    // tool_result 的正文里也可能写着 id（CLI 常写 "Command running in background with ID: xxx"）
    const blob = JSON.stringify(events.map((m) => m.message?.content).filter(Boolean));
    const hit = /(?:ID|id)["':\s]+([a-zA-Z0-9_-]{6,})/.exec(blob);
    if (hit) {
      taskId = hit[1];
      console.log(`  （从正文里抠出 id：${taskId}）`);
    }
  }

  if (!taskId) {
    console.log('  ⚠ **拿不到 task id** —— 事件里既没有 task_notification 也没有');
    console.log('    background_tasks_changed 带 id。这本身就是结论：这条路在我们这个');
    console.log('    消费方式下没有 id 可用，"每个任务单独停"得另找来源。');
  } else {
    try {
      await q.stopTask(taskId);
      console.log(`  → stopTask(${String(taskId).slice(0, 14)}…) 调用成功`);
    } catch (err) {
      console.log(`  → stopTask 抛错：${err?.message ?? err}`);
    }
    await settle(3000);
    const after = beats();
    console.log(`  停止后心跳：${after} 条`);
    console.log(`  → ${after <= before + 1 ? '**停掉了**（stopTask 有效）' : '还在跑 —— stopTask 没起作用'}`);
  }
} else {
  const mark = events.length;
  await q.interrupt();
  console.log('  → 已 interrupt()');

  const samples = [];
  for (let i = 0; i < 16; i++) {
    await settle(500);
    samples.push(beats());
  }
  const after = samples[samples.length - 1];
  console.log(`  中断后心跳：${after} 条（采样 ${samples.join(',')}）`);
  console.log(`  → ${after > before + 1 ? '**命令没被杀，还在跑**（= 挪到了后台）' : '命令被杀了（心跳停住）'}`);
  console.log(`  中断后的事件：\n    ${EVENT_LOG.slice(-8).join('\n    ') || '（无）'}`);
  const orphan = findTaskId(mark);
  console.log(`  中断后有没有冒出新 task id：${orphan ? String(orphan).slice(0, 14) : '没有'}`);
}

console.log('\n--- 收尾 ---');
input.stop();
try { await q.interrupt?.(); } catch { /* 没有回合在跑时属预期 */ }
for (const p of createdHeartbeats) {
  try { unlinkSync(p); } catch { /* 没生成就算了 */ }
}
setTimeout(() => process.exit(0), 1500);
