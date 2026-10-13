export type ItemKind =
  | 'user' | 'assistant' | 'thinking' | 'toolUse' | 'toolResult'
  | 'error' | 'result' | 'systemNote'

interface Base {
  id: string
  ts: number
}

/**
 * 用户发的一条。
 *
 * `images` 是**给眼睛看的那份**（data URL，长边 ≤900 的 JPEG）—— 发给模型的
 * 是原图，尺寸和它不一样。空/缺省 = 纯文字，与从前一字不差。
 */
export interface UserItem extends Base { kind: 'user'; text: string; images?: string[] }
export interface AssistantItem extends Base { kind: 'assistant'; text: string; parent?: string }
export interface ThinkingItem extends Base { kind: 'thinking'; text: string; parent?: string }
export interface ErrorItem extends Base { kind: 'error'; text: string }
/**
 * 一条系统提示。
 *
 * **文字是 Kotlin 侧拼好的，这里原样渲染** —— 它到达时已经是当前界面语言的
 * 成品（Kotlin 自己那份目录翻的），web 侧**不许再翻一遍**：两边各翻一次，
 * 轻则一句中文里夹着英文词，重则翻不出东西时静默退回原样，界面上看着像文案
 * 没跟上，实际是翻了第二遍。
 *
 * 换语言也**不改写它一个字**：这条是已经落库的历史消息，当时说的什么就是什么。
 * 界面文案（走 i18n.ts 那份目录）与它**是两回事**，别把这两条路混在一起。
 */
export interface SystemNoteItem extends Base { kind: 'systemNote'; text: string }
/**
 * 一次工具调用。
 *
 * `toolUseId` 是 SDK 的 `tool_use.id`；结果项带着同一个 id 回来，
 * 界面靠它把输出挂到这张卡片上。空串表示这次调用没有可配对的 id
 * （老版本 Kotlin 不送这个字段），卡片照画，只是不会等到输出。
 *
 * **同一次调用会来两条**（2026-09-22 起）：起头帧那条 `input` 是空串 —— 卡片在
 * 模型刚决定要用这个工具时就出生（卡面只有名字 + 转圈 + 秒表），参数生成完的完整
 * 消息那条才带着真参数。`applyOps` 按 `toolUseId` 把后者合并进前者（保留前一条的
 * `id`/`ts`），所以这里**不能假设"一个 id 只会来一条"**。
 */
export interface ToolUseItem extends Base {
  kind: 'toolUse'
  toolUseId: string
  name: string
  /** 工具参数原文（JSON）。**空串 = 参数还在生成**，不是"这个工具没有参数"。 */
  input: string
  /**
   * **子代理归属**：非空时这一项是某个子代理干的，值是主线程那条 `Task` 的
   * `toolUseId` —— 界面据此把它收进那张卡里（A1）。
   *
   * 缺省 = 主线程自己的（老版本 Kotlin 不送这个字段，那条路一字不差）。
   */
  parent?: string
}

/**
 * 一次工具调用的输出。它与调用是**两条独立的项** —— 协议里本来就是两条
 * 消息，操作序列因此保持只追加（同一次调用的两条 toolUse 也不改已推出的项，
 * 合并发生在 `applyOps` 里，见 [ToolUseItem] 的说明）。
 */
export interface ToolResultItem extends Base {
  kind: 'toolResult'
  toolUseId: string
  text: string
  isError: boolean
}
export interface ResultItem extends Base {
  kind: 'result'
  subtype: string
  /** 累计花费（CLI 语义）。**界面这一行不显示它**（会被读成本次花费），留给成本面板。 */
  costUsd?: number
  durationMs?: number
  /** 本回合的 token（来自 `usage`，只含主循环 —— 子代理那几次不在里面）。 */
  inputTokens?: number
  outputTokens?: number
  cacheReadTokens?: number
}

export type TranscriptItem =
  | UserItem | AssistantItem | ThinkingItem
  | ToolUseItem | ToolResultItem | ErrorItem | ResultItem | SystemNoteItem

