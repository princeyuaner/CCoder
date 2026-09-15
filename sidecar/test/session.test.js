import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSession } from '../session.js';

/**
 * 记录调用参数的假 query()，按脚本产出事件。
 *
 * [extra] 用来给假 Query 挂额外的方法（比如 applyFlagSettings）——
 * 真实 Query 上有而这里默认没有的，正是"老 CLI 不支持"那种情形，
 * 所以缺省就是缺，不要补空实现。
 */
function fakeQuery(script = [], extra = {}) {
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
      }, extra);
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

// ---- 贴图（2026-09-15）----
//
// 形状来自 sdk.d.ts:5464（SDKUserMessage.message 就是 Messages API 的 MessageParam，
// content 可以是字符串或 content blocks 数组），而图这个 block 的具体写法与
// "这条链路收不收图"是两件事 —— 后者由 tools/probe-image.mjs 实测钉住。

test('带图时 content 是数组，且图排在文字前面', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('这张图哪里不对', [{ mediaType: 'image/png', data: 'AAAA' }]);

  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const { value } = await iter.next();

  assert.ok(Array.isArray(value.message.content), '有图时必须拼成 content 数组');
  assert.deepEqual(value.message.content[0], {
    type: 'image',
    source: { type: 'base64', media_type: 'image/png', data: 'AAAA' },
  });
  assert.deepEqual(
    value.message.content[1],
    { type: 'text', text: '这张图哪里不对' },
    '文字必须在图后面：先看图再读要求',
  );
});

test('多张图按序全进 content', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('两张', [
    { mediaType: 'image/png', data: 'A' },
    { mediaType: 'image/jpeg', data: 'B' },
  ]);

  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const { value } = await iter.next();
  const images = value.message.content.filter((b) => b.type === 'image');

  assert.equal(images.length, 2);
  assert.equal(images[1].source.media_type, 'image/jpeg');
});

test('纯图消息只剩图 —— 不发一个空的 text 块', async () => {
  // 截一张图直接发（没打字）是常见用法；空的 text block 有些网关会当成非法参数
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('', [{ mediaType: 'image/png', data: 'A' }]);

  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const { value } = await iter.next();

  assert.deepEqual(value.message.content.map((b) => b.type), ['image']);
});

