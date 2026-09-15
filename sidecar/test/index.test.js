import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
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
        // 图单独记一份：sent 保持"只装文字"，既有断言一个都不用改
        sentImages: [],
        send(t, images = []) { this.sent.push(t); this.sentImages.push(images); },
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

test('图片透传给会话', () => {
  // 贴图的整条路：Kotlin 编好 base64 → 这一层原样送到会话 → session.js 拼 content blocks。
  // 中间这一层最容易被写成"只转发 text"（改 preStartQueue 时尤其），所以单独钉一条
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  d.handle(START);
  const img = { mediaType: 'image/png', data: 'AAAABBBB' };

  d.handle({ id: '2', method: 'send', params: { text: '看这张', images: [img] } });

  const s = d.getSession();
  assert.deepEqual(s.sentImages.at(-1), [img], '图片没送到会话，界面上的图会静默消失');
});

test('未 start 时的图跟着消息一起补发', () => {
  // 计划里点名的那个坑：preStartQueue 从"存字符串"改成"存对象"，
  // 补发那一侧漏改一处，就成了"ready 之前发的图被悄悄丢掉"
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  const img = { mediaType: 'image/jpeg', data: 'ZZZZ' };

  d.handle({ id: '1', method: 'send', params: { text: '早发的', images: [img] } });
  const s = d.handle(START);

  assert.deepEqual(s.sent, ['早发的']);
  assert.deepEqual(s.sentImages.at(-1), [img], '补发时把图落下了');
});

test('没图时传的是空数组，不是 undefined', () => {
  // session.js 的签名是 send(text, images = [])，这里显式传空数组是为了让
  // "有图/没图"在日志里一眼可辨
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  d.handle(START);

  d.handle({ id: '2', method: 'send', params: { text: '光文字' } });

  assert.deepEqual(d.getSession().sentImages.at(-1), []);
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

// ---- 思考深度（setEffort）----

/** 起一个会话，并给它挂上 setEffort。返回 [dispatcher, out, 收到的档位]。 */
function withEffortSession(setEffort) {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle(START);
  const got = [];
  s.setEffort = async (level) => {
    got.push(level);
    return setEffort ? setEffort(level) : undefined;
  };
  return { d, out, got };
}

test('setEffort 成功后回报新档位', async () => {
  // 与权限模式同一条规矩：界面等这条回执才改标签，
  // 否则切换失败时标签会显示一个没生效的档位
  const { d, out, got } = withEffortSession();

  d.handle({ id: '2', method: 'setEffort', params: { level: 'xhigh' } });
  await tick();

  assert.deepEqual(got, ['xhigh'], '档位必须原样到达会话');
  const ack = out.find((m) => m.type === 'effortChanged');
  assert.ok(ack, '没有回执，界面无从知道切换是否生效');
  assert.equal(ack.level, 'xhigh');
});

test('setEffort(null) 的回执带显式 null，表示回到默认档', async () => {
  // null 是有效取值，不是"没传参"。回执把它丢掉的话，
  // 插件就分不清「回到默认」和「对端协议不一致」了
  const { d, out, got } = withEffortSession();

  d.handle({ id: '2', method: 'setEffort', params: { level: null } });
  await tick();

  assert.deepEqual(got, [null], '"回到默认"要真的把 null 传下去，不能被吞成 undefined');
  const ack = out.find((m) => m.type === 'effortChanged');
  assert.ok(ack);
  assert.equal(ack.level, null);
  assert.ok('level' in ack, '字段本身必须在 —— 插件靠它区分"默认"和"畸形消息"');
});

test('setEffort 缺 level 参数时回错误，不猜成回到默认', async () => {
  // 显式 null 与"字段缺失"是两回事：后者说明对方写错了协议。
  // 猜成"回到默认"会把一个 bug 变成一次静默的行为改变
  const { d, out, got } = withEffortSession();

  d.handle({ id: '2', method: 'setEffort', params: {} });
  await tick();

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'SET_EFFORT_FAILED');
  assert.equal(e.fatal, false);
  assert.deepEqual(got, [], '参数都不全，不该去碰会话');
  assert.ok(!out.some((m) => m.type === 'effortChanged'));
});

test('setEffort 认不出的档位直接拒绝，不转发给 SDK', async () => {
  // CLI 对认不出的**字符串**未必报错，可能直接忽略 —— 那时我们会发出
  // 一条"切换成功"的回执，而档位根本没变。白名单挡的就是这个
  const { d, out, got } = withEffortSession();

  d.handle({ id: '2', method: 'setEffort', params: { level: 'ultra' } });
  await tick();

  assert.deepEqual(got, [], '脏值不该被转发给 SDK');
  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'SET_EFFORT_FAILED');
  assert.ok(!out.some((m) => m.type === 'effortChanged'));
});

