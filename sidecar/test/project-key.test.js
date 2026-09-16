import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import {
  applyProjectKey,
  literalProjectKey,
  projectKeyMismatch,
  resetAppliedForTest,
} from '../project-key.js';

/** 假装这个目录被解析成了 UNC（映射盘的样子）。 */
const asMapped = (unc) => () => unc;

beforeEach(resetAppliedForTest);

test('目录名规则与 CLI / SDK 一致', () => {
  assert.equal(literalProjectKey('Z:\\m71\\server'), 'Z--m71-server');
  assert.equal(literalProjectKey('C:\\Users\\CY\\Desktop\\CCoder'), 'C--Users-CY-Desktop-CCoder');
  assert.equal(literalProjectKey('\\\\192.168.3.221\\lihoo\\m71\\server'), '--192-168-3-221-lihoo-m71-server');
});

test('本地盘：两条路算出来一样 —— 不算不一致', () => {
  const dir = 'C:\\Users\\CY\\Desktop\\CCoder';
  assert.equal(projectKeyMismatch(dir, (d) => d), null);
});

test('映射盘：native realpath 展开成 UNC 时，两条路不一致', () => {
  const m = projectKeyMismatch('Z:\\m71\\server', asMapped('\\\\192.168.3.221\\lihoo\\m71\\server'));

  assert.equal(m.literal, 'Z--m71-server', 'CLI 写的那份');
  assert.equal(m.sdkKey, '--192-168-3-221-lihoo-m71-server', 'SDK 会去找的那份');
});

test('解析不出来（目录不在、没权限）就当不一致不存在', () => {
  assert.equal(projectKeyMismatch('Z:\\m71\\server', () => null), null);
});

test('不一致时：两个环境变量都设上，名字按字面路径那个', () => {
  const env = {};
  const r = applyProjectKey('Z:\\m71\\server', { env, resolve: asMapped('\\\\192.168.3.221\\lihoo\\m71\\server') });

  assert.equal(r.applied, true);
  assert.equal(env.CLAUDE_CODE_PROJECT_DIR_NAME, 'Z--m71-server');
  assert.ok(env.CLAUDE_CONFIG_DIR, 'CLAUDE_CONFIG_DIR 也得给 —— SDK 只认这两个一起出现时的那份覆盖');
});

test('一致时：**一个字节都不动**（本地盘不能被这条改动波及）', () => {
  const env = { PATH: 'x' };
  const r = applyProjectKey('C:\\Users\\CY\\Desktop\\CCoder', { env, resolve: (d) => d });

  assert.equal(r.applied, false);
  assert.deepEqual(env, { PATH: 'x' });
});

test('幂等：第二次调用不改环境', () => {
  const env = {};
  const mapped = { env, resolve: asMapped('\\\\192.168.3.221\\lihoo\\m71\\server') };
  applyProjectKey('Z:\\m71\\server', mapped);
  env.CLAUDE_CODE_PROJECT_DIR_NAME = 'Z--m71-server';
  const r = applyProjectKey('Z:\\m71\\server', mapped);

  assert.equal(r.applied, false);
  assert.equal(r.reason, 'already-applied');
});

test('已经定过之后换个项目：不硬改（SDK 只读第一次），但要说清', () => {
  const env = {};
  applyProjectKey('Z:\\m71\\server', { env, resolve: asMapped('\\\\192.168.3.221\\lihoo\\m71\\server') });
  const r = applyProjectKey('Y:\\other', { env, resolve: asMapped('\\\\host\\share\\other') });

  assert.equal(r.applied, false);
  assert.equal(env.CLAUDE_CODE_PROJECT_DIR_NAME, 'Z--m71-server', '不该被第二个项目改掉');
});

test('宿主自己指定过目录名：不抢', () => {
  const env = { CLAUDE_CODE_PROJECT_DIR_NAME: '别人定的名字' };
  const r = applyProjectKey('Z:\\m71\\server', { env, resolve: asMapped('\\\\192.168.3.221\\lihoo\\m71\\server') });

  assert.equal(r.applied, false);
  assert.equal(r.reason, 'preset');
  assert.equal(env.CLAUDE_CODE_PROJECT_DIR_NAME, '别人定的名字');
});
