import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createDispatcher } from '../index.js';
import { createSession } from '../session.js';
import { CATALOGS, makeT } from '../strings.js';

/**
 * 拒绝/中断那三条文案**不随界面语言变**，这条用例把它钉住。
 *
 * 它们看起来像"界面字句"，其实不是：`{behavior:'deny', message}` 是发给 CLI 的
 * 权限回执，CLI 再把它喂给模型 —— 是**协议载荷**。界面切成英文时改变模型被告知
 * 的内容，等于同一个问题在不同语言下问出不同的答案。
 *
 * 与 `shared/transcript-ops.json` 同一个形状：Kotlin 侧读的是**同一份文件**
 * （那边的 `PermissionQueue.DENY_MESSAGE` 与这两条必须逐字一致），单改一边就红。
 * 这份 fixture 只有数据、没有注释 —— 两边各自在测试里写"为什么"。
 */

const FIXTURE = JSON.parse(
  readFileSync(new URL('../../shared/deny-message.json', import.meta.url), 'utf8'),
);

const DENY = Object.values(FIXTURE);
const LANGS = Object.keys(CATALOGS);

function assertFixtureShape() {
  assert.deepEqual(Object.keys(FIXTURE).sort(), ['interrupt', 'permissionDeny', 'stop']);
  for (const [name, value] of Object.entries(FIXTURE)) {
    assert.equal(typeof value, 'string', `${name} 得是字符串`);
    assert.ok(value.length > 0, `${name} 是空的`);
  }
}

/** 记录调用的假会话 —— 只关心"收到了哪条拒绝文案"。 */
function recordingFactory() {
  const calls = [];
  return {
    calls,
    factory() {
      return {
        send() {},
        decidePermission: (id, result) => calls.push(['decide', id, result]),
        denyAllPending: (reason) => calls.push(['denyAll', reason]),
        stop: () => calls.push(['stop']),
        interrupt: () => calls.push(['interrupt']),
      };
    },
  };
}

/** 只用来拿到 options.canUseTool 的假 query()。 */
function fakeQuery() {
  const calls = { options: null };
  async function* gen() {}
  return {
    calls,
    fn(params) {
      calls.options = params.options;
      return Object.assign(gen(), { interrupt: async () => {} });
    },
  };
}

test('fixture 的形状：三条，各是一句非空中文', () => {
  // 形状先钉住：fixture 被清空或改名的话，下面那些断言会变得**看着**在跑、
  // 实际什么都没验证
  assertFixtureShape();
});

test('permissionDecision 没带 message 时，回执里是 fixture 那条（两种语言都一样）', () => {
  for (const lang of LANGS) {
    const sf = recordingFactory();
    const out = [];
    const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m), lang });
    d.handle({ id: '1', method: 'start', params: { cwd: '/tmp' } });
    d.handle({ id: '2', method: 'permissionDecision', params: { requestId: 'r1', behavior: 'deny' } });

    assert.deepEqual(
      sf.calls.find((c) => c[0] === 'decide')[2],
      { behavior: 'deny', message: FIXTURE.permissionDeny },
      `${lang}：拒绝回执的文案不该跟着界面语言走`,
    );
  }
});

test('stop 清空待决权限时，理由也是 fixture 那条', () => {
  for (const lang of LANGS) {
    const sf = recordingFactory();
    const d = createDispatcher({ sessionFactory: sf.factory, out: () => {}, lang });
    d.handle({ id: '1', method: 'start', params: { cwd: '/tmp' } });
    d.handle({ id: '2', method: 'stop', params: {} });

    const reason = sf.calls.find((c) => c[0] === 'denyAll')?.[1];
    assert.equal(reason, FIXTURE.stop, `${lang}：终止会话时清空待决权限的理由被翻了`);
  }
});

test('interrupt 作废挂起询问时，理由也是 fixture 那条', async () => {
  for (const lang of LANGS) {
    const q = fakeQuery();
    const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn, t: makeT(lang) });
    // 造一条挂起的权限询问：canUseTool 被调用时才登记进 pending
    const asked = q.calls.options.canUseTool('Bash', {}, { toolUseID: `t-${lang}` });
    await s.interrupt();

    assert.deepEqual(
      await asked,
      { behavior: 'deny', message: FIXTURE.interrupt },
      `${lang}：中断时那条理由被翻了`,
    );
  }
});

test('stop() 结束会话时，理由也是 fixture 那条', async () => {
  for (const lang of LANGS) {
    const q = fakeQuery();
    const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn, t: makeT(lang) });
    const asked = q.calls.options.canUseTool('Bash', {}, { toolUseID: `t-${lang}` });
    s.stop();

    assert.deepEqual(
      await asked,
      { behavior: 'deny', message: FIXTURE.stop },
      `${lang}：stop() 那条理由被翻了`,
    );
  }
});

test('这三条都不在词表里 —— 进了词表就等于承认它们会跟着语言变', () => {
  for (const lang of LANGS) {
    const table = CATALOGS[lang];
    for (const value of DENY) {
      // 键：词表的键是 `<area>.<leaf>` 的点分名，本来就不会长成一句话，
      // 所以真正要紧的是"值"这一侧 —— 但也一并钉住，免得将来有人往里塞
      assert.ok(!(value in table), `${lang} 词表里出现了拒绝文案当键：${value}`);
      const hit = Object.entries(table).find(([, v]) => v === value);
      assert.equal(hit, undefined, `${lang} 词表里的 ${hit?.[0]} 就是拒绝文案，它不该被翻`);
    }
  }
});