test('会话不支持 setEffort 时回错误，不静默成功', async () => {
  // 老会话没有这个方法。可选链写法在这里会 resolve，
  // 于是标签会显示一个没生效的档位
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  d.handle(START);   // 刻意不挂 setEffort

  d.handle({ id: '2', method: 'setEffort', params: { level: 'high' } });
  await tick();

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'SET_EFFORT_FAILED');
  assert.equal(e.fatal, false, '切换失败不该断开整个会话');
  assert.ok(!out.some((m) => m.type === 'effortChanged'),
    '失败时不能报成功，否则界面会显示一个没生效的档位');
});

test('还没 start 就 setEffort 时回错误，不抛', async () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });

  d.handle({ id: '2', method: 'setEffort', params: { level: 'low' } });

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'SET_EFFORT_FAILED');
  assert.equal(e.fatal, false);
});

test('setEffort 被底层拒绝时上报错误，且不回报成功', async () => {
  const { d, out } = withEffortSession(() => {
    throw new Error('effortLevel must be a string or null');
  });

  d.handle({ id: '2', method: 'setEffort', params: { level: 'high' } });
  await tick();

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'SET_EFFORT_FAILED');
  assert.match(e.message, /string or null/, '底层原因要带到界面上');
  assert.ok(!out.some((m) => m.type === 'effortChanged'));
});

// ---- 换模型（setModel）----

/** 起一个会话，并给它挂上 setModel。返回 [dispatcher, out, 收到的模型名]。 */
function withModelSession(setModel) {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle(START);
  const got = [];
  s.setModel = async (model) => {
    got.push(model);
    return setModel ? setModel(model) : undefined;
  };
  return { d, out, got };
}

test('setModel 成功后回报新模型', async () => {
  // 与权限模式、思考深度同一条规矩：界面等这条回执才改标签，
  // 否则切换失败时标签会显示一个没生效的模型
  const { d, out, got } = withModelSession();

  d.handle({ id: '2', method: 'setModel', params: { model: 'deepseek-v4-pro[1m]' } });
  await tick();

  assert.deepEqual(got, ['deepseek-v4-pro[1m]'], '模型名必须原样到达会话');
  const ack = out.find((m) => m.type === 'modelChanged');
  assert.ok(ack, '没有回执，界面无从知道切换是否生效');
  assert.equal(ack.model, 'deepseek-v4-pro[1m]', '回执要回显我们发出去的那个名字 —— 界面拿它做等值校验');
});

test('setModel 缺 model 参数时回错误，不转发', async () => {
  const { d, out, got } = withModelSession();

  d.handle({ id: '2', method: 'setModel', params: {} });
  await tick();

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'SET_MODEL_FAILED');
  assert.equal(e.fatal, false);
  assert.deepEqual(got, [], '参数都不全，不该去碰会话');
  assert.ok(!out.some((m) => m.type === 'modelChanged'));
});

