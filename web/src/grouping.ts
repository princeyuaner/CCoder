/**
 * 把连着的工具调用归成一组（设计稿 docs/design/tool-grouping.html 方案甲）。
 *
 * 纯函数，不碰 DOM：分组是"怎么摆"的事，摆错了不该靠渲染才发现。
 *
 * 一组 = 一段**连续**的 toolUse。它解决的是真实体感：一次「读代码 → 改两个文件
 * → 跑测试」的回合就是八张卡片，一屏放不下，而它们本来就是一件事。
 */

import type { ToolUseItem, TranscriptItem } from './types'
import { toolDelta, toolFile } from './tools'

/**
 * 几次调用才归堆。
 *
 * 一次**不**归：一张组卡比一张普通卡多占一行、还多一次点击，
 * 而信息一模一样 —— 阈值定在 2。
 */
export const MIN_GROUP_SIZE = 2

/** 组卡摘要里的三类动作。认不出的工具落 other，不进摘要。 */
export type ActionKind = 'read' | 'edit' | 'run' | 'other'

/**
 * 工具名 → 动作类。
 *
 * 只影响卡面上那句摘要（「读 2 · 改 2 · 跑 2」），**不影响顺序** ——
 * 组内始终按时间排。所以这张表认不出新工具（MCP、以后新增的）也没关系：
 * 它们落进 other，不算错、也不会漏画。
 */
const KIND_OF: Record<string, ActionKind> = {
  Read: 'read',
  Grep: 'read',
  Glob: 'read',
  NotebookRead: 'read',
  Edit: 'edit',
  Write: 'edit',
  MultiEdit: 'edit',
  NotebookEdit: 'edit',
  Bash: 'run',
  BashOutput: 'run',
  KillShell: 'run',
}

export function actionKindOf(name: string): ActionKind {
  return KIND_OF[name] ?? 'other'
}

/**
 * 转写区里要画的一行：要么是一张普通的项，要么是一组工具调用。
 */
export type Row = { kind: 'item'; item: TranscriptItem } | { kind: 'run'; uses: ToolUseItem[] }

/**
 * 把转写项切成要画的行。三条规矩：
 *
 * 1. `toolResult` 既不画也不打断 —— 它本来就不单独成项（挂在卡片的输出区里）。
 *    不许它打断，否则「调用 → 结果 → 调用」这种真实顺序会让每一组都只剩一个。
 * 2. 其他任何一种项（提问、正文、思考、报错、回合结束）都打断一组 ——
 *    它们本身就是内容，不是这批动作的一部分。回合结束的 `result` 也在这条里，
 *    这正好把分组限制在**一个回合之内**。
 * 3. 不足 [MIN_GROUP_SIZE] 的一组原地摊平，不包装。
 */
export function groupRows(items: TranscriptItem[]): Row[] {
  const rows: Row[] = []
  let run: ToolUseItem[] = []

  const flush = () => {
    if (run.length === 0) return
    if (run.length >= MIN_GROUP_SIZE) rows.push({ kind: 'run', uses: run })
    else run.forEach((item) => rows.push({ kind: 'item', item }))
    run = []
  }

  for (const item of items) {
    if (item.kind === 'toolResult') continue
    if (item.kind === 'toolUse') {
      run.push(item)
      continue
    }
    flush()
    rows.push({ kind: 'item', item })
  }
  flush()
  return rows
}

const KIND_ORDER: ActionKind[] = ['read', 'edit', 'run']
const KIND_LABEL: Record<ActionKind, string> = { read: '读', edit: '改', run: '跑', other: '' }

/**
 * 组卡上那句话：「读 2 · 改 2 · 跑 2」。
 *
 * 全是认不出的工具时给空串 —— 调用方据此只写「6 次工具调用」，
 * 而不是补一句「其他 6」那样的废话。
 */
export function describeRun(uses: ToolUseItem[]): string {
  return KIND_ORDER.map((kind) => ({
    kind,
    n: uses.filter((u) => actionKindOf(u.name) === kind).length,
  }))
    .filter((x) => x.n > 0)
    .map((x) => `${KIND_LABEL[x.kind]} ${x.n}`)
    .join(' · ')
}

export interface RunFile {
  path: string
  /** 改动规模。没有就是 null（Read 与认不出的工具都算没有）。 */
  delta: { add: number; del: number } | null
}

/**
 * 组里涉及的文件。
 *
 * 两条规矩：
 * - **顺序按第一次出现**，同一个文件只列一次（卡面只有一行）
 * - **改动规模在整个组里累加**：先 Read 后 Edit 是再常见不过的顺序，
 *   只认第一次出现会把那次 Edit 的 +N −M 丢掉，卡面上就成了"只读没改"
 *
 * 超过 [limit] 个的折成「+N」交给调用方。
 */
export function runFiles(
  uses: ToolUseItem[],
  limit = 3,
): { shown: RunFile[]; rest: number } {
  const byPath = new Map<string, RunFile>()
  const order: string[] = []

  for (const use of uses) {
    const file = toolFile(use.name, use.input)
    if (file === null) continue

    const delta = toolDelta(use.name, use.input)
    const seen = byPath.get(file.path)
    if (seen === undefined) {
      byPath.set(file.path, { path: file.path, delta })
      order.push(file.path)
      continue
    }
    if (delta !== null) {
      seen.delta = {
        add: (seen.delta?.add ?? 0) + delta.add,
        del: (seen.delta?.del ?? 0) + delta.del,
      }
    }
  }

  const files = order.map((path) => byPath.get(path)!)
  return { shown: files.slice(0, limit), rest: Math.max(0, files.length - limit) }
}
