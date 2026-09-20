import { query as sdkQuery } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from './env.js';
import { defaultT, makeT } from './strings.js';

/**
 * 默认 queryFn。测试通过注入假的 queryFn 绕开真实 SDK。
 *
 * 必须**同步**返回 Query 对象 —— 声明为 async 会返回 Promise，
 * for await 随即报 "query is not async iterable"。这个坑实测踩过：
 * 当时 65 个单测全绿也没抓到，因为测试注入的假实现是同步的。
 */
export const defaultQueryFn = sdkQuery;

function assertAsyncIterable(q, t) {
  if (q && typeof q[Symbol.asyncIterator] === 'function') return q;
  throw new Error(t('session.queryFnNotAsyncIterable'));
}

/**
 * 建立一个 Claude 会话。
 *
 * 必须使用流式输入模式（prompt 传 AsyncIterable 而非 string）：
 * SDK 的 Query 接口上 interrupt() / setPermissionMode() / setModel()
 * 的文档明确写着 "only supported when streaming input/output is used"
 * （sdk.d.ts:2655-2659）。传字符串等于永久放弃这些能力。
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
  // 取词器由 dispatcher 传进来（它才知道当前语言）。缺省时现读环境变量 ——
  // 默认值写成调用 defaultT() 而不是模块顶层的常量：测试是在 import 之后
  // 才钉语言的，顶层求值会永远拿到进程启动时那份
  t: initialT = defaultT(),
} = {}) {
  /**
   * 当前取词器。**不是常量**：界面语言可以在会话跑着的时候换 —— `setUiLang`
   * 那条协议消息让 dispatcher 调 [setLang]（2026-09-20 热切换）。
   *
   * 参数因此改名成 `initialT`：下面所有 `t(...)` 都是闭包读这个变量，把它换掉，
   * 下一句话就是新语言 —— 不必去改任何一处调用点。
   */
  let t = initialT;
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

    // 子代理那层（2026-09-18，用户从选型台上挑的 A1/B2）。
    //
    // 不开这个时，SDK 只转发子代理的 tool_use/tool_result（文档原话 "enough for a
    // heartbeat counter"）—— 实测（tools/probe-subagent-text.mjs 甲跑）：带
    // parent_tool_use_id 的 assistant 只有 2 条、正文 0、思考 0。开了之后子代理的
    // 正文 1 条、思考 2 条也带 parent 过来（乙跑），转写区才能把它渲染成嵌套的一块。
    //
    // 一个必须知道的事：**子代理的正文没有流式增量帧**（三跑都量到 0 条
    // stream_event 带 parent）。所以那块是"整段冒出来"，不会像主线程那样逐字长。
    forwardSubagentText: true,
    // 每 ~30s 让 CLI fork 一次子代理的对话，写一句"现在在干嘛"挂在
    // task_progress.summary 上（丙跑实测：+42.4s、+72.9s 各一条）。文档说这个 fork
    // 复用子代理自己的模型与提示缓存，"cost is typically minimal"。
    //
    // **不买也不会空着**：task_progress.description 每步都来（"Reading note-17.txt"），
    // 插件侧 B2 那两行就是 summary ?: description。关掉它就是删这一行。
    agentProgressSummaries: true,
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
  // 界面那份模式列表是"6 个全列、点了直接生效"（见 ComposerMode），所以
  // 热切必须真能切过去 —— 只在以绕过启动时才开，等于让下拉里那一项对别的
  // 会话永远是死路。真要以绕过**启动**仍由设置里那个"我明白风险"把关
  // （见 ClaudeSettings.effectivePermissionMode），CLI 侧的禁用配置
  // （permissions.disableBypassPermissionsMode / restricted）也照旧生效；
  // sdk.d.ts:1891-1894 要求的"用 bypassPermissions 必须设它"一并满足。
  //
  // 实测：tools/probe-bypass-switch.mjs —— 不带它上面那条报错，带上切换成功。
  //
  // 它对 'auto' **不起作用**，也不必起：auto 的闸门是另一套（permissions.disableAutoMode、
  // 用户设置的 autoModeEnabled、订阅档、服务器端断路器），撞上时 CLI 直接报错
  // "Cannot set permission mode to auto: auto mode disabled by settings"（实测见
  // tools/probe-auto-mode.mjs）。所以这个资格位不是"所有模式的总开关"，只是绕过那一项的门票。
  options.allowDangerouslySkipPermissions = true;

  let query = null;
  try {
    query = assertAsyncIterable(queryFn({ prompt: inputStream(), options }), t);
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
     * 换界面语言（热切换）。dispatcher 收到 `setUiLang` 时调它。
     *
     * 只换取词器：会话、正在跑的那轮、CLI 进程都不动。会话里还会用到它的，是
     * "CLI 太老"那几条失败回执（下面 `t('session.cliTooOld…')` 的几处）——
     * 它们正是用户在会话中途点了个按钮却失败时看到的那句。
     *
     * 传进来的标签由调用方先 [normalizeLang] 过；本函数不认得的标签会落到
     * `makeT` 的缺省语言（英文），所以**别把没验过的值递进来**。
     */
    setLang(lang) {
      t = makeT(lang);
    },

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
      //
      // '已中断' **刻意保持中文、不进词表**：它随 deny 回执发给 CLI、进而进
      // 模型上下文，是协议载荷而不是界面字句 —— 界面切英文不该改变模型被告知
      // 的内容。见 shared/deny-message.json
      this.denyAllPending('已中断');
      await query?.interrupt?.();
    },

    /**
     * 终止一个正在跑的任务（子代理 / 后台命令）—— **会话与回合都不动**。
     *
     * 停完 CLI 会发一条 `task_notification`（status 'stopped'）：界面上
     * "运行中"那一行靠它自己消失（RunStatusTracker 已有那条分支）。
     *
     * 与 [setModel] 同一条规矩：缺方法就抛、**不照抄** [setPermissionMode] 的
     * 可选链 —— 那种写法在 `query` 为 null 时静默成功，用户点了个没用的
     * 按钮却以为已经停掉了。
     */
    async stopTask(taskId) {
      if (!query || typeof query.stopTask !== 'function') {
        throw new Error(t('session.cliTooOldStopTask'));
      }
      await query.stopTask(taskId);
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
        throw new Error(t('session.cliTooOldEffort'));
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
        throw new Error(t('session.cliTooOldModel'));
      }
      await query.setModel(model);
    },

    /**
     * 当前会话里各 MCP server 的实时状态。
     *
     * 与 setEffort / setModel 同一条规矩：**缺方法就抛**，不照抄
     * setPermissionMode 的可选链 —— 那种写法在 `query` 为 null 时整句静默成功，
     * 上层据此发出一条假回执。这里尤其要紧：「状态是空的」与「问不到状态」
     * 必须在界面上能分开：前者是正常的（一个 server 都没配），后者是 CLI 太老。
     */
    async mcpServerStatus() {
      if (!query || typeof query.mcpServerStatus !== 'function') {
        throw new Error(t('session.cliTooOldMcp'));
      }
      return await query.mcpServerStatus();
    },

    /**
     * 会话可用的命令列表（带描述）。
     *
     * 尽力而为：取不到给空数组，**不抛**。命令补全挂着不该把聊天带崩 ——
     * 调用方（listCommands）拿空数组就当成"没有候选"。
     *
     * **首选 `initializationResult()`，退回 `supportedCommands()`。**
     * 2026-09-16 实测（tools/probe-init-before-send.mjs，把两条的冷启动
     * 耗时对调着各量一次）：
     *
     * - 两份数据**完全一样**：都 31 条，字段都是 `name/description/argumentHint`；
     * - 但冷启动差 4 倍 —— `initializationResult()` **680ms**，
     *   `supportedCommands()` **2976ms**（它俩都要等 CLI 把会话建起来，
     *   而前者答的是"建会话时顺手算好的那份"）。
     *
     * 用户看到的差别就是：打 `/` 之后列表是立刻出来还是干等三秒。
     * 先问快的；它没给（老 CLI、或字段形状变了）再走原来那条。
     */
    async supportedCommands() {
      try {
        const r = await query?.initializationResult?.();
        if (Array.isArray(r?.commands) && r.commands.length > 0) return r.commands;
      } catch {
        // 掉到下面那条路 —— 退回不是降级到"没有"，是换个问法再问一次
      }
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
        throw new Error(t('session.cliTooOldContext'));
      }
      return await query.getContextUsage({ detail: 'summary' });
    },

    stop() {
      if (stopped) return;
      stopped = true;
      // '会话已终止' 同 interrupt 那条：协议载荷，**保持中文不翻**
      //（与 index.js stop 里那句同字），见 shared/deny-message.json
      this.denyAllPending('会话已终止');
      notifyInput?.(null);   // 结束输入流
    },
  };
}
