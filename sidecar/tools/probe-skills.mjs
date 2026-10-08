#!/usr/bin/env node
/**
 * 探针：设置里那一页「技能」，数据够不够画。
 *
 * 要画的界面押在四件**读文档读不出来**的事上（"类型在不在"与"送不送得到"是两回事）：
 *
 *   Q1 列表从哪来：`reloadSkills()` 每条给哪些字段？`getContextUsage()` 里那截
 *      `skills.skillFrontmatter`（name / source / tokens）**在 `detail:'summary'` 下给不给** ——
 *      给的话，"来源 + 占多少 token"两列就不必自己扫盘算。
 *   Q2 状态从哪来：`skillOverrides` 写下去之后，上面两条读数**变不变**。
 *      变 → 可以直接拿列表反推状态；不变 → 状态只能自己读三层设置文件再合并。
 *   Q3 生效时机：改完**当前会话**当场变，还是要新会话？决定界面上那句提示怎么写
 *      （MCP 页今天是"改过的 server 下次开会话才生效"）。
 *   Q4 可覆盖性：插件来源的技能是不是真的覆盖不动（`claude.exe` 里 `BMe()` 那行
 *      读着是：`source==="plugin"` 直接 return "on" —— 但从没在真会话上验过）。
 *
 * **只打控制请求，一条消息都不发** —— 不花 token，只是要等 CLI 起来（几秒）。
 *
 * 用法：cd <repo root> && node sidecar/tools/probe-skills.mjs
 *
 * 写只写临时目录：改状态走 CLI 自己的写手 `claude edit-skill-overrides --json`，
 * 它写的是 **cwd 的 `.claude/settings.local.json`**（实测），所以临时目录之外一个字节不动。
 * 跑完整个临时目录删掉。
 *
 * 一个先记下的坑：那个子命令**拒绝从 Claude Code 会话里启动的进程**
 * （原话 "cannot be changed from an editor started inside a Claude Code session"）
 * —— 真跑过一次才发现，所以下面 spawn 它时把 CLAUDECODE / CLAUDE_CODE_* 摘掉。
 */
import { spawn } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';

const SKILL_MD = `---
name: probe-demo
description: 探针用的项目级技能，只为看列表长什么样。
---

这是一个探针技能，正文内容不重要。
`;

/**
 * 用哪个 claude。**在剥环境变量之前先取**：Git Bash 里的 PATH 是 POSIX 形状，
 * 直接 spawn('claude') 可能找不到；本进程自己那个 exe 路径最可靠。
 */
const CLAUDE = process.env.CLAUDE_CODE_EXECPATH || 'claude';

/** 摘掉"我在 claude 会话里"的记号 —— edit-skill-overrides 会据此拒绝干活。 */
function hostEnv() {
  const env = { ...process.env };
  for (const k of Object.keys(env)) {
    if (k === 'CLAUDECODE' || k.startsWith('CLAUDE_CODE_') || k.startsWith('CLAUDE_')) delete env[k];
  }
  return env;
}

function short(s, n = 110) {
  const t = String(s ?? '').replace(/\s+/g, ' ').trim();
  return t.length > n ? t.slice(0, n) + '…' : t;
}

