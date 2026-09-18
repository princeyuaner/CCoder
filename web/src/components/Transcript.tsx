import { memo, useCallback, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import type { ToolResultItem, TranscriptItem, TranscriptState } from '../types'
import { nestByParent, type NestedItem } from '../nesting'
import { AssistantBubble } from './AssistantBubble'
import { ErrorBubble } from './ErrorBubble'
import { Markdown } from './Markdown'
import { ResultLine } from './ResultLine'
import { StreamingCursor } from './StreamingCursor'
import { SystemNote } from './SystemNote'
import { LiveThinkingBlock, ThinkingBlock } from './ThinkingBlock'
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

/**
 * 一项转写内容。**必须 memo** —— 流式输出期间每一帧都会重渲染整段，不 memo 的话
 * 每来一个字，历史里每一条消息都要重新渲染一遍（2026-09-14 实测每帧 48ms）。
 *
 * 所以 props 也得是"引用稳定"的：结果与收尾状态**精确到这一条**地传进来，
 * 而不是把整张 map 传下来 —— 传 map 的话，任何一条工具结果到达都会让所有
 * 项拿到新引用，memo 当场失效，等于没做。
 */
const Item = memo(function Item({
  item,
  result,
  turnEnded = false,
  nested,
}: {
  item: TranscriptItem
  /** 只有 toolUse 项有：按 toolUseId 配好的结果。 */
  result?: ToolResultItem
  /** 只有 toolUse 项有：回合已结束仍没等到结果。 */
  turnEnded?: boolean
  /**
   * 只有 toolUse 项有：**子代理那一块**（A1）。
   *
   * 传的是**事先建好的节点**而不是子项数组：数组每次渲染都是新对象，memo 会当场失效，
   * 于是每次流式增量都让所有卡片重绘 —— 那条账 2026-09-14 已经算过一次（见文件头）。
   * 节点在 Transcript 的 useMemo 里随 items 一起重算，顺序与 items 同步。
   */
  nested?: ReactNode
}) {
  switch (item.kind) {
    case 'user':
      // 用户输入不走 Markdown：用户敲的 * 不该被当成语法
      return (
        <div className="entry">
          <UserBubble text={item.text} images={item.images} />
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
      return <ToolCallBlock item={item} result={result} turnEnded={turnEnded} nested={nested} />

    case 'toolResult':
      // 结果不单独成项：它已经挂进对应的那张工具卡片里了（见 resultsByToolUseId）。
      // 两处都画就等于同一条输出在转写区里出现两遍
      return null

    case 'systemNote':
      return <SystemNote text={item.text} />

    case 'result':
      return (
        <ResultLine
          subtype={item.subtype}
          durationMs={item.durationMs}
          inputTokens={item.inputTokens}
          outputTokens={item.outputTokens}
          cacheReadTokens={item.cacheReadTokens}
        />
      )

    default:
      // 未知 kind 静默忽略（设计文档 §3.3）。parseItem 已过滤过一层，
      // 这里兜住直接构造 state 的情况。
      return null
  }
})

/**
 * 距底阈值（px）。不判精确等于 0：浏览器缩放与亚像素布局下"恰好为 0"不可靠，
 * 而误判的代价是把跟随莫名关掉（设计文档 §4.5）。
 */
const STICK_THRESHOLD_PX = 32

function isAtBottom(el: HTMLElement): boolean {
  return el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_THRESHOLD_PX
}

/**
 * 转写区里最后一条**用户消息**的 id，没有就是 null。
 *
 * 拿它当"用户刚发了一条"的判据。用 id 而不是"用户消息的条数"：`reset`
 * 与回放都会把 items 整个换掉，条数会在换会话时撞出假阳性。
 */
/**
 * 子代理那一块（A1）：缩进 + 左侧一条竖线，里面是**它自己的对话**。
 *
 * 数据来自 `parent` 分组（[nestByParent]）—— 不是另开一条流，也不是去读磁盘，
 * 所以它是**边跑边长**的（唯一不长的是正文：实测子代理没有增量帧，整段到达）。
 *
 * 递归：子代理里再派子代理时，那一层会自己套一层（同一个 key：那张卡的 toolUseId）。
 */
function SubagentBlock({
  nodes,
  results,
  ended,
}: {
  nodes: NestedItem[]
  results: Map<string, ToolResultItem>
  ended: Set<string>
}) {
  return (
    <div className="subagent" data-testid="subagent-block">
      <div className="subagent__head">
        子代理的对话
        <span className="subagent__count">{countTools(nodes)} 次工具调用</span>
      </div>
      {nodes.map((node) => (
        <Item
          key={node.item.id}
          item={node.item}
          result={
            node.item.kind === 'toolUse' ? results.get(node.item.toolUseId) : undefined
          }
          turnEnded={node.item.kind === 'toolUse' && ended.has(node.item.toolUseId)}
          nested={
            node.children.length > 0 ? (
              <SubagentBlock nodes={node.children} results={results} ended={ended} />
            ) : undefined
          }
        />
      ))}
    </div>
  )
}

/** 这一块里一共跑了几次工具（含更深那几层）—— 卡面上那个计数就是它。 */
function countTools(nodes: NestedItem[]): number {
  let n = 0
  for (const node of nodes) {
    if (node.item.kind === 'toolUse') n += 1
    n += countTools(node.children)
  }
  return n
}

function lastUserItemIdOf(items: TranscriptItem[]): string | null {
  for (let i = items.length - 1; i >= 0; i--) {
    if (items[i].kind === 'user') return items[i].id
  }
  return null
}

export function Transcript({ state }: { state: TranscriptState }) {
  const liveText = state.live.assistant
  // 进行中的思考。空串按"没有"处理：Kotlin 侧会把空增量过滤掉，这里的判断是兜底
  const liveThinking = state.live.thinking !== '' ? state.live.thinking : undefined

  // 工具结果与工具调用在协议里是**两条独立的消息**（结果是后到的那条），
  // 这里按 toolUseId 配好再往下传 —— 配对只此一处，卡片自己不得到处找
  const resultsByToolUseId = useMemo(() => {
    const map = new Map<string, ToolResultItem>()
    for (const item of state.items) {
      if (item.kind === 'toolResult' && item.toolUseId !== '') {
        map.set(item.toolUseId, item)
      }
    }
    return map
  }, [state.items])

  // 哪些工具调用**再也等不到结果**了：在它之后已经出现过回合结束的 result 事件。
  // 被拒绝、被中断、会话被杀掉的工具不会再有 toolResult —— 不收尾那张卡片就会
  // 永远转圈，而且转得和"真的在跑"一模一样（设计稿 tool-progress.html 细节②）。
  //
  // 倒着扫：先用 turnEndSeen 记住"这条之后有没有回合结束"，再判断这条工具要不要
  // 收尾。正着扫会漏掉一个关键区别 —— 新回合里正在跑的工具，前面也有旧回合的
  // result，正着扫会把它误判成已中断。
  const endedToolUseIds = useMemo(() => {
    const ended = new Set<string>()
    let turnEndSeen = false
    for (let i = state.items.length - 1; i >= 0; i--) {
      const it = state.items[i]
      if (it.kind === 'result') turnEndSeen = true
      else if (it.kind === 'toolUse' && turnEndSeen && !resultsByToolUseId.has(it.toolUseId)) {
        ended.add(it.toolUseId)
      }
    }
    return ended
  }, [state.items, resultsByToolUseId])

  const scrollerRef = useRef<HTMLDivElement>(null)
  // 跟随意图的同步读版本。scroll 事件处理器必须在同一次事件里读到最新值，
  // 而 state 要等下一次渲染才更新 —— 只读 state 会读到上一轮的旧值。
  const stickRef = useRef(true)
  // 程序化平滑滚动期间会连续派发 scroll 事件，其**中间位置**看起来正是"用户上滚"。
  // 这个标记让处理器跳过程序化滚动产生的事件。
  const programmaticRef = useRef(false)

  const [stick, setStick] = useState(true)
  const [hasNewWhilePaused, setHasNewWhilePaused] = useState(false)
  // 上一次看见的"最后一条用户消息"。见下面 layout effect 的第一段。
  const lastUserItemIdRef = useRef<string | null>(null)

  // 内容增长后跟随到底。用 layout effect 而非 effect，避免"先渲染再跳动"的闪烁。
  //
  // 依赖整个 state 而非 items.length：appendDelta 期间 items 不变、只有 live 在
  // 增长，只监听长度会让流式过程完全不被跟随（设计文档 §4.5）。
  useLayoutEffect(() => {
    const el = scrollerRef.current
    if (!el) return

    // 自己发出去一条 = 把视口带回底部，哪怕上一秒还在翻旧内容（2026-09-15 用户提：
    // 暂停态下按发送，屏幕上什么都不动，看起来像没发出去）。
    //
    // 发送发生在 Kotlin 侧（输入框是 Swing 的），web 能看见的信号只有那一条
    // `append(user)` —— 所以判据是**最后一条用户消息换了 id**。敲下发送的那一刻
    // 它就到了（ClaudePanel.sendCurrentInput），不必等模型回答；忙时排队的那条在
    // 真正发出时由 flushQueue 推，同样落在这一条上。
    //
    // 回放历史（切会话 / 恢复）走的也是这条路，于是同样落到最底端 —— 那正是
    // "刚换了会话，想看看聊到哪了"该在的位置（详见设计稿 §4.5 的修订块）。
    const lastUserId = lastUserItemIdOf(state.items)
    if (lastUserId !== null && lastUserId !== lastUserItemIdRef.current) {
      lastUserItemIdRef.current = lastUserId
      stickRef.current = true
      setStick(true)
      setHasNewWhilePaused(false)
    }

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

  // 子代理那一块：按 `parent` 把流水归位（A1）。两个 memo 都只依赖 items 与那两张表，
  // 所以**流式的每一帧都不重算** —— 与上面那条 memo 同一条账。
  const nestedNodes = useMemo(() => nestByParent(state.items), [state.items])
  const nestedBlocks = useMemo(() => {
    const map = new Map<string, ReactNode>()
    for (const node of nestedNodes) {
      if (node.children.length === 0) continue
      map.set(
        node.item.id,
        <SubagentBlock
          nodes={node.children}
          results={resultsByToolUseId}
          ended={endedToolUseIds}
        />,
      )
    }
    return map
  }, [nestedNodes, resultsByToolUseId, endedToolUseIds])

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
        {/* 一次调用一张卡 —— 「归堆」已按用户要求撤销（2026-09-14）：
            并成一组之后，每次调用各自的描述与对应文件就看不见了 */}
        {nestedNodes.map((node) => (
          <Item
            key={node.item.id}
            item={node.item}
            // 精确到这一条地取（不是把 map 传下去）：memo 靠 props 引用相等
            // 才跳得过重渲染，传 map 等于每来一条结果就让所有项一起失效
            result={
              node.item.kind === 'toolUse'
                ? resultsByToolUseId.get(node.item.toolUseId)
                : undefined
            }
            turnEnded={
              node.item.kind === 'toolUse' && endedToolUseIds.has(node.item.toolUseId)
            }
            nested={nestedBlocks.get(node.item.id)}
          />
        ))}
        {/* 思考在正文之前 —— 与 SDK 给的块顺序一致 */}
        {liveThinking !== undefined && <LiveThinkingBlock text={liveThinking} />}
        {liveText !== undefined && (
          <div className="entry">
            <AssistantBubble>
              <Markdown text={liveText} />
              <StreamingCursor />
            </AssistantBubble>
          </div>
        )}
      </div>

      {/* 只要离开底部就浮出来 —— 用户滚上去多半就是想回来，那时按钮必须点得到
          （2026-09-15 用户报"回到底部的按钮现在怎么不显示了"：原来的条件是
          `!stick && hasNewWhilePaused`，滚上去但没新内容时它按设计不出现）。

          这期间**又来了新内容**的话多一个小圆点。它同时是"暂停已被登记"的精确
          证据：`data-new` 只在那个 layout effect 的暂停分支里被置起，单测盯它
          （见 Transcript.test.tsx）—— 否则"不移动视口"与"什么都没做"分不开。 */}
      {!stick && (
        <button
          type="button"
          className="jump-to-bottom"
          data-testid="jump-to-bottom"
          data-new={hasNewWhilePaused}
          aria-label={hasNewWhilePaused ? '回到底部（有新内容）' : '回到底部'}
          onClick={jumpToBottom}
        >
          回到底部
          {hasNewWhilePaused && <span className="jump-to-bottom__dot" aria-hidden="true" />}
        </button>
      )}
    </div>
  )
}
