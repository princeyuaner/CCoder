import type { RevertResultWire, ToolResultItem, TranscriptItem } from './types'
import { toolDelta, toolFile } from './tools'

/**
 * 会**写盘**的编辑类工具。
 *
 * 为什么不能直接拿 [toolFile] 当白名单：它还认 `Read` / `NotebookRead`（那是给
 * "卡片上点文件名打开"用的）—— 拿它当编辑判据会把只读的调用也算成"改过"。
 * 与侧车的 `EDIT_TOOLS`（snapshots.js）和 Kotlin 的 `DIFF_TOOLS` 同一族口径。
 */
const EDIT_TOOLS = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit'])

/**
 * 「本轮改动」卡的聚合 —— 全是纯函数。
 *
 * 口径（spec：`docs/superpowers/specs/2026-10-13-turn-changes-spec.md` §2）：
 *
 *  - 「本轮」= **最后一条用户消息之后**；
 *  - 只认编辑类工具（[toolFile] 认得的那一族），且**结果落地成功才计入** ——
 *    被拒绝、失败的不算（"改过"与"动过手"是两件事）；
 *  - 子代理的编辑也算（它们的工具项本来就在同一份 items 里，带 parent）；
 *  - Bash 改的文件看不见（工具入参里没有路径）—— 这是共同盲区，卡片文案里照实说。
 *
 * 「新 / 无存档」的标记**不在这里算**：它按 toolUseId 对到 [SnapshotMark] 表上
 *（见 [markOf]）。为什么不信路径字符串：hook 报的路径与工具卡里的路径不保证
 * 逐字节一致（CLI 可能换了写法），拿字符串配会一边有一边没有。
 */

/** 本轮改过的一个文件（按首次出现的顺序排列）。 */
export interface TurnFile {
  /** 原样路径（工具入参里那个）。**不做归一化** —— 归一化只活在 Kotlin/侧车那一侧。 */
  path: string
  /** 本轮的编辑次数（Edit/MultiEdit/Write 各算一次调用）。 */
  edits: number
  /** 加了几行（口径同工具卡：`toolDelta`）。 */
  add: number
  /** 删了几行。 */
  del: number
  /**
   * 本轮**第一次**落在它上面的编辑调用 id —— 标记（新 / 无存档）按它取：
   * 同一轮同一文件的快照只留第一份（= 改动前），后来的那些看到的已经是改过的样子。
   */
  firstToolUseId: string
}

export interface TurnSummary {
  files: TurnFile[]
  add: number
  del: number
  /**
   * 本轮的编辑工具里还有**没落地结果**的（回合没跑完 / 结果还没来）。
   * 回滚入口据此禁用 —— 回滚要在空闲时做（spec §5）。
   */
  running: boolean
}

/** 一个文件的标记。`unknown` = 还没收到任何标记（老侧车 / 快照被关）。 */
export type FileMark = 'snapshot' | 'new' | 'noSnapshot' | 'unknown'

export function markOf(file: TurnFile, marks: Record<string, boolean | null> | undefined): FileMark {
  const existed = marks?.[file.firstToolUseId]
  if (existed === undefined) return 'unknown'
  if (existed === null) return 'noSnapshot'
  return existed ? 'snapshot' : 'new'
}

/** 一张回滚回执的读法（按路径）。没有 = 这个文件还没被回滚过。 */
export function revertStateOf(
  path: string,
  revert: Record<string, RevertResultWire> | undefined,
): RevertResultWire | undefined {
  return revert?.[path]
}

function lastUserIndex(items: TranscriptItem[]): number {
  for (let i = items.length - 1; i >= 0; i--) {
    if (items[i].kind === 'user') return i
  }
  return -1
}

/**
 * 把最后一条用户消息之后的编辑，按文件聚合。
 *
 * 结果先配对再过滤：一次编辑只有在**它的结果到了且没报错**时才计入 ——
 * 还在跑的不算（那会在卡片上闪一下又消失），被拒的不算（文件根本没动）。
 */
export function turnFiles(items: TranscriptItem[]): TurnFile[] {
  const from = lastUserIndex(items)
  if (from < 0) return []

  const results = new Map<string, ToolResultItem>()
  for (const item of items) {
    if (item.kind === 'toolResult' && item.toolUseId !== '') {
      results.set(item.toolUseId, item)
    }
  }

  const byPath = new Map<string, TurnFile>()
  for (let i = from + 1; i < items.length; i++) {
    const item = items[i]
    if (item.kind !== 'toolUse') continue
    if (!EDIT_TOOLS.has(item.name)) continue
    const ref = toolFile(item.name, item.input)
    if (ref === null) continue
    const result = results.get(item.toolUseId)
    if (!result || result.isError) continue
    const delta = toolDelta(item.name, item.input)
    const existing = byPath.get(ref.path)
    if (existing) {
      existing.edits += 1
      existing.add += delta?.add ?? 0
      existing.del += delta?.del ?? 0
    } else {
      byPath.set(ref.path, {
        path: ref.path,
        edits: 1,
        add: delta?.add ?? 0,
        del: delta?.del ?? 0,
        firstToolUseId: item.toolUseId,
      })
    }
  }
  return [...byPath.values()]
}

/**
 * 卡片要的全部摘要。
 *
 * `running` 的判据与卡片自己那份一致：本轮的编辑工具里，有**还没配到结果**的
 *（不论成功失败，结果本身就是"这段结束了"的信号）。回合结束的 result 事件
 * 不参与这条判据 —— 被打断的那一轮也不会再补 toolResult（同 Transcript.tsx
 * 的 `endedToolUseIds` 那条注释）。
 */
export function turnSummary(items: TranscriptItem[]): TurnSummary {
  const from = lastUserIndex(items)
  const files = turnFiles(items)
  if (from < 0) return { files, add: 0, del: 0, running: false }

  const resultIds = new Set<string>()
  let running = false
  for (let i = from + 1; i < items.length; i++) {
    const item = items[i]
    if (item.kind === 'toolResult' && item.toolUseId !== '') resultIds.add(item.toolUseId)
  }
  for (let i = from + 1; i < items.length; i++) {
    const item = items[i]
    if (item.kind !== 'toolUse') continue
    if (!EDIT_TOOLS.has(item.name)) continue
    if (toolFile(item.name, item.input) === null) continue
    if (!resultIds.has(item.toolUseId)) running = true
  }

  return {
    files,
    add: files.reduce((sum, f) => sum + f.add, 0),
    del: files.reduce((sum, f) => sum + f.del, 0),
    running,
  }
}