export type TranscriptOp =
  | { op: 'reset' }
  /**
   * **新会话就绪**。空转写区的标记，见 [TranscriptState.fresh]。
   *
   * 从前这件事由一条「会话已就绪」的系统提示承担，而那条提示会永远留在
   * 时间线里（第一条消息上方一直挂着它）；改成标记之后，空态由引导卡表达，
   * 来了内容它就自然消失（2026-10-10，设计稿 empty-session.html 方案乙）。
   */
  | { op: 'fresh' }
  | { op: 'append'; item: TranscriptItem }
  | { op: 'appendDelta'; target: string; text: string }
  | { op: 'finalizeDelta'; target: string; text: string }
  | { op: 'clearDelta'; target: string }
  /**
   * 文件快照的标记（2026-10-13，「本轮改动」卡）。见 [TranscriptState.snapshots]。
   *
   * 两条来源同一个形状：实时每抓一份一条；恢复会话时 ready 那一刻全量一条。
   * `existed` 三值：true = 存下了原样、false = **当时不存在**（本轮新建）、
   * null = 没存下（无存档）。
   */
  | { op: 'snapshots'; entries: SnapshotMark[] }
  /** 一次回滚的逐文件回执。见 [TranscriptState.revert]。 */
  | { op: 'revertReport'; results: RevertResultWire[] }
  /**
   * 定时待发的那张表（2026-10-13，定时发送 · 丙案）。见 [TranscriptState.scheduled]。
   *
   * **全量替换**：每加一条 / 撤一条 / 到点发走一条，Kotlin 都把整张表重推一遍。
   * `entries` 为空 = 清空（撤完了 / 会话换了）—— 所以这条 op **空表也要应用**
   * （与 snapshots 那条"空批丢掉"相反，见 codec.ts）。
   */
  | { op: 'scheduled'; entries: ScheduledEntryWire[] }

/** [TranscriptOp] 里那两种消息的条目形状（与 Kotlin 的 SnapshotEntry / RevertResult 逐字对应）。 */
export interface SnapshotMark {
  toolUseId: string
  path: string
  existed: boolean | null
}

export interface RevertResultWire {
  path: string
  ok: boolean
  action: 'restored' | 'deleted' | 'failed'
  error?: string
  dirty?: boolean
}

/**
 * 定时待发列表里的一条（`scheduled` op 的条目，与 Kotlin 的 `ScheduledEntry` 逐字对应）。
 *
 * `label` 是 **Kotlin 拼好的成品**（"今天 18:00"，词表翻好的）—— 网页不碰日期
 * 逻辑，它连"现在几点"都不知道（经 scheduledTimeText 于推送那一刻现拼，
 * 换语言由 Kotlin 重推；同 [SystemNoteItem] 那条"原样渲染、不再翻一遍"）。
 *
 * `imageCount` 是那条消息带的图数（0 = 没图，字段缺省）：图本体跟着消息留在
 * Kotlin 侧，气泡上只留个"图 N"的记号（同排队条 queueChipText 的道理）。
 */
export interface ScheduledEntryWire {
  id: string
  label: string
  text: string
  imageCount?: number
}

export interface TranscriptState {
  items: TranscriptItem[]
  /** 进行中的流式气泡，按 target 分。当前只有 'assistant'。 */
  live: Record<string, string>
  /**
   * 本轮的快照标记，**按 toolUseId**（2026-10-13）。值即 `existed` 三态，
   * 缺省（键不在）= 还没有标记。
   *
   * 为什么按 toolUseId 而不是路径：hook 报的路径与工具卡里的路径**不保证
   * 逐字节一致**（CLI 可能换了写法），拿字符串配会一边有一边没有；
   * toolUseId 是同一次调用的身份证，两侧拿的是同一个。
   *
   * 清空时机：Reset，以及**一条新的 user 项落地**（= 新一轮开始）——
   * 标记只对最新一轮有意义（卡片也只活最新一轮）。
   */
  snapshots?: Record<string, boolean | null>
  /**
   * 最近一次回滚的逐文件回执，按**路径**（同一个 toolUseId 表是另一回事：
   * 回执来自侧车、打的是路径）。清空时机同 [snapshots]。
   */
  revert?: Record<string, RevertResultWire>
  /**
   * 定时待发的那张表（2026-10-13，定时发送 · 丙案）。渲染成流水末尾的
   * **幽灵气泡**（虚线，到点变成一条真 user 气泡）。
   *
   * 由 Kotlin 的 `scheduled` op **全量替换**；空表（op 收到 0 条）时这里是
   * `undefined` —— 与 [snapshots]/[revert] 同一条"有才带上"的口径，
   * 免得别处构造状态、逐字段比对的用例凭空多一个键。
   *
   * 清空时机：Reset（切会话），以及任何一条把整张表推空的 op。
   */
  scheduled?: ScheduledEntryWire[]
  /**
   * 「这是一条新会话，还没有任何内容」—— 由 Kotlin 在会话就绪时推来（`fresh` op），
   * 随 Reset 清掉。
   *
   * 这是空态引导卡的**唯一开关**，别拿"items 是否为空"来替代：切换会话时
   * Kotlin 先 Reset 再从磁盘回放历史，中间有一段真空期 —— 只看"空"的话，
   * 引导卡会在真空期里闪一下再被历史顶掉（回放包含图片时能到几百毫秒）。
   *
   * 缺省 = 不是新会话。老版本 Kotlin 不推这个 op，那条路一字不差（没有卡，
   * 空转写区就是干净的空）。
   */
  fresh?: boolean
}

