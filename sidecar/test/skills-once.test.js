import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * skills-once.js 是**导入即跑**的脚本（顶层就 main()），没法当模块测；这两条走子进程。
 * 两条都在 SDK 起进程之前分岔，所以不需要真 claude、跑得也快。
 *
 * 为什么值这两条：真机第一版死在 `--claude` 被**原样**丢给 SDK —— 检测路径是
 * npm 的 `claude.cmd` 垫片，Node 拒绝直接 spawn 批处理（CVE-2024-27980），
 * 同步抛 `spawn EINVAL`，页面上就这一句、连路径都没有（复现记录在设计稿的落地记录里）。
 * 垫片解析是 claude-path.js 的职责，后一条钉住"解析真的发生在这个边界上"。
 */
const SCRIPT = fileURLToPath(new URL('../skills-once.js', import.meta.url));

function run(args) {
  return spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8', timeout: 30_000 });
}

function lastJson(r) {
  const line = (r.stdout ?? '').trim().split('\n').filter(Boolean).pop();
  assert.ok(
    line,
    `stdout 里没有 JSON 行：stdout=${JSON.stringify(r.stdout)} stderr=${JSON.stringify(r.stderr)}`,
  );
  return JSON.parse(line);
}

test('不给 --cwd 就报一句人话', () => {
  const r = run([]);
  assert.equal(r.status, 1);
  assert.deepEqual(lastJson(r), { ok: false, error: 'missing --cwd' });
});

test('解析不出目标的垫片报的是解析器的错（带上路径），不是 spawn EINVAL', () => {
  // 形状照着 npm 垫片来，但里面没有可解析的 .exe —— resolveClaudePath 必须拒绝它。
  //
  // 断言钉在"错误里带着那条路径"上：spawn EINVAL 的 message 恰恰没有路径，
  // 人拿着那句话根本不知道去哪查（真机上就是这一句）。EINVAL 那条负断言是
  // 双保险：万一哪天 Node 的错误格式变了、把路径也带进 message 里，
  // 只靠 includes 就漏了。
  const dir = mkdtempSync(join(tmpdir(), 'skills-once-'));
  const shim = join(dir, 'claude.cmd');
  writeFileSync(shim, '@ECHO off\r\nREM 这不是一个可解析的垫片\r\n');
  const r = run(['--cwd', dir, '--claude', shim]);
  assert.equal(r.status, 1);
  const out = lastJson(r);
  assert.equal(out.ok, false);
  assert.ok(out.error.includes('claude.cmd'), `错误里该带上那条垫片路径：${out.error}`);
  assert.ok(!/EINVAL/.test(out.error), `不该是"原样丢给 SDK"的症状：${out.error}`);
});
