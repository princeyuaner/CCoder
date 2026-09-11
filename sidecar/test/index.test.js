import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDispatcher } from '../index.js';

function fakeSessionFactory() {
  const calls = [];
  return {
    calls,
    factory(opts) {
      const s = {
        opts,
        sent: [],
        send(t) { this.sent.push(t); },
        interrupt: async () => { calls.push(['interrupt']); },
        setPermissionMode: async (m) => { calls.push(['setPermissionMode', m]); },
        decidePermission: (id, r) => { calls.push(['decide', id, r]); },
        denyAllPending: (r) => { calls.push(['denyAll', r]); },
        stop: () => { calls.push(['stop']); },
        _emit: opts.onEvent,
        _perm: opts.onPermission,
      };
      calls.push(['create', opts]);
      return s;
    },
  };
}

const START = { id: '1', method: 'start', params: { cwd: '/tmp', permissionMode: 'default' } };

test('start 建立会话并把 ready 上报', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });

  d.handle(START);

  assert.equal(out[0].type, 'ready');
  assert.equal(sf.calls.find((c) => c[0] === 'create')[1].cwd, '/tmp');
});

test('handler 返回当前 session 供测试断言', () => {
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  const s = d.handle(START);
  assert.equal(s, d.getSession());
  assert.ok(s);
});

test('未 start 就 send 时排队，start 后补发', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });

  d.handle({ id: '1', method: 'send', params: { text: 'early' } });
  const s = d.handle(START);
  d.handle({ id: '3', method: 'send', params: { text: 'late' } });

  assert.deepEqual(s.sent, ['early', 'late'], '排队的消息必须在 start 后按序补发');
});

test('重复 start 被拒绝但不崩溃', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });

  d.handle(START);
  d.handle(START);

  const err = out.find((m) => m.type === 'error');
  assert.equal(err.code, 'ALREADY_STARTED');
  assert.equal(err.fatal, false);
  assert.equal(sf.calls.filter((c) => c[0] === 'create').length, 1, '不应重复建会话');
});

test('SDK 事件原样透传为 event 消息', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle(START);

  s._emit({ type: 'assistant', message: { content: [] } });

  const ev = out.find((m) => m.type === 'event');
  assert.equal(ev.event.type, 'assistant');
});

test('未知事件类型也原样透传，不在 sidecar 层过滤', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle(START);

  s._emit({ type: 'some_future_type_v99' });

  assert.ok(out.some((m) => m.type === 'event' && m.event.type === 'some_future_type_v99'),
    '过滤是插件的职责，sidecar 透传（spec §3.3）');
});

test('assistant 的 authentication_failed 抽成独立的 AUTH_FAILED 错误', () => {
  // 实测该失败由 SDK 作为 assistant 事件的 error 字段回传，而非独立错误
  // （spec §11.1）。不抽取的话插件只能显示 "Not logged in"，
  // 用户无从知道要检查配置的哪一部分。
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle(START);

  s._emit({ type: 'assistant', error: 'authentication_failed',
            message: { content: [{ type: 'text', text: 'Not logged in' }] } });

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'AUTH_FAILED');
  assert.equal(e.fatal, true);
  // 事件本身仍要透传——渲染层依赖它显示原始错误文本
  assert.ok(out.some((m) => m.type === 'event' && m.event.error === 'authentication_failed'));
});

test('其他 error 字段不产生 AUTH_FAILED', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle(START);

  s._emit({ type: 'assistant', error: 'rate_limit', message: { content: [] } });

  assert.ok(!out.some((m) => m.type === 'error' && m.code === 'AUTH_FAILED'));
});

test('权限回调转为 permission 消息', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle(START);

  s._perm({ requestId: 'tu-1', toolName: 'Read', input: {}, title: '读文件' });

  const p = out.find((m) => m.type === 'permission');
  assert.equal(p.requestId, 'tu-1');
  assert.equal(p.title, '读文件');
});

test('permissionDecision 允许时不带 message', () => {
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  d.handle(START);

  d.handle({ id: '2', method: 'permissionDecision',
             params: { requestId: 'tu-1', behavior: 'allow' } });

  const call = sf.calls.find((c) => c[0] === 'decide');
  assert.deepEqual(call[2], { behavior: 'allow' });
});

test('permissionDecision 拒绝时带 message', () => {
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  d.handle(START);

  d.handle({ id: '2', method: 'permissionDecision',
             params: { requestId: 'tu-1', behavior: 'deny' } });

  const call = sf.calls.find((c) => c[0] === 'decide');
  assert.equal(call[2].behavior, 'deny');
  assert.equal(typeof call[2].message, 'string');
});

test('permissionDecision 可携带 updatedPermissions', () => {
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  d.handle(START);

  d.handle({ id: '2', method: 'permissionDecision',
             params: { requestId: 'tu-1', behavior: 'allow',
                       updatedPermissions: [{ type: 'addRules' }] } });

  const call = sf.calls.find((c) => c[0] === 'decide');
  assert.deepEqual(call[2].updatedPermissions, [{ type: 'addRules' }]);
});

test('stop 会清空待决权限', () => {
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  d.handle(START);

  d.handle({ id: '2', method: 'stop', params: {} });

  assert.ok(sf.calls.some((c) => c[0] === 'denyAll'), 'stop 必须先清空待决表');
  assert.ok(sf.calls.some((c) => c[0] === 'stop'));
});

test('stop 之后 send 不抛错也不卡住', () => {
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  d.handle(START);
  d.handle({ id: '2', method: 'stop', params: {} });

  d.handle({ id: '3', method: 'send', params: { text: 'after stop' } });

  // 不抛错即为通过；stop 后的 send 进入 preStartQueue 等待可能的新 start
  assert.equal(d.getSession(), null);
});

test('claude 找不到时上报 CLAUDE_NOT_FOUND 且不抛错', () => {
  const out = [];
  const err = new Error('未找到 claude');
  err.code = 'CLAUDE_NOT_FOUND';
  const factory = () => { throw err; };
  const d = createDispatcher({ sessionFactory: factory, out: (m) => out.push(m) });

  d.handle(START);

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'CLAUDE_NOT_FOUND');
  assert.equal(e.fatal, true);
  assert.equal(d.getSession(), null, '建会话失败时不应留下半成品 session');
});

test('建会话的其他异常归一为 SDK_INIT_FAILED', () => {
  const out = [];
  const factory = () => { throw new Error('某个内部错误'); };
  const d = createDispatcher({ sessionFactory: factory, out: (m) => out.push(m) });

  d.handle(START);

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'SDK_INIT_FAILED');
  assert.match(e.message, /某个内部错误/);
});

test('未知 method 上报错误但不崩溃', () => {
  const out = [];
  const d = createDispatcher({ sessionFactory: () => ({}), out: (m) => out.push(m) });
  d.handle({ id: '1', method: 'nonsense', params: {} });
  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'UNKNOWN_METHOD');
  assert.equal(e.fatal, false);
});

test('畸形输入被忽略而非抛错', () => {
  const out = [];
  const d = createDispatcher({ sessionFactory: () => ({}), out: (m) => out.push(m) });
  assert.doesNotThrow(() => d.handle(null));
  assert.doesNotThrow(() => d.handle('string'));
  assert.doesNotThrow(() => d.handle({ id: 'x' }));   // 无 method
  assert.equal(out.length, 1, '仅无 method 那条产生 UNKNOWN_METHOD');
});
