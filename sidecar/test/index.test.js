import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDispatcher } from '../index.js';

/** 等一拍，让 dispatcher 里 await 的那个 Promise 落地。 */
const tick = () => new Promise((r) => setImmediate(r));

function fakeSessionFactory({ setModeError = null } = {}) {
  const calls = [];
  return {
    calls,
    factory(opts) {
      const s = {
        opts,
        sent: [],
        send(t) { this.sent.push(t); },
        interrupt: async () => { calls.push(['interrupt']); },
        setPermissionMode: async (m) => {
          calls.push(['setPermissionMode', m]);
          if (setModeError) throw new Error(setModeError);
        },
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

test('permissionDecision 透传 updatedInput', () => {
  // AskUserQuestion 的答案就是这么回传的：允许这个工具调用时改写它的入参。
  // PermissionResult 的 allow 分支带 updatedInput（sdk.d.ts:2340）
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  d.handle(START);

  d.handle({
    id: '2', method: 'permissionDecision',
    params: {
      requestId: 'tu-1', behavior: 'allow',
      updatedInput: { questions: [], answers: { '你希望我做什么？': '继续改动' } },
    },
  });

  const call = sf.calls.find((c) => c[0] === 'decide');
  assert.deepEqual(call[2].updatedInput.answers, { '你希望我做什么？': '继续改动' });
});

test('permissionDecision 不带 updatedInput 时不塞这个字段', () => {
  // 传 undefined 与不传语义不同：塞一个空对象进去，
  // SDK 会当成"显式把入参改写成了空"
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  d.handle(START);

  d.handle({ id: '2', method: 'permissionDecision',
             params: { requestId: 'tu-1', behavior: 'allow' } });

  const call = sf.calls.find((c) => c[0] === 'decide');
  assert.ok(!('updatedInput' in call[2]), '不该凭空多出 updatedInput');
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

test('setPermissionMode 成功后回报新模式', async () => {
  // 界面必须等这个回执才更新标签。先改标签、后等结果的话，
  // 切换失败时界面就会显示一个没生效的模式 —— 安全控件撒谎比不好用严重。
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  d.handle(START);

  d.handle({ id: '2', method: 'setPermissionMode', params: { mode: 'plan' } });
  await tick();

  const ack = out.find((m) => m.type === 'permissionModeChanged');
  assert.ok(ack, '没有回执，界面无从知道切换是否生效');
  assert.equal(ack.mode, 'plan');
});

test('setPermissionMode 被拒时上报错误，且不回报成功', async () => {
  // 改之前这里是 `session?.setPermissionMode?.(mode)` —— 不 await，
  // 拒绝就成了一条 unhandled rejection，界面上什么都看不见
  const out = [];
  const sf = fakeSessionFactory({ setModeError: 'bypass 需要启动时开启' });
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  d.handle(START);

  d.handle({ id: '2', method: 'setPermissionMode', params: { mode: 'bypassPermissions' } });
  await tick();

  const e = out.find((m) => m.type === 'error');
  assert.ok(e, '切换失败必须上报，不能静默吞掉');
  assert.equal(e.code, 'SET_MODE_FAILED');
  assert.equal(e.fatal, false, '切换失败不该断开整个会话');
  assert.match(e.message, /bypass 需要启动时开启/);
  assert.ok(!out.some((m) => m.type === 'permissionModeChanged'),
    '失败时不能报成功，否则界面会显示一个没生效的模式');
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

// ---- 会话列表与历史（Task 3）----

/** 假的 SDK 会话 API，用来把真实 SDK 挡在单测之外。 */
function fakeSessionApi({ listError = null, historyError = null, deleteFails = false } = {}) {
  const calls = [];
  return {
    calls,
    api: {
      listSessions: async (opts) => {
        calls.push(['listSessions', opts]);
        if (listError) throw new Error(listError);
        return [
          {
            sessionId: 'a', summary: '标题甲', firstPrompt: '甲', lastModified: 111,
            gitBranch: 'v0.2.0-dev', fileSize: 999, cwd: '/x', tag: null,
          },
        ];
      },
      getSessionMessages: async (sid, opts) => {
        calls.push(['getSessionMessages', sid, opts]);
        if (historyError) throw new Error(historyError);
        return [{ type: 'user', message: { role: 'user', content: '你好' } }];
      },
      deleteSession: async (opts) => {
        calls.push(['deleteSession', opts]);
        if (deleteFails) throw new Error('找不到这个会话');
      },
    },
  };
}

test('listSessions 不需要活会话也能应答', () => {
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  // 刻意不 start —— 面板一打开就要能列出历史，这是设计的关键点
  d.handle({ id: 'r1', method: 'listSessions', params: { dir: '/proj', limit: 50, offset: 0 } });

  return tick().then(() => {
    const msg = out.find((m) => m.type === 'sessions');
    assert.ok(msg, '没有活会话时也必须应答');
    assert.equal(msg.id, 'r1', '响应必须回显请求 id');
    assert.equal(msg.sessions.length, 1);
    assert.equal(msg.sessions[0].summary, '标题甲');
    assert.equal(fa.calls[0][1].dir, '/proj');
  });
});

test('listSessions 只传界面要用的字段', () => {
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'listSessions', params: { dir: '/proj' } });

  return tick().then(() => {
    const s = out.find((m) => m.type === 'sessions').sessions[0];
    assert.deepEqual(
      Object.keys(s).sort(),
      ['firstPrompt', 'lastModified', 'sessionId', 'summary'],
      'SDK 的 gitBranch / fileSize / cwd / tag 不该过线',
    );
  });
});

test('listSessions 不传 includeProgrammatic', () => {
  // 回归测试。SDK 文档说 IDE 选择器该传 false —— 但那会把插件自己的
  // 会话也滤掉（实测本机 19/19 全被滤）。传 false 不报错，只是安静地
  // 返回空，所以必须由测试守住这条
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'listSessions', params: { dir: '/proj' } });

  return tick().then(() => {
    const opts = fa.calls[0][1];
    assert.ok(!('includeProgrammatic' in opts), '传了它会把插件自己的会话一起滤掉');
    assert.ok(!('includeWorktrees' in opts), 'worktree 参数本版用不上，不要顺手带上');
  });
});

test('listSessions 出错时以 error 回执而非静默', () => {
  const out = [];
  const fa = fakeSessionApi({ listError: '读不了' });
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'listSessions', params: { dir: '/proj' } });

  return tick().then(() => {
    const err = out.find((m) => m.type === 'error');
    assert.equal(err.code, 'LIST_SESSIONS_FAILED');
    assert.match(err.message, /读不了/);
    assert.equal(err.fatal, false, '列不出会话不该杀掉会话');
  });
});

test('loadHistory 把条目原样透传', () => {
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r2', method: 'loadHistory', params: { dir: '/proj', sessionId: 'sess-1' } });

  return tick().then(() => {
    const msg = out.find((m) => m.type === 'history');
    assert.equal(msg.id, 'r2');
    assert.equal(msg.sessionId, 'sess-1');
    assert.equal(msg.items.length, 1);
    assert.equal(msg.items[0].type, 'user');
    assert.deepEqual(fa.calls[0], ['getSessionMessages', 'sess-1', { dir: '/proj' }]);
  });
});

test('loadHistory 出错时以 error 回执', () => {
  const out = [];
  const fa = fakeSessionApi({ historyError: '会话不存在' });
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r2', method: 'loadHistory', params: { dir: '/proj', sessionId: 'nope' } });

  return tick().then(() => {
    const err = out.find((m) => m.type === 'error');
    assert.equal(err.code, 'LOAD_HISTORY_FAILED');
    assert.match(err.message, /会话不存在/);
  });
});

test('start 把 resumeSessionId 透传给 sessionFactory', () => {
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {}, sessionApi: fakeSessionApi().api });

  d.handle({ id: '1', method: 'start', params: { cwd: '/tmp', permissionMode: 'default', resumeSessionId: 'sess-9' } });

  const created = sf.calls.find((c) => c[0] === 'create')[1];
  assert.equal(created.resumeSessionId, 'sess-9');
});

