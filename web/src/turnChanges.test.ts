import { describe, expect, it } from 'vitest'
import type { TranscriptItem } from './types'
import { markOf, revertStateOf, turnFiles, turnSummary, type TurnFile } from './turnChanges'

/**
 * 「本轮改动」卡的聚合口径（spec §2）。
 *
 * 这里盯的是**哪些算、哪些不算**：结果落地才计入、被拒不算、子代理算、
 * Bash 看不见（它压根没有工具项可算）。
 */

let seq = 0
function user(text: string): TranscriptItem {
  seq += 1
  return { kind: 'user', id: `u${seq}`, ts: seq, text }
}
function toolUse(name: string, input: object, id: string, parent?: string): TranscriptItem {
  seq += 1
  return {
    kind: 'toolUse', id: `t${seq}`, ts: seq, toolUseId: id, name,
    input: JSON.stringify(input), ...(parent !== undefined ? { parent } : {}),
  }
}
function toolResult(toolUseId: string, isError = false): TranscriptItem {
  seq += 1
  return { kind: 'toolResult', id: `r${seq}`, ts: seq, toolUseId, text: 'ok', isError }
}
function result(): TranscriptItem {
  seq += 1
  return { kind: 'result', id: `e${seq}`, ts: seq, subtype: 'success' }
}

describe('turnFiles', () => {
  it('只统计最后一条用户消息之后的编辑', () => {
    const items: TranscriptItem[] = [
      user('第一轮'),
      toolUse('Edit', { file_path: '/old.kt', old_string: 'a', new_string: 'b' }, 't-old'),
      toolResult('t-old'),
      result(),
      user('第二轮'),
      toolUse('Edit', { file_path: '/new.kt', old_string: 'a', new_string: 'b' }, 't-new'),
      toolResult('t-new'),
    ]
    const files = turnFiles(items)
    expect(files.map((f) => f.path)).toEqual(['/new.kt'])
  })

  it('结果还没落地的编辑不算（会在卡上闪一下又消失）', () => {
    const items: TranscriptItem[] = [
      user('改一下'),
      toolUse('Edit', { file_path: '/a.kt', old_string: 'a', new_string: 'b' }, 't1'),
    ]
    expect(turnFiles(items)).toEqual([])
  })

  it('失败/被拒的编辑不算 —— 文件根本没动', () => {
    const items: TranscriptItem[] = [
      user('改一下'),
      toolUse('Edit', { file_path: '/a.kt', old_string: 'a', new_string: 'b' }, 't1'),
      toolResult('t1', true),
    ]
    expect(turnFiles(items)).toEqual([])
  })

  it('同一文件多次编辑聚合成一行：次数与 ± 行数都累加，firstToolUseId 是第一次那个', () => {
    const items: TranscriptItem[] = [
      user('改一下'),
      toolUse('Edit', { file_path: '/a.kt', old_string: 'aaa', new_string: 'bbb' }, 't1'),
      toolResult('t1'),
      toolUse('Write', { file_path: '/a.kt', content: 'x\ny\nz' }, 't2'),
      toolResult('t2'),
      toolUse('MultiEdit', { file_path: '/a.kt', edits: [{ old_string: 'p', new_string: 'q' }] }, 't3'),
      toolResult('t3'),
    ]
    const [f] = turnFiles(items)
    expect(f.edits).toBe(3)
    // Edit：旧 1 行全删、新 1 行全加；Write：3 行全加；MultiEdit：删 1 加 1
    expect(f.add).toBe(5)
    expect(f.del).toBe(2)
    expect(f.firstToolUseId).toBe('t1')
  })

  it('子代理的编辑也算（它们带 parent，但文件是真改了的）', () => {
    const items: TranscriptItem[] = [
      user('让子代理去改'),
      toolUse('Task', { description: '干活' }, 'task-1'),
      toolUse('Edit', { file_path: '/sub.kt', old_string: 'a', new_string: 'b' }, 't-sub', 'task-1'),
      toolResult('t-sub'),
    ]
    expect(turnFiles(items).map((f) => f.path)).toEqual(['/sub.kt'])
  })

  it('不写盘的工具不进清单（Read/Bash 没有单一文件可算）', () => {
    const items: TranscriptItem[] = [
      user('看看'),
      toolUse('Read', { file_path: '/a.kt' }, 't1'),
      toolResult('t1'),
      toolUse('Bash', { command: 'rm -rf /' }, 't2'),
      toolResult('t2'),
    ]
    expect(turnFiles(items)).toEqual([])
  })

  it('没有用户消息时一律空', () => {
    expect(turnFiles([toolUse('Write', { file_path: '/a.kt', content: 'x' }, 't1')])).toEqual([])
  })
})

describe('turnSummary', () => {
  it('running：本轮的编辑还有没配到结果的（回滚入口据此禁用）', () => {
    const running = turnSummary([
      user('改一下'),
      toolUse('Edit', { file_path: '/a.kt', old_string: 'a', new_string: 'b' }, 't1'),
    ])
    expect(running.running).toBe(true)

    const done = turnSummary([
      user('改一下'),
      toolUse('Edit', { file_path: '/a.kt', old_string: 'a', new_string: 'b' }, 't1'),
      toolResult('t1'),
      result(),
    ])
    expect(done.running).toBe(false)
  })

  it('add/del 是全文件的合计（收起行那对数字）', () => {
    const s = turnSummary([
      user('改一下'),
      toolUse('Edit', { file_path: '/a.kt', old_string: 'a', new_string: 'b' }, 't1'),
      toolResult('t1'),
      toolUse('Edit', { file_path: '/b.kt', old_string: 'x\ny', new_string: 'z' }, 't2'),
      toolResult('t2'),
    ])
    // Edit#1：删 a 加 b（各 1）；Edit#2：删两行加一行
    expect(s.add).toBe(2)
    expect(s.del).toBe(3)
  })
})

describe('markOf / revertStateOf', () => {
  const file: TurnFile = { path: '/a.kt', edits: 1, add: 1, del: 0, firstToolUseId: 't1' }

  it('三种标记各有其名；没有标记是 unknown', () => {
    expect(markOf(file, { t1: true })).toBe('snapshot')
    expect(markOf(file, { t1: false })).toBe('new')
    expect(markOf(file, { t1: null })).toBe('noSnapshot')
    expect(markOf(file, {})).toBe('unknown')
    expect(markOf(file, undefined)).toBe('unknown')
  })

  it('回执按路径取', () => {
    const revert = { '/a.kt': { path: '/a.kt', ok: true, action: 'restored' as const } }
    expect(revertStateOf('/a.kt', revert)?.action).toBe('restored')
    expect(revertStateOf('/b.kt', revert)).toBeUndefined()
    expect(revertStateOf('/a.kt', undefined)).toBeUndefined()
  })
})
