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
  resumeSessionId,
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
  // 思考深度**刻意不在这里**：Options.effort 会被 SDK 翻成 CLI 的 `--effort`
  // 启动开关（sdk.mjs：`if (this.options.effort) W.push("--effort", ...)`），
  // 而中途切换走的是 applyFlagSettings 的 flag 层 —— 是两个优先级来源。
  // 两条路一起用的话，用户选「默认」只清得掉 flag 层、清不掉启动时那个：
  // 标签显示「默认」而会话照旧按启动档位跑。控件撒谎比它不好用严重，
  // 所以它只有一条路：起会话后由界面发 setEffort（见下面的 setEffort）。
  if (extraDirs?.length) options.additionalDirectories = extraDirs;
  if (claudePath) options.pathToClaudeCodeExecutable = claudePath;

  // 恢复既有会话。与 continue 互斥、可配 forkSession —— 本插件只用
  // "接着写"这一种语义（spec §1.2），所以这里不带 forkSession。
  if (resumeSessionId) options.resume = resumeSessionId;

  // 一律带上这个开关 —— 它是**资格**，不是绕过本身。
  //
  // - SDK 把它翻成 CLI 的 `--allow-dangerously-skip-permissions`
  //   （sdk.mjs：`if (b) W.push("--allow-dangerously-skip-permissions")`）；
  // - CLI 用它算资格位（2026-09-14 读 claude.exe 2.1.268 内嵌 JS 核实）：
  //   `isBypassPermissionsModeAvailable = (mode === "bypassPermissions"
  //     || allowDangerouslySkipPermissions) && !被设置禁用 && !restricted`；
  //   **起始模式仍由上面的 permissionMode 决定**，带资格不会让会话以绕过启动；
  // - 会话中途切到绕过（setPermissionMode → 控制请求 set_permission_mode）
  //   在 CLI 侧就是拿这个资格位放行的，不带它必失败：
  //   "Cannot set permission mode to bypassPermissions because the session
  //    was not launched with --dangerously-skip-permissions"。
  //
  // 界面那份模式列表是"5 个全列、点了直接生效"（见 ComposerMode），所以
  // 热切必须真能切过去 —— 只在以绕过启动时才开，等于让下拉里那一项对别的
  // 会话永远是死路。真要以绕过**启动**仍由设置里那个"我明白风险"把关
  // （见 ClaudeSettings.effectivePermissionMode），CLI 侧的禁用配置
  // （permissions.disableBypassPermissionsMode / restricted）也照旧生效；
  // sdk.d.ts:1853-1856 要求的"用 bypassPermissions 必须设它"一并满足。
  //
  // 实测：tools/probe-bypass-switch.mjs —— 不带它上面那条报错，带上切换成功。
  options.allowDangerouslySkipPermissions = true;

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
    /**
     * 发一条用户消息。[images] 是 `[{ mediaType, data }]`，data 为 base64。
     *
     * **没有图时 content 仍是字符串** —— 与从前一字不差，那条路不必重验一遍
     * （测试里钉着这一条）。
     *
     * 有图时按 Messages API 的形状拼数组，且**图排在文字前面**：让模型先看图、
     * 再读要求。反过来写模型会先按文字猜一遍，遇到"这张图哪里不对"这种问题时
     * 猜错之后很难自己纠回来。
     */
    send(text, images = []) {
      if (stopped) return;
      const content = images.length > 0
        ? [
            ...images.map((img) => ({
              type: 'image',
              source: { type: 'base64', media_type: img.mediaType, data: img.data },
            })),
            // 纯图消息（没打字）就只剩图 —— 效果图上就是这个用法
            ...(text ? [{ type: 'text', text }] : []),
          ]
        : text;
      queue.push({
        type: 'user',
        message: { role: 'user', content },
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
      // 中断即终止当前回合，该回合挂起的权限询问必须一并作废。
      // 不清的话卡片会一直留在界面上，用户还能"批准"一个已经不存在的
      // 工具调用 —— SDK 文档明说权限询问**没有超时**，悬着就不会自己消失。
      // 顺序同 stop：先清空待决权限，否则工具会挂住。
      this.denyAllPending('已中断');
      await query?.interrupt?.();
    },

    async setPermissionMode(mode) {
      await query?.setPermissionMode?.(mode);
    },

    /**
     * 改思考深度。level 为 null = 清除 flag 层、回到模型默认档。
     *
     * **刻意不照抄上面 setPermissionMode 的写法**：那行用可选链，
     * 在 `query` 为 null（`queryFn` 抛错那条路径）时整句会静默成功，
     * 上层据此发出一条假回执 —— 界面标签切过去了，实际什么都没生效。
     * 这里缺方法就抛，让失败顺着回执暴露出来，而不是猜。
     */
    async setEffort(level) {
      if (!query || typeof query.applyFlagSettings !== 'function') {
        throw new Error(
          '当前 CLI 不支持在会话中途调整思考深度，需要更新 claude 可执行文件'
        );
      }
      // effortLevel 传 null 是「清除」而不是「不设置」—— applyFlagSettings
      // 的文档明说 null 会把这一项从 flag 层抹掉、回落到低优先级的来源
      await query.applyFlagSettings({ effortLevel: level ?? null });
    },

    /**
     * 换模型，**后续回合**生效，会话与上下文都留着。
     *
     * 与 [setEffort] 刻意相反：那边启动参数一个字节都不发（`Options.effort` 会被
     * SDK 翻成 CLI 的 `--effort`，与 `applyFlagSettings` 是两个优先级来源，
     * 一起用会互相顶）。模型这边 **`Options.model` 照发不误** —— 它翻成
     * `--model`，而 `set_model` 改的是同一个来源（"这个会话用哪个模型"），
     * 不存在"清不掉启动那份"的问题：模型永远是个具体名字，没有「默认」这种
     * 需要清除的档位。启动带 `--model` 保证第一轮就对，中途切换带 `set_model`。
     *
     * 与 [setEffort] 同一条规矩：缺方法就抛，**不照抄 setPermissionMode 的可选链**
     * —— 那种写法在 `query === null`（`queryFn` 抛错那条路径）时整句静默成功，
     * 上层据此发出一条假回执，标签切过去了而什么都没生效。
     */
    async setModel(model) {
      if (!query || typeof query.setModel !== 'function') {
        throw new Error(
          '当前 CLI 不支持在会话中途换模型，需要更新 claude 可执行文件'
        );
      }
      await query.setModel(model);
    },

    /**
     * 会话可用的命令列表（带描述）。
     *
     * 尽力而为：取不到给空数组，**不抛**。命令补全挂着不该把聊天带崩 ——
     * 调用方（listCommands）拿空数组就当成"没有候选"。
     */
    async supportedCommands() {
      try {
        return (await query?.supportedCommands?.()) ?? [];
      } catch {
        return [];
      }
    },

    /**
     * 命令列表里的**技能子集**，只用来分组。
     *
     * `reloadSkills` 名字里带 reload，但它同时就把刷新后的列表返回了，
     * 不需要先调再查。同样尽力而为。
     */
    async skills() {
      try {
        return (await query?.reloadSkills?.())?.skills ?? [];
      } catch {
        return [];
      }
    },

    /**
     * 上下文占用的**权威读数** —— CLI 自己算的那份（`/context` 用的就是它）。
     *
     * 2026-09-14 实测（CLI 2.1.268）：
     * - **一条消息都没发就能调**（`query()` 建好之后即可），所以恢复会话时
     *   不必等第一轮跑完；
     * - **会把恢复的历史算进去** —— resume 一条长会话报的是
     *   `Messages: 455407`，不是 0；
     * - 返回 `rawMaxTokens`（它用来算比例的那个窗口），分母也一并解决了。
     *
     * 这正是它替掉"插件自己从 result 事件拼 + 从历史推"那套的理由：那边要
     * 三个 input 字段相加、还要按会话记窗口，因为它拿不到窗口。
     *
     * `detail: 'summary'` 走"上次响应的 usage + 本地估算"，不额外发起
     * token 计数调用；卡片只要总数，不需要按类目拆。
     *
     * 与 [setEffort] 同一条规矩：方法缺失就抛，不静默给一个空读数。
     */
    async contextUsage() {
      if (!query || typeof query.getContextUsage !== 'function') {
        throw new Error('当前 CLI 不支持读取上下文用量，需要更新 claude 可执行文件');
      }
      return await query.getContextUsage({ detail: 'summary' });
    },

    stop() {
      if (stopped) return;
      stopped = true;
      this.denyAllPending('会话已终止');
      notifyInput?.(null);   // 结束输入流
    },
  };
}
