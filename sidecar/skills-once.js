#!/usr/bin/env node
/**
 * 一次性技能枚举：起一个**用完就扔**的会话，问两条控制请求，打印一行 JSON 就退出。
 *
 * ## 为什么不让设置页去问正在跑的那个会话
 *
 * 技能页要回答的是"**新**会话会看到哪些技能"—— 而实测（`tools/probe-skills.mjs`）
 * 说得很清楚：`skillOverrides` 改完，**当前会话纹丝不动**，新会话才变。借正在跑的
 * 会话去读，读到的是"上一版设置的效果"，恰恰是这页要帮用户改掉的那份。
 *
 * ## 为什么不是第三条 NDJSON 通道
 *
 * 走 `index.js` 那套要立一个会话、认主人、退订、断开……设置页只问一次、问完就完。
 * 一次性进程把生命周期压成"起、问、印、退"，没有要归还的东西。
 *
 * ## `persistSession: false` 是硬要求
 *
 * 不带的后果不是"多一个文件"：插件自己的会话列表就是扫 `~/.claude/projects/` 出来的，
 * 每开一次设置就会多出一条**用户没开过**的会话 —— 那是把界面弄脏，比慢几秒严重。
 * （sdk.d.ts 对这个开关的原话："Useful for ephemeral or automated workflows"。）
 *
 * ## 输出（一行，stdout）
 *
 *   {"ok":true,"commands":[…],"skills":{totalSkills,includedSkills,tokens,skillFrontmatter[…]}}
 *   {"ok":false,"error":"…"}
 *
 * 失败也印 JSON（并退出码 1）：调用方拿一句话就能解释，不必去猜 stderr。
 * stderr 照旧随便写，那是给人看的。
 *
 * 用法：
 *   node skills-once.js --cwd <项目目录> [--claude <claude 路径，.cmd 垫片也行>]
 *
 * 不花 token（一条消息都不发），代价是等 CLI 起来 —— 实测 3～5 秒。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from './env.js';
import { resolveClaudePath } from './claude-path.js';

const TIMEOUT_MS = 60_000;

function argOf(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : null;
}

const cwd = argOf('--cwd');
const claudePath = argOf('--claude');

function emit(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  if (!cwd) {
    emit({ ok: false, error: 'missing --cwd' });
    process.exit(1);
  }

  let q = null;
  try {
    const options = {
      cwd,
      // 只读枚举：不落盘（见文件头）、不问权限（下面一律拒绝，反正一个工具都不会跑）
      persistSession: false,
      permissionMode: 'default',
      includePartialMessages: false,
      canUseTool: () => Promise.resolve({ behavior: 'deny', message: 'skills-once: 只枚举，不执行工具' }),
      env: buildChildEnv(process.env),
    };
    if (claudePath) {
      // `--claude` 是**检测到的那条路径**（这台机器上是 npm 的 `claude.cmd` 垫片），
      // 而 SDK 内部用 spawn 调起它：CVE-2024-27980 之后 Node 拒绝直接 spawn 批处理，
      // **同步**抛 `spawn EINVAL` —— 真机第一版就是这么死的，页面上只有一句
      // "spawn EINVAL"，连是哪条路径都没说。
      //
      // 垫片解析必须借 claude-path.js：主会话（index.js 的 sessionFactory）走的
      // 是**同一个函数**，这里另写一套的话迟早出现"聊天能跑、这页说找不到"。
      // 解析不了它抛 ClaudeNotFoundError（消息里带着那条路径），下面照常印成一行 JSON。
      options.pathToClaudeCodeExecutable = resolveClaudePath({ explicit: claudePath });
    }

    q = query({
      // 输入流开着但**永不喂消息** —— 流式输入模式才能用控制请求，而我们一条都不发
      prompt: (async function* never() {
        await new Promise(() => {});
      })(),
      options,
    });

    // CLI 起来之前控制请求会挂。轮询到它答话 —— 与探针同一个判据。
    let ready = false;
    for (let i = 0; i < 40 && !ready; i++) {
      try {
        await q.reloadSkills();
        ready = true;
      } catch {
        await sleep(1000);
      }
    }
    if (!ready) {
      emit({ ok: false, error: 'claude 40 秒内没有就绪' });
      process.exit(1);
    }

    // 这两条都要：清单（含没进上下文的那些的显示名）与用量（来源 + token 估计）
    const reloaded = await q.reloadSkills();
    const usage = await q.getContextUsage({ detail: 'summary' });

    emit({
      ok: true,
      commands: (reloaded?.skills ?? []).map((c) => ({
        name: c?.name ?? '',
        description: c?.description ?? '',
        argumentHint: c?.argumentHint ?? '',
        builtin: c?.builtin === true,
      })),
      skills: usage?.skills ?? null,
    });
  } catch (err) {
    emit({ ok: false, error: String(err?.message ?? err) });
    process.exit(1);
  } finally {
    try { q?.close?.(); } catch { /* 已退出 */ }
  }
}

const bail = setTimeout(() => {
  emit({ ok: false, error: `超时（${TIMEOUT_MS}ms）` });
  process.exit(1);
}, TIMEOUT_MS);
bail.unref();

main().then(() => process.exit(0));
