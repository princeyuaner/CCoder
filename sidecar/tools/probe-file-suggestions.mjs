/**
 * 探针：`file_suggestions` —— CLI 的文件补全，**能不能用来替掉我们自己的 `@` 补全**。
 *
 * 起因（2026-09-16）：插件的 `@` 补全现在自己供数 —— `collectProjectFiles()` 走
 * IDE 的 ProjectFileIndex 拿全量路径，`fileCandidates()` 做**前缀**匹配（限 50 条）。
 * 而 CLI 那边宣传的是 "the same fuzzy-matched results the TUI shows"。
 *
 * 要回答的其实只有一句：**这条控制请求在 SDK 子进程里稳定可用吗**。
 * 补全是每敲一个字符都要问一次的，时有时无就等于不能用。
 *
 * 三件配套的事：
 *   - SDK **没有**给它便捷方法（`sdk.mjs` 里 `file_suggestions` 出现 0 次），
 *     只有未声明的内部入口 `q.request({subtype,…})`。用不用它，先看它在不在。
 *   - 索引由 CLI 后台建（二进制：`[FileIndex] git ls-files: N tracked files`、
 *     `startBackgroundCacheRefresh`、`skipped index rebuild — tracked paths unchanged`），
 *     所以要**轮询**看它何时可用，而不是问一次就下结论。
 *   - 排除规则、模糊程度、耗时 —— 决定换不换的三个数字。
 *
 * 二进制里另有一处要注意：`file_suggestions is not supported in this context
 * (onFileSuggestions callback not registered)` —— 那是**宿主回调**那条路
 * （远程/瘦客户端反过来问宿主）。别把两条路搞混。
 *
 * **不发消息**：全是控制请求，零模型调用。选项照抄 session.js。
 *
 * 用法：
 *   cd <repo root>
 *   node sidecar/tools/probe-file-suggestions.mjs
 *
 * ---- 实测结论（2026-09-16，claude.exe v1.2.3）：**不换** ----
 *
 * | 维度 | CLI 这份 | 我们现在这份（ProjectFileIndex + 前缀匹配） |
 * |---|---|---|
 * | 可用时机 | 前 **1363ms 回空**，且期间结果条数跳变 2 次（索引后台建） | 一打开就有（内存缓存） |
 * | 速度 | 3~6ms/次 | 同步，微秒级 |
 * | 噪声 | **倒出 `build/`、`web/node_modules/`** —— 而这两条在 .gitignore 第 3、16 行里写着，`git check-ignore` 也认 | 排除规则来自 IDE，这两处本来就不在 |
 * | 条数上限 | **15** | 50 |
 * | 匹配 | **模糊/子串**（`Candidates` 命中 8 条）—— 它唯一比我们强的地方 | 仅前缀（路径或文件名） |
 * | 路径分隔符 | **混着反斜杠**（`src/main/kotlin/com/ccoder\`） | 一律正斜杠 |
 * | SDK 入口 | **未声明**的内部 `q.request()` | — |
 *
 * 补全的候选列表要"每敲一个字"就换一次：前 1.4 秒空着、之后又混进 build 产物，
 * 这个体验不如现在。**唯一值得偷的是模糊匹配**（见 [fileCandidates] 的 prefix 规则）。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';

const cwd = process.cwd();
const CALL_TIMEOUT_MS = 10_000;
const POLL_MS = 500;
const POLL_TOTAL_MS = 15_000;

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

function withTimeout(label, promise, ms = CALL_TIMEOUT_MS) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} 超时（${ms}ms）`)), ms)),
  ]);
}

const input = makeInputStream();
const q = query({
  prompt: input.stream,
  options: {
    cwd,
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: true,
    allowDangerouslySkipPermissions: true,
    canUseTool: () => Promise.resolve({ behavior: 'deny', message: '探针：不执行任何工具' }),
  },
});

(async () => {
  try {
    for await (const _ of q) { /* 事件流得有人消费，否则 query 不推进 */ }
  } catch { /* 收尾时断开属预期 */ }
})();

await new Promise((r) => setTimeout(r, 400));

console.log('=== 0. 内部入口在不在 ===');
console.log(`  typeof q.request = ${typeof q.request}`);

/** 问一次，回 { ms, paths } 或抛。 */
async function askOnce(queryText) {
  const t0 = Date.now();
  const v = await withTimeout('file_suggestions', q.request({ subtype: 'file_suggestions', query: queryText }));
  const ms = Date.now() - t0;
  const resp = v?.response ?? v;
  const paths = (resp?.suggestions ?? []).map((s) => s.path);
  return { ms, paths };
}

// ---- 1. 轮询：索引什么时候可用 ----
// 这条是整份探针的重点。"有时有、有时没有"的候选源对补全来说等于不能用，
// 所以必须看清楚它是"等几秒就好了"还是"看运气"。
console.log(`\n=== 1. 轮询 "CommandCandidates"（每 ${POLL_MS}ms 一次，共 ${POLL_TOTAL_MS / 1000} 秒）===`);
const probeQuery = 'CommandCandidates';
let firstHitAt = null;
let flips = 0;
let lastCount = null;
const t0 = Date.now();
while (Date.now() - t0 < POLL_TOTAL_MS) {
  let r;
  try {
    r = await askOnce(probeQuery);
  } catch (err) {
    console.log(`  ${String(Date.now() - t0).padStart(5)}ms  抛错：${err?.message ?? err}`);
    break;
  }
  if (lastCount !== null && r.paths.length !== lastCount) flips++;
  if (r.paths.length > 0 && firstHitAt === null) {
    firstHitAt = Date.now() - t0;
    console.log(`  ${String(firstHitAt).padStart(5)}ms  **首次命中** ${r.paths.length} 条：${JSON.stringify(r.paths.slice(0, 2))}`);
  }
  lastCount = r.paths.length;
  await new Promise((r) => setTimeout(r, POLL_MS));
}
console.log(`  结论：${firstHitAt === null
  ? '15 秒内**一次都没命中** —— 这条路在 SDK 子进程里拿不到索引'
  : `首次命中在 ${firstHitAt}ms，其间结果条数变了 ${flips} 次`}`);

// ---- 2. 稳定后的查询形态 ----
async function ask(label, queryText) {
  let r;
  try {
    r = await askOnce(queryText);
  } catch (err) {
    return console.log(`  ${label}（${JSON.stringify(queryText)}）→ 抛错：${err?.message ?? err}`);
  }
  console.log(`  ${label}（${JSON.stringify(queryText)}）→ ${r.ms}ms，${r.paths.length} 条  ${JSON.stringify(r.paths.slice(0, 3))}`);
}

console.log('\n=== 2. 查询形态（此刻的状态）===');
await ask('空查询', '');
await ask('文件名前缀', 'Composer');
await ask('子串（非前缀）', 'Candidates');
await ask('正斜杠路径', 'src/main/kotlin/com/ccoder');
await ask('不带目录的文件名', 'CommandCandidates');
await ask('子序列（模糊）', 'cmprk');
await ask('被排除的目录 build', 'build');
await ask('依赖目录 node_modules', 'node_modules');

console.log('\n--- 收尾 ---');
input.stop();
try { await q.interrupt?.(); } catch { /* 没有回合在跑时属预期 */ }
setTimeout(() => process.exit(0), 1000);
