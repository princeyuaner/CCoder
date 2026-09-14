/**
 * 探针：会话中途切到 bypassPermissions 靠什么放行。
 *
 * 「热切到底需不需要启动时就带开关」这一条曾经在 SDK 的 JS 里看不到结论
 * （校验在 CLI 二进制里，SDK 只是透传），所以旧实现只在"以绕过启动"时才带
 * 资格位 —— 结果是模式列表里那一项对别的会话永远切不过去，点了必吃一条
 * CLI 报错。本探针把两半摆在一起（2026-09-14，对应 claude.exe 2.1.268；
 * 与内嵌 JS 里的 `isBypassPermissionsModeAvailable` 算法对过账）：
 *
 *   A. 不带 allowDangerouslySkipPermissions → 被拒，报
 *      "…was not launched with --dangerously-skip-permissions"
 *   B. 带上（session.js 现在的做法）→ 切换成功
 *
 * 走的是生产路径：选项照抄 session.js，环境过 env.js 的清洗。
 * **不发送任何消息** —— 切模式是控制请求，不需要跑回合，也不产生模型调用。
 *
 * 用法：
 *   cd <repo root>
 *   node sidecar/tools/probe-bypass-switch.mjs
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';

const cwd = process.cwd();

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
    stop: () => {
      stopped = true;
      notifyInput?.(null);
    },
  };
}

function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} 超时（${ms}ms）`)), ms)),
  ]);
}

async function attempt(label, extraOptions) {
  console.log(`\n=== ${label} ===`);
  const input = makeInputStream();
  const q = query({
    prompt: input.stream,
    options: {
      cwd,
      permissionMode: 'default',
      env: buildChildEnv(process.env),
      includePartialMessages: true,
      canUseTool: () => Promise.resolve({ behavior: 'deny', message: '探针：不执行任何工具' }),
      ...extraOptions,
    },
  });
  // 事件流得有人消费，否则 query 对象不推进
  (async () => {
    try {
      for await (const _ of q) { /* 探针不看事件 */ }
    } catch { /* 收尾时流断开属预期 */ }
  })();

  let result;
  try {
    await withTimeout(q.setPermissionMode('bypassPermissions'), 30000, 'setPermissionMode');
    result = '切换成功';
  } catch (err) {
    result = `被拒：${err?.message ?? err}`;
  }
  console.log(`  → ${result}`);

  input.stop();
  try {
    await q.interrupt?.();
  } catch { /* 没有回合在跑时属预期 */ }
  return result;
}

const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  process.exit(2);
}, 120000);

const without = await attempt('A. 不带 allowDangerouslySkipPermissions（旧行为）', {});
const withFlag = await attempt('B. 带上它（session.js 现在的做法）', {
  allowDangerouslySkipPermissions: true,
});

console.log('\n--- 结论 ---');
console.log(`  A: ${without}`);
console.log(`  B: ${withFlag}`);
const ok = withFlag === '切换成功';
console.log(ok
  ? '  资格位是热切的必要条件，带上它就足以放行 ✓'
  : '  B 也没成功 —— 结论不成立，看上面的报错 ✗');

clearTimeout(watchdog);
setTimeout(() => process.exit(ok ? 0 : 1), 1000);
