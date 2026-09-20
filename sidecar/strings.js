/**
 * sidecar 的界面文案 —— 与 Kotlin 侧 `com.ccoder.text.CcoderText` 是同一套约定，
 * 只是换了一种语言的载体（那边是 ResourceBundle，这边是两张普通对象）。
 *
 * ## 语言从哪儿来（两个杠杆，都由插件侧给）
 *
 * - **进程环境** `CCODER_UI_LANG`：Kotlin 侧启动 node 进程时设的（见
 *   `SidecarProcess`）。[langFromEnv] 读它 —— 缺省/认不出就是 [DEFAULT_LANG]。
 * - **每会话** `start` 消息里的 `uiLang`：同一个 node 进程会被复用（新开一个
 *   标签并不新起进程），所以**新会话可以重新定语言**（见 index.js 的 start）。
 *
 * 两个杠杆都不在这层判断"谁赢"：这层只提供取值与查表，合并顺序写在 index.js。
 *
 * ## 三条硬规矩
 *
 * 1. **缺键返回键本身**，不回退英文。界面上显示 `error.unknownMethod` 是一眼能
 *    报的 bug；静默给英文是在撒谎（用户以为自己选的语言生效了）。同 TextCatalog。
 * 2. **占位符 `{0}`、`{1}` 朴素替换，不用 MessageFormat**：英文文案里满是撇号
 *    （`can't`），MessageFormat 会把 `'` 当转义符吃掉。Kotlin 侧同一个理由。
 * 3. **键永不动态拼**：`strings.test.js` 扫源码里的 `t('…')` 字面量做双向对账
 *    （引用的键都在、词表里的键都有人引用），拼出来的键它看不见。
 *
 * ## 什么该进词表、什么不该
 *
 * 进：插件自己的界面字句。不进（原样透传或保持中文）：
 * - 协议码（`fail(code, …)` 的 `code`）、工具名、路径、模型名；
 * - SDK/CLI 的错误原文（`String(err?.message ?? err)` 这类站点）—— 转它等于
 *   替 CLI 撒谎；
 * - 拒绝文案（"用户拒绝"/"已中断"/"会话已终止"）：它们随权限回执发给 CLI、
 *   进而进模型上下文，**不随界面语言变**（见 shared/deny-message.json）；
 * - stderr 诊断（`[ccoder] …`）：开发者用，只在崩溃报告最后十几行里出现。
 */

/** 进程环境里那个变量名。Kotlin 侧 `SidecarProcess.UI_LANG_ENV` 逐字对应。 */
export const UI_LANG_ENV = 'CCODER_UI_LANG';

/**
 * 没有语言信息时用哪门。
 *
 * 英文而不是中文：插件的词表本就是"基础 = 英文"（见 CcoderBundle.properties
 * 顶上的约定），sidecar 这份跟着走 —— 两份缺省不一致的话，同一个界面上会出现
 * 中文标签配英文错误句。
 */
export const DEFAULT_LANG = 'en';

/**
 * 只有这两个值是语言标签，别的都算"没给"。
 *
 * **刻意不认 `zh-CN` / `zh_CN`**：契约就是 `CcoderText.tag()` 那两个字符。
 * 放宽成前缀匹配的话，哪天上游改送 `zh-Hant` 我们会"猜"成简体中文 —— 猜错
 * 不像缺键那样看得见，用户只会觉得翻译怪。宁可退回英文，让它显形。
 */
export function normalizeLang(value) {
  return value === 'zh' || value === 'en' ? value : null;
}

/**
 * 词表。两份**键集必须完全一致**（strings.test.js 钉着），叶子小驼峰。
 *
 * 中文那份的用词尽量与既有断言/既有界面逐字一致 —— 改中文不是这次的事，
 * 这次只把字句从代码里搬进表里，顺手补上英文。
 */
