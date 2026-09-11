import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, chmodSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { resolveClaudePath, ClaudeNotFoundError } from '../claude-path.js';

// PATH 相关测试必须用宿主平台构造。
// 用 platform:'linux' 配 Windows 路径是不自洽的：split(':') 会把盘符 "C:"
// 切成一个假目录，测出来的东西没有意义。
const HOST = process.platform;
const SEP = HOST === 'win32' ? ';' : ':';
// 用 .exe 而非 .cmd：.cmd 在本实现里会走垫片解析，需要真实指向一个 exe 才算合法。
// 垫片路径由下面独立的用例专门覆盖。
const BIN_NAME = HOST === 'win32' ? 'claude.exe' : 'claude';

function makeFakeBin(dir, name = BIN_NAME) {
  const p = join(dir, name);
  writeFileSync(p, '#!/bin/sh\necho fake\n');
  try { chmodSync(p, 0o755); } catch { /* Windows 上 chmod 是空操作 */ }
  return p;
}

test('显式路径优先于 PATH', () => {
  const d1 = mkdtempSync(join(tmpdir(), 'cc-a-'));
  const d2 = mkdtempSync(join(tmpdir(), 'cc-b-'));
  const explicit = makeFakeBin(d1);
  makeFakeBin(d2);
  const got = resolveClaudePath({ explicit, env: { PATH: `${d1}${SEP}${d2}` }, platform: HOST });
  assert.equal(got, explicit);
});

test('未指定显式路径时从 PATH 解析', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-p-'));
  const bin = makeFakeBin(dir);
  const got = resolveClaudePath({ env: { PATH: dir }, platform: HOST });
  assert.equal(got, bin);
});

test('PATH 中多个目录时取第一个命中的', () => {
  const a = mkdtempSync(join(tmpdir(), 'cc-x-'));
  const b = mkdtempSync(join(tmpdir(), 'cc-y-'));
  const binA = makeFakeBin(a);
  makeFakeBin(b);
  const got = resolveClaudePath({ env: { PATH: `${a}${SEP}${b}` }, platform: HOST });
  assert.equal(got, binA);
});

test('PATH 分隔符由注入的 platform 决定，而非宿主平台', () => {
  // 这条钉住 separatorFor() 的契约：实现不能用 node:path 的 delimiter，
  // 否则注入 platform 就失去意义，跨平台测试也无从构造。
  const a = mkdtempSync(join(tmpdir(), 'cc-s1-'));
  const b = mkdtempSync(join(tmpdir(), 'cc-s2-'));
  makeFakeBin(a);
  makeFakeBin(b);

  const wrongSep = HOST === 'win32' ? ':' : ';';
  // 用错误的分隔符拼接时，整串会被当成一个目录，找不到任何东西
  assert.throws(
    () => resolveClaudePath({ env: { PATH: `${a}${wrongSep}${b}` }, platform: HOST }),
    (err) => err.code === 'CLAUDE_NOT_FOUND'
  );
});

test('Windows 上尝试 .exe 后缀', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-w1-'));
  const bin = makeFakeBin(dir, 'claude.exe');
  const got = resolveClaudePath({ env: { PATH: dir }, platform: 'win32' });
  assert.equal(got, bin);
});

test('Windows 上 .exe 优先于 .cmd', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-w2-'));
  const exe = makeFakeBin(dir, 'claude.exe');
  makeFakeBin(dir, 'claude.cmd');
  const got = resolveClaudePath({ env: { PATH: dir }, platform: 'win32' });
  assert.equal(got, exe);
});

test('找不到时抛 CLAUDE_NOT_FOUND', () => {
  const empty = mkdtempSync(join(tmpdir(), 'cc-e-'));
  assert.throws(
    () => resolveClaudePath({ env: { PATH: empty }, platform: HOST }),
    (err) => err instanceof ClaudeNotFoundError && err.code === 'CLAUDE_NOT_FOUND'
  );
});

test('PATH 为空时抛 CLAUDE_NOT_FOUND', () => {
  assert.throws(
    () => resolveClaudePath({ env: {}, platform: HOST }),
    (err) => err.code === 'CLAUDE_NOT_FOUND'
  );
});

test('显式路径不存在时也抛错，不回退到 PATH', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-f-'));
  makeFakeBin(dir);
  assert.throws(
    () => resolveClaudePath({ explicit: join(dir, 'nope'), env: { PATH: dir }, platform: HOST }),
    (err) => err.code === 'CLAUDE_NOT_FOUND'
  );
});

test('显式路径为空串时视为未指定，走 PATH 解析', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-g-'));
  const bin = makeFakeBin(dir);
  const got = resolveClaudePath({ explicit: '   ', env: { PATH: dir }, platform: HOST });
  assert.equal(got, bin);
});

// ---- Windows 垫片解析 ----
// npm 全局包在 Windows 上装的是批处理垫片（claude.cmd），Node 无法直接 spawn：
// CVE-2024-27980 之后 .cmd/.bat 必须经 shell 执行，而 SDK 内部用 spawn
// 调起 CLI，不给我们传 shell 的机会 —— 实测报 spawn EINVAL。
// 垫片内容指向真实 exe，必须跟进去。

function makeNpmLayout(root) {
  const realDir = join(root, 'node_modules', '@anthropic-ai', 'claude-code', 'bin');
  mkdirSync(realDir, { recursive: true });
  const realExe = join(realDir, 'claude.exe');
  writeFileSync(realExe, 'fake-exe');
  return realExe;
}

test('Windows 上 .cmd 垫片被解析到真实可执行文件', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-shim-'));
  const realExe = makeNpmLayout(dir);

  const shimContent = String.raw`@ECHO off
"%dp0%\node_modules\@anthropic-ai\claude-code\bin\claude.exe"   %*`;
  writeFileSync(join(dir, 'claude.cmd'), shimContent);

  const got = resolveClaudePath({ env: { PATH: dir }, platform: 'win32' });
  assert.equal(got, realExe, '必须返回真实 exe，而非无法 spawn 的 .cmd');
});

test('垫片指向的 exe 不存在时抛错，不回退到 .cmd', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-shim2-'));
  // 故意不创建真实的 exe
  writeFileSync(join(dir, 'claude.cmd'), String.raw`"%dp0%\node_modules\@anthropic-ai\claude-code\bin\claude.exe" %*`);

  assert.throws(
    () => resolveClaudePath({ env: { PATH: dir }, platform: 'win32' }),
    (err) => err.code === 'CLAUDE_NOT_FOUND',
    '返回无法 spawn 的 .cmd 只会在 SDK 内部炸成 EINVAL，不如在这里报清楚'
  );
});

test('显式指定的 .cmd 也会被解析到真实 exe', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-shim3-'));
  const realExe = makeNpmLayout(dir);
  const shim = join(dir, 'claude.cmd');
  writeFileSync(shim, String.raw`"%dp0%\node_modules\@anthropic-ai\claude-code\bin\claude.exe" %*`);

  const got = resolveClaudePath({ explicit: shim, env: { PATH: dir }, platform: 'win32' });
  assert.equal(got, realExe);
});

test('已经是 .exe 的路径不做垫片解析', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-shim4-'));
  const exe = join(dir, 'claude.exe');
  writeFileSync(exe, 'fake');

  const got = resolveClaudePath({ explicit: exe, env: { PATH: dir }, platform: 'win32' });
  assert.equal(got, exe);
});
