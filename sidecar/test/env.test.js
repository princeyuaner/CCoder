import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildChildEnv, HOST_ENV_BLACKLIST } from '../env.js';

test('黑名单中每个变量都被移除', () => {
  const base = {};
  for (const k of HOST_ENV_BLACKLIST) base[k] = 'x';
  const out = buildChildEnv(base);
  for (const k of HOST_ENV_BLACKLIST) {
    assert.equal(out[k], undefined, `${k} 应被移除`);
  }
});

test('非黑名单变量被保留', () => {
  const base = { PATH: '/usr/bin', HOME: '/home/u', USERPROFILE: 'C:\\Users\\u' };
  const out = buildChildEnv(base);
  assert.equal(out.PATH, '/usr/bin');
  assert.equal(out.HOME, '/home/u');
  assert.equal(out.USERPROFILE, 'C:\\Users\\u');
});

// 这条测试防的是"图省事改成 startsWith('CLAUDE_CODE_')"这类重构。
// 实测保留这些变量不影响认证（spec §3.2）。
test('黑名单是精确匹配，不是前缀匹配', () => {
  const base = {
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
    CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING: 'true',
    CLAUDE_CODE_DISABLE_1M_CONTEXT: '',
    CLAUDE_CODE_EFFORT_LEVEL: 'max',
  };
  const out = buildChildEnv(base);
  assert.equal(out.CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC, '1');
  assert.equal(out.CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING, 'true');
  assert.equal(out.CLAUDE_CODE_DISABLE_1M_CONTEXT, '');
  assert.equal(out.CLAUDE_CODE_EFFORT_LEVEL, 'max');
});

test('envOverrides 能追加变量', () => {
  const out = buildChildEnv({ PATH: '/usr/bin' }, { MY_VAR: 'hello' });
  assert.equal(out.MY_VAR, 'hello');
  assert.equal(out.PATH, '/usr/bin');
});

test('envOverrides 不能恢复黑名单项', () => {
  const out = buildChildEnv({}, { CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST: '1' });
  assert.equal(out.CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST, undefined);
});

test('undefined 值不进入结果', () => {
  const out = buildChildEnv({ A: '1', B: undefined });
  assert.ok(!('B' in out));
  assert.equal(out.A, '1');
});

test('黑名单恰好是 10 项', () => {
  assert.equal(HOST_ENV_BLACKLIST.length, 10);
});
