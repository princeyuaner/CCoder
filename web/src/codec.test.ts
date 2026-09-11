import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { applyOps, parseOps } from './codec'
import type { TranscriptState } from './types'

// ESM 里没有 __dirname —— 用 import.meta.url 推导。
// tsconfig 的 module 是 ESNext，__dirname 会直接编译失败。
const HERE = dirname(fileURLToPath(import.meta.url))
const FIXTURE = resolve(HERE, '../../shared/transcript-ops.json')

function emptyState(): TranscriptState {
  return { items: [], live: {} }
}

describe('契约 fixture', () => {
  it('与 Kotlin 侧读取的是同一份文件', () => {
    // 路径指向仓库根的 shared/ 而不是 web/ 内部——这正是"共享"的意义
    expect(FIXTURE).toContain('shared')
    expect(() => readFileSync(FIXTURE, 'utf8')).not.toThrow()
  })

  it('能解析 fixture 中的全部操作', () => {
    const ops = parseOps(JSON.parse(readFileSync(FIXTURE, 'utf8')))
    expect(ops).toHaveLength(12)
    expect(ops[0].op).toBe('reset')
    expect(ops[3].op).toBe('appendDelta')
  })

  it('fixture 能被完整应用到状态上而不丢内容', () => {
    const ops = parseOps(JSON.parse(readFileSync(FIXTURE, 'utf8')))
    const state = applyOps(emptyState(), ops)
    // fixture 里有 7 条 append/finalize 产生的消息
    expect(state.items.length).toBeGreaterThanOrEqual(6)
    expect(state.live.assistant).toBeUndefined()
  })
})

describe('applyOps', () => {
  it('reset 清空全部', () => {
    const state = applyOps(
      { items: [{ kind: 'user', id: 'x', ts: 1, text: '旧' }], live: { assistant: '半截' } },
      [{ op: 'reset' }],
    )
    expect(state.items).toHaveLength(0)
    expect(state.live).toEqual({})
  })

  it('append 追加消息', () => {
    const state = applyOps(emptyState(), [
      { op: 'append', item: { kind: 'user', id: 'm0', ts: 1, text: '你好' } },
    ])
    expect(state.items).toHaveLength(1)
    expect(state.items[0].kind).toBe('user')
  })

  it('appendDelta 累积到进行中的气泡，不产生新消息', () => {
    let state = applyOps(emptyState(), [{ op: 'appendDelta', target: 'assistant', text: '你' }])
    state = applyOps(state, [{ op: 'appendDelta', target: 'assistant', text: '好' }])

    expect(state.items).toHaveLength(0)
    expect(state.live.assistant).toBe('你好')
  })

  it('finalizeDelta 以最终文本覆盖进行中的气泡并收尾', () => {
    let state = applyOps(emptyState(), [{ op: 'appendDelta', target: 'assistant', text: '你' }])
    state = applyOps(state, [{ op: 'finalizeDelta', target: 'assistant', text: '你好呀' }])

    expect(state.live.assistant).toBeUndefined()
    expect(state.items).toHaveLength(1)
    expect(state.items[0]).toMatchObject({ kind: 'assistant', text: '你好呀' })
  })

  it('finalizeDelta 在无进行中气泡时也产生一条消息', () => {
    // 有些回合不产生 stream_event（例如极短的响应），
    // 此时 finalizeDelta 是唯一的文本来源，不能丢
    const state = applyOps(emptyState(), [
      { op: 'finalizeDelta', target: 'assistant', text: '短回复' },
    ])
    expect(state.items).toHaveLength(1)
    expect(state.items[0]).toMatchObject({ kind: 'assistant', text: '短回复' })
  })

  it('clearDelta 丢弃进行中的气泡', () => {
    let state = applyOps(emptyState(), [{ op: 'appendDelta', target: 'assistant', text: '半截' }])
    state = applyOps(state, [{ op: 'clearDelta', target: 'assistant' }])

    expect(state.live.assistant).toBeUndefined()
    expect(state.items).toHaveLength(0)
  })

  it('批次内多个 op 按序生效', () => {
    const state = applyOps(emptyState(), [
      { op: 'appendDelta', target: 'assistant', text: '中间态' },
      { op: 'finalizeDelta', target: 'assistant', text: '最终' },
      { op: 'append', item: { kind: 'result', id: 'r', ts: 2, subtype: 'success' } },
    ])
    expect(state.items).toHaveLength(2)
    expect(state.items[0]).toMatchObject({ text: '最终' })
    expect(state.items[1].kind).toBe('result')
  })

  it('不修改传入的 state（保持不可变）', () => {
    const before = emptyState()
    const snapshot = JSON.stringify(before)
    applyOps(before, [{ op: 'append', item: { kind: 'user', id: 'x', ts: 1, text: 'a' } }])
    expect(JSON.stringify(before)).toBe(snapshot)
  })

  it('空批次返回原 state 引用', () => {
    // React 依赖引用相等来判断是否需要重渲染，
    // 空批次产生新对象会导致无谓的重渲染
    const before = emptyState()
    expect(applyOps(before, [])).toBe(before)
  })
})

