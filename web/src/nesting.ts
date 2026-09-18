import type { TranscriptItem } from './types'

/**
 * 把子代理的项收进那张 `Task` 卡里（A1）。
 *
 * 协议是**只追加的一条流水**（见 `TranscriptOp` 的注释）：子代理说的正文、跑的工具
 * 都是独立的一项，靠 `parent` 指回主线程那条 `Task` 的 `toolUseId`。这里做的就是把
 * 那条流水按 `parent` 归位 —— **不重排、不丢项**。
 *
 * 四条规矩：
 *
 * 1. **只挂到工具卡上**：父项必须是 `kind === 'toolUse'` 且 `toolUseId` 对得上。
 *    同样的 id 出现在别处不算数。
 * 2. **父项找不到就留在主流水里**。这条与 `ToolResult` 的容忍口径一致 —— Kotlin
 *    老版本不送 `parent`、或 id 对不上时，最坏也只是"没嵌套"，绝不能把内容吃掉。
 * 3. **顺序不变**：子项按它们在流水里出现的次序，父项也在原位。
 * 4. **可以再嵌一层**（子代理里再派子代理）：父项自己也可能带 `parent`，
 *    于是它既进别人的 children，又带着自己的 children。
 *
 * 纯函数：不碰 DOM、不碰 React，用例直接打。
 */

export interface NestedItem {
  item: TranscriptItem
  /** 归到这一项底下的（只有工具卡会有）。 */
  children: NestedItem[]
}

export function nestByParent(items: TranscriptItem[]): NestedItem[] {
  const nodes = new Map<TranscriptItem, NestedItem>()
  for (const item of items) nodes.set(item, { item, children: [] })

  // 父项表：只认工具卡的 toolUseId
  const groups = new Map<string, NestedItem>()
  for (const item of items) {
    if (item.kind === 'toolUse' && item.toolUseId !== '') {
      const node = nodes.get(item)
      if (node) groups.set(item.toolUseId, node)
    }
  }

  const roots: NestedItem[] = []
  for (const item of items) {
    const node = nodes.get(item)
    if (!node) continue
    const parentId = parentOfItem(item)
    const parent = parentId === undefined ? undefined : groups.get(parentId)
    // 挂不上（没 parent / 父项不存在 / 父项就是自己）→ 留在主流水里
    if (parent === undefined || parent === node) roots.push(node)
    else parent.children.push(node)
  }
  return roots
}

function parentOfItem(item: TranscriptItem): string | undefined {
  if (item.kind === 'toolUse' || item.kind === 'assistant' || item.kind === 'thinking') {
    return item.parent
  }
  return undefined
}