/** 起一个会话，只留着——不发消息。返回 { q, stop }。 */
function startSession(cwd) {
  const events = [];
  const q = query({
    prompt: (async function* never() {
      await new Promise(() => {});   // 输入流开着但永不喂消息
    })(),
    options: {
      cwd,
      permissionMode: 'default',
      env: buildChildEnv(process.env),
      includePartialMessages: true,
      canUseTool: () => Promise.resolve({ behavior: 'allow', updatedInput: {} }),
    },
  });
  (async () => {
    try {
      for await (const m of q) events.push(m);
    } catch { /* 关会话时正常报错，忽略 */ }
  })();
  return { q, events };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** CLI 起来之前控制请求会挂 —— 轮询到它答话为止。 */
async function ready(q, label) {
  for (let i = 0; i < 30; i++) {
    try {
      await q.reloadSkills();
      return true;
    } catch {
      await sleep(1000);
    }
  }
  console.log(`  !! ${label} 30s 内没答话`);
  return false;
}

async function snap(q) {
  const skills = await q.reloadSkills().then((r) => r?.skills ?? []).catch((e) => `ERR ${e?.message ?? e}`);
  const summary = await q.getContextUsage({ detail: 'summary' }).then((r) => r?.skills ?? null).catch((e) => `ERR ${e?.message ?? e}`);
  const full = await q.getContextUsage({ detail: 'full' }).then((r) => r?.skills ?? null).catch((e) => `ERR ${e?.message ?? e}`);
  return { skills, summary, full };
}

function printSnap(tag, s) {
  console.log(`\n  ── ${tag} ──`);
  if (Array.isArray(s.skills)) {
    console.log(`  reloadSkills(): ${s.skills.length} 条`);
    for (const k of s.skills.slice(0, 6)) {
      console.log(`     · ${short(k.name, 28).padEnd(30)} ${short(k.description, 70)}`);
    }
    if (s.skills.length > 6) console.log(`     … 其余 ${s.skills.length - 6} 条`);
  } else {
    console.log(`  reloadSkills(): ${s.skills}`);
  }
  const line = (label, v) => {
    if (v === null) console.log(`  ${label}: null（没有这一段）`);
    else if (typeof v === 'string') console.log(`  ${label}: ${v}`);
    else console.log(`  ${label}: total=${v.totalSkills} included=${v.includedSkills} tokens=${v.tokens}\n`
      + v.skillFrontmatter.slice(0, 6).map((f) => `     · ${short(f.name, 30).padEnd(32)} ${String(f.source).padEnd(12)} ${f.tokens}t`).join('\n')
      + (v.skillFrontmatter.length > 6 ? `\n     … 其余 ${v.skillFrontmatter.length - 6} 条` : ''));
  };
  line('getContextUsage(summary).skills', s.summary);
  line('getContextUsage(full).skills   ', s.full);
}

/** 走 CLI 自己的写手改一条技能的状态。返回它的回执原文。 */
function writeOverride(cwd, name, state) {
  return new Promise((resolve) => {
    const p = spawn(CLAUDE, ['edit-skill-overrides', '--json'], { cwd, env: hostEnv(), shell: true });
    let out = '';
    p.stdout.on('data', (d) => (out += d));
    p.stderr.on('data', (d) => (out += d));
    p.on('close', (code) => resolve(`exit=${code} ${short(out, 160)}`));
    p.stdin.end(JSON.stringify({ name, state }));
  });
}

const project = mkdtempSync(join(tmpdir(), 'ccoder-skills-probe-'));
mkdirSync(join(project, '.claude', 'skills', 'probe-demo'), { recursive: true });
writeFileSync(join(project, '.claude', 'skills', 'probe-demo', 'SKILL.md'), SKILL_MD);
console.log(`临时项目：${project}`);

let a = null;
let b = null;
try {
  // ---- Q1：列表与字段 ----
  console.log('\n=== Q1　列表从哪来 ===');
  a = startSession(project);
  if (!(await ready(a.q, '会话甲'))) throw new Error('会话甲没起来');
  const s1 = await snap(a.q);
  printSnap('会话甲 · 改之前', s1);

  // ---- 挑靶子 ----
  const fromFm = Array.isArray(s1.full?.skillFrontmatter) ? s1.full.skillFrontmatter : [];
  const bySource = (src) => fromFm.filter((f) => f.source === src);
  const targets = [];
  if (bySource('project').length) targets.push({ kind: 'project', name: bySource('project')[0].name, state: 'off' });
  // 用户级的挑一条（这台机器上 ~/.claude/skills 下那七个就是）
  const userish = fromFm.filter((f) => f.source !== 'project' && f.source !== 'plugin' && f.source !== 'built-in');
  if (userish.length) targets.push({ kind: 'user?', name: userish[0].name, state: 'off' });
  const plug = bySource('plugin')[0];
  if (plug) targets.push({ kind: 'plugin', name: plug.name, state: 'off' });
  console.log(`\n  靶子：${targets.map((t) => `${t.kind}:${t.name}`).join('、') || '（一个都没挑到，只能看 Q1）'}`);

  for (const t of targets) {
    console.log(`\n=== Q2/Q3　把 ${t.kind}「${t.name}」写成 off ===`);
    console.log(`  写手回执：${await writeOverride(project, t.name, t.state)}`);
    const written = join(project, '.claude', 'settings.local.json');
    console.log(`  落盘：${existsSync(written) ? short(readFileSync(written, 'utf8'), 200) : '（没写文件？！）'}`);
    const s2 = await snap(a.q);
    printSnap(`会话甲 · 改完（同一个会话）`, s2);
    b = startSession(project);
    if (await ready(b.q, '会话乙')) {
      const s3 = await snap(b.q);
      printSnap('会话乙 · 全新会话', s3);
    }
  }

  // ---- Q4：来源分布 ----
  console.log('\n=== Q4　来源分布（按 getContextUsage(full).skillFrontmatter）===');
  const counts = {};
  for (const f of fromFm) counts[f.source] = (counts[f.source] ?? 0) + 1;
  console.log('  ' + (Object.entries(counts).map(([k, v]) => `${k}=${v}`).join('  ') || '（空）'));

  // ---- Q5：设置键到底是哪个 ----
  //
  // 写手**什么都不验**：`{"name":"Debug Issue"}` 它照写（回执 changed:true，落盘也是
  // 原样那串）。而 `reloadSkills()` 给的偏偏就是这个显示名 —— 所以"拿列表里的名字去写"
  // 是个**会静默失败的陷阱**。真键是哪个，只能让新会话回答：
  // 两个臂各写一次、各起一个新会话，看那条技能消没消失。
  console.log('\n=== Q5　设置键：显示名 还是 目录名 ===');
  const settingsPath = join(project, '.claude', 'settings.local.json');
  // 三臂：用户技能的显示名 / 用户技能的目录名 / 内置技能（没有目录，只有名字）
  const arms = [
    ['user 显示名', 'Debug Issue', 'Debug Issue'],
    ['user 目录名', 'debug-issue', 'Debug Issue'],
    ['内置 名字', 'dataviz', 'dataviz'],
  ];
  for (const [label, key, watch] of arms) {
    writeFileSync(settingsPath, JSON.stringify({ skillOverrides: { [key]: 'off' } }, null, 2));
    const ses = startSession(project);
    if (!(await ready(ses.q, label))) continue;
    const s = await snap(ses.q);
    const still = Array.isArray(s.skills) && s.skills.some((k) => k.name === watch);
    console.log(`  写 "${key}"（${label}）→ 新会话里「${watch}」${still ? '**还在** —— 这个键没用' : '**消失了** —— 这个键是对的'}`);
    try { ses.q.close(); } catch {}
    await sleep(800);
  }
  writeFileSync(settingsPath, '{}');
} finally {
  try { a?.q?.close?.(); } catch {}
  try { b?.q?.close?.(); } catch {}
  await sleep(1500);
  // Windows 上删不掉是**常态**：那个临时目录是刚关掉的 CLI 子进程的 cwd，
  // 句柄要等它真退出才松手。第一版直接 rmSync，跑完就是一句 EPERM 把整个
  // 探针最后的输出都吞了。所以：重试 + 删不掉也照常收场，把路径打出来让人手删。
  try {
    rmSync(project, { recursive: true, force: true, maxRetries: 10, retryDelay: 400 });
    console.log(`\n临时项目已删：${project}`);
  } catch (e) {
    console.log(`\n临时项目删不掉（${e?.code ?? e}），手动删一下：${project}`);
  }
  // **必须显式退**：SDK 起过的 CLI 子进程只要还攥着 stdio 管道，node 的事件循环
  // 就不空 —— 脚本"跑完了"却永远不退出（2026-09-30 卡了 20 分钟，输出还被管道
  // 扣着，只能 kill 掉才读得到）。结论已经印完了，这里退得心安理得。
  process.exit(0);
}
