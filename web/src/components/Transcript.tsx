import { useCallback, useLayoutEffect, useRef, useState } from 'react'
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
      // 用户输入不走 Markdown：用户敲的 * 不该被当成语法。
      // text 可能是空串（纯图消息），气泡自己决定画不画那个文字块
      return (
        <div className="entry">
          <UserBubble text={item.text} images={item.images} omittedImages={item.omittedImages} />
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

/**
 * 距底阈值（px）。不判精确等于 0：浏览器缩放与亚像素布局下"恰好为 0"不可靠，
 * 而误判的代价是把跟随莫名关掉（设计文档 §4.5）。
 */
const STICK_THRESHOLD_PX = 32

function isAtBottom(el: HTMLElement): boolean {
  return el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_THRESHOLD_PX
}

export function Transcript({ state }: { state: TranscriptState }) {
  const liveText = state.live.assistant

  const scrollerRef = useRef<HTMLDivElement>(null)
  // 跟随意图的同步读版本。scroll 事件处理器必须在同一次事件里读到最新值，
  // 而 state 要等下一次渲染才更新 —— 只读 state 会读到上一轮的旧值。
  const stickRef = useRef(true)
  // 程序化平滑滚动期间会连续派发 scroll 事件，其**中间位置**看起来正是"用户上滚"。
  // 这个标记让处理器跳过程序化滚动产生的事件。
  const programmaticRef = useRef(false)

  const [stick, setStick] = useState(true)
  const [hasNewWhilePaused, setHasNewWhilePaused] = useState(false)

  // 内容增长后跟随到底。用 layout effect 而非 effect，避免"先渲染再跳动"的闪烁。
  //
  // 依赖整个 state 而非 items.length：appendDelta 期间 items 不变、只有 live 在
  // 增长，只监听长度会让流式过程完全不被跟随（设计文档 §4.5）。
  useLayoutEffect(() => {
    const el = scrollerRef.current
    if (!el) return
    if (!stickRef.current) {
      setHasNewWhilePaused(true)
      return
    }
    // 瞬时到底，不走平滑：逐 token 更新下平滑动画每次都被新内容重启，
    // 视口永远追不上，表现为滞后抖动。
    el.scrollTop = el.scrollHeight
  }, [state])

  const handleScroll = useCallback(() => {
    const el = scrollerRef.current
    if (!el) return
    if (programmaticRef.current) {
      // 动画结束的判据是"真正到达底部"，而不是某个时间点 —— 用时间猜会在
      // 慢机器上提前放行，把动画中间位置误读成用户上滚。
      if (isAtBottom(el)) programmaticRef.current = false
      return
    }
    const atBottom = isAtBottom(el)
    stickRef.current = atBottom
    setStick(atBottom)
    if (atBottom) setHasNewWhilePaused(false)
  }, [])

  const jumpToBottom = useCallback(() => {
    const el = scrollerRef.current
    if (!el) return
    stickRef.current = true
    setStick(true)
    setHasNewWhilePaused(false)
    // 只有这一次跳转值得动画，因此在这里显式请求，而不是让 CSS 全局生效。
    // scrollTo 在 jsdom 中不存在（实测）—— 这也是真实浏览器的老版本兜底路径。
    if (typeof el.scrollTo === 'function') {
      programmaticRef.current = true
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    } else {
      el.scrollTop = el.scrollHeight
    }
  }, [])

  return (
    // 包装层只为了让按钮相对**视口**定位：按钮若放进 .transcript 会成为滚动
    // 内容的一部分，跟着内容一起滚走。
    <div className="transcript-wrap">
      <div
        className="transcript"
        data-testid="transcript"
        ref={scrollerRef}
        onScroll={handleScroll}
      >
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

      {!stick && hasNewWhilePaused && (
        <button
          type="button"
          className="jump-to-bottom"
          data-testid="jump-to-bottom"
          onClick={jumpToBottom}
        >
          回到底部
        </button>
      )}
    </div>
  )
}