// ---- 删除会话（Task 2）----

test('deleteSession 不需要活会话也能应答', () => {
  // 删除是列表上的动作，不该要求先起会话 —— 同 listSessions
  const fa = fakeSessionApi();
  const out = [];
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'deleteSession', params: { sessionId: 'sess-9' } });

  assert.equal(fa.calls.length, 1);
  assert.deepEqual(fa.calls[0][1], { sessionId: 'sess-9' });
});

test('删除成功回 sessionDeleted，且带 id 回显', async () => {
  const fa = fakeSessionApi();
  const out = [];
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r7', method: 'deleteSession', params: { sessionId: 'sess-9' } });
  await tick();

  const ack = out.find((m) => m.type === 'sessionDeleted');
  assert.ok(ack, '没有回 sessionDeleted');
  assert.equal(ack.id, 'r7');
  assert.equal(ack.sessionId, 'sess-9');
});

// ---- 带图发送（Task 5）----

test('send 把图透传给 session', () => {
  const calls = [];
  const d = createDispatcher({
    sessionFactory: () => ({ send: (t, i) => calls.push([t, i]) }),
    out: () => {},
  });
  d.handle({ method: 'start', params: { cwd: '/x' } });
  d.handle({ method: 'send', params: { text: '看', images: [{ mediaType: 'image/png', data: 'A' }] } });

  assert.equal(calls[0][0], '看');
  assert.deepEqual(calls[0][1], [{ mediaType: 'image/png', data: 'A' }]);
});

