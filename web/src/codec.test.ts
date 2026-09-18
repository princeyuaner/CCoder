import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { applyOps, parseOps } from './codec'
import type { TranscriptOp, TranscriptState, UserItem } from './types'

// ESM 里没有 __dirname —— 用 import.meta.url 推导。
// tsconfig 的 module 是 ESNext，__dirname 会直接编译失败。
const HERE = dirname(fileURLToPath(import.meta.url))
const FIXTURE = resolve(HERE, '../../shared/transcript-ops.json')

function emptyState(): TranscriptState {
  return { items: [], live: {} }
}

/** 第一条用户项。带图的断言都要先过它，省得每条都写一遍收窄。 */
function firstUser(ops: TranscriptOp[]): UserItem {
  const op = ops[0]
  if (op.op !== 'append' || op.item.kind !== 'user') throw new Error('第一条不是 user 项')
  return op.item
}

describe('契约 fixture', () => {
  it('与 Kotlin 侧读取的是同一份文件', () => {
    // 路径指向仓库根的 shared/ 而不是 web/ 内部——这正是"共享"的意义
    expect(FIXTURE).toContain('shared')
    expect(() => readFileSync(FIXTURE, 'utf8')).not.toThrow()
  })

  it('能解析 fixture 中的全部操作', () => {
    const ops = parseOps(JSON.parse(readFileSync(FIXTURE, 'utf8')))
    // 17 = 14 + 末尾三条子代理那层（A1，2026-09-18 补）
    expect(ops).toHaveLength(17)
    expect(ops[0].op).toBe('reset')
    expect(ops[3].op).toBe('appendDelta')
  })

  it('fixture 能被完整应用到状态上而不丢内容', () => {
    const ops = parseOps(JSON.parse(readFileSync(FIXTURE, 'utf8')))
    const state = applyOps(emptyState(), ops)
    // fixture 里有 13 条 append/finalize 产生的消息（含工具调用与它的结果）
    expect(state.items).toHaveLength(13)
    expect(state.live.assistant).toBeUndefined()
  })

  it('工具调用带着配对的 toolUseId 过来', () => {
    // 结果要挂回这次调用，全靠这个 id。丢了这个字段界面上就配不上对
    const ops = parseOps(JSON.parse(readFileSync(FIXTURE, 'utf8')))
    const use = ops.find((o) => o.op === 'append' && o.item.kind === 'toolUse')
    expect(use).toMatchObject({ item: { name: 'Read', toolUseId: 'toolu_1' } })
  })

  it('子代理的 parent 一路解析过来，主线程那条不带这个字段（A1）', () => {
    // 夹具里那三条（m8/m9/m10）是这一版补的：不带 parent 的话，两端任何一侧
    // 把它吃掉都测不出来 —— 与 2026-09-15 的 images 是同一类漏洞
    const ops = parseOps(JSON.parse(readFileSync(FIXTURE, 'utf8')))
    const appended = ops.filter((o) => o.op === 'append')
    const taskCard = appended.find((o) => o.item.kind === 'toolUse' && o.item.toolUseId === 'toolu_task')
    const subTool = appended.find((o) => o.item.kind === 'toolUse' && o.item.toolUseId === 'toolu_sub')
    const subText = appended.find((o) => o.item.kind === 'assistant' && o.item.parent !== undefined)

    expect(taskCard?.item).not.toHaveProperty('parent')
    expect(subTool?.item).toMatchObject({ parent: 'toolu_task' })
    expect(subText?.item).toMatchObject({ parent: 'toolu_task', text: '找到了三处' })
  })

  it('工具结果带着配对 id、正文与错误标记过来', () => {
    const ops = parseOps(JSON.parse(readFileSync(FIXTURE, 'utf8')))
    const result = ops.find((o) => o.op === 'append' && (o.item.kind as string) === 'toolResult')
    expect(result).toMatchObject({
      item: { toolUseId: 'toolu_1', text: '1\tpackage a\n2\t\n', isError: false },
    })
  })
})

describe('用户消息里的图', () => {
  it('夹具里带图的那条，图能一路走到状态里', () => {
    // 2026-09-15：正是这一步漏了 —— Kotlin 推出了图、消息也发出去了（模型收到了），
    // 转写区却只有字。夹具里当时没有带图的用例，所以两侧测试都是绿的
    const ops = parseOps(JSON.parse(readFileSync(FIXTURE, 'utf8')))
    const state = applyOps(emptyState(), ops)
    const withPics = state.items.filter(
      (i): i is UserItem => i.kind === 'user' && (i.images?.length ?? 0) > 0,
    )
    expect(withPics).toHaveLength(1)
    expect(withPics[0].images).toEqual([
      'data:image/jpeg;base64,AAAA',
      'data:image/jpeg;base64,BBBB',
    ])
  })

  it('images 原样带过来，不被逐字段重建时丢掉', () => {
    const item = firstUser(
      parseOps([
        { op: 'append', item: { kind: 'user', id: 'm', ts: 1, text: '看这张', images: ['data:image/png;base64,AA'] } },
      ] as unknown[]),
    )
    expect(item.text).toBe('看这张')
    expect(item.images).toEqual(['data:image/png;base64,AA'])
  })

  it('纯文字那条路一字不变：没有 images 就不带这个字段', () => {
    const item = firstUser(
      parseOps([{ op: 'append', item: { kind: 'user', id: 'm', ts: 1, text: '你好' } }] as unknown[]),
    )
    expect(item.images).toBeUndefined()
  })

  it('数组里混进坏值只丢那一项，不连坐文字与别的图', () => {
    const item = firstUser(
      parseOps([
        { op: 'append', item: { kind: 'user', id: 'm', ts: 1, text: '看', images: ['data:a', 42, null, 'data:b'] } },
      ] as unknown[]),
    )
    expect(item.images).toEqual(['data:a', 'data:b'])
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

// 思考块的逐字缓冲与"整块到达"是两条来源：整块到了，逐字的就该作废，
// 否则同一段思考会在转写区里出现两遍。正文那边靠 finalizeDelta 收尾，
// 思考这边没有 finalize 语义（完成时是 Append 一个 thinking 项），
// 所以由这条规则收 —— 它同时也兜住回放路径：历史里反正也不会留下缓冲。
describe('思考块收尾', () => {
  it('append 一条 thinking 项会清掉进行中的思考缓冲', () => {
    let s = applyOps(emptyState(), [
      { op: 'appendDelta', target: 'thinking', text: '它' },
      { op: 'appendDelta', target: 'thinking', text: '在想' },
    ])
    expect(s.live.thinking).toBe('它在想')

    s = applyOps(s, [
      { op: 'append', item: { kind: 'thinking', id: 't1', ts: 1, text: '它在想什么' } },
    ])

    expect(s.live.thinking).toBeUndefined()
    expect(s.items).toHaveLength(1)
  })

  it('只清思考，不动进行中的正文气泡', () => {
    let s = applyOps(emptyState(), [
      { op: 'appendDelta', target: 'assistant', text: '正' },
      { op: 'appendDelta', target: 'thinking', text: '思' },
    ])
    s = applyOps(s, [{ op: 'append', item: { kind: 'thinking', id: 't1', ts: 1, text: '思' } }])

    expect(s.live.thinking).toBeUndefined()
    expect(s.live.assistant).toBe('正')
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
