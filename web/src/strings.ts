/**
 * 界面文案的两份目录。
 *
 * **前端自己带一份**，不走桥：`npm run dev`、各种探针、单测里根本没有 Kotlin，
 * 界面照样得是完整的。Kotlin 侧那份目录是它自己的事（它要翻的是另一批东西 ——
 * 状态栏、输入框、Kotlin 拼出来的系统提示），两边不共用也不该共用：
 * 共用意味着"没有宿主就没有界面"。
 *
 * 键名 `<区域>.<组件>.<条目>`，叶子小驼峰。取键的入口是 `i18n.ts` 的 [t]。
 *
 * 两条约定，`strings.test.ts` 各自盯着：
 *
 *  1. **中英键集必须一致**：多一个键、少一个键都编译不过（`zh` 的类型由 `en`
 *     长出来），运行期再核一遍。
 *  2. **英文里不许有汉字**（`en` 是基底目录，中文只许出现在 `zh` 里）。
 *
 * 不翻的东西也在这一条线上：工具名、命令、diff、文件路径、Claude 自己写的
 * description、`⟦路径 24-27 · 4 行⟧` 这种 Kotlin 造的参考记号、`·` 与 `12.4k`
 * 这类分隔符和单位 —— 它们是**数据**，不是文案，翻一遍就成了假信息。
 */

/**
 * 英文是**基底**目录：类型从它长出来，其余目录以它为形状。
 *
 * 拼写风格：这里是 CLI 的词汇表，不是正式文稿 —— 回合结束那一行的
 * `success / in / cache / out` 与 CLI 输出逐字对应（见 ResultLine.tsx），
 * 认不出的 subtype 也原样留着英文。按钮那几处按句首大写（`Copy code`）。
 */
const en = {
  // ---- 转写区（Transcript.tsx）----
  /** 子代理那一块的标题。与下面那个计数连在一起读：「子代理的对话  3 次工具调用」 */
  'transcript.subagent.title': 'Subagent conversation',
  /** 只有一次调用时用这个 —— `1 tool calls` 是句病句，而 1 次调用很常见 */
  'transcript.subagent.toolCall': '{0} tool call',
  'transcript.subagent.toolCalls': '{0} tool calls',
  /** 「回到底部」按钮。类名与测试 id 就叫 jump-to-bottom，文案跟它们对齐 */
  'transcript.backToBottom': 'Jump to bottom',
  /** 离开底部期间又来了新内容时的无障碍名（按钮上同时多一个小圆点） */
  'transcript.backToBottomNew': 'Jump to bottom (new content)',

  // ---- 工具卡片（ToolCallBlock.tsx / tools.ts）----
  /** 结果到了、但一个字符都没有。说的就是"这次调用确实没吐东西"，不是"还在跑" */
  'tool.noOutput': '(no output)',
  /** ✗ 的无障碍名。与 result.success 同一套小写词汇 */
  'tool.failed': 'failed',
  /**
   * ⊘ 的 title（悬停才看得见）。它说的是**已知**的那点事：回合结束了、结果没来。
   * 被拒绝、被中断、会话被杀掉都长这样 —— 所以只能列可能性，不能挑一个断言
   */
  'tool.aborted': 'No result arrived (interrupted or the session ended)',
  /** 长输出折叠按钮。只有超过 14 行才会出现，所以不必考虑单数 */
  'tool.showAllLines': 'Show all {0} lines',
  /** 长正文（计划之类）铺开后，其余字段那一段的分隔头 */
  'tool.paramsRest': 'Other parameters:',

  // ---- 回合结束那一行（ResultLine.tsx）----
  /** 只有 `success` 走目录；别的 subtype 原样透传（见 ResultLine.tsx 的注释） */
  'result.success': 'success',
  'result.input': 'in {0}',
  'result.cache': 'cache {0}',
  'result.output': 'out {0}',

  // ---- 思考块（ThinkingBlock.tsx）----
  /** 已完成的那块 */
  'thinking.title': 'Thought process',
  /** 进行中的那块：转圈 + 秒数 + 这个标题。省略号就是"还没完" */
  'thinking.live': 'Thinking…',

  // ---- 代码块（CodeBlock.tsx）----
  /** 换行开关的可见文字（`aria-pressed` 表示当前是不是开着） */
  'code.wrap': 'Word wrap',
  /** 悬停提示说的是**点下去会发生什么**，不是当前状态 */
  'code.enableWrap': 'Turn on word wrap',
  'code.disableWrap': 'Turn off word wrap',
  'code.copyTitle': 'Copy code',
  'code.copy': 'Copy',
  'code.copied': 'Copied',

  // ---- 图（ImageLightbox.tsx / UserBubble.tsx）----
  /** 放大浮层的无障碍名（role=dialog） */
  'image.dialog': 'Image',
  /** 图本身的名字：`Image 2`。读屏软件念的就是它 */
  'image.alt': 'Image {0}',
  /** 缩略图按钮的无障碍名 —— 它比 alt 多一个动词，因为点它是有后果的 */
  'image.view': 'View image {0}',
  /** 浮层底部的提示。前面还会带上 `2 / 3 · `（数字不进目录） */
  'image.closeHint': 'Esc to close',
}

/** 目录的键 —— 从基底目录长出来，别的目录缺一个键就编译不过。 */
type Key = keyof typeof en

/**
 * 中文目录。
 *
 * 注解成 `Record<Key, string>` 是**故意的**：这样少写一个键、或写出一个
 * `en` 里没有的键，`tsc` 当场报错 —— 中英对齐不靠人眼。运行期那一条
 * （strings.test.ts）是第二张网，兜住类型够不着的地方。
 */
const zh: Record<Key, string> = {
  'transcript.subagent.title': '子代理的对话',
  'transcript.subagent.toolCall': '{0} 次工具调用',
  'transcript.subagent.toolCalls': '{0} 次工具调用',
  'transcript.backToBottom': '回到底部',
  'transcript.backToBottomNew': '回到底部（有新内容）',

  'tool.noOutput': '（无输出）',
  'tool.failed': '失败',
  'tool.aborted': '没有等到结果（被中断或会话已结束）',
  'tool.showAllLines': '展开全部 {0} 行',
  'tool.paramsRest': '其余参数：',

  'result.success': '成功',
  'result.input': '输入 {0}',
  'result.cache': '缓存 {0}',
  'result.output': '输出 {0}',

  'thinking.title': '思考过程',
  'thinking.live': '思考中',

  'code.wrap': '自动换行',
  'code.enableWrap': '开启自动换行',
  'code.disableWrap': '关闭自动换行',
  'code.copyTitle': '复制代码',
  'code.copy': '复制',
  'code.copied': '已复制',

  'image.dialog': '图片',
  'image.alt': '图片 {0}',
  'image.view': '查看图片 {0}',
  'image.closeHint': 'Esc 关闭',
}

/**
 * 全部目录，按语言标签取。
 *
 * 标签类型写死成 `'zh' | 'en'` 而不是从 i18n.ts 引 Lang：那会绕出一个
 * 类型上的环（i18n.ts 要引这里的两份目录）。这里的形状与 Lang 一致，
 * i18n.ts 那边对得上。
 *
 * 值那层刻意写成 `Record<string, string>`（宽）而不是 `Record<Key, string>`：
 * [t] 收的是任意字符串，**缺键要返回键本身**而不是抛错/退回英文，所以取键
 * 那一步必须允许取不到。中英对齐那件事由上面 `zh` 的注解把关，与这里无关。
 */
export const CATALOGS: Record<'zh' | 'en', Record<string, string>> = { en, zh }