test('start 之前到达的 send 连图一起补发', () => {
  // 排队的是整个条目而不只是文本 —— 只存 text 的话抢跑发的那几张图会被丢掉，
  // 而且用户看到的是一句没有图的话
  const calls = [];
  const d = createDispatcher({
    sessionFactory: () => ({ send: (t, i) => calls.push([t, i]) }),
    out: () => {},
  });
  d.handle({ method: 'send', params: { text: '看', images: [{ mediaType: 'image/png', data: 'A' }] } });
  assert.equal(calls.length, 0);

  d.handle({ method: 'start', params: { cwd: '/x' } });

  assert.equal(calls[0][0], '看');
  assert.deepEqual(calls[0][1], [{ mediaType: 'image/png', data: 'A' }]);
});

test('没有 images 字段时传空数组而非 undefined', () => {
  // 老插件（0.2.4 及以前）发的 send 里根本没有 images 这个字段。
  // 归一成空数组，session 侧就只有一个形状要处理 —— 纯文本走哪条分支由
  // content 决定，不靠第二个参数是不是 undefined 来猜
  const calls = [];
  const d = createDispatcher({
    sessionFactory: () => ({ send: (t, i) => calls.push([t, i]) }),
    out: () => {},
  });
  d.handle({ method: 'start', params: { cwd: '/x' } });
  d.handle({ method: 'send', params: { text: '纯文本' } });

  assert.equal(calls[0][0], '纯文本');
  assert.deepEqual(calls[0][1], []);
});

test('删除失败回 error，且不回 sessionDeleted', async () => {
  // 会话已经被终端删掉时 SDK 会抛错。必须如实报出去 ——
  // 假装删成功了，界面上那一行就会消失，而那是在撒谎（见设计稿 §4.4）
  const fa = fakeSessionApi({ deleteFails: true });
  const out = [];
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r8', method: 'deleteSession', params: { sessionId: 'gone' } });
  await tick();

  assert.ok(out.some((m) => m.type === 'error' && m.code === 'DELETE_FAILED'));
  assert.ok(!out.some((m) => m.type === 'sessionDeleted'), '失败了不该回成功回执');
});