test('setModel 的空名字与非字符串都拒绝', async () => {
  // 与档位那条**刻意不同**：档位是闭集所以要白名单，模型名是用户在网关上
  // 定义的、sidecar 无从知道，认不出的名字会在下一轮请求时响亮地失败。
  // 但空名字要挡 —— setModel 表达不了"不要模型"，空名字只会变成一次莫名其妙的请求
  for (const bad of ['', '   ', 123, null, { model: 'x' }]) {
    const { d, out, got } = withModelSession();

    d.handle({ id: '2', method: 'setModel', params: { model: bad } });
    await tick();

    const e = out.find((m) => m.type === 'error');
    assert.equal(e.code, 'SET_MODEL_FAILED', `没挡住：${JSON.stringify(bad)}`);
    assert.deepEqual(got, [], `脏值被转发了：${JSON.stringify(bad)}`);
    assert.ok(!out.some((m) => m.type === 'modelChanged'));
  }
});

test('会话不支持 setModel 时回错误，不静默成功', async () => {
  // 老会话没有这个方法。可选链写法在这里会 resolve，
  // 于是标签会显示一个没生效的模型
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  d.handle(START);   // 刻意不挂 setModel

  d.handle({ id: '2', method: 'setModel', params: { model: 'x' } });
  await tick();

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'SET_MODEL_FAILED');
  assert.equal(e.fatal, false, '切换失败不该断开整个会话');
  assert.ok(!out.some((m) => m.type === 'modelChanged'),
    '失败时不能报成功，否则界面会显示一个没生效的模型');
});

test('还没 start 就 setModel 时回错误，不抛', async () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });

  d.handle({ id: '2', method: 'setModel', params: { model: 'x' } });

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'SET_MODEL_FAILED');
  assert.equal(e.fatal, false);
});

test('setModel 被底层拒绝时上报错误，且不回报成功', async () => {
  const { d, out } = withModelSession(() => {
    throw new Error('Unsupported control request subtype: set_model');
  });

  d.handle({ id: '2', method: 'setModel', params: { model: 'x' } });
  await tick();

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'SET_MODEL_FAILED');
  assert.match(e.message, /set_model/, '底层原因要带到界面上');
  assert.ok(!out.some((m) => m.type === 'modelChanged'));
});

// ---- 上下文用量（contextUsage）----

/** 起一个会话并给它挂上 contextUsage。 */
function withUsageSession(fn) {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle(START);
  s.contextUsage = async () => fn();
  return { d, out };
}

test('contextUsage 把 CLI 的读数按协议字段回报', async () => {
  const { d, out } = withUsageSession(async () => ({
    totalTokens: 456990,
    rawMaxTokens: 1000000,
    maxTokens: 1000000,
  }));

  d.handle({ id: '9', method: 'contextUsage', params: {} });
  await tick();

  const ack = out.find((m) => m.type === 'contextUsage');
  assert.ok(ack, '没有应答，卡片就一直没有数');
  assert.equal(ack.id, '9', 'id 必须回传 —— 插件靠它配对');
  assert.equal(ack.usedTokens, 456990);
  assert.equal(ack.windowTokens, 1000000);
});

test('contextUsage：rawMaxTokens 缺失时退回 maxTokens 当分母', async () => {
  const { d, out } = withUsageSession(async () => ({ totalTokens: 5, maxTokens: 200000 }));

  d.handle({ id: '9', method: 'contextUsage', params: {} });
  await tick();

  assert.equal(out.find((m) => m.type === 'contextUsage').windowTokens, 200000);
});

test('contextUsage 失败时回错误，不静默给 0', async () => {
  // 静默给 0 的话，"卡片一直是 0"和"上下文真的是空的"在界面上长得一模一样
  const { d, out } = withUsageSession(async () => {
    throw new Error('当前 CLI 不支持读取上下文用量');
  });

  d.handle({ id: '9', method: 'contextUsage', params: {} });
  await tick();

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'CONTEXT_USAGE_FAILED');
  assert.equal(e.fatal, false, '读不到用量不该断开整个会话');
  assert.ok(!out.some((m) => m.type === 'contextUsage'), '失败时不能报一个假读数');
});

