import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSession } from '../session.js';

/** 记录调用参数的假 query()，按脚本产出事件。 */
function fakeQuery(script = []) {
  const calls = { options: null, prompts: [] };
  async function* gen(prompt) {
    calls.prompts.push(prompt);
    for (const item of script) yield item;
  }
  return {
    calls,
    fn(params) {
      calls.options = params.options;
      return Object.assign(gen(params.prompt), {
        interrupt: async () => { calls.interrupted = true; },
        setPermissionMode: async (m) => { calls.permissionMode = m; },
      });
    },
  };
}

test('必须使用流式输入模式，不能传字符串 prompt', () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('hello');
  // 流式输入模式的判据：prompt 不是 string
  assert.notEqual(typeof q.calls.prompts[0], 'string',
    'prompt 必须是 AsyncIterable —— 传字符串会丢失 interrupt/setPermissionMode 能力');
});

test('send 的内容以 SDKUserMessage 形状进入输入流', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('第一条');
  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const { value } = await iter.next();
  assert.equal(value.type, 'user');
  assert.equal(value.message.role, 'user');
  assert.equal(value.message.content, '第一条');
  assert.equal(value.parent_tool_use_id, null);
});

test('多条 send 按序进入输入流', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('一');
  s.send('二');
  s.send('三');
  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const got = [];
  for (let i = 0; i < 3; i++) got.push((await iter.next()).value.message.content);
  assert.deepEqual(got, ['一', '二', '三']);
});

test('带图发送：content 是内容块数组，图在前文字在后', async () => {
  // 顺序与 CLI 自己写进会话 jsonl 的一致（实测真实会话：先图后文）。
  // 队列是闭包私有的，但输入流这侧本来就是公开接缝 —— prompt 是 AsyncIterable
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('看看这个', [{ mediaType: 'image/png', data: 'AAA' }]);
  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const { value } = await iter.next();

  assert.deepEqual(value.message.content, [
    { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'AAA' } },
    { type: 'text', text: '看看这个' },
  ]);
});

test('只发图不带文字时，不塞空的 text 块', async () => {
  // content 里一个 {"type":"text","text":""} 是没意义的内容块，API 侧只会多花 token
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('', [{ mediaType: 'image/png', data: 'AAA' }]);
  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const { value } = await iter.next();

  assert.deepEqual(value.message.content, [
    { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'AAA' } },
  ]);
});

test('多张图按传入顺序排列', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('两张', [
    { mediaType: 'image/png', data: 'A' },
    { mediaType: 'image/jpeg', data: 'B' },
  ]);
  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const { value } = await iter.next();

  assert.deepEqual(
    value.message.content.map((c) => c.source?.data ?? c.text),
    ['A', 'B', '两张'],
  );
});

test('注册了 canUseTool 与 includePartialMessages', () => {
  const q = fakeQuery();
  createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  assert.equal(typeof q.calls.options.canUseTool, 'function');
  assert.equal(q.calls.options.includePartialMessages, true,
    '逐 token 流式显示依赖这个开关');
});

test('canUseTool 通过 onPermission 上报并挂起等待决定', async () => {
  const q = fakeQuery();
  const seen = [];
  const s = createSession({
    cwd: '/tmp', permissionMode: 'default', queryFn: q.fn,
    onPermission: (req) => seen.push(req),
  });
  await new Promise((r) => setImmediate(r));

  let resolved = null;
  const promise = q.calls.options.canUseTool('Read', { file_path: '/x' }, {
    toolUseID: 'tu-1', title: 'Claude wants to read /x', displayName: 'Read file',
    defaultToNo: true, suggestions: [{ type: 'addRules' }],
  }).then((r) => { resolved = r; });

  assert.equal(seen.length, 1);
  assert.equal(seen[0].toolName, 'Read');
  assert.equal(seen[0].requestId, 'tu-1', 'requestId 用 toolUseID');
  assert.equal(seen[0].title, 'Claude wants to read /x');
  assert.equal(seen[0].defaultToNo, true);
  assert.equal(resolved, null, '决定前不应 resolve');

  s.decidePermission('tu-1', { behavior: 'allow' });
  await promise;
  assert.deepEqual(resolved, { behavior: 'allow' });
});

