import { describe, expect, it } from 'vitest'
import { nestByParent } from './nesting'
import type { TranscriptItem } from './types'

/** 造一条工具卡。 */
function tool(id: string, toolUseId: string, parent?: string): TranscriptItem {
  return { kind: 'toolUse', id, ts: 1, name: 'Read', input: '{}', toolUseId, ...(parent ? { parent } : {}) }
}

const assistant = (id: string, parent?: string): TranscriptItem => ({
  kind: 'assistant', id, ts: 1, text: '找到了', ...(parent ? { parent } : {}),
})

const user = (id: string): TranscriptItem => ({ kind: 'user', id, ts: 1, text: '去找' })

describe('子代理分组（A1）', () => {
  it('带 parent 的项收进那张 Task 卡里，主流水只剩一张卡', () => {
    const items = [
      user('m0'),
      tool('m1', 'toolu_task'),          // Task 卡：子代理的父项
      tool('m2', 'toolu_sub1', 'toolu_task'),
      assistant('m3', 'toolu_task'),
    ]

    const roots = nestByParent(items)

    expect(roots.map((n) => n.item.id)).toEqual(['m0', 'm1'])
    expect(roots[1].children.map((n) => n.item.id)).toEqual(['m2', 'm3'])
  })

  it('父项找不到就留在主流水里，不丢', () => {
    // 老版本 Kotlin 不送 parent、或 id 对不上时，最坏只是"没嵌套"
    const items = [user('m0'), assistant('m1', 'toolu_不存在'), tool('m2', 'toolu_x')]

    const roots = nestByParent(items)

    expect(roots.map((n) => n.item.id)).toEqual(['m0', 'm1', 'm2'])
    expect(roots.every((n) => n.children.length === 0)).toBe(true)
  })

  it('子项按出现顺序排，与父项在第几条无关', () => {
    const items = [
      assistant('m0', 'toolu_task'),      // 先到的是子代理的正文
      tool('m1', 'toolu_sub', 'toolu_task'),
      tool('m2', 'toolu_task'),           // Task 卡后到（回放那条路不保证顺序）
    ]

    const roots = nestByParent(items)

    expect(roots.map((n) => n.item.id)).toEqual(['m2'])
    expect(roots[0].children.map((n) => n.item.id)).toEqual(['m0', 'm1'])
  })

  it('可以再嵌一层（子代理里再派子代理）', () => {
    const items = [
      tool('m1', 'toolu_task'),
      tool('m2', 'toolu_sub', 'toolu_task'),
      tool('m3', 'toolu_subsub', 'toolu_sub'),
    ]

    const roots = nestByParent(items)

    expect(roots.map((n) => n.item.id)).toEqual(['m1'])
    expect(roots[0].children[0].item.id).toBe('m2')
    expect(roots[0].children[0].children[0].item.id).toBe('m3')
  })

  it('没有 parent 的那条路一字不差（全部留在主流水）', () => {
    const items = [user('m0'), tool('m1', 'toolu_1'), assistant('m2')]

    const roots = nestByParent(items)

    expect(roots.map((n) => n.item.id)).toEqual(['m0', 'm1', 'm2'])
    expect(roots.every((n) => n.children.length === 0)).toBe(true)
  })
})
