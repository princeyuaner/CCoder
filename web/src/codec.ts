import type { TranscriptItem, TranscriptOp, TranscriptState, UserItem } from './types'

const KNOWN_KINDS = new Set([
  'user', 'assistant', 'thinking', 'toolUse', 'toolResult',
  'error', 'result', 'systemNote',
])

/**
 * 校验并解析来自 Kotlin 的操作批次。
 *
 * 未知的 op 与未知的消息 kind 一律丢弃而非抛错：Kotlin 侧新增类型时，
 * 已安装的旧版本前端不该白屏（对应设计文档 §3.3 的"未知即忽略"）。
 */
export function parseOps(raw: unknown): TranscriptOp[] {
  if (!Array.isArray(raw)) return []
  const out: TranscriptOp[] = []

  for (const entry of raw) {
    if (!entry || typeof entry !== 'object') continue
    const op = entry as Record<string, unknown>

    switch (op.op) {
      case 'reset':
        out.push({ op: 'reset' })
        break

      case 'append': {
        const item = parseItem(op.item)
        if (item) out.push({ op: 'append', item })
        break
      }

      case 'appendDelta':
      case 'finalizeDelta':
        if (typeof op.target === 'string' && typeof op.text === 'string') {
          out.push({ op: op.op, target: op.target, text: op.text })
        }
        break

      case 'clearDelta':
        if (typeof op.target === 'string') out.push({ op: 'clearDelta', target: op.target })
        break

      default:
        break // 未知 op，丢弃
    }
  }
  return out
}

/** 只留字符串项。坏数据丢的是**那个字段**，不是整条消息 —— 图坏了不该把文字也吃掉。 */
function stringArray(raw: unknown): string[] {
  return Array.isArray(raw) ? raw.filter((s): s is string => typeof s === 'string') : []
}

/**
 * 子代理归属（`parent`）。空串、非字符串一律**当没有** —— 一个坏值不该让这一项
 * 从转写区消失，最坏的结果就是它留在主流水里（与"配不上父项"同一条容忍口径）。
 */
function parentOf(it: Record<string, unknown>): { parent?: string } {
  return typeof it.parent === 'string' && it.parent !== '' ? { parent: it.parent } : {}
}

function parseItem(raw: unknown): TranscriptItem | null {
  if (!raw || typeof raw !== 'object') return null
  const it = raw as Record<string, unknown>

  if (typeof it.id !== 'string' || typeof it.ts !== 'number') return null
  if (typeof it.kind !== 'string' || !KNOWN_KINDS.has(it.kind)) return null

  const base = { id: it.id, ts: it.ts }

  switch (it.kind) {
    case 'user': {
      if (typeof it.text !== 'string') return null
      const item: UserItem = { ...base, kind: 'user', text: it.text }
      // **图必须原样带过去**。这个函数是逐字段重建的，漏一个字段它就永远到不了
      // 转写区：2026-09-15 就是这么丢了 images —— Kotlin 把图推出去了、消息也发
      // 出去了（模型确实收到了图），界面上却只有字。当时两侧测试与共享夹具里
      // 都没有一条带图的用例，全绿；布局探针量的又是自己拼的 HTML，根本没走这里。
      const pics = stringArray(it.images)
      if (pics.length > 0) item.images = pics
      return item
    }

    case 'assistant':
    case 'thinking':
      // 只有这两种会带 parent（子代理说的）；error / systemNote 没有归属可言
      return typeof it.text === 'string'
        ? ({ ...base, kind: it.kind, text: it.text, ...parentOf(it) } as TranscriptItem)
        : null

    case 'error':
    case 'systemNote':
      return typeof it.text === 'string'
        ? ({ ...base, kind: it.kind, text: it.text } as TranscriptItem)
        : null

    case 'toolUse':
      return typeof it.name === 'string' && typeof it.input === 'string'
        ? {
            ...base,
            kind: 'toolUse',
            name: it.name,
            input: it.input,
            // 缺这个字段不丢整条：调用本身要显示出来，只是等不到输出
            // （老版本 Kotlin 不送它）
            toolUseId: typeof it.toolUseId === 'string' ? it.toolUseId : '',
            ...parentOf(it),
          }
        : null

    case 'toolResult':
      // 与调用相反：没有 toolUseId 的结果**只能丢掉** —— 它挂不回任何一张
      // 卡片，画出来就是一段无主的输出
      return typeof it.toolUseId === 'string' && it.toolUseId !== '' && typeof it.text === 'string'
        ? {
            ...base,
            kind: 'toolResult',
            toolUseId: it.toolUseId,
            text: it.text,
            isError: it.isError === true,
          }
        : null

    case 'result': {
      if (typeof it.subtype !== 'string') return null
      const item: TranscriptItem = { ...base, kind: 'result', subtype: it.subtype }
      // 可选字段类型不符时丢弃该字段而非整条消息——一条 result 少了个成本
      // 数字不值得浪费掉整段对话的反馈
      if (typeof it.costUsd === 'number') item.costUsd = it.costUsd
      if (typeof it.durationMs === 'number') item.durationMs = it.durationMs
      if (typeof it.inputTokens === 'number') item.inputTokens = it.inputTokens
      if (typeof it.outputTokens === 'number') item.outputTokens = it.outputTokens
      if (typeof it.cacheReadTokens === 'number') item.cacheReadTokens = it.cacheReadTokens
      return item
    }

    default:
      return null
  }
}