test('canUseTool 缺省的安全字段有合理默认值', async () => {
  const q = fakeQuery();
  const seen = [];
  createSession({
    cwd: '/tmp', permissionMode: 'default', queryFn: q.fn,
    onPermission: (req) => seen.push(req),
  });
  await new Promise((r) => setImmediate(r));

  q.calls.options.canUseTool('Bash', {}, { toolUseID: 'x' });

  assert.equal(seen[0].defaultToNo, false);
  assert.equal(seen[0].suppressAlwaysAllowRule, false);
  assert.equal(seen[0].suggestions, undefined);
});

test('denyAllPending 把所有挂起的权限 resolve 为 deny', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  const opts = { toolUseID: 'x', defaultToNo: false };
  const p1 = q.calls.options.canUseTool('Read', {}, { ...opts, toolUseID: 'a' });
  const p2 = q.calls.options.canUseTool('Write', {}, { ...opts, toolUseID: 'b' });

  s.denyAllPending('会话已终止');

  const [r1, r2] = await Promise.all([p1, p2]);
  assert.equal(r1.behavior, 'deny');
  assert.equal(r2.behavior, 'deny');
  assert.match(r1.message, /会话已终止/);
});

test('stop() 会清空待决权限并关闭输入流', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  const p = q.calls.options.canUseTool('Read', {}, { toolUseID: 'z', defaultToNo: false });
  s.stop();
  const r = await p;
  assert.equal(r.behavior, 'deny', 'stop 必须 resolve 而非遗留挂起 —— 工具没有 park deadline');

  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const done = await iter.next();
  assert.equal(done.done, true, 'stop 后输入流应结束');
});

test('重复决定同一 requestId 不产生副作用', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  const p = q.calls.options.canUseTool('Read', {}, { toolUseID: 'dup', defaultToNo: false });
  s.decidePermission('dup', { behavior: 'allow' });
  s.decidePermission('dup', { behavior: 'deny', message: 'second' });
  const r = await p;
  assert.equal(r.behavior, 'allow', '第一次决定生效，第二次被忽略');
});

test('interrupt 转发到 SDK Query', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));
  await s.interrupt();
  assert.equal(q.calls.interrupted, true);
});

// 带超时：缺了 denyAllPending 时这个 promise 永远不 resolve，
// 没有超时的话整个测试进程会挂住，回归只会表现为"卡死"而不是"失败"
test('interrupt 同时作废该回合挂起的权限询问', { timeout: 5000 }, async () => {
  // 不清的话卡片会一直留着，用户还能批准一个已不存在的工具调用 ——
  // SDK 文档明说权限询问没有超时
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  const pending = q.calls.options.canUseTool('Bash', {}, { toolUseID: 'p1', defaultToNo: false });
  await s.interrupt();

  const r = await pending;
  assert.equal(r.behavior, 'deny', '中断后挂起的询问必须被拒，不能一直悬着');
});

test('interrupt 保留会话 —— 与 stop 不同', async () => {
  // stop 会销毁会话（index.js 把 session 置 null），界面却仍显示"已连接"，
  // 用户之后再也发不出消息。interrupt 必须只中断当前回合
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));
  await s.interrupt();

  // 会话仍可用：中断之后新的权限询问依然能被正常决定
  const pending = q.calls.options.canUseTool('Read', {}, { toolUseID: 'after', defaultToNo: false });
  s.decidePermission('after', { behavior: 'allow' });
  const r = await pending;
  assert.equal(r.behavior, 'allow', '中断后会话仍能处理新的权限询问');
});

test('setPermissionMode 转发到 SDK Query', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));
  await s.setPermissionMode('acceptEdits');
  assert.equal(q.calls.permissionMode, 'acceptEdits');
});

test('SDK 事件原样透传给 onEvent', async () => {
  const seen = [];
  const q = fakeQuery([
    { type: 'assistant', message: { content: [] } },
    { type: 'some_future_type_v99' },
  ]);
  createSession({
    cwd: '/tmp', permissionMode: 'default', queryFn: q.fn,
    onEvent: (e) => seen.push(e),
  });
  await new Promise((r) => setTimeout(r, 20));

  assert.deepEqual(seen.map((e) => e.type), ['assistant', 'some_future_type_v99']);
});