test('显式传空数组也走字符串那条老路', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('只有字', []);

  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const { value } = await iter.next();

  assert.equal(value.message.content, '只有字', '没图时 content 必须是字符串，与从前一字不差');
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

test('setEffort 走 applyFlagSettings 把档位原样转发', async () => {
  const applied = [];
  const q = fakeQuery([], {
    applyFlagSettings: async (s) => { applied.push(s); },
  });
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  await s.setEffort('xhigh');

  assert.deepEqual(applied, [{ effortLevel: 'xhigh' }]);
});

test('setEffort(null) 传的是显式 null —— 省略等于什么都没做', async () => {
  // SDK 文档：undefined 会被 JSON 序列化丢掉、没有任何效果。
  // 要"回到默认"必须显式传 null（sdk.d.ts:2695-2700）。
  // 写成 `{ effortLevel: level ?? undefined }` 或 `if (level) {...}` 的话这条会挂
  const applied = [];
  const q = fakeQuery([], {
    applyFlagSettings: async (s) => { applied.push(s); },
  });
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  await s.setEffort(null);

  assert.equal(applied.length, 1);
  assert.ok('effortLevel' in applied[0], '字段必须在，哪怕值是 null');
  assert.equal(applied[0].effortLevel, null);
});

test('没有 applyFlagSettings 时抛错，不静默成功', async () => {
  // 可选链 `query?.applyFlagSettings?.()` 在这里会 await 一个 undefined，
  // 于是"成功"了 —— 上层据此发出一条假回执，标签切过去了而什么都没生效
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  // 错误信息是**面向用户**的（它会原样进转写区），所以钉中文而不是内部 API 名
  await assert.rejects(() => s.setEffort('high'), /思考深度/);
});

test('query 建不起来时 setEffort 也抛错，不静默成功', async () => {
  // queryFn 抛错（返回了 Promise 之类）时 query 会是 null。这正是
  // setPermissionMode 那条的可选链会误报成功的场景
  const s = createSession({
    cwd: '/tmp',
    permissionMode: 'default',
    queryFn: () => { throw new Error('建不起来'); },
  });
  await new Promise((r) => setImmediate(r));

  await assert.rejects(() => s.setEffort('high'), /思考深度/);
});

test('思考深度不进启动参数', () => {
  // Options.effort 会被 SDK 翻成 CLI 的 `--effort`，而中途切换走的是
  // applyFlagSettings 的 flag 层 —— 两个优先级来源。两条一起用的话，
  // 用户选「默认」只清得掉 flag 层、清不掉启动时那个：标签显示默认、
  // 会话照旧按启动档位跑。所以它只有一条路
  const q = fakeQuery();
  createSession({ cwd: '/tmp', permissionMode: 'default', effort: 'max', queryFn: q.fn });

  assert.ok(!('effort' in q.calls.options), '思考深度不该经过启动参数');
});

// ---- 换模型（setModel）----

test('setModel 把模型名原样转发', async () => {
  const got = [];
  const q = fakeQuery([], { setModel: async (m) => { got.push(m); } });
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  await s.setModel('deepseek-v4-pro[1m]');

  assert.deepEqual(got, ['deepseek-v4-pro[1m]']);
});

test('没有 setModel 时抛错，不静默成功', async () => {
  // 同 setEffort：可选链 `query?.setModel?.()` 在这里会 await 一个 undefined，
  // 于是"成功"了 —— 上层据此发出一条假回执，标签切过去了而什么都没生效
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  // 错误信息是**面向用户**的（它会原样进转写区），所以钉中文而不是内部 API 名
  await assert.rejects(() => s.setModel('x'), /换模型/);
});

test('query 建不起来时 setModel 也抛错，不静默成功', async () => {
  const s = createSession({
    cwd: '/tmp',
    permissionMode: 'default',
    queryFn: () => { throw new Error('建不起来'); },
  });
  await new Promise((r) => setImmediate(r));

  await assert.rejects(() => s.setModel('x'), /换模型/);
});

test('模型照旧走启动参数 —— 与思考深度那条正相反', () => {
  // 两边是**刻意**不同的：effort 的「默认」是个需要"清除"的状态，而
  // `Options.effort`（--effort）与 applyFlagSettings 是两个优先级来源，
  // 一起用会互相顶，所以它只有中途那条路。
  // 模型永远是个具体名字、没有"清除"这一档，`Options.model`（--model）与
  // `set_model` 改的是同一个来源 —— 启动带着它，第一轮就是对的
  const q = fakeQuery();
  createSession({ cwd: '/tmp', permissionMode: 'default', model: 'deepseek-v4-flash[1m]', queryFn: q.fn });

  assert.equal(q.calls.options.model, 'deepseek-v4-flash[1m]', '模型必须走启动参数');
});

test('起会话时不主动发 setModel', async () => {
  // 与 `applyEffortToSession` 正相反：模型已经在启动参数里了，ready 之后再补一刀
  // 是多余的（还会多出一条毫无意义的回执）。这条钉住那个"顺手对齐一下"的念头
  const got = [];
  const q = fakeQuery([], { setModel: async (m) => { got.push(m); } });
  createSession({ cwd: '/tmp', permissionMode: 'default', model: 'm', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  assert.deepEqual(got, [], '起会话不该顺手发 setModel');
});

test('contextUsage 走 CLI 的 getContextUsage，且只要 summary', async () => {
  const asked = [];
  const q = fakeQuery([], {
    getContextUsage: async (opts) => {
      asked.push(opts);
      return { totalTokens: 456990, rawMaxTokens: 1000000, percentage: 46 };
    },
  });
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  const cu = await s.contextUsage();

  assert.deepEqual(asked, [{ detail: 'summary' }],
    '只要 summary —— full 会为每个类目各发起一次 token 计数调用');
  assert.equal(cu.totalTokens, 456990);
});

test('没有 getContextUsage 时抛错，不静默给个空读数', async () => {
  // 与 setEffort 同一条规矩：可选链会把"方法不存在"变成"成功"，
  // 卡片从此永远显示 0 而没人知道为什么
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  await assert.rejects(() => s.contextUsage(), /上下文用量/);
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

test('一律带上 allowDangerouslySkipPermissions —— 热切绕过靠这个资格位放行', () => {
  // 它是**资格**不是绕过：起始模式仍由 permissionMode 决定，带了它也不会
  // 让会话以绕过启动。CLI 侧的算法（claude.exe 2.1.268 内嵌 JS）：
  //   isBypassPermissionsModeAvailable = (mode === "bypassPermissions"
  //     || allowDangerouslySkipPermissions) && !被设置禁用 && !restricted
  // 少了它，会话中途切到绕过必失败（探针 probe-bypass-switch.mjs 实测）：
  //   "Cannot set permission mode to bypassPermissions because the session
  //    was not launched with --dangerously-skip-permissions"
  //
  // 遍历全部模式：界面那份列表是"5 个全列、点了生效"，任何一个会话
  // 都可能被切到绕过，所以资格位一个都不能漏
  for (const mode of ['default', 'plan', 'acceptEdits', 'dontAsk', 'bypassPermissions']) {
    const q = fakeQuery();
    createSession({ cwd: '/tmp', permissionMode: mode, queryFn: q.fn });
    assert.equal(
      q.calls.options.allowDangerouslySkipPermissions,
      true,
      `${mode} 会话没带资格位，热切到绕过会失败`,
    );
    assert.equal(q.calls.options.permissionMode, mode, '资格位不能把起始模式带偏');
  }
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

/** 在 fakeQuery 的基础上补上命令相关的 control 方法。 */
function withCommands(q, methods) {
  const orig = q.fn;
  q.fn = (params) => Object.assign(orig(params), methods);
  return q;
}

test('supportedCommands 拿到就原样返回', async () => {
  const list = [{ name: 'compact', description: '压缩', argumentHint: '', aliases: [] }];
  const q = withCommands(fakeQuery(), { supportedCommands: async () => list });
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });

  assert.deepEqual(await s.supportedCommands(), list);
});

test('supportedCommands 失败时回空数组，不抛 —— 命令补全挂着不该把聊天带崩', async () => {
  const q = withCommands(fakeQuery(), {
    supportedCommands: async () => { throw new Error('control 请求失败'); },
  });
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });

  assert.deepEqual(await s.supportedCommands(), []);
});

test('会话根本没有 supportedCommands 时也回空数组（老 SDK）', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });

  assert.deepEqual(await s.supportedCommands(), []);
});

test('skills 从 reloadSkills 的回执里取 skills 字段', async () => {
  const q = withCommands(fakeQuery(), {
    reloadSkills: async () => ({ skills: [{ name: 'brainstorming' }] }),
  });
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });

  assert.deepEqual(await s.skills(), [{ name: 'brainstorming' }]);
});

test('skills 失败或缺失时回空数组', async () => {
  const boom = withCommands(fakeQuery(), {
    reloadSkills: async () => { throw new Error('control 请求失败'); },
  });
  const s1 = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: boom.fn });
  assert.deepEqual(await s1.skills(), []);

  const plain = createSession({
    cwd: '/tmp', permissionMode: 'default', queryFn: fakeQuery().fn,
  });
  assert.deepEqual(await plain.skills(), []);
});
