import { describe, expect, it } from 'vitest'
import { actionKindOf, describeRun, groupRows, runFiles } from './grouping'
import type { ToolUseItem, TranscriptItem } from './types'

/**
 * 分组是纯函数（设计稿 docs/design/tool-grouping.html 方案甲）：
 * 哪些算一组、组卡上写什么、涉及哪些文件 —— 全在这里，
 * 渲染层只管画。
 */

const use = (id: string, name: string, input: unknown): ToolUseItem => ({
  kind: 'toolUse',
  id,
  ts: 1,
  toolUseId: `toolu_${id}`,
  name,
  input: JSON.stringify(input),
})

const res = (toolUseId: string): TranscriptItem => ({
  kind: 'toolResult',
  id: `r_${toolUseId}`,
  ts: 2,
  toolUseId,
  text: 'out',
  isError: false,
})

const say = (id: string, text: string): TranscriptItem => ({ kind: 'assistant', id, ts: 3, text })

/** 组里那几个调用的 id，用来看"谁跟谁在一组"。 */
const idsOf = (rows: ReturnType<typeof groupRows>) =>
  rows.map((row) => (row.kind === 'run' ? row.uses.map((u) => u.id).join('+') : row.item.id))

describe('groupRows', () => {
  it('两次连着调用并成一组', () => {
    const rows = groupRows([use('a', 'Read', { file_path: '/a' }), use('b', 'Bash', { command: 'ls' })])
    expect(rows).toHaveLength(1)
    expect(rows[0].kind).toBe('run')
  })

  it('只有一次调用不并组 —— 组卡多占一行还多点一下，信息却一样', () => {
    const rows = groupRows([use('a', 'Read', { file_path: '/a' })])
    expect(idsOf(rows)).toEqual(['a'])
    expect(rows[0].kind).toBe('item')
  })

  it('toolResult 既不画也不打断 —— 真实顺序就是「调用 → 结果 → 调用」', () => {
    const rows = groupRows([
      use('a', 'Read', { file_path: '/a' }),
      res('toolu_a'),
      use('b', 'Read', { file_path: '/b' }),
      res('toolu_b'),
    ])
    expect(idsOf(rows)).toEqual(['a+b'])
  })

  it('正文、思考、提问都打断一组', () => {
    const rows = groupRows([
      use('a', 'Read', { file_path: '/a' }),
      use('b', 'Read', { file_path: '/b' }),
      say('t', '看完了'),
      use('c', 'Edit', { file_path: '/a', old_string: 'x', new_string: 'y' }),
      use('d', 'Bash', { command: 'ls' }),
      { kind: 'thinking', id: 'th', ts: 4, text: '想想' },
      use('e', 'Bash', { command: 'pwd' }),
    ])
    expect(idsOf(rows)).toEqual(['a+b', 't', 'c+d', 'th', 'e'])
  })

  it('回合结束的 result 也打断 —— 分组因此被限制在一个回合之内', () => {
    const rows = groupRows([
      use('a', 'Read', { file_path: '/a' }),
      use('b', 'Read', { file_path: '/b' }),
      { kind: 'result', id: 'r', ts: 5, subtype: 'success' },
      use('c', 'Read', { file_path: '/c' }),
      use('d', 'Read', { file_path: '/d' }),
    ])
    expect(idsOf(rows)).toEqual(['a+b', 'r', 'c+d'])
  })

  it('空转写区给空', () => {
    expect(groupRows([])).toEqual([])
  })
})

describe('describeRun', () => {
  it('按 读 / 改 / 跑 分类计数', () => {
    expect(
      describeRun([
        use('a', 'Read', {}),
        use('b', 'Grep', {}),
        use('c', 'Edit', {}),
        use('d', 'Bash', {}),
        use('e', 'Bash', {}),
      ]),
    ).toBe('读 2 · 改 1 · 跑 2')
  })

  it('认不出的工具不进摘要 —— 全是 MCP 工具时给空串，而不是「其他 3」', () => {
    expect(
      describeRun([use('a', 'mcp__x__y', {}), use('b', 'mcp__x__z', {})]),
    ).toBe('')
  })
})

describe('actionKindOf', () => {
  it('三类认得准', () => {
    expect(actionKindOf('Read')).toBe('read')
    expect(actionKindOf('NotebookRead')).toBe('read')
    expect(actionKindOf('MultiEdit')).toBe('edit')
    expect(actionKindOf('Write')).toBe('edit')
    expect(actionKindOf('Bash')).toBe('run')
  })

  it('新工具落 other —— 分类表认不出也不会算错，它只影响那句摘要', () => {
    expect(actionKindOf('mcp__codegraph__explore')).toBe('other')
    expect(actionKindOf('Task')).toBe('other')
  })
})

describe('runFiles', () => {
  it('按出现顺序去重，带改动规模', () => {
    const { shown, rest } = runFiles([
      use('a', 'Read', { file_path: '/p/tools.ts' }),
      use('b', 'Edit', { file_path: '/p/tools.ts', old_string: 'a\nb', new_string: 'a' }),
      use('c', 'Bash', { command: 'ls' }),
      use('d', 'Write', { file_path: '/p/new.ts', content: 'x\ny' }),
    ])
    expect(shown.map((f) => [f.path, f.delta])).toEqual([
      ['/p/tools.ts', { add: 1, del: 2 }],
      ['/p/new.ts', { add: 2, del: 0 }],
    ])
    expect(rest).toBe(0)
  })

  it('同一个文件改过两次，规模累加', () => {
    const { shown } = runFiles([
      use('a', 'Edit', { file_path: '/p/a.ts', old_string: '1\n2', new_string: '1' }),
      use('b', 'Edit', { file_path: '/p/a.ts', old_string: 'x', new_string: 'x\ny\nz' }),
    ])
    // 删 2 加 1，再删 1 加 3 → 一共 +4 −3
    expect(shown).toEqual([{ path: '/p/a.ts', delta: { add: 4, del: 3 } }])
  })

  it('超过上限折成 +N —— 卡面只有一行', () => {
    const { shown, rest } = runFiles(
      ['/a.ts', '/b.ts', '/c.ts', '/d.ts'].map((p, i) =>
        use(`u${i}`, 'Read', { file_path: p }),
      ),
    )
    expect(shown).toHaveLength(3)
    expect(rest).toBe(1)
  })

  it('没有文件的组给空 —— 调用方据此不画那一行', () => {
    expect(runFiles([use('a', 'Bash', { command: 'ls' })]).shown).toEqual([])
  })
})