test('清洗后的环境传给 SDK，不含黑名单变量', () => {
  const q = fakeQuery();
  process.env.CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST = '1';
  process.env.CLAUDE_CODE_EFFORT_LEVEL = 'max';
  try {
    createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
    const env = q.calls.options.env;
    assert.equal(env.CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST, undefined,
      '宿主变量必须被剥离，否则 CLI 会跳过认证');
    assert.equal(env.CLAUDE_CODE_EFFORT_LEVEL, 'max',
      '非黑名单变量必须保留');
  } finally {
    delete process.env.CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST;
    delete process.env.CLAUDE_CODE_EFFORT_LEVEL;
  }
});

test('可选参数仅在提供时传给 SDK', () => {
  const q1 = fakeQuery();
  createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q1.fn });
  assert.ok(!('model' in q1.calls.options));
  assert.ok(!('pathToClaudeCodeExecutable' in q1.calls.options));
  assert.ok(!('additionalDirectories' in q1.calls.options));

  const q2 = fakeQuery();
  createSession({
    cwd: '/tmp', permissionMode: 'default', queryFn: q2.fn,
    model: 'claude-opus-5', claudePath: 'C:\\bin\\claude.exe', extraDirs: ['/other'],
  });
  assert.equal(q2.calls.options.model, 'claude-opus-5');
  assert.equal(q2.calls.options.pathToClaudeCodeExecutable, 'C:\\bin\\claude.exe');
  assert.deepEqual(q2.calls.options.additionalDirectories, ['/other']);
});

test('以 bypassPermissions 启动时补上 allowDangerouslySkipPermissions', () => {
  // SDK 对这个字段的用词是"必须"（sdk.d.ts:1853-1856）：
  // "Must be set to true when using permissionMode: 'bypassPermissions'"。
  // 不传的话这个模式根本生效不了 —— 设置里选得中，实际什么都不绕。
  const q = fakeQuery();
  createSession({ cwd: '/tmp', permissionMode: 'bypassPermissions', queryFn: q.fn });
  assert.equal(q.calls.options.allowDangerouslySkipPermissions, true);
});

test('非 bypass 模式不带这个开关', () => {
  // 它是 SDK 那道"绕过必须有意为之"的闸。图省事常开等于把闸拆了 ——
  // 真有别处误设 bypassPermissions 时，就没有任何东西拦得住了
  const q = fakeQuery();
  createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  assert.ok(!('allowDangerouslySkipPermissions' in q.calls.options));

  const q2 = fakeQuery();
  createSession({ cwd: '/tmp', permissionMode: 'plan', queryFn: q2.fn });
  assert.ok(!('allowDangerouslySkipPermissions' in q2.calls.options));
});

test('默认 queryFn 不能声明为 async', async () => {
  // 实测踩过这个坑：async 函数返回 Promise 而非 Query 对象，
  // for await 报 "query is not async iterable"。当时 65 个单测全绿也没抓到，
  // 因为假的 queryFn 是同步的。
  const { defaultQueryFn } = await import('../session.js');
  assert.equal(typeof defaultQueryFn, 'function');
  assert.notEqual(defaultQueryFn.constructor.name, 'AsyncFunction',
    'async 函数返回 Promise 而非 Query 对象');
});

test('queryFn 返回非 AsyncIterable 时报明确错误', async () => {
  const seen = [];
  createSession({
    cwd: '/tmp', permissionMode: 'default',
    queryFn: async () => ({ fake: true }),
    onEvent: (e) => seen.push(e),
  });
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(seen[0].type, 'ccoder_stream_error');
  assert.match(seen[0].message, /AsyncIterable/,
    '错误信息要指向真正的原因，而不是模糊的迭代失败');
});

test('query 抛错时转为 stream_error 事件而非静默吞掉', async () => {
  const seen = [];
  const boom = () => { throw new Error('SDK 启动失败'); };
  createSession({
    cwd: '/tmp', permissionMode: 'default', queryFn: boom,
    onEvent: (e) => seen.push(e),
  });
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(seen[0].type, 'ccoder_stream_error');
  assert.match(seen[0].message, /SDK 启动失败/);
});
