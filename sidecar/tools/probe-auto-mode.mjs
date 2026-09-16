/**
 * 探针：权限模式里的 'auto' —— CLI 收不收，闸门拉下时是拒绝还是静默忽略。
 *
 * 起因（2026-09-16）：SDK 的 PermissionMode 有六个值（sdk.d.ts:2327 —
 * default / acceptEdits / bypassPermissions / plan / dontAsk / **auto**），
 * 插件的枚举只接了五个，'auto' 一直没有界面入口。接之前要量清楚两件事，
 * 都不是读类型定义能读出来的：
 *
 *   1. `setPermissionMode('auto')` 收不收 —— 界面那条路走的就是它
 *      （ComposerMode 的"5 个全列、点了直接生效"）；
 *   2. **闸门拉下时是拒绝还是静默忽略**。claude.exe 里那串字符串说明 auto
 *      不止一道闸门：`permissions.disableAutoMode`（restrictive 项，管理/项目
 *      设置都能设）、用户设置里的 `autoModeEnabled`（"Allow Auto mode"）、
 *      服务器端断路器（`tengu_auto_mode_config.enabled === "disabled"`）、
 *      还有 `Auto mode is unavailable for your plan`。
 *      若是"接受但忽略"，界面上那个标签就会撒谎 —— 那正是本仓库最不能忍的
 *      一类 bug（见 session.js 里 setEffort 那段注释）。所以宁可先量。
 *
 * 三问，**都不发消息**（切模式是控制请求，不需要跑回合，零模型调用）：
 *   A. 起会话时就带 permissionMode:'auto' —— 起得来吗
 *   B. 以 default 起、中途切 auto —— 成功还是被拒，报错原文
 *   C. 把闸门拉下（临时项目里 `permissions.disableAutoMode="disable"`）后重复 B
 *
 * 选项照抄 session.js（含那个资格位 allowDangerouslySkipPermissions ——
 * 它对 bypassPermissions 是必要条件，对 auto 是不是也一样，顺手量）。
 * 环境走 env.js 的清洗，测的是生产路径。
 *
 * 已知看不出来的：**生效模式**。init 系统消息里带 `permissionMode`
 * （sdk.d.ts 的 SDKSystemMessage），但 init 要等第一条消息才来
 * （probe-init-before-send.mjs 实测：什么都不发，15 秒零事件）。
 * 所以"控制请求返回成功"不等于"真的切过去了" —— 下面 C 会区分这两种可能。
 *
 * 用法：
 *   cd <repo root>
 *   node sidecar/tools/probe-auto-mode.mjs
 *
 * 实测结论（2026-09-16，claude.exe v1.2.3）：
 *   A 以 'auto' 起（正常项目）→ 起得来，零事件、零报错
 *   B 中途切 'auto'          → **成功**，而且 CLI 会吐一条 `system/status`，
 *                              里面带着 `permissionMode: 'auto'` —— 这是**不发消息
 *                              就能读回生效模式**的通道（本仓库从前没有用过它）
 *   C 闸门拉下后中途切       → 被拒："Cannot set permission mode to auto:
 *                              auto mode disabled by settings"。**不是静默忽略**，
 *                              这条报错可以直接端到用户面前
 *   D 闸门拉下、且以 'auto' 起 → 之后的 set auto 仍被拒；但如上所述，这推不出
 *                              启动期是否回落 —— 见文件末尾那段
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
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

function settle(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

/**
 * 起一个会话，把事件流里冒出来的东西收着（尤其是错误）。
 *
 * query() 本身可能同步抛（选项不合法那条路），所以调用方要包 try。
 */
function open({ permissionMode, dir }) {
  const input = makeInputStream();
  const events = [];
  const streamErrors = [];
  const q = query({
    prompt: input.stream,
    options: {
      cwd: dir,
      permissionMode,
      env: buildChildEnv(process.env),
      includePartialMessages: true,
      // session.js 一律带它（是"资格"不是"绕过本身"）
      allowDangerouslySkipPermissions: true,
      canUseTool: () => Promise.resolve({ behavior: 'deny', message: '探针：不执行任何工具' }),
    },
  });
  (async () => {
    try {
      for await (const m of q) {
        if (m.type === 'stream_event') continue;
        if (m.type === 'system' && m.subtype === 'init') {
          events.push(`init:permissionMode=${m.permissionMode}`);
        } else if (m.type === 'system' && m.subtype === 'status') {
          // 这个事件带可选的 permissionMode —— 若它是"切完就吐一条"，
          // 那就有一个**不用发消息**就能读回生效模式的通道。B 里它出现过。
          events.push(`status:permissionMode=${m.permissionMode} status=${m.status}`);
        } else {
          events.push(`${m.type}/${m.subtype ?? ''}`);
        }
      }
    } catch (err) {
      streamErrors.push(String(err?.message ?? err));
    }
  })();
  return { q, input, events, streamErrors };
}

