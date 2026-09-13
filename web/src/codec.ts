import type { TranscriptItem, TranscriptOp, TranscriptState } from './types'

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

function parseItem(raw: unknown): TranscriptItem | null {
  if (!raw || typeof raw !== 'object') return null
  const it = raw as Record<string, unknown>

  if (typeof it.id !== 'string' || typeof it.ts !== 'number') return null
  if (typeof it.kind !== 'string' || !KNOWN_KINDS.has(it.kind)) return null

  const base = { id: it.id, ts: it.ts }

  switch (it.kind) {
    case 'user': {
      if (typeof it.text !== 'string') return null
      const item: TranscriptItem = { ...base, kind: 'user', text: it.text }
      // 逐项校验、坏的丢单项不丢整条：客户端与服务端版本不一定同步，
      // 一条脏图不该让整句提问消失
      if (Array.isArray(it.images)) {
        const images = it.images
          .filter(
            (v): v is { mediaType: string; data: string } =>
              !!v && typeof v === 'object' &&
              typeof (v as { mediaType?: unknown }).mediaType === 'string' &&
              typeof (v as { data?: unknown }).data === 'string',
          )
          // 线上的键叫 data，模型字段叫 base64（与 Kotlin 侧 TranscriptImage 同名）——
          // 原样留着 data，气泡拼出来的就是 data:image/png;base64,undefined
          .map((v) => ({ mediaType: v.mediaType, base64: v.data }))
        // 全坏等于没有：空数组是"有值但为空"，与契约的零值语义（缺失）不是一回事
        if (images.length > 0) item.images = images
      }
      if (typeof it.omittedImages === 'number' && it.omittedImages > 0) {
        item.omittedImages = it.omittedImages
      }
      return item
    }

    case 'assistant':
    case 'thinking':
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

  for (const op of ops) {
    switch (op.op) {
      case 'reset':
        items = []
        live = {}
        itemsCloned = true
        liveCloned = true
        break

      case 'append':
        mutateItems().push(op.item)
        // 整块思考到了，逐字缓冲就作废 —— 两条来源都留着，同一段思考会在转写区
        // 里出现两遍。正文那边靠 finalizeDelta 收尾，思考这边完成时是一个普通的
        // append（没有 finalize 语义），所以由这条规则收
        if (op.item.kind === 'thinking' && live['thinking'] !== undefined) {
          delete mutateLive()['thinking']
        }
        break

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
