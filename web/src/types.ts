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
export interface AssistantItem extends Base { kind: 'assistant'; text: string }
export interface ThinkingItem extends Base { kind: 'thinking'; text: string }
export interface ErrorItem extends Base { kind: 'error'; text: string }
export interface SystemNoteItem extends Base { kind: 'systemNote'; text: string }
/**
 * 一次工具调用。
 *
 * `toolUseId` 是 SDK 的 `tool_use.id`；结果项带着同一个 id 回来，
 * 界面靠它把输出挂到这张卡片上。空串表示这次调用没有可配对的 id
 * （老版本 Kotlin 不送这个字段），卡片照画，只是不会等到输出。
 */
export interface ToolUseItem extends Base {
  kind: 'toolUse'
  toolUseId: string
  name: string
  input: string
}

/**
 * 一次工具调用的输出。它与调用是**两条独立的项** —— 协议里本来就是两条
 * 消息，操作序列因此保持只追加。
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
  | { op: 'append'; item: TranscriptItem }
  | { op: 'appendDelta'; target: string; text: string }
  | { op: 'finalizeDelta'; target: string; text: string }
  | { op: 'clearDelta'; target: string }

export interface TranscriptState {
  items: TranscriptItem[]
  /** 进行中的流式气泡，按 target 分。当前只有 'assistant'。 */
  live: Record<string, string>
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
    }
  }
}
