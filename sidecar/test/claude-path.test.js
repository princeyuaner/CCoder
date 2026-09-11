import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { resolveClaudePath, ClaudeNotFoundError } from '../claude-path.js';

// PATH 相关测试必须用宿主平台构造。
// 用 platform:'linux' 配 Windows 路径是不自洽的：split(':') 会把盘符 "C:"
// 切成一个假目录，测出来的东西没有意义。
const HOST = process.platform;
const SEP = HOST === 'win32' ? ';' : ':';
const BIN_NAME = HOST === 'win32' ? 'claude.cmd' : 'claude';

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
