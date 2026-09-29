/**
 * 探针：`@目录` 到底展开不展开。
 *
 * 起因：`AddFileToChat.kt` 里有一句写死的判据 ——
 *
 *     「目录一律滤掉：`@` 认的是文件，给过去一个目录名 CLI 展开不出东西。」
 *
 * 那句话**没有被量过**：2026-09-14 那次实测（纯路径展开、0 次工具调用）只覆盖了文件。
 * 用户要的是"目录树里右键文件夹也能加进聊天框"，所以先把这条判据量清楚。
 *
 * ## 怎么量
 *
 * 展开发生在 CLI 侧、不产生工具调用 —— 所以**看工具调用**就是判据：
 *
 *   - 问了"这目录里有什么"，**一次工具都没调**却答对了 → 展开过了；
 *   - 调了 Read/LS/Glob 才答上来 → 没展开，模型是自己去翻的。
 *
 * 三个阶段，第一个是**对照**（已知会展开的文件，2026-09-14 实测 0 次调用），
 * 免得把"模型今天懒得调工具"误读成"展开成功"。
 *
 * 认证走 sidecar 自己那套（`buildChildEnv`，同 probe-mcp-hooks/controls）——
 * 直接敲 `claude` 在这个壳里是 "Not logged in"。
 *
 * 用法（cwd 必须是仓库根）：
 *
 *   node sidecar/tools/probe-mention-dir.mjs
 *
 * 退出码：0 = `@目录` 展开了（右键加文件夹这条路走得通）；1 = 没展开；2 = 超时。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const workDir = mkdtempSync(join(tmpdir(), 'ccoder-mention-'));
mkdirSync(join(workDir, 'sub'));
writeFileSync(join(workDir, 'sub', 'a.txt'), 'hello-from-a\n');
writeFileSync(join(workDir, 'sub', 'b.txt'), 'hello-from-b\n');
writeFileSync(join(workDir, 'root.txt'), 'root-file-here\n');

const t0 = Date.now();
const ts = () => `+${String(Date.now() - t0).padStart(6)}ms`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
    send(text) { queue.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null }); notifyInput?.('go'); },
    stop() { stopped = true; notifyInput?.(null); },
  };
}

/** 开一个会话，把命令行喂进去，收回来的东西：最终答复 + 用过的工具。 */
async function ask(prompt) {
  const input = makeInputStream();
  const events = [];
  const q = query({
    prompt: input.stream,
    options: {
      cwd: workDir,
      permissionMode: 'default',
      env: buildChildEnv(process.env),
      includePartialMessages: false,
      // 想读就读（我们要看的就是"它需不需要读"）；写一律不许
      canUseTool: (name) => Promise.resolve(
        name === 'Read' || name === 'LS' || name === 'Glob' || name === 'Grep' || name === 'Bash'
          ? { behavior: 'allow', updatedInput: undefined }
          : { behavior: 'deny', message: '探针：不发写操作' },
      ),
    },
  });
  (async () => {
    try { for await (const m of q) events.push(m); } catch { /* 收尾时断开属预期 */ }
  })();

  await sleep(300);
  input.send(prompt);
  // 等 result（一轮结束），最多 90 秒
  const deadline = Date.now() + 90000;
  while (Date.now() < deadline && !events.some((e) => e?.type === 'result')) await sleep(200);

  input.stop();
  try { await q.interrupt?.(); } catch { /* 预期 */ }

  const tools = [];
  for (const e of events) {
    if (e?.type !== 'assistant') continue;
    for (const block of e.message?.content ?? []) {
      if (block?.type === 'tool_use') tools.push(`${block.name}(${JSON.stringify(block.input ?? {}).slice(0, 80)})`);
    }
  }
  const result = events.find((e) => e?.type === 'result');
  return { tools, answer: String(result?.result ?? '').trim().slice(0, 200), isError: result?.is_error === true };
}

const findings = [];
const note = (label, text) => { findings.push(`  ${label}: ${text}`); console.log(`  ${ts()} → ${label}: ${text}`); };

let verdict = 1;
const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  console.log(`临时目录：${workDir}`);
  process.exit(2);
}, 420000);

try {
  console.log(`工作目录：${workDir}`);

  // ---- 对照：文件（2026-09-14 实测会展开，0 次工具调用）----
  console.log('\n=== 对照：@root.txt（已知会展开的文件）===');
  const file = await ask('@root.txt 这个文件里写了什么？只回内容本身，别调工具。');
  console.log(`  ${ts()} 用了 ${file.tools.length} 次工具：${file.tools.join(' , ') || '（一次都没有）'}`);
  console.log(`  ${ts()} 答复：${file.answer}`);
  note('对照', file.tools.length === 0 ? '0 次工具（与 2026-09-14 一致，仪器可信）' : `**${file.tools.length} 次工具 —— 仪器这次不灵，后面两阶段不能作数**`);

  // ---- A：@目录（不带斜杠）----
  console.log('\n=== A：@sub（不带尾斜杠）===');
  const bare = await ask('@sub 这个目录里有哪些文件？只列文件名，别调工具。');
  console.log(`  ${ts()} 用了 ${bare.tools.length} 次工具：${bare.tools.join(' , ') || '（一次都没有）'}`);
  console.log(`  ${ts()} 答复：${bare.answer}`);
  const bareOk = bare.tools.length === 0 && /a\.txt/.test(bare.answer);
  note('A', bareOk
    ? '**0 次调用且答对了 → `@目录` 展开了**'
    : `没展开（${bare.tools.length} 次工具；答复里${/a\.txt/.test(bare.answer) ? '有' : '没有'}文件名）`);

  // ---- B：@目录/（CLI 自己的补全插的就是这个形状）----
  console.log('\n=== B：@sub/（CLI 补全插的带尾斜杠那种）===');
  const slash = await ask('@sub/ 这个目录里有哪些文件？只列文件名，别调工具。');
  console.log(`  ${ts()} 用了 ${slash.tools.length} 次工具：${slash.tools.join(' , ') || '（一次都没有）'}`);
  console.log(`  ${ts()} 答复：${slash.answer}`);
  const slashOk = slash.tools.length === 0 && /a\.txt/.test(slash.answer);
  note('B', slashOk
    ? '**0 次调用且答对了 → 带尾斜杠也展开**'
    : `没展开（${slash.tools.length} 次工具；答复里${/a\.txt/.test(slash.answer) ? '有' : '没有'}文件名）`);

  verdict = bareOk || slashOk ? 0 : 1;
} catch (err) {
  console.log(`\n提前收场：${err?.message ?? err}`);
} finally {
  clearTimeout(watchdog);
  console.log('\n================ 结论 ================');
  findings.forEach((f) => console.log(f));
  console.log(`\n退出码 ${verdict}：${verdict === 0 ? '右键加文件夹走得通' : '`@目录` 不展开 —— 右键加文件夹得换个做法'}`);
  console.log(`临时目录（**没有删**）：${workDir}`);
  process.exit(verdict);
}
