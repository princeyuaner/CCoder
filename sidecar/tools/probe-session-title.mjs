/**
 * 探针：会话的名字到底由什么决定 —— 第一条消息，还是**最后**一条。
 *
 * 起因：用户问「现在会话的名字是按最后发送的聊天文本命名吗？」
 *
 * 插件这一侧的规则是明确的（`SessionSwitchState.sessionLabelTitle`）：
 * `customTitle` > `summary` > `firstPrompt`，而**我们自己只在一个时刻写名字** ——
 * 这条会话的第一条消息（`titleFromFirstMessage`，斜杠命令不算、已有标题不算）。
 * 其余时候标题都是"从 SessionInfo 读的"，而其中的 `summary` **是 CLI 给的**。
 *
 * 所以"名字跟着最后一条变"这种观感，只可能来自 **CLI 在重新生成 summary**。
 * 本探针就是来验这一条的：同一个会话里说两件**话题完全不同**的事，
 * 各看一次 listSessions，比较 summary / firstPrompt 变没变。
 *
 * 走生产路径：选项照抄 session.js，环境过 env.js 的清洗。
 * **SDK 的 cwd 是临时目录** —— 每个临时目录是一个独立 project，互不干扰。
 *
 * 用法（cwd 必须是仓库根）：
 *
 *   node sidecar/tools/probe-session-title.mjs
 *
 * 退出码：0 = 假设成立（summary 跟着最近的内容变）；
 *         1 = summary 没变（那用户看到的"改名"来自别处）；
 *         2 = 全局超时；3 = 跑挂了。
 */
import { query, listSessions } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const workDir = mkdtempSync(join(tmpdir(), 'ccoder-title-'));

const t0 = Date.now();
const ts = () => `+${String(Date.now() - t0).padStart(6)}ms`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
const q = query({
  prompt: inputStream(),
  options: {
    cwd: workDir,
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: false,   // 这一问只看回合结果，不看增量
    canUseTool: () => Promise.resolve({ behavior: 'deny', message: '探针：不执行任何工具' }),
  },
});
(async () => {
  try { for await (const msg of q) events.push(msg); } catch { /* 收尾时断开属预期 */ }
})();

/** 发一条，等它跑完。 */
async function say(text, label) {
  const from = events.length;
  console.log(`\n${ts()} >>> ${label}：${JSON.stringify(text.slice(0, 40))}`);
  queue.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null });
  notifyInput?.('go');
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    if (events.slice(from).some((m) => m.type === 'result')) return;
    await sleep(200);
  }
  throw new Error(`等不到 result：${label}`);
}

/** 我们那个会话在列表里长什么样。 */
async function look(label) {
  const sessions = await listSessions({ dir: workDir, limit: 50, offset: 0 });
  const mine = (sessions ?? [])[0];
  if (!mine) {
    console.log(`  ${ts()} ${label}：列表里没有这个会话`);
    return null;
  }
  console.log(`  ${ts()} ${label}`);
  console.log(`      summary     = ${JSON.stringify(mine.summary ?? null)}`);
  console.log(`      firstPrompt = ${JSON.stringify(mine.firstPrompt ?? null)}`);
  console.log(`      customTitle = ${JSON.stringify(mine.customTitle ?? null)}`);
  return mine;
}

const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  process.exit(2);
}, 300000);

let verdict = 1;

try {
  console.log(`工作目录：${workDir}`);

  await say('我们聊第一件事：这个项目的构建脚本放在哪？一句话回答就行。', '第一条（话题 A）');
  const first = await look('第一条之后的列表');

  await say(
    '换个完全不相干的话题：番茄炒蛋应该先炒蛋还是先炒番茄？一句话回答就行。',
    '第二条（话题 B，与 A 无关）',
  );
  const second = await look('第二条之后的列表');

  console.log('\n--- 结论 ---');
  if (!first || !second) {
    console.log('  ✗ 拿不到会话，未验证');
    verdict = 1;
  } else {
    const summaryChanged = first.summary !== second.summary;
    const promptChanged = first.firstPrompt !== second.firstPrompt;
    console.log(`  summary     ${summaryChanged ? '**变了**' : '没变'}`);
    console.log(`  firstPrompt ${promptChanged ? '**变了**' : '没变'}`);
    if (summaryChanged) {
      console.log('  → 名字跟着**最近的内容**走：是 CLI 在重新生成 summary，不是插件改的标签');
      console.log('    （插件只在第一条消息时写名字，之后只在改名 / 切换 / 恢复时从 SessionInfo 读）');
      verdict = 0;
    } else {
      console.log('  → summary 没变。用户看到的"改名"另有来源，得再看别处');
      verdict = 1;
    }
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
