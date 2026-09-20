import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildChildEnv, HOST_ENV_BLACKLIST, HOST_ENV_OVERRIDABLE } from '../env.js';
import { UI_LANG_ENV } from '../strings.js';

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

test('envOverrides 不能恢复黑名单项（宿主隔离那几项）', () => {
  const out = buildChildEnv({}, {
    CLAUDE_CODE_ENTRYPOINT: 'sdk-cli',
    CLAUDE_SESSION_ID: 'abc',
    ANTHROPIC_MODEL: 'x',
  });
  assert.equal(out.CLAUDE_CODE_ENTRYPOINT, undefined);
  assert.equal(out.CLAUDE_SESSION_ID, undefined);
  assert.equal(out.ANTHROPIC_MODEL, undefined);
});

// 这一条是本次修复的核心：同一个变量，继承来的要剥、插件显式给的放行。
// 剥错了 → settings.json 里的凭证被 CLI 丢掉而插件又没给 → authentication_failed；
// 放行错了 → profile 的端点被 settings.json 盖掉，密钥却发过去 → 401。
test('CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST 是唯一可被插件重新引入的黑名单项', () => {
  const out = buildChildEnv(
    { CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST: '0', PATH: '/usr/bin' },
    { CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST: '1' },
  );
  assert.equal(out.CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST, '1', '插件显式给的值要生效');
  assert.equal(out.PATH, '/usr/bin');
});

test('继承来的那一份仍然被剥掉（插件没给时不该传下去）', () => {
  const out = buildChildEnv({ CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST: '1' });
  assert.equal(out.CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST, undefined);
});

// 防的是"顺手把整张黑名单都标成可覆盖"这种扩大化的重构：
// 每一项都单独试一遍，只有白名单里那几项能留下
test('可覆盖白名单是黑名单的极小子集', () => {
  for (const k of HOST_ENV_BLACKLIST) {
    assert.ok(HOST_ENV_BLACKLIST.includes(k), `${k} 必须在黑名单里`);
    const out = buildChildEnv({}, { [k]: 'x' });
    if (HOST_ENV_OVERRIDABLE.includes(k)) continue;
    assert.equal(out[k], undefined, `${k} 不该能经 envOverrides 恢复`);
  }
  assert.deepEqual([...HOST_ENV_OVERRIDABLE], ['CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST']);
});

test('undefined 值不进入结果', () => {
  const out = buildChildEnv({ A: '1', B: undefined });
  assert.ok(!('B' in out));
  assert.equal(out.A, '1');
});

test('黑名单恰好是 11 项', () => {
  // 数字是**故意**钉的：这张表的每一项都压在"CLI 能不能认证"上，加一条删一条
  // 都该有人在这一行停一下。11 = spec §11.1 实测那 10 项 + CCODER_UI_LANG
  assert.equal(HOST_ENV_BLACKLIST.length, 11);
});

// 我们自己的语言开关（strings.js 的 UI_LANG_ENV）。放黑名单的理由与上面那 10 项
// **不同**：它无关认证，是"别把我们的开关渗进别人的环境"—— CLI 与它拉起的
// hooks / MCP 子进程都不认识这个变量。
test('CCODER_UI_LANG 会被剥掉，且没有重新引入的通道', () => {
  const out = buildChildEnv({ [UI_LANG_ENV]: 'zh', PATH: '/usr/bin' });
  assert.equal(out[UI_LANG_ENV], undefined, '不该渗进 claude 子进程');
  assert.equal(out.PATH, '/usr/bin', '剥它不该波及别的变量');

  // 可覆盖清单是"宿主管端点"的资格位，语言不在其列 —— 每会话的语言走
  // start 的 uiLang（Lever B），不需要经环境变量重新注入
  assert.ok(!HOST_ENV_OVERRIDABLE.includes(UI_LANG_ENV));
  assert.equal(buildChildEnv({}, { [UI_LANG_ENV]: 'en' })[UI_LANG_ENV], undefined);
});
