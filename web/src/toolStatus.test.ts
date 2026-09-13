import { describe, expect, it } from 'vitest'
import { toolStateOf } from './toolStatus'
import type { ToolResultItem } from './types'

/**
 * 工具卡片的四种状态与耗时文字。
 *
 * 这一层之所以是纯函数：界面上"它还在跑 / 跑完了 / 被中断了"全靠这里，
 * 而它最容易做错的两处 —— 把"没结果"一律当成还在跑（于是永远转圈）、
 * 以及回放历史时算出一个凭空的耗时 —— 都必须在纯函数里钉住。
 */

const done = (): ToolResultItem => ({
  kind: 'toolResult', id: 'm2', ts: 2000, toolUseId: 'toolu_1', text: 'out', isError: false,
})
const failed = (): ToolResultItem => ({ ...done(), isError: true })

describe('toolStateOf', () => {
  it('结果还没到、回合也没结束 → 进行中', () => {
    expect(toolStateOf(undefined, false)).toBe('running')
  })

  it('结果还没到，但回合已经结束了 → 已中断', () => {
    // 被拒绝、被中断、会话被杀掉的工具**永远不会**再收到结果。
    // 不收尾的话这张卡片会一直转圈，转到用户下次开面板
    expect(toolStateOf(undefined, true)).toBe('aborted')
  })

  it('结果到了 → 完成', () => {
    expect(toolStateOf(done(), false)).toBe('done')
  })

  it('结果到了且标了错 → 失败', () => {
    expect(toolStateOf(failed(), false)).toBe('failed')
  })

  it('结果到了之后，回合结不结束都不改变状态', () => {
    expect(toolStateOf(done(), true)).toBe('done')
    expect(toolStateOf(failed(), true)).toBe('failed')
  })
})