export const CATALOGS = Object.freeze({
  en: Object.freeze({
    // —— index.js：协议层的失败回执。都是"用户做了一半的操作没做成"，
    //    所以句子短、说清缺什么即可。
    'error.alreadyStarted': 'Session already started.',
    'error.authFailed': 'Claude CLI authentication failed.',
    'error.deleteMissingSessionId': 'Missing sessionId for deleting a session.',
    'error.clearMissingDir': 'Missing dir for clearing sessions.',
    'error.updateMissingSessionId': 'Missing sessionId for updating a session.',
    // {0} = 字段名（title / tag）—— 字段名是协议词，不翻
    'error.missingUpdateField': 'Missing {0} parameter.',
    'error.subagentsMissingSessionId': 'Missing sessionId.',
    'error.subagentMessagesMissingIds': 'Missing sessionId or agentId.',
    'error.missingTaskId': 'Missing taskId.',
    'error.stopTaskUnsupported': "This session can't stop tasks.",
    'error.setModeUnsupported': "This session can't change permission mode.",
    'error.missingLevel': 'Missing level.',
    // 这几条用 thinking effort（设计稿 §九术语表：思考深度 = Thinking effort）——
    // 报错里的词要跟界面上那个控件的名字对得上，用户才知道该去哪儿改
    'error.unknownEffort':
      'Unknown thinking effort: {0} (accepts low/medium/high/xhigh/max or null).',
    'error.setEffortUnsupported': "This session can't change thinking effort.",
    'error.setEffortNoSession':
      'No session yet; thinking effort can be changed once a session is connected.',
    'error.missingModel': 'Missing model. The model switch has no clear option.',
    'error.setModelUnsupported': "This session can't switch models.",
    'error.setModelNoSession': 'No session yet; the model can be changed once a session is connected.',
    'error.sessionNotStarted': 'No session yet.',
    'error.unknownMethod': 'Unknown method: {0}',

    // —— session.js：CLI 太老。中文那句点出是哪个能力不支持，英文不点 ——
    //    用户刚点的就是那个按钮，上下文不缺；一句 "This needs a newer claude
    //    executable." 反而更直接。于是五个键的英文一模一样，这是**刻意**的：
    //    中文值不同 → 键必须分开；英文收敛是巧合，不是漏写。
    'session.cliTooOldStopTask': 'This needs a newer claude executable.',
    'session.cliTooOldEffort': 'This needs a newer claude executable.',
    'session.cliTooOldModel': 'This needs a newer claude executable.',
    'session.cliTooOldMcp': 'This needs a newer claude executable.',
    'session.cliTooOldContext': 'This needs a newer claude executable.',
    'session.queryFnNotAsyncIterable':
      'queryFn must return an AsyncIterable (a Query) synchronously. An async function returns a Promise, which breaks iteration.',

    // —— history-images.js：补在图被裁掉的那条消息末尾。
    //    这条**是**要翻的（与拒绝文案相反）：它落在 chat 气泡里、跟用户自己的
    //    中/英文混排，而且模型也会读到 —— 见 history-images.js 里的说明。
    'history.imagesOmittedOne': '({0} earlier image in this message omitted)',
    'history.imagesOmittedOther': '({0} earlier images in this message omitted)',

    // —— claude-path.js。"在插件设置中"翻成 Settings → General：英文界面里
    //    "plugin settings" 指不到具体哪一页，而这个路径只有通用页填得了。
    'error.claudeNotFound': 'Claude executable not found. Set its full path in Settings → General.',
    'error.claudePathMissing': 'The claude path set in Settings does not exist: {0}',
    // {0} = 路径是哪来的（三条 where），{1} = 垫片路径
    'error.claudeShimUnresolved':
      '{0} points to a batch shim, but the real executable inside it could not be resolved:\n  {1}\n' +
      'Node cannot run .cmd files directly on Windows. Set the full path to claude.exe in Settings → General.',
    'error.claudeShimWhereExplicit': 'The path set in Settings',
    'error.claudeShimWherePath': 'The PATH entry',
    'error.claudeShimWhereKnownDir': 'The entry in a known install location',
  }),

  zh: Object.freeze({
    'error.alreadyStarted': '会话已建立',
    'error.authFailed': 'Claude CLI 认证失败。',
    'error.deleteMissingSessionId': '删除会话缺少 sessionId',
    'error.clearMissingDir': '清空会话缺少 dir',
    'error.updateMissingSessionId': '改会话缺少 sessionId',
    'error.missingUpdateField': '缺少 {0} 参数',
    'error.subagentsMissingSessionId': '缺少 sessionId',
    'error.subagentMessagesMissingIds': '缺少 sessionId 或 agentId',
    'error.missingTaskId': '缺少 taskId 参数',
    'error.stopTaskUnsupported': '当前会话不支持终止任务',
    'error.setModeUnsupported': '当前会话不支持切换权限模式',
    'error.missingLevel': '缺少 level 参数',
    'error.unknownEffort': '不认识的思考深度：{0}（只接受 low/medium/high/xhigh/max 或 null）',
    'error.setEffortUnsupported': '当前会话不支持调整思考深度',
    'error.setEffortNoSession': '会话还没建立，思考深度要等连上会话再改',
    'error.missingModel': '缺少 model 参数（换模型没有"清空"这一档）',
    'error.setModelUnsupported': '当前会话不支持换模型',
    'error.setModelNoSession': '会话还没建立，换模型要等连上会话再改',
    'error.sessionNotStarted': '会话尚未建立',
    'error.unknownMethod': '未知方法：{0}',

    'session.cliTooOldStopTask': '当前 CLI 不支持终止任务，需要更新 claude 可执行文件',
    'session.cliTooOldEffort': '当前 CLI 不支持在会话中途调整思考深度，需要更新 claude 可执行文件',
    'session.cliTooOldModel': '当前 CLI 不支持在会话中途换模型，需要更新 claude 可执行文件',
    'session.cliTooOldMcp': '当前 CLI 不支持查询 MCP 连接状态，需要更新 claude 可执行文件',
    'session.cliTooOldContext': '当前 CLI 不支持读取上下文用量，需要更新 claude 可执行文件',
    'session.queryFnNotAsyncIterable':
      'queryFn 必须同步返回 AsyncIterable（Query 对象）。async 函数返回的是 Promise 而非 Query，会导致迭代失败。',

    // 中文没有单复数，两份值同形 —— 键必须分开是为了英文那一份
    'history.imagesOmittedOne': '（这一条里更早的 {0} 张图已省略）',
    'history.imagesOmittedOther': '（这一条里更早的 {0} 张图已省略）',

    'error.claudeNotFound': '未找到 claude 可执行文件。请在插件设置中指定其完整路径。',
    'error.claudePathMissing': '设置中指定的 claude 路径不存在：{0}',
    'error.claudeShimUnresolved':
      '{0}指向批处理垫片，但无法解析出其中的真实可执行文件：\n  {1}\n' +
      'Node 在 Windows 上无法直接运行 .cmd 文件。请在插件设置中直接指定 claude.exe 的完整路径。',
    'error.claudeShimWhereExplicit': '设置中指定的路径',
    'error.claudeShimWherePath': 'PATH 中的',
    'error.claudeShimWhereKnownDir': '已知安装位置中的',
  }),
});

