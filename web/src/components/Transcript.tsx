import type { TranscriptItem, TranscriptState } from '../types'
import { AssistantBubble } from './AssistantBubble'
import { ErrorBubble } from './ErrorBubble'
import { Markdown } from './Markdown'
import { ResultLine } from './ResultLine'
import { StreamingCursor } from './StreamingCursor'
import { SystemNote } from './SystemNote'
import { ThinkingBlock } from './ThinkingBlock'
import { ToolCallBlock } from './ToolCallBlock'
import { UserBubble } from './UserBubble'

function Timestamp({ ts }: { ts: number }) {
  const d = new Date(ts)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return (
    <time className="timestamp" data-testid="timestamp" title={d.toLocaleString()}>
      {hh}:{mm}
    </time>
  )
}

function Item({ item }: { item: TranscriptItem }) {
  switch (item.kind) {
    case 'user':
      // 用户输入不走 Markdown：用户敲的 * 不该被当成语法
      return (
        <div className="entry">
          <UserBubble text={item.text} />
          <Timestamp ts={item.ts} />
        </div>
      )

    case 'assistant':
      return (
        <div className="entry">
          <AssistantBubble>
            <Markdown text={item.text} />
          </AssistantBubble>
          <Timestamp ts={item.ts} />
        </div>
      )

    case 'error':
      // 错误文本保持原文，避免 Markdown 渲染掩盖关键信息
      return (
        <div className="entry">
          <ErrorBubble text={item.text} />
          <Timestamp ts={item.ts} />
        </div>
      )

    case 'thinking':
      return <ThinkingBlock text={item.text} />

    case 'toolUse':
      return <ToolCallBlock name={item.name} input={item.input} />

    case 'systemNote':
      return <SystemNote text={item.text} />

    case 'result':
      return (
        <ResultLine subtype={item.subtype} costUsd={item.costUsd} durationMs={item.durationMs} />
      )

    default:
      // 未知 kind 静默忽略（设计文档 §3.3）。parseItem 已过滤过一层，
      // 这里兜住直接构造 state 的情况。
      return null
  }
}

export function Transcript({ state }: { state: TranscriptState }) {
  const liveText = state.live.assistant

  return (
    <div className="transcript" data-testid="transcript">
      {state.items.map((item) => (
        <Item key={item.id} item={item} />
      ))}
      {liveText !== undefined && (
        <div className="entry">
          <AssistantBubble>
            <Markdown text={liveText} />
            <StreamingCursor />
          </AssistantBubble>
        </div>
      )}
    </div>
  )
}
