import { query as sdkQuery } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from './env.js';

/**
 * 默认 queryFn。测试通过注入假的 queryFn 绕开真实 SDK。
 *
 * 必须**同步**返回 Query 对象 —— 声明为 async 会返回 Promise，
 * for await 随即报 "query is not async iterable"。这个坑实测踩过：
 * 当时 65 个单测全绿也没抓到，因为测试注入的假实现是同步的。
 */
export const defaultQueryFn = sdkQuery;

function assertAsyncIterable(q) {
  if (q && typeof q[Symbol.asyncIterator] === 'function') return q;
  throw new Error(
    'queryFn 必须同步返回 AsyncIterable（Query 对象）。' +
    'async 函数返回的是 Promise 而非 Query，会导致迭代失败。'
  );
}

/**
 * 建立一个 Claude 会话。
 *
 * 必须使用流式输入模式（prompt 传 AsyncIterable 而非 string）：
 * SDK 的 Query 接口上 interrupt() / setPermissionMode() / setModel()
 * 的文档明确写着 "only supported when streaming input/output is used"
 * （sdk.d.ts:2614-2616）。传字符串等于永久放弃这些能力。
 */
export function createSession({
  cwd,
  permissionMode,
  model,
  claudePath,
  extraDirs,
  envOverrides,
  onEvent = () => {},
  onPermission = () => {},
  queryFn = defaultQueryFn,
} = {}) {
  /** @type {Map<string, (result: object) => void>} */
  const pending = new Map();

  const queue = [];
  let notifyInput = null;   // 唤醒输入流生成器
  let stopped = false;

  async function* inputStream() {
    while (!stopped) {
      if (queue.length > 0) {
        yield queue.shift();
        continue;
      }
      const item = await new Promise((resolve) => { notifyInput = resolve; });
      notifyInput = null;
      if (item === null) return;
      yield item;
    }
  }

  const options = {
    cwd,
    permissionMode,
    env: buildChildEnv(process.env, envOverrides),
    includePartialMessages: true,
    canUseTool: (toolName, input, opts) => {
      return new Promise((resolve) => {
        const requestId = opts.toolUseID;
        pending.set(requestId, resolve);
        onPermission({
          requestId,
          toolName,
          input,
          title: opts.title,
          displayName: opts.displayName,
          description: opts.description,
          blockedPath: opts.blockedPath,
          decisionReason: opts.decisionReason,
          defaultToNo: opts.defaultToNo ?? false,
          suppressAlwaysAllowRule: opts.suppressAlwaysAllowRule ?? false,
          suggestions: opts.suggestions,
        });
      });
    },
  };

  // 可选参数仅在提供时才传给 SDK —— 传 undefined 与不传的语义不同
  if (model) options.model = model;
  if (extraDirs?.length) options.additionalDirectories = extraDirs;
  if (claudePath) options.pathToClaudeCodeExecutable = claudePath;

  let query = null;
  try {
    query = assertAsyncIterable(queryFn({ prompt: inputStream(), options }));
  } catch (err) {
    // queryFn 抛错（参数非法、返回 Promise 等）也要走 onEvent，
    // 否则调用方只能看到一个没有任何输出的空会话
    onEvent({ type: 'ccoder_stream_error', message: String(err?.message ?? err) });
  }

  // 消费事件流，全部原样透传。未知类型不做过滤 —— 那是插件侧的职责（spec §3.3）
  if (query) {
    (async () => {
      try {
        for await (const msg of query) {
          onEvent(msg);
        }
      } catch (err) {
        onEvent({ type: 'ccoder_stream_error', message: String(err?.message ?? err) });
      }
    })();
  }

  function settle(requestId, result) {
    const resolve = pending.get(requestId);
    if (!resolve) return;         // 已被决定或不存在，静默忽略
    pending.delete(requestId);
    resolve(result);
  }

  return {
    send(text) {
      if (stopped) return;
      queue.push({
        type: 'user',
        message: { role: 'user', content: text },
        parent_tool_use_id: null,
      });
      notifyInput?.('go');
    },

    decidePermission(requestId, result) {
      settle(requestId, result);
    },

    /**
     * 把所有挂起的权限请求 resolve 为 deny。
     *
     * 这是 spec §6.2 规则① 的落实：SDK 文档明确警告权限询问
     * "have no park deadline"，返回 null 或不 resolve 会让工具无限期阻塞。
     * 任何终止路径都必须走这里。
     */
    denyAllPending(reason) {
      for (const [requestId, resolve] of pending) {
        pending.delete(requestId);
        resolve({ behavior: 'deny', message: reason });
      }
    },

    async interrupt() {
      await query?.interrupt?.();
    },

    async setPermissionMode(mode) {
      await query?.setPermissionMode?.(mode);
    },

    stop() {
      if (stopped) return;
      stopped = true;
      this.denyAllPending('会话已终止');
      notifyInput?.(null);   // 结束输入流
    },
  };
}