/**
 * Kotlin 侧注入的桥（见 Task 6 的 ClaudeTranscriptView）。
 *
 * 声明只放在这里一处 —— 多个文件各写一个 `declare global` 虽然能靠
 * 接口合并通过编译，但形状一旦不同就是静默的类型不一致。
 */
declare global {
  interface Window {
    ccoder?: {
      pushBatch?: (json: string) => void
      send?: (message: string) => void
      /**
       * 当前界面语言的标签。Kotlin 在页面加载时注入，页面**惰性**读一次
       * （见 i18n.ts 的 getLang）；此后只在 ready 握手里回推一次，页面
       * 不必重载。
       *
       * 类型是 `string` 而不是 `'zh' | 'en'`：它是跨进程来的外部输入，
       * 声明成联合类型只会把"没预料到的写法"从类型上藏起来 —— 归一化
       * 那一步在 i18n.ts 里，那里看得见。
       */
      locale?: string
    }
    /**
     * 换主题（Kotlin 注入，参数是一份 CSS 文本）。
     *
     * 页面这一侧目前不调它 —— 主题由 Kotlin 侧的内嵌编辑器配置驱动；
     * 声明在这里是因为它是桥的一部分，形状得以 Kotlin 那份为准。
     */
    ccoderSetTheme?: (css: string) => void
    /**
     * 换语言：把标签落到 `ccoder.locale` 上，再叫醒页面这一侧的接收端。
     *
     * Kotlin 注入并由它自己调用（设置页改语言那次不必重载页面）。
     * 页面**不主动调**它 —— 语言是 Kotlin 的事。
     */
    ccoderSetLocale?: (tag: string) => void
    /**
     * 页面这一侧的语言接收端，由 App 在装 `pushBatch` 的同一处装上
     * （见 App.tsx），并在 effect 清理时摘掉。
     *
     * 是**可选**的：Kotlin 那边调之前先判存在 —— 页面还没挂上时切语言
     * 不该炸（桥晚于挂载注入是常态，见 App.test.tsx 的握手用例）。
     */
    ccoderLocaleSink?: (tag: string) => void
    /**
     * 当前界面偏好的快照（今天只有「思考折叠」）。Kotlin 在页面加载时注入并推一次，
     * 页面**惰性**读一次（见 prefs.ts 的 getPrefs）；此后只在 ready 握手与设置
     * 对话框关掉时回推，页面不必重载。
     *
     * 形状是**对象**而不是裸布尔：字号、密度、配色（还没做）要往同一份快照里继续加键。
     * 值是跨进程来的外部输入，所以这里每个键都可选 —— 归一化在 prefs.ts 里，那里看得见。
     */
    ccoderPrefs?: { collapseThinking?: boolean }
    /**
     * 换偏好：把快照落到 `window.ccoderPrefs` 上，再叫醒页面这一侧的接收端。
     *
     * 与 `ccoderSetLocale` 是同一对分工：Kotlin 注入、Kotlin 调，页面不主动调。
     */
    ccoderSetPrefs?: (prefs: unknown) => void
    /**
     * 页面这一侧的偏好接收端，由 App 在装 `ccoderLocaleSink` 的同一处装上。
     *
     * 可选的，理由同 `ccoderLocaleSink`：页面还没挂上时推过来不该炸。
     */
    ccoderPrefsSink?: (prefs: unknown) => void
  }
}