test('还没 start 就 contextUsage 时回错误，不抛', async () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });

  d.handle({ id: '9', method: 'contextUsage', params: {} });

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'NO_SESSION');
  assert.equal(e.fatal, false);
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
function fakeSessionApi({
  listError = null,
  historyError = null,
  deleteFails = false,
  updateError = null,
  subagentError = null,
} = {}) {
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
      // **位置参数**：SDK 的签名是 (sessionId, options)。签名写错了假实现
      // 照样"成功"，所以这里的形状必须和真的一模一样
      deleteSession: async (sid, opts) => {
        calls.push(['deleteSession', sid, opts]);
        if (deleteFails) throw new Error('找不到这个会话');
      },
      renameSession: async (sid, title, opts) => {
        calls.push(['renameSession', sid, title, opts]);
        if (updateError) throw new Error(updateError);
      },
      tagSession: async (sid, tag, opts) => {
        calls.push(['tagSession', sid, tag, opts]);
        if (updateError) throw new Error(updateError);
      },
      // 回读：改了名之后由它给出权威值
      getSessionInfo: async (sid) => ({ sessionId: sid, customTitle: '新名字', tag: 'wip' }),
      listSubagents: async (sid, opts) => {
        calls.push(['listSubagents', sid, opts]);
        if (subagentError) throw new Error(subagentError);
        // 真实的 agentId **不带** agent- 前缀（文件名才是 agent-<id>.jsonl）
        return ['a1b2c3d4'];
      },
      getSubagentMessages: async (sid, aid, opts) => {
        calls.push(['getSubagentMessages', sid, aid, opts]);
        if (subagentError) throw new Error(subagentError);
        return [{ type: 'user', message: { role: 'user', content: '干活' } }];
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
      ['customTitle', 'firstPrompt', 'lastModified', 'sessionId', 'summary', 'tag'],
      'SDK 的 gitBranch / fileSize / cwd 不该过线（customTitle 与 tag 是界面要用的）',
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
  // **位置参数**。这条原本断言的是 `{ sessionId: 'sess-9' }` —— 它把错误的
  // 调用形状当成期望值钉住了，于是删除坏了整整一版都没人发现
  assert.equal(fa.calls[0][1], 'sess-9');
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

test('listCommands 把命令与技能一并上报', async () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  d.handle(START);

  // 给假 session 补上这两个方法
  const s = d.getSession();
  s.supportedCommands = async () => [{ name: 'compact', description: '压缩', argumentHint: '', aliases: [] }];
  s.skills = async () => [{ name: 'brainstorming', description: '想清楚', argumentHint: '', aliases: [] }];

  d.handle({ id: '9', method: 'listCommands', params: {} });
  await tick();

  const msg = out.find((m) => m.type === 'commands');
  assert.ok(msg, '应有一条 commands 消息');
  assert.equal(msg.id, '9');
  assert.equal(msg.commands.length, 1);
  assert.equal(msg.commands[0].name, 'compact');
  assert.equal(msg.skills[0].name, 'brainstorming');
});

test('未 start 就 listCommands 时回一条错误，不抛', async () => {
  const out = [];
  const d = createDispatcher({ sessionFactory: fakeSessionFactory().factory, out: (m) => out.push(m) });

  d.handle({ id: '9', method: 'listCommands', params: {} });
  await tick();

  assert.equal(out.at(-1).type, 'error');
  assert.equal(out.at(-1).code, 'NO_SESSION');
});

test('命令列表取不到时回空数组，不是错误', async () => {
  // "取不到"这一层的兜底在 session.js（它自己吞掉 control 失败回空数组），
  // 分发层只管透传。真正的吞异常断言在 session.test.js
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  d.handle(START);

  const s = d.getSession();
  s.supportedCommands = async () => [];
  s.skills = async () => [];

  d.handle({ id: '9', method: 'listCommands', params: {} });
  await tick();

  const msg = out.find((m) => m.type === 'commands');
  assert.ok(msg, '空列表也要有一条 commands，不是 error');
  assert.deepEqual(msg.commands, []);
  assert.deepEqual(msg.skills, []);
});

test('会话方法意外抛错时回 error，不静默挂着', async () => {
  // session.js 是吞异常的那一层，所以走到这里说明出了它没兜住的事。
  // 那时必须出声 —— 静默的话插件会一直等一条永远不来的回执
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  d.handle(START);

  const s = d.getSession();
  s.supportedCommands = async () => { throw new Error('control 请求失败'); };
  s.skills = async () => { throw new Error('control 请求失败'); };

  d.handle({ id: '9', method: 'listCommands', params: {} });
  await tick();

  assert.ok(out.some((m) => m.type === 'error' && m.code === 'LIST_COMMANDS_FAILED'));
  assert.ok(!out.some((m) => m.type === 'commands'), '失败了不该回一份空列表假装成功');
});

// ---- 会话改名 / 打标签 / 子代理 ----

test('deleteSession 用位置参数调 SDK —— 传对象会被 UUID 校验挡下', async () => {
  // 回归测试。改之前这里是 `deleteSession({ sessionId })`，而 SDK 的签名是
  // `(sessionId, options?)`，实现第一行就做 UUID 校验 —— 传对象必然抛
  // "Invalid sessionId: [object Object]"，也就是删除**从来没成功过**。
  // 假实现来者不拒，所以形状错了也全绿；这条钉的就是形状
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'deleteSession', params: { sessionId: 'sess-9' } });
  await tick();

  assert.equal(fa.calls[0][0], 'deleteSession');
  assert.equal(fa.calls[0][1], 'sess-9', '第一个参数必须是 sessionId 本身');
  assert.ok(out.some((m) => m.type === 'sessionDeleted'));
});