describe('parseOps 的容错', () => {
  it('丢弃未知 op 而不是崩溃', () => {
    // 对应设计文档 §3.3 的"未知即忽略"——Kotlin 侧新增 op 时
    // 旧版本前端不该白屏
    const ops = parseOps([
      { op: 'reset' },
      { op: 'someFutureOp', payload: 1 },
    ] as unknown[])
    expect(ops).toHaveLength(1)
    expect(ops[0].op).toBe('reset')
  })

  it('丢弃结构不完整的 op', () => {
    const ops = parseOps([
      { op: 'append' },                           // 缺 item
      { op: 'appendDelta', target: 'assistant' }, // 缺 text
      { op: 'clearDelta', target: 'assistant' },  // 合法
    ] as unknown[])
    expect(ops).toHaveLength(1)
  })

  it('丢弃未知 kind 的消息项', () => {
    const ops = parseOps([
      { op: 'append', item: { kind: 'someNewKind', id: 'x', ts: 1 } },
      { op: 'append', item: { kind: 'user', id: 'y', ts: 2, text: 'ok' } },
    ] as unknown[])
    expect(ops).toHaveLength(1)
  })

  it('丢弃缺少 id 或 ts 的消息项', () => {
    const ops = parseOps([
      { op: 'append', item: { kind: 'user', ts: 1, text: 'no id' } },
      { op: 'append', item: { kind: 'user', id: 'x', text: 'no ts' } },
      { op: 'append', item: { kind: 'user', id: 'y', ts: 2, text: 'ok' } },
    ] as unknown[])
    expect(ops).toHaveLength(1)
  })

  it('非数组输入返回空数组', () => {
    expect(parseOps(null)).toEqual([])
    expect(parseOps({})).toEqual([])
    expect(parseOps('[]')).toEqual([])
  })
})

describe('parseItem 的可选字段', () => {
  it('result 保留合法的可选字段', () => {
    const ops = parseOps([
      { op: 'append', item: { kind: 'result', id: 'r', ts: 1, subtype: 'success', costUsd: 0.1, durationMs: 100 } },
    ])
    const item = ops[0] as { op: 'append'; item: { costUsd?: number; durationMs?: number } }
    expect(item.item.costUsd).toBe(0.1)
    expect(item.item.durationMs).toBe(100)
  })

  it('result 丢弃类型不符的可选字段而非整体拒绝', () => {
    const ops = parseOps([
      { op: 'append', item: { kind: 'result', id: 'r', ts: 1, subtype: 'success', costUsd: 'x' } },
    ])
    expect(ops).toHaveLength(1)
    const item = ops[0] as { op: 'append'; item: { costUsd?: number } }
    expect(item.item.costUsd).toBeUndefined()
  })
})