/**
 * 应用一批操作，返回新的 state。
 *
 * **不修改传入的 state** —— React 依赖引用变化来触发重渲染，
 * 原地修改会导致界面不更新这类难查的问题。
 *
 * 也没有无条件复制：只在真正要改某个字段时才复制。
 * 空批次直接返回原引用，避免无谓的重渲染。
 */
export function applyOps(state: TranscriptState, ops: TranscriptOp[]): TranscriptState {
  if (ops.length === 0) return state

  let items = state.items
  let live = state.live
  let itemsCloned = false
  let liveCloned = false

  const mutateItems = (): TranscriptItem[] => {
    if (!itemsCloned) {
      items = items.slice()
      itemsCloned = true
    }
    return items
  }
  const mutateLive = (): Record<string, string> => {
    if (!liveCloned) {
      live = { ...live }
      liveCloned = true
    }
    return live
  }

  let finalizeCounter = 0

  /**
   * 把还挂在逐字缓冲里的东西**落成条目**（思考与正文各一种）。
   *
   * 正常情况下轮不到它：正文由 `finalizeDelta` 收尾、思考由「整块到达」那条
   * append 收尾。但**被打断 / 出错的一轮不会再产那两条消息** —— 缓冲留着，界面
   * 就永远停在「思考中」那只转圈加还在走的秒表上（2026-09-21 用户报「思考中时
   * 点击停止按钮，思考中不会停止」）。
   *
   * 回合结束是还认得出"它不会再来了"的唯一时刻：SDK 的契约是**一轮恰好一条
   * result**，把它当回合结束的信号（sdk.d.ts:5390）。
   *
   * 落成条目而不是丢掉：那些字是这一轮真实发生过的思考与输出，丢掉等于把用户
   * 唯一能看的东西抹了。
   */
  const flushLive = (): void => {
    const thinking = live['thinking']
    const text = live['assistant']
    if (thinking !== undefined && thinking !== '') {
      mutateItems().push({
        kind: 'thinking',
        id: `flushed-thinking-${items.length}-${finalizeCounter++}`,
        ts: Date.now(),
        text: thinking,
      })
    }
    if (text !== undefined && text !== '') {
      mutateItems().push({
        kind: 'assistant',
        id: `flushed-assistant-${items.length}-${finalizeCounter++}`,
        ts: Date.now(),
        text,
      })
    }
    if (thinking !== undefined || text !== undefined) {
      const next = mutateLive()
      delete next['thinking']
      delete next['assistant']
    }
  }

  for (const op of ops) {
    switch (op.op) {
      case 'reset':
        items = []
        live = {}
        itemsCloned = true
        liveCloned = true
        break

      case 'append': {
        // 回合结束那一条**最后**压：先把没收尾的落下来，再放结果行 ——
        // 顺序反过来的话，被打断的那段思考会跑到「回合结束」那行下面，
        // 看着像下一轮的东西
        if (op.item.kind === 'result') flushLive()

        const arrived = op.item
        // 同一次工具调用的**第二条**（参数生成完的那条）不是一张新卡片：起头帧那条
        // 已经把它画出来了（input 是空串），这里把参数补上去。不合并就是一次调用
        // 两张卡 —— 而结果按 toolUseId 配对，**两张都会配上**，屏幕上并排两张一样
        // 的卡，其中一张永远没有参数（2026-09-22：卡片提前到起头帧出生，见 Kotlin
        // 侧的 startedToolCard）。
        //
        // 只吸收**空参数那张**（`input === ''`）：完整卡 → 完整卡不走这条路。宁可
        // 多画一张，也不要凭空少一张。
        //
        // 保留旧的 `id` / `ts` 是要害不是美观：React 的 key 用的是 item.id，换掉
        // 等于重挂组件 —— 秒表归零（elapsed.ts 的 mountedAt）、展开态与「显示全部」
        // 也一起丢。
        //
        // 找的是**本地 items** 而不是 state.items：起头帧与完整消息可能落在同一批
        // applyOps 里（Kotlin 侧按 16ms 一批推），那时 state.items 里还没有前一条。
        if (arrived.kind === 'toolUse' && arrived.toolUseId !== '') {
          const toolUseId = arrived.toolUseId
          const next = mutateItems()
          const at = next.findIndex(
            (it) => it.kind === 'toolUse' && it.toolUseId === toolUseId && it.input === '',
          )
          if (at >= 0) {
            const started = next[at]
            next[at] = { ...arrived, id: started.id, ts: started.ts }
          } else {
            next.push(arrived)
          }
        } else {
          mutateItems().push(arrived)
        }

        // 整块思考到了，逐字缓冲就作废 —— 两条来源都留着，同一段思考会在转写区
        // 里出现两遍。正文那边靠 finalizeDelta 收尾，思考这边完成时是一个普通的
        // append（没有 finalize 语义），所以由这条规则收
        if (arrived.kind === 'thinking' && live['thinking'] !== undefined) {
          delete mutateLive()['thinking']
        }
        break
      }

      case 'appendDelta': {
        const next = mutateLive()
        next[op.target] = (next[op.target] ?? '') + op.text
        break
      }

      case 'finalizeDelta': {
        // 以最终文本为准——它可能包含增量之外的修正
        const next = mutateItems()
        next.push({
          kind: 'assistant',
          id: `final-${next.length}-${finalizeCounter++}`,
          ts: Date.now(),
          text: op.text,
        })
        delete mutateLive()[op.target]
        break
      }

      case 'clearDelta':
        delete mutateLive()[op.target]
        break
    }
  }

  return { items, live }
}