test('renameSession 回报**回读**来的名字，不是我们刚发出去的那个', async () => {
  // 回读才知道写入真的落下了。这里是假的 getSessionInfo 返回 '新名字'，
  // 而请求里发的是 '我起的'
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({
    id: 'r1', method: 'renameSession', params: { sessionId: 'sess-9', title: '我起的', dir: '/proj' },
  });
  await tick();

  assert.deepEqual(fa.calls[0], ['renameSession', 'sess-9', '我起的', { dir: '/proj' }]);
  const msg = out.find((m) => m.type === 'sessionRenamed');
  assert.ok(msg, '改名必须有回执，界面等它才动那一行');
  assert.equal(msg.value, '新名字');
  assert.equal(msg.id, 'r1');
});

test('renameSession 缺 title 时回错误，不猜成"清掉名字"', async () => {
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'renameSession', params: { sessionId: 'sess-9' } });
  await tick();

  assert.equal(out.find((m) => m.type === 'error').code, 'SESSION_UPDATE_FAILED');
  assert.ok(!out.some((m) => m.type === 'sessionRenamed'));
});

test('renameSession 失败时上报，且不回报成功', async () => {
  const out = [];
  const fa = fakeSessionApi({ updateError: '文件被占用' });
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'renameSession', params: { sessionId: 's', title: 'x' } });
  await tick();

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'SESSION_UPDATE_FAILED');
  assert.match(e.message, /文件被占用/);
  assert.ok(!out.some((m) => m.type === 'sessionRenamed'));
});

test('tagSession 的 null 是"清掉"，要原样传下去', async () => {
  // 与 setEffort 同一条：null 是有效取值，不是"没传参"。
  // 吞成 undefined 的话，"清除标签"会变成什么都不做
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({ id: 'r1', method: 'tagSession', params: { sessionId: 's', tag: null } });
  await tick();

  assert.equal(fa.calls[0][1], 's');
  assert.equal(fa.calls[0][2], null, '清标签要真的把 null 传下去');
  assert.ok(out.some((m) => m.type === 'sessionTagged'));
});

