export type ItemKind =
  | 'user' | 'assistant' | 'thinking' | 'toolUse' | 'error' | 'result' | 'systemNote'

interface Base {
  id: string
  ts: number
}

export interface UserItem extends Base { kind: 'user'; text: string }
export interface AssistantItem extends Base { kind: 'assistant'; text: string }
export interface ThinkingItem extends Base { kind: 'thinking'; text: string }
export interface ErrorItem extends Base { kind: 'error'; text: string }
export interface SystemNoteItem extends Base { kind: 'systemNote'; text: string }
export interface ToolUseItem extends Base { kind: 'toolUse'; name: string; input: string }
export interface ResultItem extends Base {
  kind: 'result'
  subtype: string
  costUsd?: number
  durationMs?: number
}

export type TranscriptItem =
  | UserItem | AssistantItem | ThinkingItem
  | ToolUseItem | ErrorItem | ResultItem | SystemNoteItem

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