async function attempt(label, { startMode, dir, doSwitch = true }) {
  console.log(`\n=== ${label} ===`);
  let h;
  try {
    h = open({ permissionMode: startMode, dir });
  } catch (err) {
    console.log(`  × query 起不来：${err?.message ?? err}`);
    return { result: 'query 起不来', detail: String(err?.message ?? err) };
  }
  await settle(1500); // 让流有口气把启动期的事件/错误吐出来
  let result = '（未切）';
  let detail = '';
  if (doSwitch) {
    try {
      await withTimeout(h.q.setPermissionMode('auto'), 30000, 'setPermissionMode');
      result = '切换成功';
    } catch (err) {
      result = '被拒';
      detail = String(err?.message ?? err);
    }
    console.log(`  → ${result}${detail ? `：${detail}` : ''}`);
  } else {
    console.log('  → 只看启动，不切模式');
  }
  await settle(1500);
  if (h.events.length > 0) console.log(`  事件：${h.events.slice(0, 4).join(' | ')}`);
  if (h.streamErrors.length > 0) console.log(`  流里的错：${h.streamErrors.join(' | ')}`);
  if (h.events.length === 0 && h.streamErrors.length === 0) {
    console.log('  事件：零（与 probe-init-before-send 一致 —— init 要等第一条消息）');
  }
  h.input.stop();
  try { await h.q.interrupt?.(); } catch { /* 没有回合在跑时属预期 */ }
  return { result, detail };
}

/** 造一个临时项目，里面只有一道拉下的闸门。 */
function gatedProject() {
  const dir = join(tmpdir(), `ccoder-probe-auto-${Date.now()}`);
  mkdirSync(join(dir, '.claude'), { recursive: true });
  writeFileSync(
    join(dir, '.claude', 'settings.json'),
    JSON.stringify({ permissions: { disableAutoMode: 'disable' } }, null, 2),
  );
  console.log(`（闸门项目：${dir}）`);
  return dir;
}

const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  process.exit(2);
}, 180000);

const a = await attempt("A. 起会话就带 'auto'（起点能不能立住）", {
  startMode: 'auto',
  dir: cwd,
  doSwitch: false,
});
const b = await attempt("B. 以 default 起、中途切 'auto'", { startMode: 'default', dir: cwd });
const gated = gatedProject();
const c = await attempt("C. 闸门拉下后中途切 'auto'", { startMode: 'default', dir: gated });
// D 量的是"闸门拉下时**以 auto 起会话**" —— 那正是我们设置里存模式的走法
// （index.js:132 `params.permissionMode ?? 'default'`）。若启动期静默回落成
// 别的模式，界面上的标签就是假的。再把 auto 切一次当探针：被拒说明此刻
// 会话并不在 auto；成功则说明启动那一下没被闸门拦。
const d = await attempt("D. 闸门拉下、且以 'auto' 起（起完再切一次 auto 当探针）", {
  startMode: 'auto',
  dir: gated,
});

console.log('\n--- 结论 ---');
console.log(`  A 以 auto 起：${a.result}${a.detail ? `（${a.detail}）` : ''}`);
console.log(`  B 热切 auto：${b.result}${b.detail ? `（${b.detail}）` : ''}`);
console.log(`  C 闸门下热切：${c.result}${c.detail ? `（${c.detail}）` : ''}`);
console.log(`  D 闸门下以 auto 起：${d.result}${d.detail ? `（${d.detail}）` : ''}`);
console.log('  D 的被拒**不能**推出"启动期回落了" —— 闸门也可能只是拒绝任何一次');
console.log('  set auto（当前模式是不是 auto 都一样）。要分清得读 init 的 permissionMode，');
console.log('  而那需要发一条消息（一次模型调用）。**这一条留给要接线的人判断**：');
console.log('  两边都是更保守的那一侧（回落只会变成 default，不会变成绕过），');
console.log('  所以标签万一是假的，方向是"说要自动、其实在问"，不是反过来的安全漏洞。');
console.log('  另：claude.exe 里只有一条 refuse+fallback 的字符串，是给 bypassPermissions 的');
console.log('  （"Refusing restored mode \'bypassPermissions\' … falling back to \'default\'"），');
console.log('  auto 没有对应的一条 —— 启动期它怎么处理，二进制里没有话说。');
if (b.result === '切换成功' && c.result === '切换成功') {
  console.log('  闸门没能在控制请求这一层拦住 —— 是真放行还是静默忽略，');
  console.log('  要读 init 的 permissionMode 才能分（那需要发一条消息、产生一次模型调用）。');
} else if (b.result === '切换成功') {
  console.log('  闸门会在控制请求这一层拒绝 —— 界面上的报错就是它。');
}

clearTimeout(watchdog);
setTimeout(() => process.exit(0), 1000);