test('tagSession 缺 tag 键时回错误，不猜成清除', async () => {
  const out = [];
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fakeSessionApi().api,
  });

  d.handle({ id: 'r1', method: 'tagSession', params: { sessionId: 's' } });
  await tick();

  assert.equal(out.find((m) => m.type === 'error').code, 'SESSION_UPDATE_FAILED');
});

test('listSubagents 把磁盘上的元信息并进列表', async () => {
  // SDK 只给 id 列表；类型 / 描述 / **toolUseId** 都在 agent-<id>.meta.json 里，
  // 而最后那个是界面把它和"运行中的任务"对上号的唯一凭据
  const root = mkdtempSync(join(tmpdir(), 'ccoder-sub-'));
  try {
    const dir = join(root, 'proj-A', 'sess-1', 'subagents');
    mkdirSync(dir, { recursive: true });
    writeFileSync(
      join(dir, 'agent-a1b2c3d4.meta.json'),
      JSON.stringify({ agentType: 'Explore', description: '找调用点', toolUseId: 'call_9' }),
    );

    const out = [];
    const fa = fakeSessionApi();
    const d = createDispatcher({
      sessionFactory: fakeSessionFactory().factory,
      out: (m) => out.push(m),
      sessionApi: fa.api,
      projectsRoot: root,
    });

    d.handle({ id: 'r1', method: 'listSubagents', params: { sessionId: 'sess-1', dir: '/proj' } });
    await tick();

    const msg = out.find((m) => m.type === 'subagents');
    assert.ok(msg, '没有应答，浮层就一直是空的');
    assert.deepEqual(msg.agents, [
      { agentId: 'a1b2c3d4', agentType: 'Explore', description: '找调用点', toolUseId: 'call_9' },
    ]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('listSubagents 读不到元信息时只给 id，不整条失败', async () => {
  // 元信息读不到是常事（文件不在、被清过）。整条请求失败的话，
  // 那几个子代理在界面上会彻底消失 —— 而它们确实存在
  const out = [];
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fakeSessionApi().api,
    projectsRoot: join(tmpdir(), 'ccoder-does-not-exist'),
  });

  d.handle({ id: 'r1', method: 'listSubagents', params: { sessionId: 'sess-1' } });
  await tick();

  assert.deepEqual(out.find((m) => m.type === 'subagents').agents, [
    { agentId: 'a1b2c3d4', agentType: null, description: null, toolUseId: null },
  ]);
});

test('listSubagents 缺 sessionId 时回错误', async () => {
  const out = [];
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fakeSessionApi().api,
  });

  d.handle({ id: 'r1', method: 'listSubagents', params: {} });
  await tick();

  assert.equal(out.find((m) => m.type === 'error').code, 'SUBAGENTS_FAILED');
});

test('subagentMessages 回传某个子代理的转写', async () => {
  const out = [];
  const fa = fakeSessionApi();
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fa.api,
  });

  d.handle({
    id: 'r1', method: 'subagentMessages', params: { sessionId: 's', agentId: 'a1', dir: '/proj' },
  });
  await tick();

  assert.deepEqual(fa.calls[0], ['getSubagentMessages', 's', 'a1', { dir: '/proj' }]);
  const msg = out.find((m) => m.type === 'subagentMessages');
  assert.equal(msg.agentId, 'a1', '带 agentId 回去，界面才知道这是谁的转写');
  assert.equal(msg.items.length, 1);
});

test('subagentMessages 缺 agentId 时回错误', async () => {
  const out = [];
  const d = createDispatcher({
    sessionFactory: fakeSessionFactory().factory,
    out: (m) => out.push(m),
    sessionApi: fakeSessionApi().api,
  });

  d.handle({ id: 'r1', method: 'subagentMessages', params: { sessionId: 's' } });
  await tick();

  assert.equal(out.find((m) => m.type === 'error').code, 'SUBAGENT_MESSAGES_FAILED');
});