/** 从环境对象取语言标签：认不出/没有就是 [DEFAULT_LANG]。 */
export function langFromEnv(env) {
  return normalizeLang(env?.[UI_LANG_ENV]) ?? DEFAULT_LANG;
}

/**
 * 造一个取词器：`const t = makeT('en'); t('error.missingTaskId')`。
 *
 * 认不出的语言退回 [DEFAULT_LANG] 那份表（与 ResourceBundle 找不到 `_xx`
 * 时落回基础词表同一个行为）；认不出的**键**则返回键本身。
 *
 * `params` 是 `{0: …, 1: …}`（数组也行）。少了某个下标就把 `{n}` 原样留着 ——
 * 与"缺键返回键"同一条：看得见的占位符是好报的 bug，悄悄补个空串不是。
 */
export function makeT(lang) {
  const table = CATALOGS[normalizeLang(lang) ?? DEFAULT_LANG];
  return (key, params) => {
    const value = table[key];
    if (typeof value !== 'string') return key;
    if (params === undefined) return value;
    return value.replace(/\{(\d+)\}/g, (whole, index) => {
      const arg = params[index];
      return arg === undefined ? whole : String(arg);
    });
  };
}

/**
 * 懒取默认取词器：调用时才读 `process.env`。
 *
 * 为什么不在模块顶层算好一份：测试是在 **import 之后**才把 `CCODER_UI_LANG`
 * 钉成 `zh` 的（import 会被提升到最前面），顶层求值会永远拿到进程启动时那份。
 */
export function defaultT() {
  return makeT(langFromEnv(process.env));
}

/**
 * 英语的两分法：1 用 [one]，其余（含 0）用 [other]。
 *
 * **不用 `Intl.PluralRules`、也不上 ICU**：这套词表只有中英两种语言，中文还
 * 根本不分单复数 —— 一个运行时依赖换不来任何东西。哪天真要加俄语/阿拉伯语，
 * 换的是这个函数，不是它的调用点。
 */
export function plural(n, one, other) {
  return n === 1 ? one : other;
}
